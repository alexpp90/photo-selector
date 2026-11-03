"""Short action labels with the keys in tooltips and the ? sheet; text that follows the font
setting; a mode switcher with a pressed state (BL-UX-3/5/7, maintainer decision 2026-10-24)."""

import re
from unittest.mock import MagicMock

import pytest

from photo_selector_linux.ui import styling
from photo_selector_linux.ui import window as window_module
from photo_selector_linux.ui.action_bar import ActionBarView
from photo_selector_linux.ui.shortcuts_dialog import SHORTCUTS
from photo_selector_linux.viewmodels.culling_workspace import CullingWorkspaceViewModel

STUBBED = isinstance(window_module.Gtk, MagicMock)


def test_action_buttons_have_at_most_two_words_and_the_key_is_in_the_tooltip():
    bar = ActionBarView(viewmodel=CullingWorkspaceViewModel())
    labels = {n: getattr(bar, n).get_label() for n in ("btn_move", "btn_copy", "btn_trash", "btn_undo")}
    assert labels == {"btn_move": "Keep", "btn_copy": "Copy", "btn_trash": "Trash", "btn_undo": "Undo"}
    assert all(len(label.split()) <= 2 and "(" not in label for label in labels.values())


@pytest.mark.skipif(STUBBED, reason="tooltips are only kept by real widgets")
def test_every_action_button_names_its_key_in_the_tooltip():
    bar = ActionBarView(viewmodel=CullingWorkspaceViewModel())
    keys = {"btn_move": "(M)", "btn_copy": "(C)", "btn_trash": "Delete", "btn_undo": "Ctrl+Z"}
    for name, key in keys.items():
        assert key in getattr(bar, name).get_tooltip_text(), name


def test_the_shortcut_sheet_lists_the_keys_of_the_action_buttons():
    listed = {accel for _section, items in SHORTCUTS for _title, accel in items}
    for key in ("M", "C", "Delete / Backspace", "Ctrl+Z", "1", "2", "3"):
        assert key in listed


def test_font_sizes_follow_the_system_text_scaling():
    assert not re.findall(r"font-size:\s*\d+(\.\d+)?px", styling.STUDIO_DARK_CSS)
    assert re.findall(r"font-size:\s*\d+%", styling.STUDIO_DARK_CSS)


@pytest.mark.skipif(STUBBED, reason="needs real Gtk")
def test_the_stylesheet_parses_without_errors():
    from gi.repository import Gtk

    errors = []
    provider = Gtk.CssProvider()
    provider.connect("parsing-error", lambda _p, section, error: errors.append((section.to_string(), error.message)))
    provider.load_from_data(styling.STUDIO_DARK_CSS.encode("utf-8"))
    assert errors == []


@pytest.mark.skipif(STUBBED, reason="needs real Gtk")
def test_text_scaling_grows_the_counters():
    """A percentage font-size is relative to the inherited size, so a bigger system font grows it."""
    from gi.repository import Gdk, Gtk

    css = Gtk.CssProvider()
    css.load_from_data(styling.STUDIO_DARK_CSS.encode("utf-8"))
    display = Gdk.Display.get_default()
    Gtk.StyleContext.add_provider_for_display(display, css, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
    sizes = []
    settings = Gtk.Settings.get_for_display(display)
    original = settings.get_property("gtk-font-name")
    try:
        for font in ("Sans 10", "Sans 20"):
            settings.set_property("gtk-font-name", font)
            label = Gtk.Label(label="0 Selected")
            label.add_css_class("status-counter-badge")
            window = Gtk.Window()
            window.set_child(label)
            window.present()
            sizes.append(label.create_pango_layout("0").get_pixel_size()[1])
            window.destroy()
    finally:
        settings.set_property("gtk-font-name", original)
        Gtk.StyleContext.remove_provider_for_display(display, css)  # leave the display as found
    assert sizes[1] > sizes[0] * 1.5, sizes


@pytest.mark.skipif(STUBBED, reason="needs real Gtk")
def test_mode_switcher_is_a_toggle_group_that_does_not_echo():
    from gi.repository import Gtk

    win = window_module.MainWindow()
    vm = win.viewmodel
    buttons = [win.btn_mode_1, win.btn_mode_2, win.btn_mode_3]
    assert all(isinstance(b, Gtk.ToggleButton) for b in buttons)
    assert [b.get_active() for b in buttons] == [True, False, False]

    win.btn_mode_2.set_active(True)  # a click
    assert vm.comparison_mode == 2
    assert [b.get_active() for b in buttons] == [False, True, False]

    calls = []
    original = vm.set_comparison_mode
    vm.set_comparison_mode = lambda mode: (calls.append(mode), original(mode))[1]
    vm.set_comparison_mode(3)  # the key: toggles follow, no second call back from the handler
    assert calls == [3]
    assert [b.get_active() for b in buttons] == [False, False, True]
    assert not any(b.get_focus_on_click() for b in buttons)
