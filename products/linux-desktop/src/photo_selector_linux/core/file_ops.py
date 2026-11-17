"""Transactional file operations and FreeDesktop trash integration for culling engine.

Adheres to:
- R-LINUX-CULL-03: Atomic Multi-File Move & Cross-Filesystem Rollback Safety
- R-LINUX-CULL-04: Atomic Multi-File Copy to Selection & Staged Rollback
- R-LINUX-CULL-05: FreeDesktop Safe Trash Integration
- R-LINUX-CULL-06: Transactional Copy-Undo Safety Invariant
- R-LINUX-CULL-07: Transactional Move and Trash Undo
"""

from __future__ import annotations

import dataclasses
import logging
import os
from pathlib import Path
from typing import Callable, Dict, List, Optional, Tuple

from photo_selector_linux.core.destination import selection_subfolder_name
from photo_selector_linux.core.gvfs_names import (
    DisplayNameResolver,
    default_resolver,
)
from photo_selector_linux.core.models import (
    CandidatePhoto,
    CullingActionType,
    CullingRecord,
    PhotoStatus,
)
from photo_selector_linux.core.file_errors import FileCullingError, InsufficientDiskSpaceError
from photo_selector_linux.core.fs_transfer import (  # noqa: F401
    _RENAME_LOCK,
    _apply_bundle_suffix,
    _copy_file,
    _entry_path,
    _move_no_overwrite,
    _resolve_bundle_destinations,
)
from photo_selector_linux.core.trash import _HAVE_GIO, TrashMixin  # noqa: F401

logger = logging.getLogger(__name__)

SELECTION_MARKER_FILENAME: str = ".photoselector-selection"


class FileCullingManager(TrashMixin):
    """Manages transactional file relocations, copy operations, trash, and undos."""

    def __init__(
        self,
        default_selection_folder: str = "Selection",
        trash_dir: Optional[Path] = None,
        name_resolver: Optional[DisplayNameResolver] = None,
        mount_root_for: Optional[Callable[[Path], Optional[Path]]] = None,
        dev_of: Optional[Callable[[Path], int]] = None,
        uid: Optional[int] = None,
    ):
        self.default_selection_folder = default_selection_folder
        self._trash_dir = trash_dir
        self.name_resolver: DisplayNameResolver = name_resolver or default_resolver()
        self._mount_root_for_fn = mount_root_for
        self._dev_of_fn = dev_of
        self._uid = uid

    def _resolve_bundle_destinations(
        self,
        candidate: CandidatePhoto,
        dest_dir: Path,
    ) -> List[Tuple[Path, Path]]:
        """Resolve collision-free ``(src, dst)`` pairs in ``dest_dir`` for ``candidate``."""
        return _resolve_bundle_destinations(candidate, dest_dir, self.name_resolver)

    SELECTION_MARKER_FILENAME: str = ".photoselector-selection"

    def _get_selection_dir(self, root_dir: Path, custom_folder: Optional[str] = None) -> Path:
        folder_name = selection_subfolder_name(custom_folder or self.default_selection_folder)
        # On GVFS cloud mounts an existing folder is listed under an opaque ID, not its name:
        # reuse it instead of creating a duplicate "Selection" on every cull.
        for on_disk, display in self.name_resolver.names_in(root_dir).items():
            if display == folder_name and (root_dir / on_disk).is_dir():
                return (root_dir / on_disk).resolve()
        sel_dir = (root_dir / folder_name).resolve()
        sel_dir.mkdir(parents=True, exist_ok=True)
        marker = sel_dir / self.SELECTION_MARKER_FILENAME
        if not marker.exists():
            try:
                marker.write_bytes(b"")
            except OSError:
                pass
        return sel_dir

    def _verify_destination_capacity(self, sel_dir: Path, files: List[Path]) -> None:
        """Pre-flight check: ensure destination filesystem has enough space (R-LINUX-CULL-03)."""
        total_bundle_size = sum(f.stat().st_size for f in files if f.exists())
        try:
            st = os.statvfs(sel_dir)
            available_bytes = st.f_bavail * st.f_frsize
            if available_bytes < total_bundle_size:
                raise InsufficientDiskSpaceError(
                    f"Insufficient free space at {sel_dir}: "
                    f"required {total_bundle_size} bytes, available {available_bytes} bytes."
                )
        except OSError as e:
            if isinstance(e, InsufficientDiskSpaceError):
                raise
            # If statvfs is unsupported on host, proceed

    @staticmethod
    def _in_flight_to_clean(
        in_flight: Optional[Tuple[Path, Path]], error: BaseException
    ) -> List[Tuple[Path, Path]]:
        """The in-flight pair to clean up after ``error``. When the exclusive create itself failed
        because the name exists, the file there belongs to someone else and is never removed."""
        if in_flight is None or isinstance(error, FileExistsError):
            return []
        return [in_flight]

    @staticmethod
    def _remove_replicas(pairs: List[Tuple[Path, Path]]) -> List[Tuple[Path, Path]]:
        """Unlink the destination replica of each ``(source, replica)`` pair; return the pairs whose
        replica could not be removed (they are still on disk and must be reported)."""
        leftovers: List[Tuple[Path, Path]] = []
        for src, dst in pairs:
            if src.resolve() != dst.resolve() and dst.exists():
                try:
                    dst.unlink()
                except Exception as err:
                    logger.error("Could not remove the replica %s: %s", dst, err)
                    leftovers.append((src, dst))
        return leftovers

    def move_candidate(
        self,
        candidate: CandidatePhoto,
        root_dir: Path,
        selection_folder: Optional[str] = None,
        selection_folder_name: Optional[str] = None,
    ) -> CullingRecord:
        """Atomically move candidate photo and all bound companions to Selection/ (R-LINUX-CULL-03)."""
        sel_dir = self._get_selection_dir(root_dir, selection_folder_name or selection_folder)
        planned_moves = self._resolve_bundle_destinations(candidate, sel_dir)
        targets = [src for src, _ in planned_moves]

        # Detect cross-filesystem boundaries
        sel_dev = self._dev_of(sel_dir)
        is_cross_mount = any(t.exists() and self._dev_of(t) != sel_dev for t in targets)

        # Pre-flight capacity check: only a cross-mount move copies bytes. A same-filesystem
        # move is a rename and needs no free space, so a nearly full card must not refuse it.
        if is_cross_mount:
            self._verify_destination_capacity(sel_dir, targets)

        affected: List[Tuple[Path, Path]] = []

        if not is_cross_mount:
            # === Intra-Filesystem Collision-Free Atomic Move ===
            completed_moves: List[Tuple[Path, Path]] = []
            try:
                for src, dst in planned_moves:
                    src_res = src.resolve()

                    # Self-Move Guard: Idempotent no-op
                    if src_res == dst:
                        completed_moves.append((src, dst))
                        continue

                    _move_no_overwrite(src, dst)
                    completed_moves.append((src, dst))
            except Exception as e:
                # Atomic Rollback: restore previously moved files to original paths. A member that
                # cannot be put back stays in Selection/; it is reported, never dropped (D-12).
                stranded_moves: List[Tuple[Path, Path]] = []
                for moved_src, moved_dst in reversed(completed_moves):
                    if moved_src.resolve() != moved_dst.resolve() and moved_dst.exists():
                        try:
                            _move_no_overwrite(moved_dst, moved_src)
                        except Exception as rollback_err:
                            logger.error("Rollback could not put %s back: %s", moved_src.name, rollback_err)
                            stranded_moves.append((moved_src, moved_dst))
                if stranded_moves:
                    stranded_moves.reverse()
                    names = ", ".join(s.name for s, _ in stranded_moves)
                    raise FileCullingError(
                        f"Intra-filesystem move failed ({e}); could not be put back: {names} "
                        f"(still in {sel_dir.name}/)",
                        partial_record=CullingRecord(
                            photo_id=candidate.id,
                            action_type=CullingActionType.MOVE,
                            original_primary_path=candidate.primary_path,
                            affected_paths=stranded_moves,
                            previous_status=PhotoStatus.CANDIDATE,
                            stranded=[d for _, d in stranded_moves],
                        ),
                    ) from e
                raise FileCullingError(f"Intra-filesystem move failed, rolled back: {e}") from e

            affected = completed_moves
        else:
            # === Cross-Filesystem Two-Phase Staged Transaction ===
            staged_dsts: List[Tuple[Path, Path]] = []
            current_in_flight: Optional[Tuple[Path, Path]] = None

            # --- Phase 1: Staging (replicate all companions to destination) ---
            try:
                for src, dst in planned_moves:
                    src_res = src.resolve()

                    # Self-Move Guard
                    if src_res == dst:
                        staged_dsts.append((src, dst))
                        continue

                    current_in_flight = (src, dst)
                    _copy_file(src, dst, fsync=True)
                    # Verify integrity
                    if dst.stat().st_size != src.stat().st_size:
                        raise IOError(f"Byte mismatch during cross-mount staging for {src.name}")
                    staged_dsts.append((src, dst))
                    current_in_flight = None
            except Exception as e:
                # Rollback Phase 1: remove the in-flight partial/corrupt replica and every staged
                # replica, leaving sources untouched. A replica that cannot be removed stays in
                # Selection/ and is reported (D-12).
                leftovers = self._remove_replicas(
                    self._in_flight_to_clean(current_in_flight, e) + staged_dsts
                )
                if leftovers:
                    names = ", ".join(s.name for s, _ in leftovers)
                    raise FileCullingError(
                        f"Cross-filesystem move failed during staging ({e}); copies could not be "
                        f"removed from {sel_dir.name}/: {names}; the originals are untouched",
                        partial_record=CullingRecord(
                            photo_id=candidate.id,
                            action_type=CullingActionType.MOVE,
                            original_primary_path=candidate.primary_path,
                            affected_paths=leftovers,
                            previous_status=PhotoStatus.CANDIDATE,
                            unremoved_sources=[s for s, _ in leftovers],
                            stranded=[d for _, d in leftovers],
                        ),
                    ) from e
                raise FileCullingError(f"Cross-filesystem move failed during staging, aborted safely: {e}") from e

            # --- Phase 2: Commit (unlink sources only after ALL destination replicas are verified) ---
            # Invariant: Once Phase 2 begins, destination replicas must NEVER be deleted.
            failed_unlinks: List[Tuple[Path, Exception]] = []
            for src, dst in staged_dsts:
                if src.resolve() != dst.resolve() and src.exists():
                    try:
                        src.unlink()
                    except Exception as unlink_err:
                        failed_unlinks.append((src, unlink_err))

            if failed_unlinks:
                failed_names = ", ".join(f"{s.name} ({err})" for s, err in failed_unlinks)
                logger.error(
                    "Cross-filesystem move commit partially succeeded: destination replicas preserved "
                    "in %s, but source file(s) could not be removed: %s",
                    sel_dir,
                    failed_names,
                )
                # The verified copies are the user's data now: hand back a record (marked partial
                # through `unremoved_sources`) so the caller can keep it for undo and report
                # "Copied to «folder» — original could not be removed" (FILE_OPERATIONS.md §4).
                partial_record = CullingRecord(
                    photo_id=candidate.id,
                    action_type=CullingActionType.MOVE,
                    original_primary_path=candidate.primary_path,
                    affected_paths=staged_dsts,
                    previous_status=PhotoStatus.CANDIDATE,
                    unremoved_sources=[s for s, _ in failed_unlinks],
                )
                raise FileCullingError(
                    f"Copied to {sel_dir.name} — original could not be removed: {failed_names}; "
                    f"destination replicas preserved",
                    partial_record=partial_record,
                )

            affected = staged_dsts

        candidate.status = PhotoStatus.SELECTED
        return CullingRecord(
            photo_id=candidate.id,
            action_type=CullingActionType.MOVE,
            original_primary_path=candidate.primary_path,
            affected_paths=affected,
            previous_status=PhotoStatus.CANDIDATE,
        )

    def copy_candidate(
        self,
        candidate: CandidatePhoto,
        root_dir: Path,
        selection_folder: Optional[str] = None,
        selection_folder_name: Optional[str] = None,
    ) -> CullingRecord:
        """Atomically copy candidate photo and all bound companions to Selection/ (R-LINUX-CULL-04)."""
        sel_dir = self._get_selection_dir(root_dir, selection_folder_name or selection_folder)
        planned_copies = self._resolve_bundle_destinations(candidate, sel_dir)
        targets = [src for src, _ in planned_copies]

        # Pre-flight capacity check
        self._verify_destination_capacity(sel_dir, targets)

        staged_copies: List[Tuple[Path, Path]] = []
        current_in_flight: Optional[Tuple[Path, Path]] = None
        try:
            for src, dst in planned_copies:
                src_res = src.resolve()

                # Self-Copy Guard: Idempotent no-op
                if src_res == dst:
                    staged_copies.append((src, dst))
                    continue

                current_in_flight = (src, dst)
                _copy_file(src, dst)
                if dst.stat().st_size != src.stat().st_size:
                    raise IOError(f"Byte mismatch copying {src.name}")
                staged_copies.append((src, dst))
                current_in_flight = None
        except Exception as e:
            # Clean up the in-flight partial/corrupt replica and every staged copy, never touching
            # originals. A copy that cannot be removed stays in Selection/ and is reported (D-12).
            leftovers = self._remove_replicas(
                self._in_flight_to_clean(current_in_flight, e) + staged_copies
            )
            if leftovers:
                names = ", ".join(s.name for s, _ in leftovers)
                raise FileCullingError(
                    f"Copy failed mid-flight ({e}); copies could not be removed from "
                    f"{sel_dir.name}/: {names}",
                    partial_record=CullingRecord(
                        photo_id=candidate.id,
                        action_type=CullingActionType.COPY,
                        original_primary_path=candidate.primary_path,
                        affected_paths=leftovers,
                        previous_status=PhotoStatus.CANDIDATE,
                        stranded=[d for _, d in leftovers],
                    ),
                ) from e
            raise FileCullingError(f"Atomic copy failed mid-flight, rolled back: {e}") from e

        candidate.status = PhotoStatus.COPIED
        return CullingRecord(
            photo_id=candidate.id,
            action_type=CullingActionType.COPY,
            original_primary_path=candidate.primary_path,
            affected_paths=staged_copies,
            previous_status=PhotoStatus.CANDIDATE,
        )

    def _resolve_undo_targets(
        self,
        record: CullingRecord,
        candidate: Optional[CandidatePhoto] = None,
    ) -> List[Tuple[Path, Path, Path]]:
        """Resolve collision-free ``(orig_src, restore_target, current_dst)`` triples for undo.

        If any original source path is now occupied by a file created after the operation, the
        entire restored bundle is assigned a shared collision-free ``-N`` suffix (``N >= 2``)
        in ``orig_src.parent`` so existing user files are never overwritten or deleted.
        """
        if not record.affected_paths:
            return []

        primary_name = (
            candidate.name_of(record.original_primary_path)
            if candidate is not None
            else record.original_primary_path.name
        )
        base_stem = Path(primary_name).stem

        display_by_parent: Dict[Path, Dict[str, str]] = {}

        def _display_names_in(parent: Path) -> Dict[str, str]:
            if parent not in display_by_parent:
                display_by_parent[parent] = self.name_resolver.names_in(parent)
            return display_by_parent[parent]

        def _orig_name(orig_src: Path) -> str:
            if candidate is not None:
                return candidate.name_of(orig_src)
            return orig_src.name

        def _is_self_move(orig_src: Path, current_dst: Path) -> bool:
            if record.action_type != CullingActionType.MOVE:
                return False
            if str(current_dst).startswith("trash://"):
                return False
            try:
                return orig_src.resolve() == current_dst.resolve()
            except OSError:
                return False

        def _target_occupied(parent: Path, name: str, current_dst: Path, allow_self: bool) -> bool:
            target = parent / name
            if allow_self and _is_self_move(target, current_dst):
                return False
            if target.exists() or target.is_symlink():
                return True
            if name in _display_names_in(parent).values():
                return True
            return False

        has_collision = any(
            not _is_self_move(orig_src, current_dst)
            and (
                orig_src.exists()
                or orig_src.is_symlink()
                or _target_occupied(orig_src.parent, _orig_name(orig_src), current_dst, allow_self=True)
            )
            for orig_src, current_dst in record.affected_paths
        )

        if not has_collision:
            return [
                (orig_src, orig_src, current_dst)
                for orig_src, current_dst in record.affected_paths
            ]

        suffix_num = 2
        while True:
            candidate_triples: List[Tuple[Path, Path, Path]] = []
            occupied = False
            for orig_src, current_dst in record.affected_paths:
                if _is_self_move(orig_src, current_dst):
                    candidate_triples.append((orig_src, orig_src, current_dst))
                    continue
                suffixed_name = _apply_bundle_suffix(_orig_name(orig_src), base_stem, suffix_num)
                if _target_occupied(orig_src.parent, suffixed_name, current_dst, allow_self=False):
                    occupied = True
                    break
                candidate_triples.append((orig_src, orig_src.parent / suffixed_name, current_dst))
            if not occupied:
                return candidate_triples
            suffix_num += 1

    def _update_candidate_restored_paths(
        self,
        candidate: CandidatePhoto,
        restored_pairs: List[Tuple[Path, Path]],
    ) -> None:
        """Update ``candidate`` paths when undo restores a bundle under a collision-free suffix."""
        path_map: Dict[Path, Path] = {}
        resolved_map: Dict[Path, Path] = {}
        for orig_src, restore_target in restored_pairs:
            path_map[orig_src] = restore_target
            try:
                resolved_map[orig_src.resolve()] = restore_target
            except OSError:
                pass

        def _map_path(p: Path) -> Path:
            if p in path_map:
                return path_map[p]
            try:
                res = p.resolve()
                if res in resolved_map:
                    return resolved_map[res]
            except OSError:
                pass
            return p

        old_primary = candidate.primary_path
        new_primary = _map_path(old_primary)
        candidate.primary_path = new_primary
        candidate.companion_paths = [_map_path(p) for p in candidate.companion_paths]

        for orig_src, restore_target in restored_pairs:
            if orig_src in candidate.display_names and restore_target != orig_src:
                candidate.display_names.pop(orig_src, None)
                candidate.display_names[restore_target] = restore_target.name

    @staticmethod
    def _safe_resolve(path: Path) -> Path:
        try:
            return path.resolve()
        except OSError:
            return path

    def undo_culling(self, record: CullingRecord, candidate: Optional[CandidatePhoto] = None) -> bool:
        """Atomically reverse culling operation with absolute source preservation (R-LINUX-CULL-06/07)."""
        if record.action_type == CullingActionType.COPY:
            # === Transactional Copy Undo ===
            # Strict Invariant: Delete ONLY the destination copy in Selection/.
            # NEVER modify, touch, or delete the original source media on disk!
            for src, dst in record.affected_paths:
                src_res = src.resolve()
                dst_res = dst.resolve()
                if dst_res != src_res and dst.exists():
                    try:
                        dst.unlink()
                    except Exception as e:
                        raise FileCullingError(f"Failed to delete destination copy during undo: {e}") from e
            if candidate:
                candidate.status = record.previous_status
            return True

        elif record.action_type == CullingActionType.MOVE:
            # === Transactional Move Undo (R-LINUX-CULL-07) ===
            # Restore files from Selection/ back to original source directory (with collision suffix if occupied)
            #
            # A partial move (copy verified, original not removable) left some originals in
            # place. Restoring their copies would put a "-2" duplicate next to the original
            # (D-3), so those copies are deleted instead (docs/shared/FILE_OPERATIONS.md §3);
            # a copy whose original has meanwhile disappeared is the only one left and is
            # restored like any other member.
            still_home: List[Tuple[Path, Path]] = []
            to_restore: List[Tuple[Path, Path]] = list(record.affected_paths)
            if record.unremoved_sources:
                to_restore = []
                unremoved = {self._safe_resolve(p) for p in record.unremoved_sources}
                for pair in record.affected_paths:
                    src_of_pair = pair[0]
                    if self._safe_resolve(src_of_pair) in unremoved and src_of_pair.exists():
                        still_home.append(pair)
                    else:
                        to_restore.append(pair)
            restore_record = dataclasses.replace(record, affected_paths=to_restore)
            undo_targets = self._resolve_undo_targets(restore_record, candidate)
            restored_pairs: List[Tuple[Path, Path]] = []
            completed_moves: List[Tuple[Path, Path]] = []
            try:
                for orig_src, restore_target, dst in undo_targets:
                    target_res = restore_target.resolve()
                    dst_res = dst.resolve()
                    if dst_res != target_res and dst.exists():
                        restore_target.parent.mkdir(parents=True, exist_ok=True)
                        if self._dev_of(restore_target.parent) == self._dev_of(dst):
                            _move_no_overwrite(dst, restore_target)
                        else:
                            _copy_file(dst, restore_target, fsync=True)
                            if restore_target.stat().st_size != dst.stat().st_size:
                                raise IOError(f"Byte mismatch restoring {dst.name}")
                            dst.unlink()
                        completed_moves.append((restore_target, dst))
                    restored_pairs.append((orig_src, restore_target))
            except Exception as e:
                # A member that cannot be put back into Selection/ stays at its restored path;
                # it is named, never dropped silently (OPEN-undo-swallow).
                stranded: List[Path] = []
                for moved_target, orig_dst in reversed(completed_moves):
                    if moved_target.exists() and not orig_dst.exists():
                        try:
                            if self._dev_of(orig_dst.parent) == self._dev_of(moved_target):
                                _move_no_overwrite(moved_target, orig_dst)
                            else:
                                _copy_file(moved_target, orig_dst, fsync=True)
                                moved_target.unlink()
                        except Exception as rollback_err:
                            logger.error("Undo rollback could not put %s back: %s", orig_dst.name, rollback_err)
                            stranded.append(moved_target)
                if stranded:
                    stranded.reverse()
                    names = ", ".join(p.name for p in stranded)
                    raise FileCullingError(
                        f"Failed to restore moved file during undo: {e}; "
                        f"could not be put back into {stranded[0].parent.name}/: {names}"
                    ) from e
                raise FileCullingError(f"Failed to restore moved file during undo: {e}") from e

            for src, dst in still_home:
                if dst.exists() and self._safe_resolve(dst) != self._safe_resolve(src):
                    try:
                        dst.unlink()
                    except Exception as e:
                        raise FileCullingError(
                            f"Failed to delete the copy of {src.name} during undo: {e}"
                        ) from e

            if candidate:
                self._update_candidate_restored_paths(candidate, restored_pairs)
                self._relink_restored(candidate)
                candidate.status = record.previous_status
            return True

        elif record.action_type == CullingActionType.TRASH:
            # === Transactional Trash Undo (R-LINUX-CULL-07) ===
            # Restore trashed primary image and all companions from their exact recorded Trash paths
            # A retry after a half-failed undo: a member whose trash item is gone and whose
            # original path exists again was restored by the earlier attempt (D-TRASH-UNDO).
            already_restored: List[Tuple[Path, Path]] = []
            pending: List[Tuple[Path, Path]] = []
            for pair in record.affected_paths:
                orig, trashed = pair
                trash_gone = (
                    trashed is None
                    or (not str(trashed).startswith("trash://") and not trashed.exists() and not trashed.is_symlink())
                )
                if trash_gone and (orig.exists() or orig.is_symlink()):
                    already_restored.append((orig, orig))
                else:
                    pending.append(pair)
            undo_targets = self._resolve_undo_targets(
                dataclasses.replace(record, affected_paths=pending), candidate
            )
            for orig_src, _restore_target, dst in undo_targets:
                if dst is None or str(dst).startswith("trash://") or (not dst.exists() and not dst.is_symlink()):
                    raise FileCullingError(
                        f"Failed to restore trashed file {orig_src.name} during undo: "
                        f"recorded trash item {dst} is missing"
                    )
            restored_pairs = list(already_restored)
            for orig_src, restore_target, dst in undo_targets:
                try:
                    info_path = record.trash_info_paths.get(orig_src)
                    self._restore_from_trash(
                        orig_src,
                        dst,
                        restore_target=restore_target,
                        info_path=info_path,
                    )
                    restored_pairs.append((orig_src, restore_target))
                except FileCullingError:
                    raise
                except Exception as e:
                    raise FileCullingError(f"Failed to restore trashed file {orig_src.name} during undo: {e}") from e
            if candidate:
                self._update_candidate_restored_paths(candidate, restored_pairs)
                self._relink_restored(candidate)
                candidate.status = record.previous_status
            return True

        return False
