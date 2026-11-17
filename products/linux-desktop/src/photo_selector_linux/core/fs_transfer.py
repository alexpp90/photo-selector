"""Low-level file transfers of the culling engine: bundle naming, no-overwrite move and copy.

Moved out of ``file_ops.py`` unchanged (re-exported there; ``file_ops._copy_file`` and
``file_ops._move_no_overwrite`` stay the names the manager calls and tests patch).
"""

from __future__ import annotations

import errno
import logging
import os
import shutil
import threading
import uuid
from pathlib import Path
from typing import List, Optional, Tuple

from photo_selector_linux.core.gvfs_names import (
    DisplayNameResolver,
    is_gvfs_fuse_path,
)
from photo_selector_linux.core.models import CandidatePhoto


logger = logging.getLogger(__name__)

_RENAME_LOCK = threading.Lock()


def _apply_bundle_suffix(name: str, base_stem: str, suffix_num: int) -> str:
    """Insert ``-{suffix_num}`` into ``name`` at the bundle's primary stem boundary.

    Preserves companion pairing across primary/twin files (``IMG_0001-2.CR3``, ``IMG_0001-2.JPG``),
    file- and stem-level sidecars (``IMG_0001-2.CR3.xmp``, ``IMG_0001-2.xmp``), and Lightroom
    derivatives (``IMG_0001-2-Edit.tif``, ``IMG_0001-2_Edit.tif``, ``IMG_0001-2-Enhanced-NR.dng``).
    """
    if suffix_num < 2:
        return name

    if base_stem and name[: len(base_stem)].lower() == base_stem.lower():
        remainder = name[len(base_stem):]
        if not remainder or remainder[0] in (".", "-", "_"):
            prefix = name[: len(base_stem)]
            return f"{prefix}-{suffix_num}{remainder}"

    lower = name.lower()
    if lower.endswith(".xmp"):
        inner = name[:-4]
        outer_ext = name[-4:]
        inner_path = Path(inner)
        if inner_path.suffix:
            return f"{inner_path.stem}-{suffix_num}{inner_path.suffix}{outer_ext}"
        return f"{inner}-{suffix_num}{outer_ext}"

    p = Path(name)
    stem = p.stem
    ext = p.suffix
    stem_lower = stem.lower()
    for marker in ("-edit", "_edit", "-enhanced-nr", "_enhanced-nr"):
        idx = stem_lower.find(marker)
        if idx > 0:
            return f"{stem[:idx]}-{suffix_num}{stem[idx:]}{ext}"

    return f"{stem}-{suffix_num}{ext}"


def _resolve_bundle_destinations(
    candidate: CandidatePhoto,
    dest_dir: Path,
    name_resolver: Optional[DisplayNameResolver] = None,
) -> List[Tuple[Path, Path]]:
    """Resolve collision-free ``(src, dst)`` pairs for all existing files in ``candidate``.

    Checks both on-disk paths in ``dest_dir`` and GVFS display names reported by ``name_resolver``.
    If any member collides with an existing destination file (and is not a self-move/copy), the
    entire bundle receives the lowest integer suffix ``-N`` (``N >= 2``) for which every member
    is collision-free.
    """
    existing_sources = [src for src in candidate.all_paths if src.exists()]
    if not existing_sources:
        return []

    base_stem = candidate.stem
    display_map = name_resolver.names_in(dest_dir) if name_resolver is not None else {}
    existing_display_names = set(display_map.values())
    display_to_ondisk = {
        display: (dest_dir / on_disk).resolve()
        for on_disk, display in display_map.items()
    }

    def _is_occupied(src: Path, target_name: str, allow_self: bool) -> bool:
        dst = (dest_dir / target_name).resolve()
        src_res = src.resolve()
        if allow_self and src_res == dst:
            return False
        if allow_self and target_name in display_to_ondisk and display_to_ondisk[target_name] == src_res:
            return False
        if dst.exists() or dst.is_symlink():
            return True
        if target_name in existing_display_names:
            return True
        return False

    has_collision = any(
        _is_occupied(src, candidate.name_of(src), allow_self=True)
        for src in existing_sources
    )

    if not has_collision:
        return [
            (src, (dest_dir / candidate.name_of(src)).resolve())
            for src in existing_sources
        ]

    suffix_num = 2
    while True:
        suffixed_pairs = [
            (src, _apply_bundle_suffix(candidate.name_of(src), base_stem, suffix_num))
            for src in existing_sources
        ]
        if not any(
            _is_occupied(src, suffixed_name, allow_self=False)
            for src, suffixed_name in suffixed_pairs
        ):
            return [
                (src, (dest_dir / suffixed_name).resolve())
                for src, suffixed_name in suffixed_pairs
            ]
        suffix_num += 1


def _copy_file(src: Path, dst: Path, fsync: bool = False) -> None:
    """Copy content exclusively (never overwriting an existing file), then permissions and timestamps.

    On a local filesystem the bytes go to a hidden ``.<name>.<id>.part`` beside ``dst`` and appear
    under the real name only when complete (BL-D-4): a power loss or ``kill -9`` mid-copy leaves a
    dot-file the scanner ignores, never a truncated photo under the name the next Copy would then
    suffix around. Opens with ``O_WRONLY | O_CREAT | O_EXCL`` so nothing existing is truncated, and
    the final step never overwrites ``dst`` (``FileExistsError``). When ``fsync=True``, flushes
    file data to disk before closing. GVFS cloud mounts (Google Drive) reject ``utime``/``chmod``
    with ENOTSUP, which is ignored so copies and cross-mount moves succeed; they are written in
    place, since a rename there changes the file's identity.
    """
    if dst.exists() or dst.is_symlink():
        raise FileExistsError(errno.EEXIST, os.strerror(errno.EEXIST), str(dst))
    staged = dst if is_gvfs_fuse_path(dst) else dst.with_name(
        f".{dst.name[:100]}.{uuid.uuid4().hex[:8]}.part"
    )
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    fd = os.open(staged, flags, 0o666)
    try:
        try:
            fobj = os.fdopen(fd, "wb")
        except BaseException:
            os.close(fd)
            raise
        with fobj as fdst:
            with open(src, "rb") as fsrc:
                shutil.copyfileobj(fsrc, fdst, length=1024 * 1024)
            fdst.flush()
            if fsync:
                try:
                    os.fsync(fdst.fileno())
                except OSError as e:
                    if e.errno not in (errno.EINVAL, errno.ENOTSUP, errno.EOPNOTSUPP):
                        raise
        if staged != dst:
            _move_no_overwrite(staged, dst)
    except BaseException:
        if staged.exists() or staged.is_symlink():
            try:
                staged.unlink()
            except OSError:
                pass
        raise
    try:
        shutil.copystat(src, dst)
    except OSError as e:
        logger.debug("Metadata not preserved for %s: %s", dst, e)


def _entry_path(path: Path) -> Path:
    """``path`` with its folder resolved but the last component left alone.

    A symlinked photo is its own directory entry: Trash and Undo act on the link, never on the
    original it points at (BL-D-3). ``Path.resolve()`` would follow the link.
    """
    return path.parent.resolve() / path.name


def _move_no_overwrite(src: Path, dst: Path) -> None:
    """Atomically move ``src`` to ``dst`` on the same filesystem without ever overwriting ``dst``.

    Uses ``os.link`` + ``os.unlink`` on POSIX filesystems that support hardlinks (fails atomically
    with ``FileExistsError`` if ``dst`` exists), falling back to an existence check + ``os.rename``
    under ``_RENAME_LOCK`` for filesystems where hardlinks are unsupported (FAT/exFAT/GVFS) or symlinks.
    """
    with _RENAME_LOCK:
        if dst.exists() or dst.is_symlink():
            raise FileExistsError(errno.EEXIST, os.strerror(errno.EEXIST), str(dst))
        if not src.is_symlink():
            try:
                os.link(src, dst)
            except FileExistsError:
                raise
            except OSError:
                pass
            else:
                try:
                    os.unlink(src)
                except BaseException:
                    try:
                        os.unlink(dst)
                    except OSError:
                        pass
                    raise
                return
        if dst.exists() or dst.is_symlink():
            raise FileExistsError(errno.EEXIST, os.strerror(errno.EEXIST), str(dst))
        os.rename(src, dst)
