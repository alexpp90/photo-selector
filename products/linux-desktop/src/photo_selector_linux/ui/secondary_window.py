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


#: The smallest a secondary window may be sized to (width, height); GTK may lay it out larger.
MIN_SIZE = (360, 300)
#: A secondary window is at most this share of its parent's height.
MAX_PARENT_HEIGHT_SHARE = 0.9


def fit_size(width: int, height: int, parent_size: tuple[int, int] | None) -> tuple[int, int]:
    """Default size for a secondary window: ``width`` x ``height``, within the parent's room.

    The height never exceeds 90 % of the parent's (a 1366x768 laptop must not get a window taller
    than the screen), and neither side drops below ``MIN_SIZE``. No parent size: as asked.
    """
    if parent_size and parent_size[0] > 0 and parent_size[1] > 0:
        width = min(width, parent_size[0])
        height = min(height, int(parent_size[1] * MAX_PARENT_HEIGHT_SHARE))
    return max(MIN_SIZE[0], width), max(MIN_SIZE[1], height)


def parent_size_of(parent) -> tuple[int, int] | None:
    """The parent window's current size, or None when it has none yet."""
    if parent is None:
        return None
    try:
        width, height = parent.get_width(), parent.get_height()
        if width <= 0 or height <= 0:
            width, height = parent.get_default_size()
        return (width, height) if width > 0 and height > 0 else None
    except Exception:  # best effort: a parent without a size just means the requested size is used
        return None


def size_window(window, width: int, height: int, parent=None) -> None:
    """Set ``window``'s default size from ``fit_size`` against its parent."""
    window.set_default_size(*fit_size(width, height, parent_size_of(parent)))


def show_scrollbar(scrolled) -> None:
    """Make a scroll area visibly scrollable: a permanent scrollbar, not an overlay that fades.

    With overlay scrolling nothing shows that content continues below the fold (U-LIN-03).
    """
    scrolled.set_overlay_scrolling(False)


def install_close_shortcuts(window) -> None:
    """Escape and Ctrl+W close ``window``; closing hands the focus back to its parent."""
    controller = Gtk.ShortcutController()
    for trigger in ("Escape", "<Control>w"):
        controller.add_shortcut(
            Gtk.Shortcut.new(Gtk.ShortcutTrigger.parse_string(trigger), Gtk.NamedAction.new("window.close"))
        )
    window.add_controller(controller)
    window.connect("close-request", _return_focus_to_parent)


def _return_focus_to_parent(window) -> bool:
    parent = window.get_transient_for()
    if parent is not None:
        parent.present()
    return False


def install_chrome(window):
    """Give ``window`` a header bar and Escape/Ctrl+W-to-close; return the toolbar view to fill."""
    toolbar = Adw.ToolbarView()
    toolbar.add_top_bar(Adw.HeaderBar())
    window.set_content(toolbar)
    install_close_shortcuts(window)
    return toolbar
