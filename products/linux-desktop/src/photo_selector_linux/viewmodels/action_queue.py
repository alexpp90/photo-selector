"""The FIFO file-operation queue of the culling workspace: optimistic state, dispatch, outcomes, undo.

``ActionQueueMixin`` is the queue half of ``CullingWorkspaceViewModel`` (split out of
``culling_workspace.py`` with no change in behaviour); ``_QueuedAction`` is one queued operation.
"""

from __future__ import annotations

import logging

from dataclasses import dataclass
from pathlib import Path
from typing import Any, List, Optional, Tuple

from photo_selector_linux.core.destination import (
    display_path,
)
from photo_selector_linux.core.volumes import selection_stays_on_card
from photo_selector_linux.core.models import (
    CandidatePhoto,
    CullingActionType,
    CullingRecord,
)
from photo_selector_linux.viewmodels.outcome_text import companion_count, outcome_texts


logger = logging.getLogger(__name__)


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
    #: A Copy: the opened folder and the photo's key in the stored copy decisions (D3).
    decision_folder: Optional[Path] = None
    decision_key: Optional[str] = None


MESSAGE_NAME_CHARS = 16


#: Longest destination path a first-transfer message shows whole (the bar's slot is bounded, LIN-7).
MESSAGE_PATH_CHARS = 34
#: Appended to the first transfer message when the Selection is on the card being culled (plan 1.6).
ON_CARD_NOTE = " · on card"


def shorten_middle(name: str, limit: int = MESSAGE_NAME_CHARS) -> str:
    """Shorten a long file name in the middle so its distinguishing end stays visible.

    Burst files share their prefix (IMG_20260814_064000, IMG_20260814_064001), so the status
    message keeps the first 4 and the last 7 characters of the stem: ``IMG_…_064000``. A name
    that fits is returned whole, extension included; a shortened one drops the extension (the
    companion count says what travelled with it).
    """
    if len(name) <= limit:
        return name
    stem = name.rsplit(".", 1)[0] if "." in name.strip(".") else name
    if len(stem) <= limit:
        return stem
    return f"{stem[:4]}…{stem[-7:]}"


class ActionQueueMixin:
    """Queue, optimistic updates, outcome messages and undo of file operations."""

    def _rollback_optimistic(self, action: _QueuedAction) -> None:
        """Revert the optimistic UI state mutation of ``action``."""
        if action.generation != self._folder_generation:
            return
        photo = action.photo
        if action.kind == "move":
            self.selected_count = max(0, self.selected_count - 1)
            if self._index_of(photo) is None:
                self.current_index = self._reinsert(photo, action.orig_idx)
        elif action.kind == "copy":
            self.selected_count = max(0, self.selected_count - 1)
            self.copied_count = max(0, self.copied_count - 1)
            photo.copied = False
            self._list_version += 1
            idx = self._index_of(photo)
            if idx is not None:
                self.current_index = idx
        elif action.kind == "trash":
            self.trashed_count = max(0, self.trashed_count - 1)
            self._restore_copied_counts(photo)
            if self._index_of(photo) is None:
                self.current_index = self._reinsert(photo, action.orig_idx)

    def _restore_copied_counts(self, photo: CandidatePhoto) -> None:
        """A trashed photo that had been copied is Selected again once the Trash is rolled back."""
        if photo.copied:
            self.selected_count += 1
            self.copied_count += 1

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

                if partial_record is not None and partial_record.stranded:
                    # A failed rollback or cleanup left files in Selection/ (D-12). Never claim the
                    # operation was undone; Undo (Ctrl+Z) puts them back or removes the stray copies.
                    if action.kind == "move" and partial_record.unremoved_sources:
                        # Cross-mount staging: the originals never moved, only copies are stranded.
                        self._notify(
                            f"Move interrupted — copies of {action.photo.filename} stayed in "
                            f"{action.selection_folder}/ and could not be removed; the original is untouched",
                            "stranded",
                        )
                    elif action.kind == "move":
                        self._notify(
                            f"Move interrupted — some files of {action.photo.filename} stayed in "
                            f"{action.selection_folder}/ and could not be put back",
                            "stranded",
                        )
                    else:
                        self._notify(
                            f"Copy interrupted — some copies of {action.photo.filename} stayed in "
                            f"{action.selection_folder}/ and could not be removed",
                            "stranded",
                        )
                    return

                if (
                    action.kind == "move"
                    and partial_record is not None
                    and partial_record.unremoved_sources
                ):
                    # Verified copy in Selection/, original not removable (FILE_OPERATIONS.md §4).
                    self._notify(
                        f"Copied {action.photo.filename} to {action.selection_folder}/ "
                        f"— original could not be removed",
                        "stranded",
                    )
                    return

                if action.kind == "move":
                    prefix = f"Could not move {action.photo.filename}"
                elif action.kind == "copy":
                    prefix = f"Could not copy {action.photo.filename}"
                else:
                    prefix = f"Could not move {action.photo.filename} to Trash"
                    if "trash failed, rolled back" in str(error):
                        # The g-io reason belongs in the log; the user gets one plain sentence.
                        logger.warning("%s: %s", prefix, error)
                        self._notify("Can't use Trash here. Nothing moved.", "error")
                        return
                self._notify(f"{prefix}: {error}", "error")
                return

            action.status = "done"
            if isinstance(result, CullingRecord):
                self._replace_undo_record(action, result)
            if action.kind == "copy":
                self._remember_copy_decision(action, result)

            if action.generation == self._folder_generation:
                if action.kind in ("move", "copy"):
                    verb = "Moved" if action.kind == "move" else "Copied"
                    first = self._first_transfer_place(action)
                    visible, spoken = self._transfer_message(verb, action, result, first)
                    self._notify(visible, "status", spoken=spoken)
                elif action.kind == "trash":
                    visible, spoken = outcome_texts(
                        "Moved", action.photo, companion_count(action.photo, result), "Trash"
                    )
                    self._notify(visible, "status", spoken=spoken)

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
                        f"Could not undo {rec.action_type.value} for {action.photo.filename}: {error}",
                        "error",
                    )
                return

            action.status = "done"
            if (
                rec is not None
                and rec.action_type == CullingActionType.COPY
                and not rec.stranded
                and target is not None
            ):
                self._forget_copy_decision(target)
            if rec is not None and action.generation == self._folder_generation:
                if rec.action_type in (CullingActionType.MOVE, CullingActionType.TRASH):
                    if self._index_of(action.photo) is None:
                        self.current_index = self._reinsert(action.photo, action.orig_idx)
                        if rec.action_type == CullingActionType.MOVE:
                            self.selected_count = max(0, self.selected_count - 1)
                        else:
                            self.trashed_count = max(0, self.trashed_count - 1)
                            self._restore_copied_counts(action.photo)
                elif rec.action_type == CullingActionType.COPY and not rec.stranded:
                    # A partial copy (stray replicas) already gave its optimistic counts back.
                    self.selected_count = max(0, self.selected_count - 1)
                    self.copied_count = max(0, self.copied_count - 1)
                    action.photo.copied = False
                    self._list_version += 1
                restored_as = self._restored_suffixed_name(action.photo, rec)
                if restored_as is not None:
                    self._notify(f"Restored as {shorten_middle(restored_as)} (original name was in use)", "status")
                else:
                    self._notify(f"Undid {rec.action_type.value} for {shorten_middle(action.photo.filename)}", "status")

    def _first_transfer_place(self, action: "_QueuedAction") -> Optional[Tuple[str, bool]]:
        """The full destination and whether it is on the card being culled, for the first Move or
        Copy of an opened folder only (plan 1.6); later outcomes name the folder."""
        if self._destination_said_generation == action.generation:
            return None
        self._destination_said_generation = action.generation
        shown = display_path(Path(action.folder) / action.selection_folder) + "/"
        # [USER 2026-11-12] plan 1.6: a Selection on the card being culled says so, in the same line.
        on_card = selection_stays_on_card(Path(action.folder), self.resolve_destination().path)
        limit = MESSAGE_PATH_CHARS - (len(ON_CARD_NOTE) if on_card else 0)
        # A long path keeps its start and its end (the folder and the Selection) in the bounded slot.
        if len(shown) > limit:
            shown = f"{shown[:10]}…{shown[-(limit - 11):]}"
        return (shown + ON_CARD_NOTE if on_card else shown), on_card

    @staticmethod
    def _transfer_message(
        verb: str, action: "_QueuedAction", result: Any, first: Optional[Tuple[str, bool]] = None
    ) -> Tuple[str, str]:
        """``(visible, spoken)`` for a finished Move/Copy (``outcome_text``; docs/shared/FILE_OPERATIONS.md).

        A collision-suffixed bundle says which name it got ("Moved as a-2 +1 · Selection").
        """
        photo = action.photo
        pairs = list(getattr(result, "affected_paths", None) or [])
        primary_dst = next((dst for src, dst in pairs if src == photo.primary_path), None)
        new_name = (
            primary_dst.name
            if primary_dst is not None and primary_dst.name != photo.name_of(photo.primary_path)
            else None
        )
        return outcome_texts(
            verb, photo, companion_count(photo, result), action.selection_folder,
            first_place=first[0] if first else None, new_name=new_name,
            on_card=bool(first and first[1]),
        )

    @staticmethod
    def _restored_suffixed_name(photo: CandidatePhoto, record: CullingRecord) -> Optional[str]:
        """The new name of the restored primary file when undo had to suffix it, else None."""
        if record.action_type not in (CullingActionType.MOVE, CullingActionType.TRASH):
            return None
        original = record.original_primary_path.name
        restored = photo.primary_path.name
        return restored if restored != original else None

    def _remember_copy_decision(self, action: _QueuedAction, result: Any) -> None:
        if self.copy_decisions is None or not action.decision_folder or not action.decision_key:
            return
        destination: Optional[Path] = None
        if isinstance(result, CullingRecord):
            for source, target in result.affected_paths:
                if source == action.photo.primary_path:
                    destination = target
                    break
        self.copy_decisions.record(
            action.decision_folder, action.decision_key, action.photo.file_size, destination
        )

    def _forget_copy_decision(self, action: _QueuedAction) -> None:
        if self.copy_decisions is not None and action.decision_folder and action.decision_key:
            self.copy_decisions.forget(action.decision_folder, action.decision_key)
