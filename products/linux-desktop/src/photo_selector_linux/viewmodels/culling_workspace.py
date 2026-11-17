"""Pure-Python decoupled ViewModel for culling workspace state machine.

Adheres to:
- R-LINUX-UI-04/05/06: 1-Up, 2-Up, and 3-Up Focus Mode transitions
- R-LINUX-UI-10: Queued non-blocking background file operations with optimistic UI updates
- R-LINUX-CULL-01/02: Streaming directory ingestion in depth-first per-folder natural order
- R-LINUX-CULL-03/04/05/06/07: Transactional Move, Copy, Trash, and Undo operations
"""

from __future__ import annotations

import bisect
import logging

from pathlib import Path
import threading
from typing import Any, Callable, Dict, List, Optional, Tuple, Union

from photo_selector_linux.core.destination import (
    MODE_CUSTOM,
    MODE_WORKING,
    DestinationResolution,
    describe_destination,
    resolve_destination,
)
from photo_selector_linux.core.copy_decisions import CopyDecisionStore, photo_key
from photo_selector_linux.core.file_ops import FileCullingManager
from photo_selector_linux.core.volumes import VERDICT_SAFE, format_verdict
from photo_selector_linux.core.models import (
    CandidatePhoto,
    CullingActionType,
    CullingRecord,
    QualityScores,
    SharpnessCategory,
)
from photo_selector_linux.core.goto import resolve_goto
from photo_selector_linux.core.bursts import burst_positions
from photo_selector_linux.viewmodels.capture_pass import CapturePass, order_basis
from photo_selector_linux.core.scanner import DirectoryScanner, natural_sort_key
from photo_selector_linux.core.settings import (
    ORDER_LOWEST_SHARPNESS,
    ORDER_NAME,
    ORDER_NEWEST,
    ORDER_OLDEST,
    PERSISTED_ORDERS,
    SettingsStore,
)
from photo_selector_linux.viewmodels.action_queue import (  # noqa: F401
    MESSAGE_NAME_CHARS,
    MESSAGE_PATH_CHARS,
    ON_CARD_NOTE,
    ActionQueueMixin,
    _QueuedAction,
    shorten_middle,
)
from photo_selector_linux.viewmodels.scan_loading import ScanLoadingMixin
from photo_selector_linux.viewmodels.scoring_pass import ScoringPassMixin


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


class CullingWorkspaceViewModel(ActionQueueMixin, ScoringPassMixin, ScanLoadingMixin):
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
        copy_decisions: Optional[CopyDecisionStore] = None,
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
        #: Photos found in the open folder, fixed once the scan is done: the M of "Photo N of M" (moved
        #: and trashed photos leave the list but not the folder's count).
        self.folder_total: int = 0
        self.trashed_count: int = 0
        self.undo_stack: List[Tuple[CullingRecord, CandidatePhoto, int]] = []
        self._undo_actions: Dict[str, _QueuedAction] = {}
        #: Undo stacks of folders that were left, so an operation that finishes after a folder switch
        #: — or a folder reopened later — keeps its undo entries (Q-7). Bounded to the newest folders.
        self._folder_undo_history: Dict[Path, List[Tuple[CullingRecord, CandidatePhoto, int]]] = {}

        #: Copied photos, kept across sessions beside the settings file (FILE_OPERATIONS.md §5).
        #: Without a settings store (tests, headless use) nothing is persisted.
        self.copy_decisions: Optional[CopyDecisionStore] = copy_decisions or (
            CopyDecisionStore(settings.config_path.with_name("copy-decisions.json"))
            if settings is not None
            else None
        )

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
        self._natural_keys: Dict[str, List[Union[int, str]]] = {}
        #: Bumped whenever the list's membership or a photo's ``copied`` flag changes: what the
        #: per-state-change tallies (``remaining_count``) are cached against (BL-P-1).
        self._list_version: int = 0
        self._remaining_cache: Optional[Tuple[Tuple[int, int, int], int]] = None
        self._burst_key: Optional[Tuple[int, int, int]] = None
        self._state_epoch: int = 0
        self._timed_epoch: Optional[Tuple[int, int, int, bool]] = None
        self._timed_seen: int = 0
        self._burst_cues: Dict[str, Tuple[int, int]] = {}
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
        key = (id(self.candidates), len(self.candidates), self._list_version)
        cached = self._remaining_cache
        if cached is None or cached[0] != key:
            # One pass per change, not per read: the window reads this several times per key press.
            cached = self._remaining_cache = (key, sum(1 for c in self.candidates if not c.copied))
        return cached[1]

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
        self._state_epoch += 1
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

    def burst_cue(self, photo: CandidatePhoto) -> Optional[Tuple[int, int]]:
        """``(position, size)`` of ``photo`` in its burst, or None (decision #31; ``core/bursts.py``)."""
        key = (self._folder_generation, len(self.candidates), self._timed_count(photo))
        if key != self._burst_key:
            self._burst_key = key
            self._burst_cues = burst_positions(self.candidates)
        return self._burst_cues.get(photo.id)

    def _timed_count(self, asked: CandidatePhoto) -> int:
        """Photos whose EXIF is read: what tells ``burst_cue`` that its cache is stale.

        The loader and the capture pass assign ``exif`` without telling the view model, so this
        is a pass over the list. It runs once per state change or when the photo asked about has
        gained its EXIF since the last pass (the info card asks the cue on every redraw), not
        once per call (BL-P-1).
        """
        epoch = (self._state_epoch, id(self.candidates), len(self.candidates), asked.exif is not None)
        if self._timed_epoch != epoch:
            self._timed_epoch = epoch
            self._timed_seen = sum(1 for c in self.candidates if c.exif is not None)
        return self._timed_seen

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

    def _natural(self, text: str) -> List[Union[int, str]]:
        """``natural_sort_key`` memoised for this folder: a sort asks it for every photo, again per batch."""
        key = self._natural_keys.get(text)
        if key is None:
            key = self._natural_keys[text] = natural_sort_key(text)
        return key

    def _sort_key(self) -> Callable[[CandidatePhoto], Any]:
        """The key of the active view order (what ``_apply_sort_order`` sorts and ``_add_scanned`` merges by)."""
        natural = self._natural
        if self.sort_order == ORDER_LOWEST_SHARPNESS:
            return lambda c: (
                (0, float(c.scores.sharpness), natural(c.filename))
                if c.scores is not None
                else (1, float("inf"), natural(c.filename))
            )
        if self.sort_order in (ORDER_NEWEST, ORDER_OLDEST):
            sign = -1.0 if self.sort_order == ORDER_NEWEST else 1.0
            return lambda c: (sign * c.order_time, natural(str(c.primary_path.parent)), natural(c.filename))
        return lambda c: (natural(str(c.primary_path.parent)), natural(c.filename))

    def _apply_sort_order(self) -> None:
        self.candidates.sort(key=self._sort_key())

    def _add_scanned(self, batch: List[CandidatePhoto]) -> None:
        """Take a scan batch in; the list stays in the active order, the photo on screen stays.

        File-name order is what the scanner emits, so a batch is appended. The other orders need
        the whole folder, which a progressive scan only has at the end: each photo of the batch is
        inserted where the active order puts it (a binary search, not a re-sort of the whole list
        per batch: BL-P-1), the photo the user is looking at is followed by identity, and until the
        user has moved the view stays on the first photo of the order (the newest, for "newest
        first"). Undo positions are not stored indices that could go stale (see ``_reinsert``).
        """
        self.folder_total += len(batch)
        self._restore_copy_decisions(batch)
        if self.sort_order == ORDER_NAME:
            self.candidates.extend(batch)
            self._list_version += 1
            return
        shown_idx: Optional[int] = None
        if self._user_navigated and self.current_photo is not None:
            shown_idx = self.current_index
        self._list_version += 1
        key = self._sort_key()
        for photo in sorted(batch, key=key):
            position = bisect.bisect_right(self.candidates, key(photo), key=key)
            self.candidates.insert(position, photo)
            if shown_idx is not None and position <= shown_idx:
                shown_idx += 1
        self.current_index = shown_idx if shown_idx is not None else 0

    def _reinsert(self, photo: CandidatePhoto, orig_idx: int) -> int:
        """Put an undone photo back where the active order wants it; return its index.

        ``orig_idx`` is where it was when it was removed. The order or the list may have changed
        since (another order chosen, more of the folder scanned), so the position is only the
        starting point; sorting puts the photo where its key says, and its identity is followed.
        """
        self._list_version += 1
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
        self._list_version += 1
        self.candidates.pop(idx)
        if idx < self.current_index:
            self.current_index -= 1
        elif not self.auto_advance and self.current_index > 0:
            self.current_index = max(0, self.current_index - 1)
        if self.current_index >= len(self.candidates) and self.current_index > 0:
            self.current_index = len(self.candidates) - 1
        return idx

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
        self._list_version += 1
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
            decision_folder=folder,
            decision_key=photo_key(folder, photo.primary_path),
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
