"""Overlays never cover controls: the toast and the info HUD stay clear of the action bar, the
header bar, every button and the focused control, at every text scale and window size (class
check, real GTK4 under Xvfb).

Adheres to:
- R-LINUX-UI-09: Info HUD anchored to the active preview pane
- R-LINUX-UI-10: Tactile bottom action bar (a toast must never cover it, LIN-7)
"""

from pathlib import Path
from unittest.mock import MagicMock

import pytest

from photo_selector_linux.ui import window as window_module

STUBBED = isinstance(window_module.Gtk, MagicMock) or not window_module.HAS_GI
pytestmark = pytest.mark.skipif(STUBBED, reason="needs real Gtk/Adw under a display server")

if not STUBBED:
    import gtk_layout_probe as probe
    from gi.repository import Adw, Gdk, Gtk
    from photo_selector_linux.core.settings import SettingsStore
    from photo_selector_linux.ui import styling
    from photo_selector_linux.ui.window import MainWindow

LONG_TOAST = "Could not read a folder with a very long name: permission denied while listing its contents"


@pytest.fixture(scope="module", autouse=True)
def studio_css():
    Adw.init()
    provider = Gtk.CssProvider()
    provider.load_from_data(styling.STUDIO_DARK_CSS.encode("utf-8"))
    display = Gdk.Display.get_default()
    Gtk.StyleContext.add_provider_for_display(display, provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
    yield
    Gtk.StyleContext.remove_provider_for_display(display, provider)


@pytest.fixture(autouse=True)
def reset_scale():
    yield
    probe.set_text_scale(1.0)


@pytest.fixture
def folder(tmp_path) -> Path:
    from PIL import Image

    root = tmp_path / "shoot"
    root.mkdir()
    for number, colour in enumerate(("red", "green", "blue")):
        Image.new("RGB", (240, 160), colour).save(root / f"IMG_20260814_06400{number}.jpg")
    return root


def _window(tmp_path, folder, mode):
    win = MainWindow(settings=SettingsStore(path=tmp_path / "settings.json", auto_load=False))
    win.viewmodel.load_directory(folder)
    assert probe.pump(until=lambda: len(win.viewmodel.candidates) == 3 and not win.viewmodel.is_scanning)
    win.viewmodel.set_comparison_mode(mode)
    return win


def _toast_widget(win):
    return next((w for w in probe.walk(win) if type(w).__name__ == "AdwToastWidget" and w.get_mapped()), None)


def _show_toast(win):
    win.show_toast(LONG_TOAST, persistent=True)
    assert probe.pump(until=lambda: _toast_widget(win) is not None and _toast_widget(win).get_height() > 0)
    probe.pump(0.5)  # the slide-in animation


def _covered(overlay, win):
    """What the overlay lies over: the bars, every sensitive button and the focused control."""
    box = probe.visible_bounds(overlay, win)  # what is drawn: the canvas clips its overlays
    assert box is not None
    candidates = [win.action_bar, win.header_bar] + probe.interactive_widgets(win)
    focus = win.get_focus()
    if focus is not None:
        candidates.append(focus)
    found = []
    for widget in candidates:
        if widget is overlay or widget.is_ancestor(overlay) or overlay.is_ancestor(widget):
            continue
        other = probe.visible_bounds(widget, win)
        if other is None:
            continue
        width, height = probe.intersection(box, other)
        if width > probe.OVERLAP_PX and height > probe.OVERLAP_PX:
            found.append(probe.describe(widget))
    return found


@pytest.mark.parametrize("focus", ["none", "btn_move", "btn_undo", "btn_mode_1"])
@pytest.mark.parametrize("mode", [1, 3])
@pytest.mark.parametrize("scale", [1.0, 2.0])
def test_toast_and_hud_cover_no_control_or_focused_widget(scale, mode, focus, tmp_path, folder):
    probe.set_text_scale(scale)
    failures = []
    for size in (None,) + probe.SIZES:
        win = _window(tmp_path, folder, mode)
        try:
            probe.show_at(win, size)
            target = {
                "none": None,
                "btn_move": win.action_bar.btn_move,
                "btn_undo": win.action_bar.btn_undo,
                "btn_mode_1": win.btn_mode_1,
            }[focus]
            if target is not None:
                target.set_sensitive(True)
                target.grab_focus()
            _show_toast(win)
            hud = win.canvas_view.hud
            assert hud.get_mapped(), "the HUD is shown for a loaded photo"
            for name, overlay in (("toast", _toast_widget(win)), ("HUD", hud)):
                # The HUD at the smallest 1-Up window is a known defect: its own test below.
                known = name == "HUD" and size is None and mode == 1
                if not known and probe.visible_bounds(overlay, win) != probe.bounds(overlay, win):
                    failures.append(f"{name} is cut off (x{scale}, {size or 'minimum'}, mode {mode})")
                for covered in _covered(overlay, win):
                    failures.append(
                        f"{name} covers {covered} (x{scale}, {size or 'minimum'}, mode {mode}, focus {focus})"
                    )
        finally:
            win.destroy()
            probe.pump(0.1)
    assert not failures, "\n".join(sorted(set(failures)))


def test_the_check_goes_red_when_the_action_bar_sits_inside_the_toast_overlay(tmp_path, folder):
    """The LIN-7 defect planted: the bar inside the overlay is covered by the bottom-aligned toast."""
    probe.set_text_scale(1.0)
    win = _window(tmp_path, folder, 1)
    try:
        win.toolbar_view.remove(win.action_bar)
        win.toast_overlay.set_child(None)
        column = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        win.canvas_view.set_vexpand(True)
        column.append(win.canvas_view)
        column.append(win.action_bar)
        win.toast_overlay.set_child(column)
        probe.show_at(win, (1366, 768))
        _show_toast(win)
        assert any("action_bar" in c or "ActionBar" in c for c in _covered(_toast_widget(win), win))
    finally:
        win.destroy()
        probe.pump(0.1)


@pytest.mark.xfail(
    strict=True,
    reason="NEW (no ledger id yet): at the smallest window the 1-Up HUD (73 px) is taller than the "
    "canvas (86 px minus its 30 px image slot) and is cut off at the top; ui/canvas.py _attach_hud "
    "overlays it on the image area, ui/info_hud.py anchors it START/END without a height limit",
)
def test_the_hud_is_not_cut_off_at_the_smallest_window(tmp_path, folder):
    probe.set_text_scale(1.0)
    win = _window(tmp_path, folder, 1)
    try:
        probe.show_at(win, None)
        hud = win.canvas_view.hud
        assert hud.get_mapped()
        assert probe.visible_bounds(hud, win) == probe.bounds(hud, win)
    finally:
        win.destroy()
        probe.pump(0.1)
