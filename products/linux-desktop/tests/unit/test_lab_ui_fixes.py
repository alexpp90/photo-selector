"""Lab fixes LIN-7 (outcomes in the action bar, one toast, overlay around the canvas only) and
LIN-8 (3-Up 60/40 grid, HUD inside the active slot, restored lab widget ids).

Adheres to:
- R-LINUX-UI-06: 3-Up Focus Mode (One-over-Two)
- R-LINUX-UI-09: Info HUD anchored to the active preview pane
- R-LINUX-UI-10: Tactile Bottom Action Bar (status area)
"""

from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest

from photo_selector_linux.core.models import CandidatePhoto
from photo_selector_linux.ui import canvas as canvas_module
from photo_selector_linux.ui import window as window_module
from photo_selector_linux.ui.action_bar import ActionBarView
from photo_selector_linux.ui.canvas import CanvasView
from photo_selector_linux.ui.photo_loader import PhotoTextureLoader
from photo_selector_linux.viewmodels.culling_workspace import CullingWorkspaceViewModel

# Without a display conftest.py stubs Gtk; with one (Linux CI under xvfb-run) the real widgets run.
STUBBED = isinstance(canvas_module.Gtk, MagicMock)


# ── LIN-7: view model severity ────────────────────────────────────────────────


def test_severity_is_stored_before_callbacks_run():
    vm = CullingWorkspaceViewModel()
    seen = []
    vm.connect("notification", lambda v, m: seen.append((m, v.last_notification_severity)))
    vm._notify("Moved a.jpg to Selection/", "status")
    vm._notify("Could not move a.jpg: boom", "error")
    vm._notify("Loaded 3 photographs from x")
    assert seen == [
        ("Moved a.jpg to Selection/", "status"),
        ("Could not move a.jpg: boom", "error"),
        ("Loaded 3 photographs from x", "info"),
    ]


# ── LIN-7: action bar status area ─────────────────────────────────────────────


def test_outcome_is_shown_in_action_bar_and_expires():
    vm = CullingWorkspaceViewModel()
    bar = ActionBarView(viewmodel=vm)
    vm._notify("Moved a.jpg to Selection/", "status")
    assert bar.status_text == "Moved a.jpg to Selection/"
    assert bar.lbl_busy.get_label() == "Moved a.jpg to Selection/"
    assert bar.busy_box.get_visible() is True

    bar._expire_outcome()
    assert bar.status_text == ""
    assert bar.busy_box.get_visible() is False


def test_last_outcome_wins_and_stale_timer_is_ignored():
    vm = CullingWorkspaceViewModel()
    bar = ActionBarView(viewmodel=vm)
    vm._notify("Moved a.jpg to Selection/", "status")
    first_generation = bar._status_generation
    vm._notify("Moved b.jpg to Trash", "status")
    assert bar.status_text == "Moved b.jpg to Trash"
    assert bar._expire_outcome(first_generation) is False
    assert bar.status_text == "Moved b.jpg to Trash"


def test_busy_text_takes_precedence_over_outcome():
    vm = CullingWorkspaceViewModel()
    bar = ActionBarView(viewmodel=vm)
    vm._notify("Moved a.jpg to Selection/", "status")
    vm.busy_message = "Copying b.jpg to Selection/"
    vm._queue = [MagicMock()]
    vm._notify_state_changed()
    assert vm.is_busy
    assert bar.lbl_busy.get_label() == "Copying b.jpg to Selection/"


def test_info_messages_do_not_touch_the_action_bar():
    vm = CullingWorkspaceViewModel()
    bar = ActionBarView(viewmodel=vm)
    vm._notify("Loaded 3 photographs from x")
    assert bar.status_text == ""


def test_errors_show_in_action_bar_too():
    vm = CullingWorkspaceViewModel()
    bar = ActionBarView(viewmodel=vm)
    vm._notify("Could not move a.jpg: boom", "error")
    assert bar.status_text == "Could not move a.jpg: boom"


# ── LIN-7: window toasts ──────────────────────────────────────────────────────


class _Toast:
    def __init__(self, message):
        self.message = message
        self.dismissed = False

    def set_timeout(self, _seconds):
        pass

    def dismiss(self):
        self.dismissed = True


def test_status_outcomes_make_no_toast_but_errors_and_info_do(monkeypatch):
    toasts = []
    win = window_module.MainWindow()
    monkeypatch.setattr(window_module, "Adw", SimpleNamespace(Toast=SimpleNamespace(new=_Toast)))
    win.toast_overlay = MagicMock()
    win.toast_overlay.add_toast.side_effect = toasts.append
    vm = win.viewmodel

    vm._notify("Moved a.jpg to Selection/", "status")
    assert toasts == []
    vm._notify("Could not move a.jpg: boom", "error")
    vm._notify("Loaded 3 photographs from x")
    assert [t.message for t in toasts] == ["Could not move a.jpg: boom", "Loaded 3 photographs from x"]


def test_new_toast_dismisses_the_previous_one(monkeypatch):
    win = window_module.MainWindow()
    monkeypatch.setattr(window_module, "Adw", SimpleNamespace(Toast=SimpleNamespace(new=_Toast)))
    win.toast_overlay = MagicMock()
    shown = []
    win.toast_overlay.add_toast.side_effect = shown.append
    win.show_toast("one")
    win.show_toast("two")
    assert shown[0].dismissed is True
    assert shown[1].dismissed is False


def test_toast_overlay_wraps_only_the_canvas():
    win = window_module.MainWindow()
    # The overlay is the toolbar content around the canvas; the action bar is a bottom bar
    # of the toolbar view and so never a descendant of the overlay.
    if STUBBED:
        win.toolbar_view.set_content.assert_called_with(win.toast_overlay)
        win.toast_overlay.set_child.assert_called_with(win.canvas_view)
        win.toolbar_view.add_bottom_bar.assert_called_with(win.action_bar)
        return
    assert win.toolbar_view.get_content() is win.toast_overlay
    assert win.toast_overlay.get_child() is win.canvas_view
    assert not win.action_bar.is_ancestor(win.toast_overlay)
    assert win.toast_overlay.get_parent() is win.toolbar_view


# ── LIN-8: canvas ─────────────────────────────────────────────────────────────


@pytest.fixture
def fake_gtk(monkeypatch):
    """Recording stand-ins for Overlay/Grid/Picture (stub mode only) to assert parenting and spans."""
    if not STUBBED:
        return None
    base = canvas_module.Gtk.Box

    class FakeOverlay(base):
        def add_overlay(self, child):
            child._parent = self
            self._children = [c for c in getattr(self, "_children", []) if c is not child] + [child]

        def remove_overlay(self, child):
            child._parent = None
            self._children = [c for c in getattr(self, "_children", []) if c is not child]

    class FakeGrid(base):
        def __init__(self, *a, **k):
            super().__init__(*a, **k)
            self.attached = []

        def attach(self, child, col, row, width, height):
            self.attached.append((child, col, row, width, height))
            self.append(child)

        def __getattr__(self, name):
            if name.startswith("set_"):
                return lambda *a, **k: None
            raise AttributeError(name)

    monkeypatch.setattr(canvas_module.Gtk, "Overlay", FakeOverlay)
    monkeypatch.setattr(canvas_module.Gtk, "Grid", FakeGrid)

    class Leaf(base):
        def __getattr__(self, name):
            if name.startswith(("set_", "start", "stop")):
                return lambda *a, **k: None
            raise AttributeError(name)

    monkeypatch.setattr(canvas_module.Gtk, "Picture", Leaf)
    monkeypatch.setattr(canvas_module.Gtk, "Spinner", Leaf)
    return FakeGrid


def _canvas(mode, index=1, count=3):
    vm = CullingWorkspaceViewModel()
    vm.candidates = [CandidatePhoto(primary_path=Path(f"/tmp/DSC_{n:04d}.ARW")) for n in range(count)]
    vm.current_index = index
    vm.set_comparison_mode(mode)
    loader = PhotoTextureLoader(max_mb=512.0)
    loader.request = MagicMock()
    loader.evict_full_resolution = MagicMock(return_value=0)
    return vm, CanvasView(viewmodel=vm, loader=loader)


def _children(widget):
    if STUBBED:
        return list(getattr(widget, "_children", []))
    out, child = [], widget.get_first_child()
    while child is not None:
        out.append(child)
        child = child.get_next_sibling()
    return out


def _walk(widget):
    yield widget
    for child in _children(widget):
        yield from _walk(child)


def _grid_cells(grid):
    """name -> (column, row, width, height) of every grid child."""
    if STUBBED:
        return {c.get_name(): (col, row, w, h) for c, col, row, w, h in grid.attached}
    return {c.get_name(): tuple(grid.query_child(c)) for c in _children(grid)}


def _named(canvas, name):
    return [w for w in _walk(canvas.slots_box) if w.get_name() == name]


def test_3up_is_a_60_40_grid_without_fixed_size_request(fake_gtk):
    _vm, canvas = _canvas(3)
    grid = _children(canvas.slots_box)[0]
    assert _grid_cells(grid) == {
        "slot_current": (0, 0, 2, 3),
        "slot_previous": (0, 3, 1, 2),
        "slot_next": (1, 3, 1, 2),
    }
    assert grid.get_row_homogeneous() if not STUBBED else True
    top = next(w for w in _children(grid) if w.get_name() == "slot_current") if not STUBBED else None
    if top is not None:
        assert top.get_size_request() == (-1, -1)


@pytest.mark.parametrize("mode", [1, 2, 3])
def test_widget_ids_and_hud_in_active_slot(fake_gtk, mode):
    _vm, canvas = _canvas(mode)
    assert canvas.hud.get_name() == "info_hud"
    assert canvas.hud.get_can_target() is False
    current = _named(canvas, "slot_current")
    assert len(current) == 1
    assert _named(canvas, "slot_current_image")
    if mode >= 2:
        assert _named(canvas, "slot_next")
    if mode == 3:
        assert _named(canvas, "slot_previous") and _named(canvas, "slot_previous_image")
    parent = canvas.hud.get_parent()
    assert parent is canvas._active_image_area
    assert parent in list(_walk(current[0]))
    assert canvas.hud in _children(parent)


def test_hud_moves_with_navigation_and_mode_change(fake_gtk):
    vm, canvas = _canvas(3, index=1, count=4)
    first_parent = canvas.hud.get_parent()
    vm.nav_next()
    second_parent = canvas.hud.get_parent()
    assert second_parent is not None and second_parent is not first_parent
    assert second_parent is canvas._active_image_area
    assert canvas.hud not in _children(first_parent)
    vm.set_comparison_mode(1)
    third_parent = canvas.hud.get_parent()
    assert third_parent is canvas._active_image_area and third_parent is not second_parent
    assert canvas.hud not in _children(second_parent)
