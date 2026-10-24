"""Persistent bottom tactile action bar.

Adheres to:
- R-LINUX-UI-10: Tactile Bottom Action Bar with High-Contrast Action Buttons
"""

from __future__ import annotations

try:
    import gi
    gi.require_version("Gtk", "4.0")
    from gi.repository import Gtk
    from gi.repository import GLib
    HAS_GI = True
except (ImportError, ValueError):
    HAS_GI = False
    Gtk = None
    GLib = None

OUTCOME_SECONDS = 4
MESSAGE_MAX_CHARS = 48
# Pixels the end side of the bar needs besides the message and the Undo button (box spacing and
# the bar's padding); measured, then rounded up.
END_SIDE_SLACK = 24


class _CappedLabel(Gtk.Label if HAS_GI else object):
    """A label whose natural width never exceeds what ``cap_fn()`` returns (pixels; None: no cap).

    GtkCenterBox moves the centred widget as soon as a side asks for more than half of the free
    space, so the message may grow only as far as that symmetric room goes (LIN-7).
    """

    cap_fn = None

    def do_measure(self, orientation, for_size):
        minimum, natural, _min_baseline, _nat_baseline = Gtk.Label.do_measure(self, orientation, for_size)
        if orientation == Gtk.Orientation.HORIZONTAL and self.cap_fn is not None:
            cap = self.cap_fn()
            if cap is not None:
                natural = max(minimum, min(natural, cap))
        return minimum, natural, -1, -1


class ActionBarView(Gtk.ActionBar if HAS_GI else object):
    """Bottom tactile action bar with culling buttons, undo counter, and status tallies."""

    def __init__(self, viewmodel):
        self.viewmodel = viewmodel
        self.selected_label_text: str = "0 Selected"
        self.trashed_label_text: str = "0 Trashed"
        self.remaining_label_text: str = "0 Remaining"
        self.selected_css_class: str = "status-counter-badge"
        self.trashed_css_class: str = "status-counter-badge"
        self.remaining_css_class: str = "status-counter-remaining"
        self.undo_label_text: str = "Undo (Ctrl+Z)"
        self.undo_sensitive: bool = False
        # While a file operation runs: what it does and how many are queued; buttons stay active so rapid keys queue.
        self.busy_text: str = ""
        self.queue_label_text: str = ""
        self.cull_sensitive: bool = True
        # Latest cull/undo outcome (LIN-7); shown while idle, cleared after OUTCOME_SECONDS.
        self.status_text: str = ""
        self._status_timer = None
        self._status_generation: int = 0
        self._was_busy: bool = False

        if HAS_GI:
            super().__init__()
            self.set_name("action_bar")
            self.add_css_class("studio-actionbar")
            self._build_ui()

        self.viewmodel.connect("state-changed", self._on_state_changed)
        self.viewmodel.connect("notification", self._on_notification)
        self._update_state()

    def _on_notification(self, vm, message: str) -> None:
        """Show cull/undo outcomes and errors here; info messages (load) stay toasts."""
        if getattr(vm, "last_notification_severity", "info") in ("status", "error"):
            self.show_outcome(message)

    def show_outcome(self, message: str) -> None:
        """Last write wins; the text clears itself after OUTCOME_SECONDS."""
        self._status_generation += 1
        self.status_text = message
        # An outcome arriving while the queue is busy belongs to the action that just ran.
        self._was_busy = bool(self.viewmodel.is_busy)
        if HAS_GI:
            if self._status_timer is not None:
                GLib.source_remove(self._status_timer)
            generation = self._status_generation
            self._status_timer = GLib.timeout_add_seconds(
                OUTCOME_SECONDS, self._expire_outcome, generation
            )
        self._update_state()

    def _expire_outcome(self, generation: int | None = None) -> bool:
        if generation is not None and generation != self._status_generation:
            return False
        self._status_timer = None
        self.status_text = ""
        self._update_state()
        return False

    def _clear_outcome(self) -> None:
        """Drop the previous outcome the moment a new action starts: it no longer applies."""
        self._status_generation += 1
        self.status_text = ""
        if HAS_GI and self._status_timer is not None:
            GLib.source_remove(self._status_timer)
            self._status_timer = None

    def _build_ui(self) -> None:
        if not HAS_GI:
            return

        # Start: Status counters (Selected · Trashed · Remaining)
        self.counter_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        self.lbl_selected = Gtk.Label(label=self.selected_label_text)
        self.lbl_selected.add_css_class(self.selected_css_class)
        self.lbl_trashed = Gtk.Label(label=self.trashed_label_text)
        self.lbl_trashed.add_css_class(self.trashed_css_class)
        self.lbl_remaining = Gtk.Label(label=self.remaining_label_text)
        self.lbl_remaining.add_css_class(self.remaining_css_class)

        self.counter_box.append(self.lbl_selected)
        self.counter_box.append(self.lbl_trashed)
        self.counter_box.append(self.lbl_remaining)
        self.pack_start(self.counter_box)

        # Progress of running/queued file operations (a Google Drive upload takes seconds).
        self.busy_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        self.busy_spinner = Gtk.Spinner()
        self.lbl_busy = _CappedLabel(label="")
        self.lbl_busy.set_ellipsize(3)  # Pango.EllipsizeMode.END: only a very narrow window cuts
        # Bounded so the bar's centred Move/Copy/Trash buttons never shift under the pointer, but
        # wide enough for "Moved IMG_…_064000 + 2 to Selection/" (the file name is already
        # shortened in the middle by the view model; the full text is in the tooltip).
        self.lbl_busy.set_max_width_chars(MESSAGE_MAX_CHARS)
        self.lbl_busy.set_xalign(1.0)
        self.lbl_busy.cap_fn = self._message_cap
        self._surface_width = 0
        self.connect("realize", self._on_realize)
        self.lbl_queue = Gtk.Label(label="")
        self.lbl_queue.add_css_class("status-counter-badge")
        self.busy_box.append(self.busy_spinner)
        self.busy_box.append(self.lbl_busy)
        self.busy_box.append(self.lbl_queue)

        # Center: Tactile Action Buttons
        center_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)

        self.btn_move = Gtk.Button(label="Move to Selection (M)")
        self.btn_move.set_name("btn_move")
        self.btn_move.add_css_class("action-btn-move")
        self.btn_move.add_css_class("suggested-action")
        self.btn_move.set_tooltip_text("Move photo & companions to Selection/ (M)")
        self.btn_move.connect("clicked", lambda b: self.viewmodel.cull_move())
        center_box.append(self.btn_move)

        self.btn_copy = Gtk.Button(label="Copy to Selection (C)")
        self.btn_copy.set_name("btn_copy")
        self.btn_copy.add_css_class("action-btn-copy")
        self.btn_copy.set_tooltip_text("Copy photo & companions to Selection/ (C)")
        self.btn_copy.connect("clicked", lambda b: self.viewmodel.cull_copy())
        center_box.append(self.btn_copy)

        self.btn_trash = Gtk.Button(label="Move to Trash (Del)")
        self.btn_trash.set_name("btn_trash")
        self.btn_trash.add_css_class("action-btn-trash")
        self.btn_trash.add_css_class("destructive-action")
        self.btn_trash.set_tooltip_text("Move photo & companions to Trash (Delete/Backspace)")
        self.btn_trash.connect("clicked", lambda b: self.viewmodel.cull_trash())
        center_box.append(self.btn_trash)

        self.set_center_widget(center_box)

        # End: Transactional Undo
        self.btn_undo = Gtk.Button(label=self.undo_label_text)
        self.btn_undo.set_name("btn_undo")
        self.btn_undo.set_icon_name("edit-undo-symbolic")
        self.btn_undo.set_tooltip_text("Undo last culling action (Ctrl+Z)")
        self.btn_undo.connect("clicked", lambda b: self.viewmodel.undo())
        self.pack_end(self.btn_undo)
        # Status (busy text, last outcome) sits left of Undo: the end side has more free room
        # than the start side with its three counters.
        self.busy_box.set_visible(False)
        self.pack_end(self.busy_box)

    def _message_cap(self) -> int | None:
        """Pixels the message may take: the room beside the centred buttons minus the Undo button.

        None until the bar has a width (the first layout pass).
        """
        width = self._surface_width or self.get_width()
        if width <= 0:
            return None
        horizontal = Gtk.Orientation.HORIZONTAL
        center = self.get_center_widget().measure(horizontal, -1).natural
        undo = self.btn_undo.measure(horizontal, -1).natural
        return max(0, (width - center) // 2 - undo - END_SIDE_SLACK)

    def _on_realize(self, _widget) -> None:
        """Follow the window's size through the surface's one-shot ``layout`` signal.

        No tick callback: it would keep the frame clock running every frame for the window's life.
        """
        native = self.get_native()
        surface = native.get_surface() if native is not None else None
        if surface is not None:
            surface.connect("layout", self._on_surface_layout)
            self._on_surface_layout(surface, surface.get_width(), surface.get_height())

    def _on_surface_layout(self, _surface, width: int, _height: int) -> None:
        """The window was resized: the message is re-measured against the new width."""
        if width != self._surface_width:
            self._surface_width = width
            self.lbl_busy.queue_resize()

    def _on_state_changed(self, vm) -> None:
        self._update_state()

    def _update_state(self) -> None:
        sel = self.viewmodel.selected_count
        trs = self.viewmodel.trashed_count
        rem = self.viewmodel.remaining_count
        undo_depth = len(self.viewmodel.undo_stack)

        self.selected_label_text = f"{sel} Selected"
        self.trashed_label_text = f"{trs} Trashed"
        self.remaining_label_text = f"{rem} Remaining"
        self.selected_css_class = "status-counter-selected" if sel > 0 else "status-counter-badge"
        self.trashed_css_class = "status-counter-trashed" if trs > 0 else "status-counter-badge"
        self.remaining_css_class = "status-counter-remaining"
        busy = self.viewmodel.is_busy
        if busy and not self._was_busy:
            self._clear_outcome()
        self._was_busy = busy
        self.busy_text = self.viewmodel.busy_message or ""
        self.queue_label_text = getattr(self.viewmodel, "queue_status_label", None) or ""
        self.cull_sensitive = rem > 0
        self.undo_sensitive = undo_depth > 0
        self.undo_label_text = f"Undo ({undo_depth}) (Ctrl+Z)" if undo_depth > 0 else "Undo (Ctrl+Z)"

        if not HAS_GI:
            return

        self.lbl_selected.set_label(self.selected_label_text)
        self.lbl_selected.set_css_classes([self.selected_css_class])
        self.lbl_trashed.set_label(self.trashed_label_text)
        self.lbl_trashed.set_css_classes([self.trashed_css_class])
        self.lbl_remaining.set_label(self.remaining_label_text)
        self.lbl_remaining.set_css_classes([self.remaining_css_class])

        self.btn_undo.set_sensitive(self.undo_sensitive)
        for button in (self.btn_move, self.btn_copy, self.btn_trash):
            button.set_sensitive(self.cull_sensitive)
        shown = self.busy_text if busy else self.status_text
        self.lbl_busy.set_label(shown)
        self.lbl_busy.set_tooltip_text(shown or None)
        self.lbl_queue.set_label(self.queue_label_text)
        self.lbl_queue.set_visible(bool(self.queue_label_text))
        self.busy_box.set_visible(busy or bool(self.status_text))
        self.busy_spinner.set_visible(busy)
        if busy:
            self.busy_spinner.start()
        else:
            self.busy_spinner.stop()
        self.btn_undo.set_label(self.undo_label_text)
