"""Opening a folder: the streaming scan and its main-loop callbacks (split out of ``culling_workspace.py``)."""

from __future__ import annotations

import logging

import os
from pathlib import Path
import threading
from typing import List, Optional, Tuple

from photo_selector_linux.core.copy_decisions import photo_key
from photo_selector_linux.core.gvfs_names import is_remote_filesystem
from photo_selector_linux.core.models import (
    CandidatePhoto,
)
from photo_selector_linux.core.scanner import DirectoryScanner


logger = logging.getLogger(__name__)


class ScanLoadingMixin:
    """Opens a folder, takes scan batches in, restores stored copy decisions."""

    def _make_scanner(self) -> DirectoryScanner:
        if self._scanner_factory is not None:
            return self._scanner_factory(self.selection_folder_name)
        if self._custom_scanner is None:
            self.scanner = DirectoryScanner(custom_selection_folder=self.selection_folder_name)
        return self.scanner

    def _remember_undo_stack(self, folder: Optional[Path]) -> None:
        """Park the live undo stack under ``folder`` before another one is opened (Q-7)."""
        if folder is None or not self.undo_stack:
            return
        self._folder_undo_history[folder] = self.undo_stack
        while len(self._folder_undo_history) > self.MAX_REMEMBERED_FOLDERS:
            oldest = next(iter(self._folder_undo_history))
            for record, _photo, _idx in self._folder_undo_history.pop(oldest):
                self._undo_actions.pop(record.id, None)

    def load_directory(self, folder: Path, select: Optional[Path] = None) -> None:
        """Stream photographs from ``folder`` in depth-first per-folder natural order (U-06b, UD-9).

        Cancels any in-flight scan from a previous folder via ``_folder_generation``, while allowing
        queued file operations from the previous folder to finish draining on the worker.

        With ``scan_idle_add`` set (the window sets it) nothing here touches the disk: resolving the
        path, asking the mount table and listing the parent through GVFS (a network round trip on a
        cloud mount) happen on the scan thread, and the main loop only receives the results. Without
        it (tests, headless use) the same steps run inline.

        ``select`` names a photo of ``folder`` (a file opened from the file manager): the view
        lands on it as soon as the scan reaches it.
        """
        previous_folder = self.current_folder
        self._cancel_scoring_pass()
        self._capture.cancel(forget=True)
        self._scoring_confirmed = False
        self._select_name = select.name if select is not None else None
        self._user_navigated = False
        self._folder_generation += 1
        generation = self._folder_generation
        scanner = self._make_scanner()
        threaded = self.scan_idle_add is not None

        if threaded:
            # Provisional, no I/O; the scan thread reports the resolved folder, the filesystem kind
            # and the display name before the first photograph arrives.
            self.current_folder = Path(os.path.abspath(folder))
            self.current_folder_name = self.current_folder.name
            self.is_remote_folder = False
        else:
            self.current_folder = folder.resolve()
            self.is_remote_folder, self.current_folder_name = self._describe_folder(
                scanner, self.current_folder
            )

        self.candidates = []
        self._list_version += 1
        self._natural_keys = {}
        self.current_index = 0
        self._remember_undo_stack(previous_folder)
        self.undo_stack = [] if threaded else self._folder_undo_history.pop(self.current_folder, [])
        self.selected_count = 0
        self.copied_count = 0
        self.trashed_count = 0
        self.is_scanning = True
        self.scan_failed = False

        if threaded:
            requested = folder
            idle_add = self.scan_idle_add

            def _scan_worker() -> None:
                try:
                    resolved = requested.resolve()
                    remote, name = self._describe_folder(scanner, resolved)
                    idle_add(self._on_scan_prepared, generation, resolved, remote, name)
                    for batch in scanner.scan_stream(resolved, batch_size=30, first_batch_size=1):
                        if generation != self._folder_generation:
                            return
                        idle_add(self._on_scan_batch, generation, list(batch))
                except Exception as e:
                    idle_add(self._on_scan_failed, generation, str(e))
                    return
                if generation == self._folder_generation:
                    idle_add(self._on_scan_complete, generation)

            thread = threading.Thread(
                target=_scan_worker,
                name="directory-scanner",
                daemon=True,
            )
            self._scan_thread = thread
            # The window already names the folder and shows "Reading <folder>" before the first
            # photo arrives, instead of "No folder loaded".
            self._notify_state_changed()
            thread.start()
            return

        # Synchronous streaming path (default when scan_idle_add is None)
        notified_any = False
        for batch in scanner.scan_stream(
            self.current_folder,
            batch_size=30,
            first_batch_size=1,
        ):
            if generation != self._folder_generation:
                return
            self._add_scanned(batch)
            self._select_requested_photo(batch)
            notified_any = True
            self._notify_state_changed()

        if generation != self._folder_generation:
            return
        self.is_scanning = False
        self._prune_copy_decisions()
        self._notify(f"Loaded {len(self.candidates)} photos from {self.current_folder_name}")
        if not notified_any:
            self._notify_state_changed()
        self._begin_scoring_pass()
        self._begin_capture_pass()

    @staticmethod
    def _describe_folder(scanner: DirectoryScanner, folder: Path) -> Tuple[bool, str]:
        """``(is_remote, display name)`` of a resolved folder; reads the mount table and GVFS."""
        parent_names = scanner.name_resolver.names_in(folder.parent)
        return is_remote_filesystem(folder), parent_names.get(folder.name, folder.name)

    def _on_scan_prepared(self, generation: int, folder: Path, is_remote: bool, name: str) -> bool:
        if generation != self._folder_generation:
            return False
        self.current_folder = folder
        self.is_remote_folder = is_remote
        self.current_folder_name = name
        self.undo_stack = self._folder_undo_history.pop(folder, self.undo_stack)
        self._notify_state_changed()
        return False

    def _on_scan_failed(self, generation: int, reason: str) -> bool:
        if generation != self._folder_generation:
            return False
        self.is_scanning = False
        self.scan_failed = True
        self._notify(f"Could not read {self.current_folder_name}: {reason}", severity="error")
        self._notify_state_changed()
        return False

    def _on_scan_batch(self, generation: int, batch: List[CandidatePhoto]) -> bool:
        if generation != self._folder_generation:
            return False
        self._add_scanned(batch)
        self._select_requested_photo(batch)
        self._notify_state_changed()
        return False

    def _select_requested_photo(self, batch: Optional[List[CandidatePhoto]] = None) -> None:
        """Land on the photo the folder was opened for, once it has been scanned.

        Only the batch that just arrived can hold it (the earlier ones were looked at already), so
        each batch costs its own size and not the list's (BL-P-1).
        """
        name = self._select_name
        if name is None or self.current_folder is None:
            return
        for candidate in self.candidates if batch is None else batch:
            if any(
                p.parent == self.current_folder and candidate.name_of(p) == name
                for p in candidate.all_paths
            ):
                idx = self._index_of(candidate)
                if idx is None:
                    continue
                self.current_index = idx
                self._select_name = None
                self._user_navigated = True  # opened on this photo: later batches keep it on screen
                return

    def _on_scan_complete(self, generation: int) -> bool:
        if generation != self._folder_generation:
            return False
        self.is_scanning = False
        self._prune_copy_decisions()
        self._notify(f"Loaded {len(self.candidates)} photos from {self.current_folder_name}")
        self._notify_state_changed()
        self._begin_scoring_pass()
        self._begin_capture_pass()
        return False

    def _restore_copy_decisions(self, batch: List[CandidatePhoto]) -> None:
        """A reopened folder: photos copied in an earlier session are decided, not undecided again."""
        store, folder = self.copy_decisions, self.current_folder
        if store is None or folder is None:
            return
        entries = store.folder_entries(folder)
        if not entries:
            return
        for photo in batch:
            key = photo_key(folder, photo.primary_path)
            entry = entries.get(key)
            if entry is None or photo.copied:
                continue
            size = entry.get("size")
            if size is not None and photo.file_size not in (size, 0):
                store.forget(folder, key)  # another file under the same name
                continue
            destination = entry.get("to")
            if destination and not os.path.exists(destination):
                store.forget(folder, key)  # the copy was removed from the Selection since
                continue
            photo.copied = True
            self.copied_count += 1
            self.selected_count += 1

    def _prune_copy_decisions(self) -> None:
        """Forget copied photos that are no longer in the folder (moved or trashed since)."""
        folder = self.current_folder
        if self.copy_decisions is None or folder is None or self.scan_failed:
            return
        if self._queue or self._running_action is not None:
            return  # an operation in flight may still bring one back (undo)
        self.copy_decisions.prune(folder, {photo_key(folder, c.primary_path) for c in self.candidates})
