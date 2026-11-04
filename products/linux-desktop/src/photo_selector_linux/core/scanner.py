"""Recursive progressive directory scanner with companion binding and dynamic exclusion.

Adheres to:
- R-LINUX-META-01: Supported File Formats
- R-LINUX-META-04: Companion RAW+JPEG, Sidecar & Edit Binding
- R-LINUX-META-05: Dynamic Selection Subfolder Exclusion Invariant
- R-LINUX-CULL-01: Progressive Asynchronous Streaming Ingestion
- R-LINUX-CULL-02: Natural Alphanumeric Sorting
"""

from __future__ import annotations

import os
import re
from pathlib import Path
from typing import Dict, Generator, Iterable, List, Optional, Sequence, Set, Tuple, Union

from photo_selector_linux.core.gvfs_names import DisplayNameResolver, default_resolver
from photo_selector_linux.core.formats import (  # noqa: F401 — re-exported for callers
    BITMAP_EXTENSIONS,
    RAW_EXTENSIONS,
    SUPPORTED_EXTENSIONS,
    extension_of,
)
from photo_selector_linux.core.models import CandidatePhoto


DEFAULT_EXCLUDED_FOLDERS: Set[str] = {
    "selection",
    "selected",
    "phototok_selection",
    "phototok_leftswipe",
}
SELECTION_MARKER_FILENAME: str = ".photoselector-selection"


def _extension_of(name: str) -> str:
    """Lower-case extension of a user-facing filename, without the dot ("" when there is none)."""
    return extension_of(name)


def _is_file_entry(entry: os.DirEntry) -> bool:
    """``DirEntry.is_file`` that treats unstat-able entries as non-files.

    Google Drive shortcuts under GVFS fail ``stat`` with ENOSYS; one such entry must not abort
    the listing of its whole directory.
    """
    try:
        return entry.is_file()
    except OSError:
        return False


def natural_sort_key(s: str) -> List[Union[int, str]]:
    """Produce natural alphanumeric sort key (e.g. DSC_0002 before DSC_0010)."""
    return [int(text) if text.isdigit() else text.lower() for text in re.split(r"(\d+)", s)]


_EDIT_MARKERS = ("-edit", "_edit", "-enhanced-nr", "_enhanced-nr")


class _CompanionIndex:
    """One directory listing, indexed once so grouping a photo costs O(its group), not O(directory).

    A capture ``S.<ext>`` binds: every file with stem ``S`` and a supported extension or ``xmp``;
    ``<name>.xmp`` darktable-style sidecars of **every** member of the group (BL-D-1: the sidecar
    of a NEF that sorts after its JPG is no different from one of an ARW that sorts before it);
    and Lightroom derivatives whose stem starts ``S-edit`` / ``S_edit`` / ``S-enhanced-nr`` /
    ``S_enhanced-nr``.
    """

    def __init__(self, entries: Sequence[Tuple[Path, str]]) -> None:
        self._by_stem: Dict[str, List[Tuple[Path, str]]] = {}
        self._by_name: Dict[str, List[Tuple[Path, str]]] = {}
        self._by_edit_base: Dict[str, List[Tuple[Path, str]]] = {}
        for entry_path, entry_name in entries:
            lowered = entry_name.lower()
            if lowered.startswith("."):
                continue
            item = (entry_path, entry_name)
            stem = Path(lowered).stem
            ext = _extension_of(lowered)
            if ext in SUPPORTED_EXTENSIONS or ext == "xmp":
                self._by_stem.setdefault(stem, []).append(item)
            self._by_name.setdefault(lowered, []).append(item)
            seen_bases: Set[str] = set()
            for marker in _EDIT_MARKERS:
                at = stem.find(marker)
                while at != -1:
                    base = stem[:at]
                    if base not in seen_bases:
                        seen_bases.add(base)
                        self._by_edit_base.setdefault(base, []).append(item)
                    at = stem.find(marker, at + 1)

    def group_for(self, target: Path, target_name: str) -> List[Path]:
        stem = Path(target_name.lower()).stem
        companions: Dict[Path, str] = {target: target_name}
        for path, name in self._by_stem.get(stem, ()):
            companions[path] = name
        for path, name in self._by_edit_base.get(stem, ()):
            companions[path] = name
        for member_name in [name for name in companions.values() if _extension_of(name.lower()) != "xmp"]:
            for path, name in self._by_name.get(f"{member_name.lower()}.xmp", ()):
                companions[path] = name
        return sorted(companions, key=lambda p: natural_sort_key(companions[p]))


class DirectoryScanner:
    """High-performance directory scanner for SD cards and local folders."""

    def __init__(
        self,
        custom_selection_folder: Optional[str] = None,
        default_exclusions: Optional[Set[str]] = None,
        handle_companions: bool = True,
        name_resolver: Optional[DisplayNameResolver] = None,
    ):
        self.handle_companions: bool = bool(handle_companions)
        self.name_resolver: DisplayNameResolver = name_resolver or default_resolver()
        self.excluded_names: Set[str] = set(default_exclusions or DEFAULT_EXCLUDED_FOLDERS)
        if custom_selection_folder:
            cleaned = str(custom_selection_folder).strip().rstrip("/\\").strip()
            sanitized = Path(cleaned).name.strip().lower() if cleaned else ""
            if sanitized:
                self.excluded_names.add(sanitized)

    def is_path_excluded(self, dir_path: Path, root_path: Path) -> bool:
        """Check if dir_path has any relative path component matching excluded names or marker file (R-LINUX-META-05).

        If dir_path is identical to root_path, it is NEVER excluded (root scan exception).
        """
        try:
            root_resolved = root_path.resolve()
            rel = dir_path.resolve().relative_to(root_resolved)
        except ValueError:
            return False

        if not rel.parts:
            return False

        parts = [p.lower() for p in rel.parts]
        if any(p in self.excluded_names for p in parts):
            return True

        curr = root_resolved
        for part in rel.parts:
            curr = curr / part
            try:
                if (curr / SELECTION_MARKER_FILENAME).exists():
                    return True
            except OSError:
                pass
        return False

    @staticmethod
    def _companions_in_listing(
        target: Path,
        target_name: str,
        entries: Sequence[Tuple[Path, str]],
    ) -> List[Path]:
        """Group ``target`` with its companions from one directory listing of ``(path, display_name)``.

        Matching uses display names, so it works where on-disk names are opaque IDs (GVFS). Callers
        grouping many photos of one listing build a :class:`_CompanionIndex` once instead.
        """
        return _CompanionIndex(entries).group_for(target, target_name)

    def _resolve_primary_and_companions(
        self,
        group: Iterable[Path],
        names: Optional[Dict[Path, str]] = None,
    ) -> Tuple[Path, List[Path]]:
        """Select RAW as primary if present, otherwise sort alphabetically (R-LINUX-META-04)."""
        names = names or {}

        def name_of(p: Path) -> str:
            return names.get(p, p.name)

        paths = list(group)
        raws = [p for p in paths if _extension_of(name_of(p)) in RAW_EXTENSIONS]
        pool = raws if raws else paths
        primary = sorted(pool, key=lambda p: natural_sort_key(name_of(p)))[0]
        companions = [p for p in paths if p != primary]
        return primary, companions

    def scan_stream(
        self,
        root_path: Path,
        recursive: bool = True,
        batch_size: int = 30,
        first_batch_size: Optional[int] = None,
    ) -> Generator[List[CandidatePhoto], None, None]:
        """Progressively stream CandidatePhoto batches in depth-first per-folder natural order (R-LINUX-CULL-01/02).

        Each directory is listed once; companions are grouped from that listing in memory.
        """
        root_path = root_path.resolve()
        if not root_path.exists():
            return

        current_batch: List[CandidatePhoto] = []
        yielded_first = False
        target_size = max(1, first_batch_size) if first_batch_size is not None else max(1, batch_size)

        if recursive:
            walk_gen = os.walk(root_path, followlinks=False)
        else:
            try:
                top_files = [e.name for e in os.scandir(root_path) if _is_file_entry(e)]
            except OSError:
                top_files = []
            walk_gen = [(str(root_path), [], top_files)]

        for root_str, dirnames, filenames in walk_gen:
            curr_dir = Path(root_str)

            # Dynamic Selection Subfolder Exclusion (R-LINUX-META-05)
            if curr_dir != root_path and self.is_path_excluded(curr_dir, root_path):
                dirnames.clear()  # Prune subtree
                continue

            # Real names for opaque on-disk names (Google Drive via GVFS); empty when local.
            display = self.name_resolver.names_in(curr_dir)

            # Prune child directories that match exclusions and sort remaining in natural order (UD-9)
            dirnames[:] = sorted(
                [
                    d for d in dirnames
                    if display.get(d, d).strip().lower() not in self.excluded_names
                    and not self.is_path_excluded(curr_dir / d, root_path)
                ],
                key=lambda d: natural_sort_key(display.get(d, d)),
            )
            entries: List[Tuple[Path, str]] = [
                (curr_dir / fname, display.get(fname, fname)) for fname in filenames
            ]
            names: Dict[Path, str] = dict(entries)

            # Filter valid candidate files in current directory, sorted naturally by name
            valid_files = [
                (path, name) for path, name in entries
                if not name.startswith(".") and _extension_of(name) in SUPPORTED_EXTENSIONS
            ]
            valid_files.sort(key=lambda item: natural_sort_key(item[1]))

            index = _CompanionIndex(entries) if self.handle_companions else None
            first_by_stem: Dict[str, Tuple[Path, str]] = {}
            for path, name in valid_files:
                first_by_stem.setdefault(Path(name).stem.lower(), (path, name))

            claimed_paths: Set[Path] = set()
            for file_path, file_name in valid_files:
                if file_path in claimed_paths:
                    continue

                # An Edit file whose base capture exists in the same folder groups with that base
                search_target, search_name = file_path, file_name
                if index is not None:
                    stem_lower = Path(file_name).stem.lower()
                    for edit_marker in _EDIT_MARKERS:
                        if edit_marker in stem_lower:
                            base = first_by_stem.get(stem_lower.split(edit_marker)[0])
                            if base is not None:
                                search_target, search_name = base
                            break
                    group = index.group_for(search_target, search_name)
                else:
                    group = [file_path]
                group = [p for p in group if p not in claimed_paths]
                claimed_paths.update(group)

                primary, companions = self._resolve_primary_and_companions(group, names)
                try:
                    file_size = primary.stat().st_size
                except OSError:
                    file_size = 0

                candidate = CandidatePhoto(
                    primary_path=primary,
                    companion_paths=companions,
                    _file_size=file_size,
                    display_names={p: names[p] for p in group if names.get(p, p.name) != p.name},
                )

                current_batch.append(candidate)
                if len(current_batch) >= target_size:
                    yield current_batch
                    current_batch = []
                    if not yielded_first:
                        yielded_first = True
                        target_size = max(1, batch_size)

        if current_batch:
            yield current_batch

    def scan_directory(self, root_path: Path, recursive: bool = True) -> List[CandidatePhoto]:
        """Enumerate and return all candidates in depth-first per-folder natural order (R-LINUX-CULL-02)."""
        candidates: List[CandidatePhoto] = []
        for batch in self.scan_stream(root_path, recursive=recursive, batch_size=50):
            candidates.extend(batch)

        for candidate in candidates:
            if candidate._file_size is None:
                try:
                    candidate._file_size = candidate.primary_path.stat().st_size
                except OSError:
                    candidate._file_size = 0

        return candidates


def scan_directory(
    root_path: Path,
    recursive: bool = True,
    custom_selection_folder: Optional[str] = None,
) -> List[CandidatePhoto]:
    """Convenience functional interface for directory scanning."""
    scanner = DirectoryScanner(custom_selection_folder=custom_selection_folder)
    return scanner.scan_directory(root_path, recursive=recursive)
