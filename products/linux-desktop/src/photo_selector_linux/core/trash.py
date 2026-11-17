"""FreeDesktop Trash and GIO trashing for the culling engine (R-LINUX-CULL-05).

``TrashMixin`` carries the trash half of ``FileCullingManager``; it is split out of ``file_ops.py`` with
no change in behaviour. The manager's ``__init__`` sets ``_trash_dir`` and ``name_resolver``.
"""

from __future__ import annotations

from datetime import datetime, timezone
import logging
import os
import stat
from pathlib import Path
from typing import Callable, Dict, List, Optional, Tuple
from urllib.parse import quote, unquote

from photo_selector_linux.core.gvfs_names import DisplayNameResolver, is_gvfs_fuse_path
from photo_selector_linux.core.models import (
    CandidatePhoto,
    CullingActionType,
    CullingRecord,
    PhotoStatus,
)
from photo_selector_linux.core.file_errors import FileCullingError
from photo_selector_linux.core.fs_transfer import _copy_file, _entry_path, _move_no_overwrite

logger = logging.getLogger(__name__)

_HAVE_GIO = False
try:
    import gi
    gi.require_version("Gio", "2.0")
    from gi.repository import Gio
    _HAVE_GIO = True
except Exception:
    _HAVE_GIO = False


class TrashMixin:
    """Trash, restore-from-trash and GIO trashing of one photo bundle."""

    # Set by ``FileCullingManager.__init__``.
    _trash_dir: Optional[Path]
    name_resolver: DisplayNameResolver
    _mount_root_for_fn: Optional[Callable[[Path], Optional[Path]]]
    _dev_of_fn: Optional[Callable[[Path], int]]
    _uid: Optional[int]

    def _get_uid(self) -> int:
        if self._uid is not None:
            return self._uid
        return os.getuid() if hasattr(os, "getuid") else 1000

    def _dev_of(self, path: Path) -> int:
        if self._dev_of_fn is not None:
            return self._dev_of_fn(path)
        curr = path
        while not curr.exists() and not curr.is_symlink() and curr.parent != curr:
            curr = curr.parent
        return curr.stat().st_dev

    def _mount_root_for(self, path: Path) -> Optional[Path]:
        if self._mount_root_for_fn is not None:
            res = self._mount_root_for_fn(path)
            return res.resolve() if res is not None else None
        curr = _entry_path(path)
        while not curr.exists() and curr.parent != curr:
            curr = curr.parent
        if curr.is_file() or curr.is_symlink():
            curr = curr.parent
        dev = self._dev_of(curr)
        while curr.parent != curr:
            try:
                if self._dev_of(curr.parent) != dev:
                    return curr
            except OSError:
                return curr
            curr = curr.parent
        return curr

    def _get_trash_dir(self) -> Path:
        """Return the FreeDesktop.org Trash base directory ($XDG_DATA_HOME/Trash)."""
        if self._trash_dir is not None:
            return self._trash_dir
        xdg_data = os.environ.get("XDG_DATA_HOME")
        if xdg_data:
            return Path(xdg_data) / "Trash"
        return Path.home() / ".local" / "share" / "Trash"

    def _select_trash_base_for(self, src: Path) -> Tuple[Path, Optional[Path]]:
        """Return ``(trash_base, mount_root)`` for ``src``.

        When ``src`` resides on a mounted volume (SD card, external drive) with a different
        device ID from the user's home trash, uses the FreeDesktop top-directory trash on that
        mount (``<mount>/.Trash-$UID`` or ``<mount>/.Trash/$UID``) with relative ``Path=`` entries.
        """
        home_trash = self._get_trash_dir()
        if self._trash_dir is not None or is_gvfs_fuse_path(src):
            return home_trash, None

        try:
            src_dev = self._dev_of(src.parent)
            home_dev = self._dev_of(home_trash)
        except OSError:
            return home_trash, None

        if src_dev != home_dev:
            mount_root = self._mount_root_for(src)
            if mount_root is not None:
                try:
                    _entry_path(src).relative_to(mount_root)
                except ValueError:
                    pass
                else:
                    uid = self._get_uid()
                    # FreeDesktop Trash spec 1.0 §Trash directories: $topdir/.Trash is used only
                    # when it is a real directory (not a symlink) with the sticky bit; otherwise
                    # fall back to the per-user $topdir/.Trash-$uid.
                    shared_trash = mount_root / ".Trash"
                    if self._is_usable_shared_trash(shared_trash):
                        return shared_trash / str(uid), mount_root
                    return mount_root / f".Trash-{uid}", mount_root

        return home_trash, None

    @staticmethod
    def _is_usable_shared_trash(path: Path) -> bool:
        """True for a ``$topdir/.Trash`` that is a real directory with the sticky bit set."""
        try:
            mode = os.lstat(path).st_mode
        except OSError:
            return False
        return stat.S_ISDIR(mode) and bool(mode & stat.S_ISVTX)

    def _candidate_trash_bases(self, original_src: Path) -> List[Tuple[Path, Optional[Path]]]:
        """Return ordered ``(trash_base, mount_root)`` candidates to inspect for ``original_src``."""
        candidates: List[Tuple[Path, Optional[Path]]] = []
        seen: set[Path] = set()

        def _add(base: Path, mroot: Optional[Path]) -> None:
            try:
                key = base.resolve()
            except OSError:
                key = base
            if key not in seen:
                seen.add(key)
                candidates.append((base, mroot))

        if not is_gvfs_fuse_path(original_src):
            mount_root = self._mount_root_for(original_src)
            if mount_root is not None:
                uid = self._get_uid()
                _add(mount_root / f".Trash-{uid}", mount_root)
                _add(mount_root / ".Trash" / str(uid), mount_root)

        _add(self._get_trash_dir(), None)
        return candidates

    def _trash_file_freedesktop(self, src: Path, name: Optional[str] = None) -> Tuple[Path, Path]:
        """Relocate a file into the FreeDesktop.org Trash specification layout (files/ + info/).

        ``name`` is the user-facing filename when it differs from the on-disk one (GVFS cloud
        mounts); the trash entry and its recorded original path use it. Returns ``(files_path, info_path)``.
        """
        trash_base, mount_root = self._select_trash_base_for(src)
        files_dir = trash_base / "files"
        info_dir = trash_base / "info"
        # A top-directory trash holds other users' and programs' entries: owner-only (spec).
        dir_mode = 0o700 if mount_root is not None else 0o777
        trash_base.mkdir(mode=dir_mode, parents=True, exist_ok=True)
        files_dir.mkdir(mode=dir_mode, parents=True, exist_ok=True)
        info_dir.mkdir(mode=dir_mode, parents=True, exist_ok=True)

        src_resolved = _entry_path(src)
        name = name or src.name
        deletion_date = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")
        logical_src = src_resolved.parent / name
        if mount_root is not None:
            rel_path = logical_src.relative_to(mount_root)
            encoded_path = quote(rel_path.as_posix(), safe="/")
        else:
            encoded_path = quote(str(logical_src), safe="/")
        info_content = f"[Trash Info]\nPath={encoded_path}\nDeletionDate={deletion_date}\n"

        # The .trashinfo reserves the trash name: create it with O_EXCL (spec) so two processes
        # trashing the same name never share an entry; on EEXIST try the next name.
        candidate_name = name
        counter = 1
        while True:
            if (files_dir / candidate_name).exists() or (info_dir / f"{candidate_name}.trashinfo").exists():
                counter += 1
                candidate_name = f"{Path(name).stem}.{counter}{Path(name).suffix}"
                continue
            try:
                fd = os.open(
                    info_dir / f"{candidate_name}.trashinfo",
                    os.O_WRONLY | os.O_CREAT | os.O_EXCL,
                    0o600,
                )
            except FileExistsError:
                counter += 1
                candidate_name = f"{Path(name).stem}.{counter}{Path(name).suffix}"
                continue
            break
        dst_file = (files_dir / candidate_name).resolve()
        info_file = (info_dir / f"{candidate_name}.trashinfo").resolve()
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(info_content)

        try:
            if self._dev_of(src_resolved.parent) == self._dev_of(files_dir):
                _move_no_overwrite(src_resolved, dst_file)
            elif src_resolved.is_symlink():
                os.symlink(os.readlink(src_resolved), dst_file)
                src_resolved.unlink()
            else:
                _copy_file(src_resolved, dst_file, fsync=True)
                src_resolved.unlink()
        except Exception:
            # A copy that landed in the Trash while the original could not be removed would stay
            # there without a .trashinfo (an orphan); the original is intact, so drop the copy.
            if (src_resolved.exists() or src_resolved.is_symlink()) and (
                dst_file.exists() or dst_file.is_symlink()
            ):
                try:
                    dst_file.unlink()
                except Exception:
                    # best-effort: this cleanup runs while the original error is re-raised below,
                    # which is what the caller sees; a stuck orphan cannot change the outcome.
                    logger.warning("Could not remove the partial Trash copy %s", dst_file, exc_info=True)
            if info_file.exists():
                try:
                    info_file.unlink()
                except Exception:
                    # best-effort: as above, the original error is re-raised.
                    logger.warning("Could not remove the Trash info file %s", info_file, exc_info=True)
            raise

        return dst_file, info_file

    def _find_trashed_entry(self, original_src: Path) -> Optional[Tuple[Path, Path]]:
        """Locate ``(files_path, info_path)`` in mount or home Trash matching ``original_src``.

        Matches only via ``.trashinfo`` ``Path=`` metadata (resolving relative paths against the
        mount root for top-directory trash); never falls back to a bare filename match.
        """
        target_resolved = _entry_path(original_src)
        matches: List[Tuple[int, Path, Path]] = []

        for trash_base, mount_root in self._candidate_trash_bases(original_src):
            files_dir = trash_base / "files"
            info_dir = trash_base / "info"
            if not files_dir.is_dir() or not info_dir.is_dir():
                continue
            for info_file in info_dir.glob("*.trashinfo"):
                try:
                    raw = info_file.read_text(encoding="utf-8", errors="replace")
                    for line in raw.splitlines():
                        if not line.startswith("Path="):
                            continue
                        recorded_raw = unquote(line.split("=", 1)[1].strip())
                        recorded_path = Path(recorded_raw)
                        if recorded_path.is_absolute():
                            recorded_resolved = _entry_path(recorded_path)
                        elif mount_root is not None:
                            recorded_resolved = _entry_path(mount_root / recorded_path)
                        else:
                            recorded_resolved = _entry_path(trash_base.parent / recorded_path)

                        if recorded_resolved == target_resolved:
                            trashed_name = info_file.name[:-len(".trashinfo")]
                            payload = files_dir / trashed_name
                            if payload.exists() or payload.is_symlink():
                                matches.append(
                                    (info_file.stat().st_mtime_ns, _entry_path(payload), _entry_path(info_file))
                                )
                except Exception:
                    continue

        if matches:
            matches.sort(key=lambda item: item[0], reverse=True)
            return matches[0][1], matches[0][2]
        return None

    def _restore_from_trash(
        self,
        src: Path,
        dst: Optional[Path],
        restore_target: Optional[Path] = None,
        info_path: Optional[Path] = None,
    ) -> None:
        """Restore a single recorded file from FreeDesktop.org Trash back to ``restore_target`` (or ``src``).

        Never searches or guesses by filename: if the recorded ``dst`` is missing, raises
        ``FileCullingError`` naming ``src.name``.
        """
        if dst is None or str(dst).startswith("trash://") or (not dst.exists() and not dst.is_symlink()):
            raise FileCullingError(f"Trashed file for {src.name} is no longer in Trash (expected {dst})")

        target = restore_target if restore_target is not None else src
        target.parent.mkdir(parents=True, exist_ok=True)
        if self._dev_of(target.parent) == self._dev_of(dst.parent):
            _move_no_overwrite(dst, target)
        elif dst.is_symlink():
            os.symlink(os.readlink(dst), target)
            dst.unlink()
        else:
            _copy_file(dst, target, fsync=True)
            if target.stat().st_size != dst.stat().st_size:
                raise IOError(f"Byte mismatch restoring {dst.name}")
            dst.unlink()

        info_file = info_path or (dst.parent.parent / "info" / f"{dst.name}.trashinfo")
        if info_file.exists():
            try:
                info_file.unlink()
            except Exception:
                # The photo is back; a leftover .trashinfo only shows a ghost entry in the Trash.
                logger.warning("Restored %s but could not remove its Trash info file", dst, exc_info=True)

    def _relink_restored(self, candidate: CandidatePhoto) -> None:
        """Point the candidate at its restored files.

        A file written back to a GVFS cloud mount gets a new opaque ID, so the candidate's old
        on-disk paths are gone; find each file again by its user-facing name.
        """
        stale = [p for p in candidate.all_paths if not p.exists()]
        if not stale:
            return
        by_name = {}
        for parent in {p.parent for p in stale}:
            for on_disk, display in self.name_resolver.names_in(parent).items():
                by_name[(parent, display)] = parent / on_disk
        relinked = {}
        for old in stale:
            name = candidate.name_of(old)
            relinked[old] = by_name.get((old.parent, name), old.parent / name)
        candidate.primary_path = relinked.get(candidate.primary_path, candidate.primary_path)
        candidate.companion_paths = [relinked.get(p, p) for p in candidate.companion_paths]
        for old, new in relinked.items():
            name = candidate.display_names.pop(old, old.name)
            if new.name != name:
                candidate.display_names[new] = name

    def _use_gio(self) -> bool:
        """True when the real desktop Trash (GIO) is used rather than the injected/test layout."""
        return (
            _HAVE_GIO
            and self._trash_dir is None
            and self._mount_root_for_fn is None
            and self._dev_of_fn is None
        )

    def _gio_trash_names(self, src_resolved: Path) -> List[str]:
        """Names in GIO's ``trash:///`` listing whose recorded original path is ``src_resolved``."""
        root = Gio.File.new_for_uri("trash:///")
        enumerator = root.enumerate_children(
            "standard::name,trash::orig-path", Gio.FileQueryInfoFlags.NONE, None
        )
        names: List[str] = []
        for _ in range(20_000):  # bounded: a mocked Gio whose listing never ends grew pytest to ~9.6 GB
            item = enumerator.next_file(None)
            if item is None:
                return names
            orig = item.get_attribute_byte_string("trash::orig-path")
            names += [item.get_name()] if orig and Path(os.fsdecode(orig)) == src_resolved else []
        return names

    def _trash_via_gio(self, src: Path) -> Tuple[Path, Path]:
        """Trash ``src`` through GIO and return ``(files_path, info_path)`` of its trash entry.

        Once GIO has trashed the file it is in the Trash whether or not we can find the entry, so
        a failed lookup must never leave it there unrecorded: look again through GIO's own trash
        listing, and failing that put the file back before raising.
        """
        src_resolved = _entry_path(src)
        Gio.File.new_for_path(str(src_resolved)).trash(None)
        located = self._find_trashed_entry(src)
        names: List[str] = []
        if located is None:
            try:
                names = self._gio_trash_names(src_resolved)
            except Exception:
                names = []
            for trashed_name in names:
                for base, _ in self._candidate_trash_bases(src):
                    payload = base / "files" / trashed_name
                    info = base / "info" / f"{trashed_name}.trashinfo"
                    if (payload.exists() or payload.is_symlink()) and info.exists():
                        located = (_entry_path(payload), _entry_path(info))
                        break
                if located is not None:
                    break
        if located is not None:
            return located
        for trashed_name in names:
            try:
                Gio.File.new_for_uri("trash:///").get_child(trashed_name).move(
                    Gio.File.new_for_path(str(src_resolved)), Gio.FileCopyFlags.NONE, None, None, None
                )
            except Exception:
                continue
            raise FileCullingError(
                f"Could not track {src.name} after moving it to the Trash; it was restored to its folder"
            )
        raise FileCullingError(
            f"{src.name} was moved to the Trash but could not be tracked or put back; "
            f"restore it from the file manager's Trash"
        )

    def trash_candidate(self, candidate: CandidatePhoto) -> CullingRecord:
        """Safely send candidate and companions to FreeDesktop Trash (R-LINUX-CULL-05)."""
        targets = candidate.all_paths
        trashed_paths: List[Tuple[Path, Path]] = []
        trash_info_paths: Dict[Path, Path] = {}

        try:
            for src in targets:
                if not src.exists() and not src.is_symlink():
                    continue
                if is_gvfs_fuse_path(src):
                    # GIO cannot trash through the FUSE mount, and the cloud's own trash is out of
                    # undo's reach: keep the file in the local Trash under its real name instead.
                    name = candidate.name_of(src)
                    dst_trash, info_trash = self._trash_file_freedesktop(src, name)
                    orig_key = _entry_path(src).parent / name
                    trashed_paths.append((orig_key, dst_trash))
                    trash_info_paths[orig_key] = info_trash
                elif self._use_gio():
                    dst_trash, info_trash = self._trash_via_gio(src)
                    trashed_paths.append((src, dst_trash))
                    trash_info_paths[src] = info_trash
                else:
                    dst_trash, info_trash = self._trash_file_freedesktop(src)
                    trashed_paths.append((src, dst_trash))
                    trash_info_paths[src] = info_trash
        except Exception as e:
            failed_rollbacks: List[Tuple[Path, Path, Exception]] = []
            for orig_src, trash_dst in reversed(trashed_paths):
                try:
                    self._restore_from_trash(
                        orig_src,
                        trash_dst,
                        info_path=trash_info_paths.get(orig_src),
                    )
                except Exception as rollback_err:
                    failed_rollbacks.append((orig_src, trash_dst, rollback_err))
            if failed_rollbacks:
                failed_rollbacks.reverse()
                still_trashed = [(s, d) for s, d, _ in failed_rollbacks]
                still_info = {
                    s: trash_info_paths[s]
                    for s, _, _ in failed_rollbacks
                    if s in trash_info_paths
                }
                partial_rec = CullingRecord(
                    photo_id=candidate.id,
                    action_type=CullingActionType.TRASH,
                    original_primary_path=candidate.primary_path,
                    affected_paths=still_trashed,
                    previous_status=PhotoStatus.CANDIDATE,
                    trash_info_paths=still_info,
                )
                failed_names = ", ".join(f"{s.name} ({err})" for s, _, err in failed_rollbacks)
                raise FileCullingError(
                    f"FreeDesktop trash failed ({e}); rollback also failed for: {failed_names}",
                    partial_record=partial_rec,
                ) from e
            raise FileCullingError(f"FreeDesktop trash failed, rolled back: {e}") from e

        candidate.status = PhotoStatus.TRASHED
        return CullingRecord(
            photo_id=candidate.id,
            action_type=CullingActionType.TRASH,
            original_primary_path=candidate.primary_path,
            affected_paths=trashed_paths,
            previous_status=PhotoStatus.CANDIDATE,
            trash_info_paths=trash_info_paths,
        )
