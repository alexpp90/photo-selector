"""Duplicate Finder, Library Statistics and Shortcuts have a header bar and close on Escape (BL-UX-2)."""

import time
from unittest.mock import MagicMock

import pytest

from photo_selector_linux.ui import secondary_window, shortcuts_dialog, tools_dialogs

STUBBED = isinstance(secondary_window.Gtk, MagicMock) or not secondary_window.HAS_GI

pytestmark = pytest.mark.skipif(STUBBED, reason="needs real Gtk/Adw")


def _windows():
    return [
        tools_dialogs.DuplicateFinderDialog(),
        tools_dialogs.LibraryStatisticsDialog(),
        shortcuts_dialog.ShortcutsDialog(),
    ]


def test_every_secondary_window_has_a_header_bar():
    from gi.repository import Adw

    for window in _windows():
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
