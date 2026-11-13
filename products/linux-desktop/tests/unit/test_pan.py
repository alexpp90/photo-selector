"""Pan the 100 % view from the keyboard, linked across panes, and click a frame to make it active
(plan 2.1, 2.6; [USER 2026-11-12] UX notes B-2.1)."""

from unittest.mock import MagicMock

import pytest

from photo_selector_linux.ui import pan
from photo_selector_linux.ui import window as window_module
from photo_selector_linux.ui.keyboard_router import KeyboardRouterEngine

REAL = window_module.HAS_GI and not isinstance(window_module.Gtk, MagicMock)

KEY_LEFT, KEY_RIGHT, KEY_UP, KEY_DOWN, KEY_M = 0xFF51, 0xFF53, 0xFF52, 0xFF54, 0x6D
SHIFT = 1


def test_a_step_is_twenty_percent_of_the_viewport_and_stops_at_the_edge():
    assert pan.stepped_value(0.0, 1, 0.0, 3000.0, 1000.0) == 200.0
    assert pan.stepped_value(2000.0, 1, 0.0, 3000.0, 1000.0) == 2000.0  # already at the right edge
    assert pan.stepped_value(1900.0, 1, 0.0, 3000.0, 1000.0) == 2000.0  # clamped, not 2100
    assert pan.stepped_value(100.0, -1, 0.0, 3000.0, 1000.0) == 0.0
    assert pan.pan_value(200.0, 0.0, 3000.0, 1000.0) == pytest.approx(0.1)
    assert pan.pan_value(0.0, 0.0, 800.0, 1000.0) == 0.5  # nothing to scroll
    assert pan.focus_value(0.5, 0.0, 3000.0, 1000.0) == 1000.0
    assert pan.focus_value(0.0, 0.0, 3000.0, 1000.0) == 0.0


def test_shift_arrows_pan_only_while_zoomed_and_unzoomed_they_navigate():
    target = MagicMock()
    engine = KeyboardRouterEngine(target)
    target.on_pan.return_value = True
    assert engine.dispatch(KEY_RIGHT, SHIFT) is True
    target.on_pan.assert_called_with(1, 0)
    target.on_nav_next.assert_not_called()
    assert engine.dispatch(KEY_UP, SHIFT) is True
    target.on_pan.assert_called_with(0, -1)
    target.on_pan.return_value = False  # not zoomed
    assert engine.dispatch(KEY_LEFT, SHIFT) is True
    target.on_nav_previous.assert_called_once()
    assert engine.dispatch(KEY_DOWN, SHIFT) is False  # nothing to navigate to: unhandled, as before
    target.on_pan.reset_mock()
    assert engine.dispatch(KEY_M, SHIFT) is True  # Shift+M is not a pan
    target.on_pan.assert_not_called()
    assert engine.dispatch(KEY_RIGHT, 0) is True
    target.on_pan.assert_not_called()  # plain arrows never pan
    assert engine.dispatch(KEY_RIGHT, SHIFT, is_text_editing=True) is False  # text fields keep their keys


@pytest.fixture
def zoomed(tmp_path):
    from PIL import Image

    import gtk_layout_probe as probe
    from photo_selector_linux.core.settings import SettingsStore
    probe.set_text_scale(1.0)  # an earlier test may have left another scale

    root = tmp_path / "shoot"
    root.mkdir()
    for number in (1, 2, 3):
        Image.new("RGB", (3000, 2000), (number * 40, 90, 120)).save(root / f"DSC_000{number}.JPG")
    settings = SettingsStore(path=tmp_path / "settings.json", auto_load=False)
    settings.view_order = "natural"
    win = window_module.MainWindow(settings=settings)
    win.viewmodel.load_directory(root)
    assert probe.pump(until=lambda: len(win.viewmodel.candidates) == 3 and not win.viewmodel.is_scanning)
    probe.show_at(win, (1366, 768))
    yield win, probe
    win.destroy()
    probe.pump(0.1)


def _ranges(win):
    return [(v.get_hadjustment(), v.get_vadjustment()) for v in win.canvas_view.pan_group._views]


def _sized(win, panes):
    ranges = _ranges(win)
    return len(ranges) == panes and all(h.get_upper() > h.get_page_size() + 1 for h, _v in ranges)


@pytest.mark.skipif(not REAL, reason="needs real Gtk under a display server")
def test_shift_arrows_move_the_100_percent_view_by_a_fifth_of_the_viewport(zoomed):
    win, probe = zoomed
    canvas = win.canvas_view
    assert win.router.engine.dispatch(KEY_RIGHT, SHIFT) is True  # unzoomed: navigates
    assert win.viewmodel.current_index == 1
    win.viewmodel.nav_previous()
    canvas.toggle_zoom()
    assert probe.pump(until=lambda: _sized(win, 1))
    h, v = _ranges(win)[0]
    centre = h.get_value()
    assert centre == pytest.approx((h.get_upper() - h.get_page_size()) / 2, abs=2)  # opens centred
    assert win.router.engine.dispatch(KEY_RIGHT, SHIFT) is True
    assert h.get_value() == pytest.approx(centre + 0.2 * h.get_page_size(), abs=1)
    assert win.viewmodel.current_index == 0  # it panned, it did not navigate
    for _ in range(40):
        win.router.engine.dispatch(KEY_RIGHT, SHIFT)
    assert h.get_value() == pytest.approx(h.get_upper() - h.get_page_size(), abs=1)  # clamped at the edge
    win.router.engine.dispatch(KEY_DOWN, SHIFT)
    assert v.get_value() == pytest.approx((v.get_upper() - v.get_page_size()) / 2 + 0.2 * v.get_page_size(), abs=1)
    canvas.toggle_zoom()  # Space: back to fit, the position is forgotten
    assert canvas.linked_pan == (0.5, 0.5)


@pytest.mark.skipif(not REAL, reason="needs real Gtk under a display server")
def test_the_panes_of_2_up_move_together_and_the_spot_survives_the_next_photo(zoomed):
    win, probe = zoomed
    canvas = win.canvas_view
    win.viewmodel.set_comparison_mode(2)
    canvas.toggle_zoom()
    assert probe.pump(until=lambda: _sized(win, 1))
    assert canvas.request_neighbour_1to1(1) is True
    assert probe.pump(until=lambda: _sized(win, 2))
    win.router.engine.dispatch(KEY_RIGHT, SHIFT)
    (h1, _v1), (h2, _v2) = _ranges(win)
    assert h1.get_value() == pytest.approx(h2.get_value(), abs=4)  # panes of different widths round differently
    assert h1.get_value() > (h1.get_upper() - h1.get_page_size()) / 2 + 1
    spot = canvas.linked_pan
    win.viewmodel.nav_next()  # the slots are rebuilt: the new photo opens on the same spot
    assert probe.pump(until=lambda: _sized(win, 1))
    h, v = _ranges(win)[0]
    for adj, expected in ((h, spot[0]), (v, spot[1])):
        got = pan.pan_value(adj.get_value(), adj.get_lower(), adj.get_upper(), adj.get_page_size())
        assert got == pytest.approx(expected, abs=0.01)


def _clicks(widget):
    from gi.repository import Gtk

    controllers = widget.observe_controllers()
    found = [controllers.get_item(i) for i in range(controllers.get_n_items())]
    return [c for c in found if isinstance(c, Gtk.GestureClick)]


@pytest.mark.skipif(not REAL, reason="needs real Gtk under a display server")
def test_a_click_on_a_neighbour_makes_it_the_active_photo(zoomed):
    win, probe = zoomed
    win.viewmodel.set_comparison_mode(3)
    win.viewmodel.nav_next()
    probe.pump(0.2)
    box = win.canvas_view.slots_box
    grid = box.get_first_child()
    previous = next(w for w in probe.walk(grid) if w.get_name() == "slot_previous")
    assert len(_clicks(previous)) == 1
    _clicks(previous)[0].emit("released", 1, 5.0, 5.0)
    assert win.viewmodel.current_index == 0
    probe.pump(0.2)
    nxt = next(w for w in probe.walk(win.canvas_view.slots_box) if w.get_name() == "slot_next")
    _clicks(nxt)[0].emit("released", 1, 5.0, 5.0)
    assert win.viewmodel.current_index == 1


def test_the_shortcut_sheet_lists_the_pan_keys():
    from photo_selector_linux.ui.shortcuts_dialog import SHORTCUTS

    rows = {name: keys for _group, items in SHORTCUTS for name, keys in items}
    assert rows["Pan the 1:1 View"] == "Shift+Arrows"
