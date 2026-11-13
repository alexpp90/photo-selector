"""Persistent bottom tactile action bar.

Adheres to:
- R-LINUX-UI-10: Tactile Bottom Action Bar with High-Contrast Action Buttons
"""

from __future__ import annotations

import logging
from typing import Callable, Optional

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
try:
    gi.require_version("Adw", "1")
    from gi.repository import Adw
except (ImportError, ValueError, NameError):
    Adw = None

logger = logging.getLogger(__name__)

OUTCOME_SECONDS = 4
MESSAGE_MAX_CHARS = 48
# Pixels the end side of the bar needs besides the message and the Undo button (box spacing and
# the bar's padding); measured, then rounded up.
END_SIDE_SLACK = 24
#: [USER 2026-11-12] decision #27: the button reads "Move to Selection" with an icon. Below this
#: window width only the icon stays (name and tooltip keep the words); the words are never shortened.
MOVE_LABEL = "Move to Selection"
MOVE_ICON = "send-to-symbolic"
MOVE_ICON_FALLBACK = "go-next-symbolic"
MOVE_LABEL_MIN_WIDTH = 760


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
        #: Called by the Selected pill; the window wires it to Show in Files.
        self.on_open_selection: Optional[Callable[[], None]] = None
        self.trashed_label_text: str = "0 Trashed"
        self.remaining_label_text: str = "0 Remaining"
        self.selected_css_class: str = "status-counter-badge"
        self.trashed_css_class: str = "status-counter-badge"
        self.remaining_css_class: str = "status-counter-remaining"
        self.undo_label_text: str = "Undo"
        self.undo_tooltip_text: str = "Nothing to undo (Ctrl+Z)"
        self.undo_sensitive: bool = False
        #: What a screen reader says for the Undo button: the next undo and how many there are.
        self.undo_accessible_label: str = "Undo, nothing to undo"
        self._announced_queue: str = ""
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
        severity = getattr(vm, "last_notification_severity", "info")
        if severity in ("status", "error", "stranded"):
            spoken = getattr(vm, "last_notification_spoken", None)
            self.show_outcome(
                message, persistent=severity == "stranded", spoken=spoken if isinstance(spoken, str) else None
            )

    def show_outcome(self, message: str, persistent: bool = False, spoken: str | None = None) -> None:
        """Last write wins; the text clears itself after OUTCOME_SECONDS.

        ``persistent``: files were left in an unexpected place. The text stays until the next file
        operation starts (``_clear_outcome``), so a user who looked away still finds it.
        ``spoken`` is what a screen reader hears when it differs from the text ("and 2 companion
        files"; ``viewmodels/outcome_text.py``).
        """
        self._status_generation += 1
        self.status_text = message
        # An outcome arriving while the queue is busy belongs to the action that just ran.
        self._was_busy = bool(self.viewmodel.is_busy)
        if HAS_GI:
            if self._status_timer is not None:
                GLib.source_remove(self._status_timer)
                self._status_timer = None
            if not persistent:
                generation = self._status_generation
                self._status_timer = GLib.timeout_add_seconds(
                    OUTCOME_SECONDS, self._expire_outcome, generation
                )
        self._announce(spoken or message)
        self._update_state()

    def _announce(self, text: str) -> None:
        """Tell assistive technology about a change that moves no focus (GTK 4.14+; else silent).

        The outcome text is on screen for 4 s only, and a toast is not announced by itself on
        every toolkit version, so a screen reader user would otherwise never learn that a Move
        was queued, an Undo ran or a scan finished.
        """
        announce = getattr(self, "announce", None)
        priority = getattr(getattr(Gtk, "AccessibleAnnouncementPriority", None), "MEDIUM", None)
        if not (HAS_GI and callable(announce) and priority is not None and text):
            return
        try:
            announce(text, priority)
        except Exception:
            logger.debug("Accessible announcement failed", exc_info=True)

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

        # The Selected pill opens the Selection folder (plan 1.6): a flat button around the label.
        self.btn_selected = Gtk.Button()
        self.btn_selected.set_name("btn_selected")
        self.btn_selected.add_css_class("flat")
        self.btn_selected.set_child(self.lbl_selected)
        self.btn_selected.set_focus_on_click(False)
        self.btn_selected.set_tooltip_text("Photos moved or copied to Selection. Click to open it.")
        self.lbl_trashed.set_tooltip_text("Photos moved to Trash")
        self.lbl_remaining.set_tooltip_text("Photos not decided yet")
        self.btn_selected.update_property(
            [Gtk.AccessibleProperty.LABEL], ["Selected, open Selection folder"]
        )
        self.btn_selected.connect("clicked", self._on_selected_clicked)
        self.counter_box.append(self.btn_selected)
        self.counter_box.append(self.lbl_trashed)
        self.counter_box.append(self.lbl_remaining)
        self.pack_start(self.counter_box)

        # Progress of running/queued file operations (a Google Drive upload takes seconds).
        self.busy_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        self.busy_spinner = Gtk.Spinner()
        self.lbl_busy = _CappedLabel(label="")
        self.lbl_busy.set_ellipsize(3)  # Pango.EllipsizeMode.END: only a very narrow window cuts
        # Bounded so the bar's centred Move/Copy/Trash buttons never shift under the pointer, but
        # wide enough for "Moved IMG_…_064000 +2 · Selection" (the file name is already
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

        self.btn_move = Gtk.Button()
        self.btn_move.set_name("btn_move")
        self.btn_move.add_css_class("action-btn-move")
        self.btn_move.add_css_class("suggested-action")
        self.move_content = None
        if Adw is not None and hasattr(Adw, "ButtonContent"):
            self.move_content = Adw.ButtonContent(icon_name=self._move_icon_name(), label=MOVE_LABEL)
            self.btn_move.set_child(self.move_content)
        else:
            self.btn_move.set_label(MOVE_LABEL)
        self.btn_move.update_property([Gtk.AccessibleProperty.LABEL], [MOVE_LABEL])
        self.btn_move.set_tooltip_text("Move to Selection (M)")
        self.btn_move.connect("clicked", lambda b: self.viewmodel.cull_move())
        center_box.append(self.btn_move)

        self.btn_copy = Gtk.Button(label="Copy")
        self.btn_copy.set_name("btn_copy")
        self.btn_copy.add_css_class("action-btn-copy")
        self.btn_copy.set_tooltip_text("Copy photo & companions to Selection/ (C)")
        self.btn_copy.connect("clicked", lambda b: self.viewmodel.cull_copy())
        center_box.append(self.btn_copy)

        self.btn_trash = Gtk.Button(label="Trash")
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
        self.btn_undo.set_tooltip_text(self.undo_tooltip_text)
        self.btn_undo.connect("clicked", lambda b: self.viewmodel.undo())
        self.pack_end(self.btn_undo)
        # Buttons take focus from the keyboard only: a click would leave Space bound to the button
        # instead of the zoom toggle.
        for button in (self.btn_move, self.btn_copy, self.btn_trash, self.btn_undo):
            button.set_focus_on_click(False)
        # Status (busy text, last outcome) sits left of Undo: the end side has more free room
        # than the start side with its three counters.
        self.busy_box.set_visible(False)
        self.pack_end(self.busy_box)

    @staticmethod
    def _move_icon_name() -> str:
        """``send-to-symbolic`` where the icon theme has it, else ``go-next-symbolic``."""
        try:
            from gi.repository import Gdk
            display = Gdk.Display.get_default()
            theme = Gtk.IconTheme.get_for_display(display) if display is not None else None
            if theme is not None and not theme.has_icon(MOVE_ICON):
                return MOVE_ICON_FALLBACK
        except Exception:  # noqa: BLE001 - an icon lookup never stops the bar from building
            logger.debug("Icon theme lookup failed", exc_info=True)
        return MOVE_ICON

    def move_label_text(self) -> str:
        """The words on the Move button; empty while only its icon is shown."""
        if self.move_content is not None:
            return self.move_content.get_label() or ""
        return self.btn_move.get_label() or ""

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
            self._fit_move_label(width)

    def _fit_move_label(self, width: int) -> None:
        """Narrowest windows keep the icon, the name and the tooltip; the words are never cut."""
        if self.move_content is not None and width > 0:
            self.move_content.set_label(MOVE_LABEL if width >= MOVE_LABEL_MIN_WIDTH else "")

    def _on_state_changed(self, vm) -> None:
        self._update_state()

    def _on_selected_clicked(self, _button) -> None:
        if self.on_open_selection is not None:
            self.on_open_selection()

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
        self.undo_label_text = "Undo"
        if undo_depth > 0:
            record, photo, _idx = self.viewmodel.undo_stack[-1]
            self.undo_accessible_label = f"Undo {record.action_type.value}, {undo_depth} available"
            self.undo_tooltip_text = (
                f"Undo {record.action_type.value} of {photo.filename} (Ctrl+Z)"
                + (f", {undo_depth} in all" if undo_depth > 1 else "")
            )
        else:
            self.undo_tooltip_text = "Nothing to undo (Ctrl+Z)"
            self.undo_accessible_label = "Undo, nothing to undo"
        if self.queue_label_text and self.queue_label_text != self._announced_queue:
            self._announce(self.queue_label_text)
        self._announced_queue = self.queue_label_text

        if not HAS_GI:
            return

        self.lbl_selected.set_label(self.selected_label_text)
        self.btn_selected.set_sensitive(self.viewmodel.selected_count > 0)
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
        self.btn_undo.set_tooltip_text(self.undo_tooltip_text)
        self.btn_undo.update_property([Gtk.AccessibleProperty.LABEL], [self.undo_accessible_label])
