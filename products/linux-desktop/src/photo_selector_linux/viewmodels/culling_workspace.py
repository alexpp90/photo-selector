"""Pure-Python decoupled ViewModel for culling workspace state machine.

Adheres to:
- R-LINUX-UI-04/05/06: 1-Up, 2-Up, and 3-Up Focus Mode transitions
- R-LINUX-UI-10: Queued non-blocking background file operations with optimistic UI updates
- R-LINUX-CULL-01/02: Streaming directory ingestion in depth-first per-folder natural order
- R-LINUX-CULL-03/04/05/06/07: Transactional Move, Copy, Trash, and Undo operations
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import threading
from typing import Any, Callable, Dict, List, Optional, Tuple

from photo_selector_linux.core.file_ops import FileCullingManager
from photo_selector_linux.core.gvfs_names import is_remote_filesystem
from photo_selector_linux.core.models import (
    CandidatePhoto,
    CullingActionType,
    CullingRecord,
    QualityScores,
    SharpnessCategory,
)
from photo_selector_linux.core.scanner import DirectoryScanner, natural_sort_key
from photo_selector_linux.core.settings import SettingsStore
from photo_selector_linux.core.statistics import record_decision_log


# A file operation, and what to do with its (result, error) once it finished.
Job = Callable[[], Any]
JobDone = Callable[[Any, Optional[BaseException]], None]
Dispatcher = Callable[[Job, JobDone], None]


def run_inline(job: Job, done: JobDone) -> None:
    """Dispatcher running the job on the calling thread (tests, headless use)."""
    try:
        result = job()
    except Exception as e:
        done(None, e)
        return
    done(result, None)


@dataclass
class _QueuedAction:
    """Represents a pending, running, or completed file operation in the FIFO queue (U-08)."""

    kind: str  # "move", "copy", "trash", "undo"
    action_type: CullingActionType
    photo: CandidatePhoto
    orig_idx: int
    folder: Path
    selection_folder: str
    generation: int
    busy_message: str
    status: str = "pending"  # "pending", "running", "done", "cancelled", "failed"
    record: Optional[CullingRecord] = None
    target_action: Optional[_QueuedAction] = None
    #: The folder whose workspace (and undo stack) this action belongs to (Q-7).
    workspace: Optional[Path] = None


class CullingWorkspaceViewModel:
    """Decoupled presentation state machine driving navigation, comparison, and culling.

    File operations go through a FIFO queue dispatched to ``dispatcher`` one at a time so rapid
    culling keys (``M``, ``C``, ``Delete``, ``Ctrl+Z``) never drop while preserving undo order.
    """

    MAX_REMEMBERED_FOLDERS = 8

    def __init__(
        self,
        file_manager: Optional[FileCullingManager] = None,
        dispatcher: Optional[Dispatcher] = None,
        settings: Optional[SettingsStore] = None,
        scan_idle_add: Optional[Callable[..., Any]] = None,
        scanner: Optional[DirectoryScanner] = None,
        scanner_factory: Optional[Callable[[str], DirectoryScanner]] = None,
        auto_advance: Optional[bool] = None,
        external_destination_root: Optional[Path] = None,
        decision_log_path: Optional[Path] = None,
    ):
        self.candidates: List[CandidatePhoto] = []
        self.current_index: int = 0
        self.comparison_mode: int = 1  # 1: 1-Up, 2: 2-Up, 3: 3-Up Focus Mode
        self.current_folder: Optional[Path] = None
        self.current_folder_name: str = ""
        self.is_remote_folder: bool = False
        self.is_scanning: bool = False

        self.settings: Optional[SettingsStore] = settings
        self.selection_folder_name: str = settings.selection_folder_name if settings else "Selection"
        self.auto_advance: bool = (
            bool(auto_advance)
            if auto_advance is not None
            else (settings.auto_advance if settings else True)
        )
        self.blurry_threshold: float = settings.blurry_threshold if settings else 35.0
        self.sharp_threshold: float = settings.sharp_threshold if settings else 70.0
        self.cache_limit_mb: float = settings.cache_limit_mb if settings else 512.0
        self.external_destination_root: Optional[Path] = (
            Path(external_destination_root)
            if external_destination_root is not None
            else (
                Path(settings.external_destination_folder)
                if (settings and settings.external_destination_folder)
                else None
            )
        )
        self.stop_sharpness_threshold: Optional[float] = (
            settings.stop_sharpness_threshold if settings else None
        )
        self.sort_order: str = "natural"
        self.decision_log_path: Optional[Path] = decision_log_path

        self.selected_count: int = 0
        self.copied_count: int = 0
        self.trashed_count: int = 0
        self.undo_stack: List[Tuple[CullingRecord, CandidatePhoto, int]] = []
        self._undo_actions: Dict[str, _QueuedAction] = {}
        #: Undo stacks of folders that were left, so an operation that finishes after a folder switch
        #: — or a folder reopened later — keeps its undo entries (Q-7). Bounded to the newest folders.
        self._folder_undo_history: Dict[Path, List[Tuple[CullingRecord, CandidatePhoto, int]]] = {}

        self.file_manager = file_manager or FileCullingManager()
        self.dispatcher: Dispatcher = dispatcher or run_inline
        self._scan_idle_add: Optional[Callable[..., Any]] = scan_idle_add
        self._custom_scanner: Optional[DirectoryScanner] = scanner
        self._scanner_factory = scanner_factory
        self.scanner: DirectoryScanner = scanner or DirectoryScanner(
            custom_selection_folder=self.selection_folder_name
        )
        self._scan_thread: Optional[threading.Thread] = None

        self._queue: List[_QueuedAction] = []
        self._running_action: Optional[_QueuedAction] = None
        self.busy_message: Optional[str] = None
        self._folder_generation: int = 0

        self._state_callbacks: List[Callable[[CullingWorkspaceViewModel], None]] = []
        self._notification_callbacks: List[Callable[[CullingWorkspaceViewModel, str], None]] = []

        if self.settings is not None:
            self.settings.connect(self._on_settings_changed)

    @property
    def scan_idle_add(self) -> Optional[Callable[..., Any]]:
        return self._scan_idle_add

    @scan_idle_add.setter
    def scan_idle_add(self, value: Optional[Callable[..., Any]]) -> None:
        self._scan_idle_add = value

    def _on_settings_changed(self, store: SettingsStore) -> None:
        self.selection_folder_name = store.selection_folder_name
        self.auto_advance = store.auto_advance
        self.blurry_threshold = store.blurry_threshold
        self.sharp_threshold = store.sharp_threshold
        self.cache_limit_mb = store.cache_limit_mb
        self.external_destination_root = (
            Path(store.external_destination_folder)
            if store.external_destination_folder
            else None
        )
        self.stop_sharpness_threshold = store.stop_sharpness_threshold
        if self._custom_scanner is None:
            self.scanner = DirectoryScanner(custom_selection_folder=self.selection_folder_name)

        # Re-evaluate focus categories on already-scored photos when thresholds change (U-06c).
        for candidate in self.candidates:
            if candidate.scores is not None:
                s = candidate.scores
                new_cat = SharpnessCategory.from_score(
                    s.sharpness,
                    blurry_threshold=self.blurry_threshold,
                    sharp_threshold=self.sharp_threshold,
                )
                candidate.scores = QualityScores(
                    sharpness=s.sharpness,
                    noise=s.noise,
                    highlight_clipping=s.highlight_clipping,
                    shadow_clipping=s.shadow_clipping,
                    raw_variance=s.raw_variance,
                    category_override=new_cat,
                )
        self._notify_state_changed()

    @property
    def candidate_count(self) -> int:
        return len(self.candidates)

    @property
    def remaining_count(self) -> int:
        return len(self.candidates)

    @property
    def queued_count(self) -> int:
        """Total number of file operations currently running or waiting in the FIFO queue (U-08)."""
        return len(self._queue) + (1 if self._running_action is not None else 0)

    @property
    def queue_status_label(self) -> Optional[str]:
        """Human-readable queue depth pill text (e.g. ``'2 queued'``), or ``None`` when idle."""
        count = self.queued_count
        return f"{count} queued" if count > 0 else None

    @property
    def remote_badge_label(self) -> Optional[str]:
        """Header badge text when the active folder is on GVFS/FUSE/remote storage (U-31)."""
        return "Cloud / Remote" if self.is_remote_folder else None

    @property
    def is_busy(self) -> bool:
        return self._running_action is not None or bool(self._queue) or self.busy_message is not None

    @property
    def current_photo(self) -> Optional[CandidatePhoto]:
        if 0 <= self.current_index < len(self.candidates):
            return self.candidates[self.current_index]
        return None

    def card_summary(self) -> Dict[str, Any]:
        """Return ingest/culling tally summary and whether the source card is safe to format (U-33)."""
        return {
            "kept": self.selected_count,
            "trashed": self.trashed_count,
            "remaining": self.remaining_count,
            "queued": self.queued_count,
            "safe_to_format": self.queued_count == 0 and self.remaining_count == 0,
        }

    def connect(self, signal: str, callback: Callable) -> None:
        """Register callbacks for state changes or notifications."""
        if signal == "state-changed":
            self._state_callbacks.append(callback)
        elif signal == "notification":
            self._notification_callbacks.append(callback)

    def _notify_state_changed(self) -> None:
        for cb in self._state_callbacks:
            try:
                cb(self)
            except Exception:
                pass

    def _notify(self, message: str) -> None:
        for cb in self._notification_callbacks:
            try:
                cb(self, message)
            except Exception:
                pass

    def _check_stop_sharpness_threshold(self) -> None:
        """Notify when navigating/advancing onto a photo at or above the stop threshold (U-34)."""
        if self.stop_sharpness_threshold is None:
            return
        photo = self.current_photo
        if photo is not None and photo.scores is not None:
            if photo.scores.sharpness >= self.stop_sharpness_threshold:
                self._notify(f"Reached sharpness threshold ({self.stop_sharpness_threshold:.1f})")

    def set_sort_order(self, order: str) -> None:
        """Set candidate ordering ('natural' or 'lowest_sharpness_first') and re-sort (U-34)."""
        if order not in ("natural", "lowest_sharpness_first"):
            return
        self.sort_order = order
        self._apply_sort_order()
        self.current_index = 0
        self._check_stop_sharpness_threshold()
        self._notify_state_changed()

    def _apply_sort_order(self) -> None:
        if self.sort_order == "lowest_sharpness_first":
            self.candidates.sort(
                key=lambda c: (
                    (0, float(c.scores.sharpness), natural_sort_key(c.filename))
                    if c.scores is not None
                    else (1, float("inf"), natural_sort_key(c.filename))
                )
            )
        else:
            self.candidates.sort(
                key=lambda c: (
                    natural_sort_key(str(c.primary_path.parent)),
                    natural_sort_key(c.filename),
                )
            )

    def set_comparison_mode(self, mode: int) -> None:
        """Switch comparison mode (1: 1-Up, 2: 2-Up, 3: 3-Up Focus Mode)."""
        if mode in (1, 2, 3) and mode != self.comparison_mode:
            self.comparison_mode = mode
            self._notify_state_changed()

    def nav_next(self) -> None:
        """Navigate to the next candidate photo."""
        if self.current_index < len(self.candidates) - 1:
            self.current_index += 1
            self._check_stop_sharpness_threshold()
            self._notify_state_changed()

    def nav_previous(self) -> None:
        """Navigate to the previous candidate photo."""
        if self.current_index > 0:
            self.current_index -= 1
            self._notify_state_changed()

    def _index_of(self, photo: CandidatePhoto) -> Optional[int]:
        for i, candidate in enumerate(self.candidates):
            if candidate is photo:
                return i
        return None

    def _remove(self, photo: CandidatePhoto) -> int:
        """Drop a culled photo from the queue, keeping the view on the same or the next photo."""
        idx = self._index_of(photo)
        if idx is None:
            return self.current_index
        self.candidates.pop(idx)
        if idx < self.current_index:
            self.current_index -= 1
        elif not self.auto_advance and self.current_index > 0:
            self.current_index = max(0, self.current_index - 1)
        if self.current_index >= len(self.candidates) and self.current_index > 0:
            self.current_index = len(self.candidates) - 1
        return idx

    def _rollback_optimistic(self, action: _QueuedAction) -> None:
        """Revert the optimistic UI state mutation of ``action``."""
        if action.generation != self._folder_generation:
            return
        photo = action.photo
        if action.kind == "move":
            self.selected_count = max(0, self.selected_count - 1)
            if self._index_of(photo) is None:
                insert_pos = min(action.orig_idx, len(self.candidates))
                self.candidates.insert(insert_pos, photo)
                self.current_index = insert_pos
        elif action.kind == "copy":
            self.selected_count = max(0, self.selected_count - 1)
            self.copied_count = max(0, self.copied_count - 1)
            idx = self._index_of(photo)
            if idx is not None:
                self.current_index = idx
        elif action.kind == "trash":
            self.trashed_count = max(0, self.trashed_count - 1)
            if self._index_of(photo) is None:
                insert_pos = min(action.orig_idx, len(self.candidates))
                self.candidates.insert(insert_pos, photo)
                self.current_index = insert_pos

    def _stack_for(self, action: _QueuedAction) -> List[Tuple[CullingRecord, CandidatePhoto, int]]:
        """The undo stack of the workspace ``action`` belongs to: the live one, or — when the folder
        was left meanwhile — the one remembered for it (Q-7)."""
        if action.workspace is None or action.workspace == self.current_folder:
            return self.undo_stack
        return self._folder_undo_history.setdefault(action.workspace, [])

    def _remove_from_undo_stack(self, action: _QueuedAction) -> None:
        if action.record is None:
            return
        rec_id = action.record.id
        self._undo_actions.pop(rec_id, None)
        stack = self._stack_for(action)
        for i in range(len(stack) - 1, -1, -1):
            if stack[i][0].id == rec_id:
                stack.pop(i)
                break

    def _replace_undo_record(self, action: _QueuedAction, real_record: CullingRecord) -> None:
        old_id = action.record.id if action.record is not None else None
        action.record = real_record
        if old_id is not None:
            self._undo_actions.pop(old_id, None)
        self._undo_actions[real_record.id] = action
        stack = self._stack_for(action)
        for i in range(len(stack) - 1, -1, -1):
            rec, p, idx = stack[i]
            if (old_id is not None and rec.id == old_id) or p is action.photo:
                stack[i] = (real_record, p, idx)
                break

    def _enqueue_action(self, action: _QueuedAction) -> None:
        if action.kind in ("move", "copy", "trash"):
            placeholder = CullingRecord(
                photo_id=action.photo.id,
                action_type=action.action_type,
                original_primary_path=action.photo.primary_path,
                affected_paths=[],
            )
            action.record = placeholder
            action.workspace = self.current_folder
            self._undo_actions[placeholder.id] = action
            self.undo_stack.append((placeholder, action.photo, action.orig_idx))

        self._queue.append(action)
        completed_sync = self._pump_queue()
        if not completed_sync:
            self._notify_state_changed()

    def _pump_queue(self) -> bool:
        """Start the next pending action if no action is currently running.

        Returns True if an action ran synchronously to completion during this call.
        """
        if self._running_action is not None or not self._queue:
            if self._running_action is None and not self._queue:
                self.busy_message = None
            return False

        action = self._queue.pop(0)
        if action.status == "cancelled":
            return self._pump_queue()

        self._running_action = action
        action.status = "running"
        self.busy_message = action.busy_message
        finished_sync: List[bool] = []

        def job() -> Any:
            if action.kind == "move":
                return self.file_manager.move_candidate(
                    action.photo,
                    action.folder,
                    selection_folder=action.selection_folder,
                )
            if action.kind == "copy":
                return self.file_manager.copy_candidate(
                    action.photo,
                    action.folder,
                    selection_folder=action.selection_folder,
                )
            if action.kind == "trash":
                return self.file_manager.trash_candidate(action.photo)
            if action.kind == "undo":
                target = action.target_action
                rec = (
                    target.record
                    if (target is not None and target.record is not None)
                    else action.record
                )
                return self.file_manager.undo_culling(rec, action.photo)
            return None

        def done(result: Any, error: Optional[BaseException]) -> None:
            finished_sync.append(True)
            self._running_action = None
            self._handle_action_done(action, result, error)
            self._pump_queue()
            self._notify_state_changed()

        self.dispatcher(job, done)
        return bool(finished_sync)

    def _handle_action_done(
        self,
        action: _QueuedAction,
        result: Any,
        error: Optional[BaseException],
    ) -> None:
        if action.kind in ("move", "copy", "trash"):
            if error is not None:
                action.status = "failed"
                # Cancel and roll back any later queued actions on the same photo (U-08).
                dependent = [
                    q for q in self._queue
                    if q.photo is action.photo and q.status == "pending"
                ]
                for dep in reversed(dependent):
                    dep.status = "cancelled"
                    self._queue.remove(dep)
                    if dep.kind in ("move", "copy", "trash"):
                        self._remove_from_undo_stack(dep)
                        self._rollback_optimistic(dep)

                self._remove_from_undo_stack(action)
                self._rollback_optimistic(action)

                partial_record = getattr(error, "partial_record", None)
                if partial_record is not None:
                    self._stack_for(action).append((partial_record, action.photo, action.orig_idx))

                if (
                    action.kind == "move"
                    and partial_record is not None
                    and partial_record.unremoved_sources
                ):
                    # Verified copy in Selection/, original not removable (FILE_OPERATIONS.md §4).
                    self._notify(
                        f"Copied {action.photo.filename} to {action.selection_folder}/ "
                        f"— original could not be removed"
                    )
                    return

                if action.kind == "move":
                    prefix = f"Could not move {action.photo.filename}"
                elif action.kind == "copy":
                    prefix = f"Could not copy {action.photo.filename}"
                else:
                    prefix = f"Could not move {action.photo.filename} to Trash"
                self._notify(f"{prefix}: {error}")
                return

            action.status = "done"
            if isinstance(result, CullingRecord):
                self._replace_undo_record(action, result)

            if self.decision_log_path is not None:
                record_decision_log(action.kind, action.photo, self.decision_log_path)

            if action.generation == self._folder_generation:
                if action.kind == "move":
                    self._notify(self._transfer_message("Moved", action, result))
                elif action.kind == "copy":
                    self._notify(self._transfer_message("Copied", action, result))
                elif action.kind == "trash":
                    self._notify(f"Moved {action.photo.filename} to Trash")

        elif action.kind == "undo":
            target = action.target_action
            rec = (
                target.record
                if (target is not None and target.record is not None)
                else action.record
            )
            if error is not None or not result:
                action.status = "failed"
                if rec is not None:
                    self._stack_for(action).append((rec, action.photo, action.orig_idx))
                    if target is not None:
                        self._undo_actions[rec.id] = target
                if error is not None and rec is not None:
                    self._notify(
                        f"Could not undo {rec.action_type.value} for {action.photo.filename}: {error}"
                    )
                return

            action.status = "done"
            if rec is not None and action.generation == self._folder_generation:
                if rec.action_type in (CullingActionType.MOVE, CullingActionType.TRASH):
                    if self._index_of(action.photo) is None:
                        insert_pos = min(action.orig_idx, len(self.candidates))
                        self.candidates.insert(insert_pos, action.photo)
                        self.current_index = insert_pos
                        if rec.action_type == CullingActionType.MOVE:
                            self.selected_count = max(0, self.selected_count - 1)
                        else:
                            self.trashed_count = max(0, self.trashed_count - 1)
                elif rec.action_type == CullingActionType.COPY:
                    self.selected_count = max(0, self.selected_count - 1)
                    self.copied_count = max(0, self.copied_count - 1)
                restored_as = self._restored_suffixed_name(action.photo, rec)
                if restored_as is not None:
                    self._notify(f"Restored as {restored_as} (original name was in use)")
                else:
                    self._notify(f"Undid {rec.action_type.value} for {action.photo.filename}")

    @staticmethod
    def _transfer_message(verb: str, action: "_QueuedAction", result: Any) -> str:
        """Feedback for a finished Move/Copy (docs/shared/FILE_OPERATIONS.md, feedback templates).

        A collision-suffixed bundle says which name it got ("Moved to Selection/ as a-2.jpg (+1
        related)"); a plain transfer keeps the short "Moved a.jpg to Selection/".
        """
        photo = action.photo
        pairs = list(getattr(result, "affected_paths", None) or [])
        primary_dst = next((dst for src, dst in pairs if src == photo.primary_path), None)
        if primary_dst is not None and primary_dst.name != photo.name_of(photo.primary_path):
            related = len(pairs) - 1
            suffix = f" (+{related} related)" if related > 0 else ""
            return f"{verb} to {action.selection_folder}/ as {primary_dst.name}{suffix}"
        return f"{verb} {photo.filename} to {action.selection_folder}/"

    @staticmethod
    def _restored_suffixed_name(photo: CandidatePhoto, record: CullingRecord) -> Optional[str]:
        """The new name of the restored primary file when undo had to suffix it, else None."""
        if record.action_type not in (CullingActionType.MOVE, CullingActionType.TRASH):
            return None
        original = record.original_primary_path.name
        restored = photo.primary_path.name
        return restored if restored != original else None

    def cull_move(self) -> bool:
        """Queue moving active candidate and companions to Selection/ with optimistic advance."""
        photo, folder = self.current_photo, self.current_folder
        if not photo or not folder:
            return False
        selection = self.selection_folder_name
        dest_root = self.external_destination_root or folder
        orig_idx = self._remove(photo)
        self.selected_count += 1
        self._check_stop_sharpness_threshold()

        action = _QueuedAction(
            kind="move",
            action_type=CullingActionType.MOVE,
            photo=photo,
            orig_idx=orig_idx,
            folder=dest_root,
            selection_folder=selection,
            generation=self._folder_generation,
            busy_message=f"Moving {photo.filename} to {selection}/…",
        )
        self._enqueue_action(action)
        return True

    def cull_copy(self) -> bool:
        """Queue copying active candidate and companions to Selection/ with optimistic advance."""
        photo, folder = self.current_photo, self.current_folder
        if not photo or not folder:
            return False
        selection = self.selection_folder_name
        dest_root = self.external_destination_root or folder
        idx = self._index_of(photo)
        orig_idx = idx if idx is not None else self.current_index
        self.copied_count += 1
        self.selected_count += 1
        if self.auto_advance and self.current_photo is photo and self.current_index < len(self.candidates) - 1:
            self.current_index += 1
            self._check_stop_sharpness_threshold()

        action = _QueuedAction(
            kind="copy",
            action_type=CullingActionType.COPY,
            photo=photo,
            orig_idx=orig_idx,
            folder=dest_root,
            selection_folder=selection,
            generation=self._folder_generation,
            busy_message=f"Copying {photo.filename} to {selection}/…",
        )
        self._enqueue_action(action)
        return True

    def cull_trash(self) -> bool:
        """Queue sending active candidate and companions to Trash with optimistic advance."""
        photo = self.current_photo
        if not photo:
            return False
        folder = self.current_folder or photo.primary_path.parent
        orig_idx = self._remove(photo)
        self.trashed_count += 1
        self._check_stop_sharpness_threshold()

        action = _QueuedAction(
            kind="trash",
            action_type=CullingActionType.TRASH,
            photo=photo,
            orig_idx=orig_idx,
            folder=folder,
            selection_folder=self.selection_folder_name,
            generation=self._folder_generation,
            busy_message=f"Moving {photo.filename} to Trash…",
        )
        self._enqueue_action(action)
        return True

    def undo(self) -> bool:
        """Reverse the newest queued or completed culling operation (R-LINUX-CULL-06/07, U-08).

        If the newest operation is still pending in the queue (not yet started on disk), cancels it
        in-memory and reverts its optimistic UI change immediately. Otherwise enqueues a disk undo
        operation in FIFO order.
        """
        if not self.undo_stack:
            return False

        record, photo, orig_idx = self.undo_stack.pop()
        source_action = self._undo_actions.pop(record.id, None)

        # Case 1: Still waiting in the queue (disk operation has not started yet)
        if source_action is not None and source_action.status == "pending" and source_action in self._queue:
            source_action.status = "cancelled"
            self._queue.remove(source_action)
            self._rollback_optimistic(source_action)
            self._notify(f"Undid {record.action_type.value} for {photo.filename}")
            self._pump_queue()
            self._notify_state_changed()
            return True

        # Case 2: Currently running on disk or already completed on disk -> enqueue disk undo in FIFO order
        undo_action = _QueuedAction(
            kind="undo",
            action_type=record.action_type,
            photo=photo,
            orig_idx=orig_idx,
            folder=self.current_folder or photo.primary_path.parent,
            selection_folder=self.selection_folder_name,
            generation=self._folder_generation,
            busy_message=f"Undoing {record.action_type.value} of {photo.filename}…",
            record=record,
            target_action=source_action,
            workspace=self.current_folder,
        )
        self._queue.append(undo_action)
        completed_sync = self._pump_queue()
        if not completed_sync:
            self._notify_state_changed()
            return True
        return undo_action.status == "done"

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

    def load_directory(self, folder: Path) -> None:
        """Stream photographs from ``folder`` in depth-first per-folder natural order (U-06b, UD-9).

        Cancels any in-flight scan from a previous folder via ``_folder_generation``, while allowing
        queued file operations from the previous folder to finish draining on the worker.
        """
        previous_folder = self.current_folder
        self.current_folder = folder.resolve()
        self._folder_generation += 1
        generation = self._folder_generation
        self.is_remote_folder = is_remote_filesystem(self.current_folder)

        scanner = self._make_scanner()
        parent_names = scanner.name_resolver.names_in(self.current_folder.parent)
        self.current_folder_name = parent_names.get(
            self.current_folder.name,
            self.current_folder.name,
        )

        self.candidates = []
        self.current_index = 0
        self._remember_undo_stack(previous_folder)
        self.undo_stack = self._folder_undo_history.pop(self.current_folder, [])
        self.selected_count = 0
        self.copied_count = 0
        self.trashed_count = 0
        self.is_scanning = True

        if self.scan_idle_add is not None:
            folder_to_scan = self.current_folder

            def _scan_worker() -> None:
                for batch in scanner.scan_stream(
                    folder_to_scan,
                    batch_size=30,
                    first_batch_size=1,
                ):
                    if generation != self._folder_generation:
                        return
                    self.scan_idle_add(self._on_scan_batch, generation, list(batch))
                if generation == self._folder_generation:
                    self.scan_idle_add(self._on_scan_complete, generation)

            thread = threading.Thread(
                target=_scan_worker,
                name="directory-scanner",
                daemon=True,
            )
            self._scan_thread = thread
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
            self.candidates.extend(batch)
            if self.sort_order == "lowest_sharpness_first":
                self._apply_sort_order()
            notified_any = True
            self._notify_state_changed()

        if generation != self._folder_generation:
            return
        self.is_scanning = False
        self._notify(f"Loaded {len(self.candidates)} photographs from {self.current_folder_name}")
        if not notified_any:
            self._notify_state_changed()

    def _on_scan_batch(self, generation: int, batch: List[CandidatePhoto]) -> bool:
        if generation != self._folder_generation:
            return False
        self.candidates.extend(batch)
        if self.sort_order == "lowest_sharpness_first":
            self._apply_sort_order()
        self._notify_state_changed()
        return False

    def _on_scan_complete(self, generation: int) -> bool:
        if generation != self._folder_generation:
            return False
        self.is_scanning = False
        self._notify(f"Loaded {len(self.candidates)} photographs from {self.current_folder_name}")
        self._notify_state_changed()
        return False
