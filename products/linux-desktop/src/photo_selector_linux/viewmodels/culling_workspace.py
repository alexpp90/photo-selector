"""Pure-Python decoupled ViewModel for culling workspace state machine.

Adheres to:
- R-LINUX-UI-04/05/06: 1-Up, 2-Up, and 3-Up Focus Mode transitions
- R-LINUX-UI-10: Queued non-blocking background file operations with optimistic UI updates
- R-LINUX-CULL-01/02: Streaming directory ingestion in depth-first per-folder natural order
- R-LINUX-CULL-03/04/05/06/07: Transactional Move, Copy, Trash, and Undo operations
"""

from __future__ import annotations

import logging

from dataclasses import dataclass
import os
from pathlib import Path
import threading
from typing import Any, Callable, Dict, List, Optional, Tuple

from photo_selector_linux.core.destination import (
    MODE_CUSTOM,
    MODE_WORKING,
    DestinationResolution,
    describe_destination,
    display_path,
    resolve_destination,
)
from photo_selector_linux.core.file_ops import FileCullingManager
from photo_selector_linux.core.gvfs_names import is_remote_filesystem
from photo_selector_linux.core.volumes import VERDICT_SAFE, format_verdict, selection_stays_on_card
from photo_selector_linux.core.models import (
    CandidatePhoto,
    CullingActionType,
    CullingRecord,
    QualityScores,
    SharpnessCategory,
)
from photo_selector_linux.core.goto import resolve_goto
from photo_selector_linux.viewmodels.capture_pass import CapturePass, order_basis
from photo_selector_linux.viewmodels.outcome_text import companion_count, outcome_texts
from photo_selector_linux.core.scanner import DirectoryScanner, natural_sort_key
from photo_selector_linux.core.settings import (
    ORDER_LOWEST_SHARPNESS,
    ORDER_NAME,
    ORDER_NEWEST,
    ORDER_OLDEST,
    PERSISTED_ORDERS,
    SettingsStore,
)


# A file operation, and what to do with its (result, error) once it finished.
Job = Callable[[], Any]
JobDone = Callable[[Any, Optional[BaseException]], None]
Dispatcher = Callable[[Job, JobDone], None]
#: Scores photographs in the background. ``runner(photos, on_scored)`` returns a cancel function;
#: ``on_scored(photo, scores_or_None)`` is called on the main loop once per photo.
ScoreRunner = Callable[
    [List[CandidatePhoto], Callable[[CandidatePhoto, Optional[QualityScores]], None]],
    Callable[[], None],
]


logger = logging.getLogger(__name__)


ORDERS = (ORDER_NEWEST, ORDER_OLDEST, ORDER_NAME, ORDER_LOWEST_SHARPNESS)


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
        score_runner: Optional[ScoreRunner] = None,
        auto_advance: Optional[bool] = None,
        external_destination_root: Optional[Path] = None,
    ):
        self.candidates: List[CandidatePhoto] = []
        self.current_index: int = 0
        self.comparison_mode: int = 1  # 1: 1-Up, 2: 2-Up, 3: 3-Up Focus Mode
        self.current_folder: Optional[Path] = None
        self.current_folder_name: str = ""
        self.is_remote_folder: bool = False
        self.is_scanning: bool = False
        self.scan_failed: bool = False
        self._destination_said_generation: Optional[int] = None

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
        #: The custom destination folder (``destination_mode == "custom"`` puts photos in it).
        self.external_destination_root: Optional[Path] = (
            Path(external_destination_root)
            if external_destination_root is not None
            else (
                Path(settings.external_destination_folder)
                if (settings and settings.external_destination_folder)
                else None
            )
        )
        self.destination_mode: str = (
            MODE_CUSTOM
            if external_destination_root is not None
            else (settings.destination_mode if settings else MODE_WORKING)
        )
        self.stop_sharpness_threshold: Optional[float] = (
            settings.stop_sharpness_threshold if settings else None
        )
        #: Without a settings store (tests, headless use) the order is the file-name order.
        self.sort_order: str = settings.view_order if settings else ORDER_NAME
        #: False until the user moves, culls, jumps or the folder opens on a requested photo:
        #: while False, every re-order of an arriving batch lands on the first photo of the order.
        self._user_navigated: bool = False

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
        self._select_name: Optional[str] = None  # photo to land on once the scan reaches it

        # "Lowest sharpness first" needs a score for every photo, not only the ones viewed so far.
        self.score_runner: Optional[ScoreRunner] = score_runner
        self.scoring_confirmation_pending: bool = False
        self._scoring_confirmed: bool = False
        self._scoring_generation: int = 0
        self._scoring_inflight: Dict[str, CandidatePhoto] = {}
        self._scoring_total: int = 0
        self._scoring_done: int = 0
        self._scoring_new: int = 0  # photos given a score by this sweep ("Scored N more")
        self._scoring_since_resort: int = 0
        self._scoring_anchor: Optional[CandidatePhoto] = None
        self._cancel_scoring: Optional[Callable[[], None]] = None
        self._capture = CapturePass(self._on_capture_progress, self._on_capture_done)

        self._queue: List[_QueuedAction] = []
        self._running_action: Optional[_QueuedAction] = None
        self.busy_message: Optional[str] = None
        self._folder_generation: int = 0

        self._state_callbacks: List[Callable[[CullingWorkspaceViewModel], None]] = []
        self.last_notification_severity: str = "info"
        self.last_notification_spoken: Optional[str] = None
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
        self.destination_mode = store.destination_mode
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
        """Photos still to decide: a copied photo stays in the list but is decided (decision #29)."""
        return sum(1 for candidate in self.candidates if not candidate.copied)

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
        """Tally of the cull and the "safe to format" verdict (U-33, plan 1.2).

        The verdict follows ``core.volumes.format_verdict``: the source must be a removable volume
        and the Selection on another one; a Selection on the card itself is reported, not blessed.
        """
        destination = self.resolve_destination()
        verdict = format_verdict(
            self.current_folder, destination.path, self.queued_count, self.remaining_count,
            self.is_remote_folder,
        )
        return {
            "kept": self.selected_count,
            "trashed": self.trashed_count,
            "remaining": self.remaining_count,
            "queued": self.queued_count,
            "selection_path": destination.path,
            "verdict": verdict,
            "safe_to_format": verdict == VERDICT_SAFE,
        }

    @property
    def canvas_state(self) -> str:
        """Which of the canvas's states applies: ``start``, ``reading``, ``unreadable``, ``empty``,
        ``done`` or ``photos`` (plan 1.1). Each has its own text; the done screen is the only one
        that follows a decision."""
        if self.candidates and (self.remaining_count > 0 or self.selected_count + self.trashed_count == 0):
            return "photos"
        if self.candidates:
            return "done"  # only copied photos are left: every photo is decided
        if self.current_folder is None:
            return "start"
        if self.scan_failed:
            return "unreadable"
        if self.is_scanning:
            return "reading"
        if self.selected_count + self.trashed_count > 0:
            return "done"
        return "empty"

    def connect(self, signal: str, callback: Callable) -> None:
        """Register callbacks for state changes or notifications."""
        if signal == "state-changed":
            self._state_callbacks.append(callback)
        elif signal == "notification":
            self._notification_callbacks.append(callback)

    def disconnect(self, signal: str, callback: Callable) -> None:
        """Remove a callback registered with ``connect`` (a no-op when it is not registered)."""
        callbacks = {
            "state-changed": self._state_callbacks,
            "notification": self._notification_callbacks,
        }.get(signal)
        if callbacks is not None and callback in callbacks:
            callbacks.remove(callback)

    def resolve_destination(self) -> DestinationResolution:
        """Where Move/Copy put the next photo, for the open folder and the current settings.

        The one resolution: ``cull_move``/``cull_copy`` write where it says and Preferences
        shows what it says (R-LINUX-PREF-03).
        """
        return resolve_destination(
            self.destination_mode,
            self.selection_folder_name,
            self.current_folder,
            self.external_destination_root,
        )

    def _notify_state_changed(self) -> None:
        for cb in self._state_callbacks:
            try:
                cb(self)
            except Exception:
                # One failing view must not stop the others from refreshing.
                logger.exception("State callback %r failed", cb)

    def _notify(self, message: str, severity: str = "info", spoken: Optional[str] = None) -> None:
        """Deliver a message to notification callbacks (signature ``(vm, message)``).

        ``severity`` is stored in ``last_notification_severity`` before the callbacks run:
        ``"status"`` = cull/undo outcome (shown in the action bar, no toast), ``"error"`` =
        failure (toast and action bar), ``"stranded"`` = files left in an unexpected place (as
        ``"error"``, but the action bar keeps it until the next file operation starts),
        ``"info"`` = everything else (toast only).
        """
        self.last_notification_severity = severity
        #: What a screen reader says when it differs from ``message`` (companions in words).
        self.last_notification_spoken = spoken
        for cb in self._notification_callbacks:
            try:
                cb(self, message)
            except Exception:
                # One failing listener must not swallow the message for the others.
                logger.exception("Notification callback %r failed", cb)

    def _check_stop_sharpness_threshold(self) -> None:
        """Notify when navigating/advancing onto a photo at or above the stop threshold (U-34)."""
        if self.stop_sharpness_threshold is None:
            return
        photo = self.current_photo
        if photo is not None and photo.scores is not None:
            if photo.scores.sharpness >= self.stop_sharpness_threshold:
                self._notify(f"Reached sharpness threshold ({self.stop_sharpness_threshold:.1f})")

    def set_sort_order(self, order: str) -> None:
        """Set the view order and start at its first photo (U-34, R-LINUX-CULL-02).

        ``newest`` / ``oldest`` / ``natural`` (file name) are remembered for the next folder and
        the next start; ``lowest_sharpness_first`` is a triage view for this session only.
        """
        if order not in ORDERS:
            return
        if order == self.sort_order and self.scoring_confirmation_pending:
            self._scoring_confirmed = True  # chosen again on a remote folder: that is the consent
        self.sort_order = order
        self._apply_sort_order()
        self.current_index = 0
        self._user_navigated = False
        self._check_stop_sharpness_threshold()
        if order in (ORDER_NEWEST, ORDER_OLDEST):
            self._capture.start(self.candidates, self.is_remote_folder, user_requested=True)
        if order == ORDER_LOWEST_SHARPNESS:
            self._scoring_anchor = self.current_photo
            self._begin_scoring_pass()
        else:
            self._cancel_scoring_pass()
            if order not in (ORDER_NEWEST, ORDER_OLDEST):
                self._capture.cancel()
            if self.settings is not None and order in PERSISTED_ORDERS and self.settings.view_order != order:
                self.settings.update(view_order=order)  # notifies: _on_settings_changed refreshes
                return
        self._notify_state_changed()

    @property
    def capture_runner(self):
        return self._capture.runner

    @capture_runner.setter
    def capture_runner(self, runner) -> None:
        self._capture.runner = runner

    @property
    def order_basis(self) -> str:
        """What the time orders go by: "capture time", "file time", a mix, or "reading capture times"."""
        return order_basis(self.candidates, self._capture.reading)

    def _begin_capture_pass(self) -> None:
        """After a scan: read capture times for the time orders (a cloud folder waits to be asked)."""
        if self.sort_order in (ORDER_NEWEST, ORDER_OLDEST):
            if self._capture.start(self.candidates, self.is_remote_folder, user_requested=False):
                self._notify_state_changed()

    def _on_capture_progress(self) -> None:
        if self.sort_order in (ORDER_NEWEST, ORDER_OLDEST):
            self._resort_keeping_view()
        self._notify_state_changed()

    def _on_capture_done(self) -> None:
        self._on_capture_progress()

    @property
    def is_scoring(self) -> bool:
        return bool(self._scoring_inflight)

    @property
    def scoring_progress(self) -> Tuple[int, int]:
        """Photos scored so far and photos this sweep must score (for the order button's tooltip)."""
        return self._scoring_done, self._scoring_total

    def _cancel_scoring_pass(self) -> None:
        """Stop the background pass: folder switch, back to natural order."""
        self._scoring_generation += 1
        cancel, self._cancel_scoring = self._cancel_scoring, None
        if cancel is not None:
            cancel()
        self._scoring_inflight = {}
        self._scoring_total = self._scoring_done = self._scoring_since_resort = self._scoring_new = 0
        self.scoring_confirmation_pending = False
        self._scoring_anchor = None

    def _begin_scoring_pass(self) -> None:
        """Score every photo that has no score yet, so the order covers the whole folder (UC-13).

        Photos already scored by viewing are skipped. A remote/cloud folder is read in full only
        after the user chooses the order a second time (U-31).
        """
        if self.sort_order != ORDER_LOWEST_SHARPNESS or self.score_runner is None:
            return
        todo = [
            c for c in self.candidates
            if c.scores is None and c.id not in self._scoring_inflight
        ]
        if not todo:
            self._finish_scoring_pass()
            return
        if self.is_remote_folder and not self._scoring_confirmed:
            self.scoring_confirmation_pending = True
            self._notify("Cloud / Remote folder: choose this order again to score every photo")
            return
        self.scoring_confirmation_pending = False
        generation = self._scoring_generation
        for photo in todo:
            self._scoring_inflight[photo.id] = photo
        self._scoring_total += len(todo)
        self._notify(f"Scoring {len(todo)} photos, centre of frame…")

        def on_scored(photo: CandidatePhoto, scores: Optional[QualityScores]) -> None:
            self._on_photo_scored(generation, photo, scores)

        cancel = self.score_runner(todo, on_scored)
        # The runner may have delivered everything inline; keep only a live pass's cancel handle.
        if generation == self._scoring_generation and self._scoring_inflight:
            self._cancel_scoring = cancel

    def _on_photo_scored(
        self, generation: int, photo: CandidatePhoto, scores: Optional[QualityScores]
    ) -> None:
        if generation != self._scoring_generation:
            return
        self._scoring_inflight.pop(photo.id, None)
        self._scoring_done += 1
        self._scoring_since_resort += 1
        if scores is not None and photo.scores is None:
            photo.scores = scores
            self._scoring_new += 1
        if not self._scoring_inflight:
            self._finish_scoring_pass()
        elif self._scoring_since_resort >= (10 if self._scoring_total <= 200 else 50):
            self._resort_keeping_view()
            self._notify_state_changed()

    def _resort_keeping_view(self) -> None:
        """Re-sort by the scores that exist now; the photo on screen stays on screen."""
        shown = self.current_photo
        self._apply_sort_order()
        if shown is not None:
            idx = self._index_of(shown)
            if idx is not None:
                self.current_index = idx
        self._scoring_since_resort = 0

    def _finish_scoring_pass(self) -> None:
        if self.is_scanning:
            return  # photos still arriving; the scan's completion starts the next pass
        total = self._scoring_new
        self._resort_keeping_view()
        self._cancel_scoring = None
        self._scoring_total = self._scoring_done = self._scoring_new = 0
        anchor, self._scoring_anchor = self._scoring_anchor, None
        if anchor is not None and self.current_photo is anchor:
            self.current_index = 0  # not navigated meanwhile: start the sweep at the lowest
        if total:  # [USER 2026-11-12] decision #33 (4): the outcome line, nothing at 0
            self._notify(f"Scored {total} more", "status")
        self._notify_state_changed()

    def _apply_sort_order(self) -> None:
        if self.sort_order == ORDER_LOWEST_SHARPNESS:
            self.candidates.sort(
                key=lambda c: (
                    (0, float(c.scores.sharpness), natural_sort_key(c.filename))
                    if c.scores is not None
                    else (1, float("inf"), natural_sort_key(c.filename))
                )
            )
        elif self.sort_order in (ORDER_NEWEST, ORDER_OLDEST):
            sign = -1.0 if self.sort_order == ORDER_NEWEST else 1.0
            self.candidates.sort(
                key=lambda c: (
                    sign * c.order_time,
                    natural_sort_key(str(c.primary_path.parent)),
                    natural_sort_key(c.filename),
                )
            )
        else:
            self.candidates.sort(
                key=lambda c: (
                    natural_sort_key(str(c.primary_path.parent)),
                    natural_sort_key(c.filename),
                )
            )

    def _add_scanned(self, batch: List[CandidatePhoto]) -> None:
        """Take a scan batch in; the list stays in the active order, the photo on screen stays.

        File-name order is what the scanner emits, so a batch is appended. The other orders need
        the whole folder, which a progressive scan only has at the end: each batch is merged into
        the active order, the photo the user is looking at is followed by identity, and until the
        user has moved the view stays on the first photo of the order (the newest, for "newest
        first"). Undo positions are not stored indices that could go stale (see ``_reinsert``).
        """
        shown = self.current_photo
        self.candidates.extend(batch)
        if self.sort_order == ORDER_NAME:
            return
        self._apply_sort_order()
        if self._user_navigated and shown is not None:
            idx = self._index_of(shown)
            if idx is not None:
                self.current_index = idx
        else:
            self.current_index = 0

    def _reinsert(self, photo: CandidatePhoto, orig_idx: int) -> int:
        """Put an undone photo back where the active order wants it; return its index.

        ``orig_idx`` is where it was when it was removed. The order or the list may have changed
        since (another order chosen, more of the folder scanned), so the position is only the
        starting point; sorting puts the photo where its key says, and its identity is followed.
        """
        self.candidates.insert(min(orig_idx, len(self.candidates)), photo)
        self._apply_sort_order()
        return self._index_of(photo) or 0

    def find_photo(self, text: str) -> Optional[int]:
        """Index the "Go to photo" text points at (a position or a file name), or None."""
        return resolve_goto(text, [c.filename for c in self.candidates])

    def go_to_photo(self, text: str) -> bool:
        """Show the photo ``text`` points at; False (and nothing changes) when none matches."""
        idx = self.find_photo(text)
        if idx is None:
            return False
        self.current_index = idx
        self._user_navigated = True
        self._check_stop_sharpness_threshold()
        self._notify_state_changed()
        return True

    def set_comparison_mode(self, mode: int) -> None:
        """Switch comparison mode (1: 1-Up, 2: 2-Up, 3: 3-Up Focus Mode)."""
        if mode in (1, 2, 3) and mode != self.comparison_mode:
            self.comparison_mode = mode
            self._notify_state_changed()

    def nav_next(self) -> None:
        """Navigate to the next candidate photo."""
        if self.current_index < len(self.candidates) - 1:
            self.current_index += 1
            self._user_navigated = True
            self._check_stop_sharpness_threshold()
            self._notify_state_changed()

    def nav_previous(self) -> None:
        """Navigate to the previous candidate photo."""
        if self.current_index > 0:
            self.current_index -= 1
            self._user_navigated = True
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
        self._user_navigated = True
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
                self.current_index = self._reinsert(photo, action.orig_idx)
        elif action.kind == "copy":
            self.selected_count = max(0, self.selected_count - 1)
            self.copied_count = max(0, self.copied_count - 1)
            photo.copied = False
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

    def _already_copied(self, photo: CandidatePhoto) -> bool:
        """Move or Copy on a photo already copied files nothing twice: say so and step on."""
        self._notify("Already copied", "status")
        if self.auto_advance and self.current_photo is photo and self.current_index < len(self.candidates) - 1:
            self.current_index += 1
            self._notify_state_changed()
        return False

    def cull_move(self) -> bool:
        """Queue moving active candidate and companions to Selection/ with optimistic advance."""
        photo, folder = self.current_photo, self.current_folder
        if not photo or not folder:
            return False
        if photo.copied:
            return self._already_copied(photo)
        destination = self.resolve_destination()
        if not destination.ok or destination.root is None:
            self._notify(
                f"Cannot move: {describe_destination(destination)}", "error"
            )
            return False
        selection = destination.selection_name
        dest_root = destination.root
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
        if photo.copied:
            return self._already_copied(photo)
        destination = self.resolve_destination()
        if not destination.ok or destination.root is None:
            self._notify(
                f"Cannot copy: {describe_destination(destination)}", "error"
            )
            return False
        selection = destination.selection_name
        dest_root = destination.root
        idx = self._index_of(photo)
        orig_idx = idx if idx is not None else self.current_index
        self.copied_count += 1
        self.selected_count += 1
        photo.copied = True
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
        if photo.copied:  # the latest decision wins: Trashed, no longer Selected
            self.selected_count = max(0, self.selected_count - 1)
            self.copied_count = max(0, self.copied_count - 1)
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
            self._notify(f"Undid {record.action_type.value} for {shorten_middle(photo.filename)}", "status")
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
            self._select_requested_photo()
            notified_any = True
            self._notify_state_changed()

        if generation != self._folder_generation:
            return
        self.is_scanning = False
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
        self._select_requested_photo()
        self._notify_state_changed()
        return False

    def _select_requested_photo(self) -> None:
        """Land on the photo the folder was opened for, once it has been scanned."""
        name = self._select_name
        if name is None or self.current_folder is None:
            return
        for idx, candidate in enumerate(self.candidates):
            if any(
                p.parent == self.current_folder and candidate.name_of(p) == name
                for p in candidate.all_paths
            ):
                self.current_index = idx
                self._select_name = None
                self._user_navigated = True  # opened on this photo: later batches keep it on screen
                return

    def _on_scan_complete(self, generation: int) -> bool:
        if generation != self._folder_generation:
            return False
        self.is_scanning = False
        self._notify(f"Loaded {len(self.candidates)} photos from {self.current_folder_name}")
        self._notify_state_changed()
        self._begin_scoring_pass()
        self._begin_capture_pass()
        return False
