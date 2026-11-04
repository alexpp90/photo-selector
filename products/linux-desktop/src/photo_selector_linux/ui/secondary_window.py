"""Chrome shared by the secondary windows (Duplicate Finder, Library Statistics, Shortcuts).

An ``AdwWindow`` has no title bar of its own, so these windows had no close control and ignored
Escape. They get an ``AdwToolbarView`` with an ``AdwHeaderBar`` (title, close button) and an
Escape shortcut that closes them.
"""

from __future__ import annotations

try:
    import gi
    gi.require_version("Gtk", "4.0")
    gi.require_version("Adw", "1")
    from gi.repository import Gtk, Adw
    HAS_GI = True
except (ImportError, ValueError):
    HAS_GI = False
    Gtk = Adw = None


def install_chrome(window):
    """Give ``window`` a header bar and Escape-to-close; return the toolbar view to fill."""
    toolbar = Adw.ToolbarView()
    toolbar.add_top_bar(Adw.HeaderBar())
    window.set_content(toolbar)

    controller = Gtk.ShortcutController()
    controller.add_shortcut(
        Gtk.Shortcut.new(
            Gtk.ShortcutTrigger.parse_string("Escape"),
            Gtk.NamedAction.new("window.close"),
        )
    )
    window.add_controller(controller)
    return toolbar
