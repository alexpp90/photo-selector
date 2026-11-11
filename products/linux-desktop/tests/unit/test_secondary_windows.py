"""Duplicate Finder, Library Statistics and Shortcuts have a header bar and close on Escape (BL-UX-2)."""

import time
from unittest.mock import MagicMock

import pytest

from photo_selector_linux.ui import secondary_window, shortcuts_dialog, tools_dialogs

STUBBED = isinstance(secondary_window.Gtk, MagicMock) or not secondary_window.HAS_GI

needs_gtk = pytest.mark.skipif(STUBBED, reason="needs real Gtk/Adw")


def _windows():
    from photo_selector_linux.core.settings import SettingsStore
    from photo_selector_linux.ui.preferences import PreferencesDialog

    return [
        tools_dialogs.DuplicateFinderDialog(),
        tools_dialogs.LibraryStatisticsDialog(),
        shortcuts_dialog.ShortcutsDialog(),
        PreferencesDialog(settings=SettingsStore(auto_load=False)),
    ]


@needs_gtk
def test_every_secondary_window_has_a_header_bar():
    from gi.repository import Adw

    for window in _windows()[:3]:  # Preferences is an AdwPreferencesWindow: its header is built in
        toolbar = window.get_content()
        assert isinstance(toolbar, Adw.ToolbarView), type(window).__name__
        assert toolbar.get_content() is not None, f"{type(window).__name__} is blank"
        # AdwToolbarView has no getter for its bars: the header is the first child of the top box.
        assert _has_header_bar(toolbar), f"{type(window).__name__} has no header bar"


def _has_header_bar(widget) -> bool:
    from gi.repository import Adw

    child = widget.get_first_child()
    while child is not None:
        if isinstance(child, Adw.HeaderBar) or _has_header_bar(child):
            return True
        child = child.get_next_sibling()
    return False


@needs_gtk
def test_escape_is_bound_to_close_and_closes_the_window():
    from gi.repository import GLib, Gtk

    for window in _windows():
        controllers = window.observe_controllers()
        shortcuts = [
            controllers.get_item(i)
            for i in range(controllers.get_n_items())
            if isinstance(controllers.get_item(i), Gtk.ShortcutController)
        ]
        assert shortcuts, f"{type(window).__name__} ignores Escape"
        shortcut = shortcuts[0].get_item(0)
        assert shortcut.get_trigger().to_string() == "Escape"
        assert shortcut.get_action().get_action_name() == "window.close"

        window.present()
        assert window.is_visible()
        window.activate_action("window.close", None)
        context = GLib.MainContext.default()
        deadline = time.monotonic() + 3
        while window.is_visible() and time.monotonic() < deadline:
            context.iteration(False)
        assert not window.is_visible()


@needs_gtk
def test_escape_and_ctrl_w_both_close_every_secondary_window_and_hand_back_the_focus():
    """Escape and Ctrl+W are bound on all four windows; closing presents the parent again."""
    from gi.repository import Gtk

    parent = MagicMock()
    for window in _windows():
        controllers = window.observe_controllers()
        names = set()
        for i in range(controllers.get_n_items()):
            controller = controllers.get_item(i)
            if isinstance(controller, Gtk.ShortcutController):
                names |= {controller.get_item(j).get_trigger().to_string() for j in range(controller.get_n_items())}
        assert {"Escape", "<Control>w"} <= names, f"{type(window).__name__}: {names}"
        window.get_transient_for = lambda p=parent: p
        assert secondary_window._return_focus_to_parent(window) is False
        parent.present.assert_called()
        parent.reset_mock()


@needs_gtk
def test_the_scrolling_windows_show_a_scrollbar_instead_of_an_overlay_that_fades():
    from gi.repository import Gtk

    for window in (tools_dialogs.LibraryStatisticsDialog(), shortcuts_dialog.ShortcutsDialog()):
        content = window.get_content().get_content()
        scrolled = content if isinstance(content, Gtk.ScrolledWindow) else None
        assert scrolled is not None and not scrolled.get_overlay_scrolling(), type(window).__name__


def test_a_secondary_window_is_at_most_90_percent_of_its_parent_high_and_never_below_the_minimum():
    fit = secondary_window.fit_size
    assert fit(680, 560, (1366, 768)) == (680, 560)
    assert fit(680, 560, (900, 500)) == (680, 450)
    assert fit(680, 560, (400, 300)) == (400, 300)  # parent smaller than the minimum: the minimum
    assert fit(680, 560, (300, 200)) == secondary_window.MIN_SIZE
    assert fit(680, 560, None) == (680, 560)
    assert fit(680, 560, (0, 0)) == (680, 560)
