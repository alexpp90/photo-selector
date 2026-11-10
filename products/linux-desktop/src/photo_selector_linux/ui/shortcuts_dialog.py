"""Keyboard shortcuts cheatsheet window.

Adheres to:
- R-LINUX-KEY-06: Keyboard Shortcuts Cheat Sheet
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

from photo_selector_linux.ui.secondary_window import install_chrome, show_scrollbar, size_window


SHORTCUTS = [
    ("Navigation", [
        ("Previous Photo (H goes away next release)", "Left Arrow / K / H"),
        ("Next Photo (L goes away next release)", "Right Arrow / J / L"),
        ("Go to Photo", "G"),
    ]),
    ("Culling Actions", [
        ("Keep (move to Selection)", "M"),
        ("Copy to Selection", "C"),
        ("Trash", "Delete / Backspace"),
        ("Undo Action", "Ctrl+Z"),
    ]),
    ("View Modes", [
        ("1-Up (Single View)", "1"),
        ("2-Up (Side-by-Side)", "2"),
        ("3-Up (Focus Triplet)", "3"),
        ("1:1 Pixel Zoom Toggle", "Space"),
        ("Reset Zoom, then Leave Full Screen", "Escape"),
        ("Toggle Metadata HUD", "I"),
        ("View Order Menu (Newest, Oldest, File Name, Lowest Sharpness)", "S"),
        ("Full Screen", "F / F11"),
        ("Lowest Sharpness First, and Back (B goes away next release)", "B"),
    ]),
    ("Application", [
        ("Open Folder", "Ctrl+O"),
        ("Duplicate Finder", "Ctrl+D"),
        ("Library Statistics", "Ctrl+L"),
        ("Preferences", "Ctrl+,"),
        ("Keyboard Shortcuts", "? or Ctrl+?"),
        ("Close This Window", "Escape / Ctrl+W"),
    ]),
]


class ShortcutsDialog(Adw.Window if HAS_GI else object):
    """Cheat sheet window presenting keyboard shortcuts."""

    def __init__(self, transient_for=None):
        if HAS_GI:
            super().__init__()
            self.set_title("Keyboard Shortcuts")
            size_window(self, 500, 480, transient_for)
            if transient_for:
                self.set_transient_for(transient_for)
            self._toolbar = install_chrome(self)
            self._build_ui()

    def _build_ui(self) -> None:
        if not HAS_GI:
            return

        scrolled = Gtk.ScrolledWindow()
        show_scrollbar(scrolled)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=16)
        box.set_margin_top(16)
        box.set_margin_bottom(16)
        box.set_margin_start(16)
        box.set_margin_end(16)
        scrolled.set_child(box)

        for section_title, items in SHORTCUTS:
            group = Adw.PreferencesGroup()
            group.set_title(section_title)
            for title, accel in items:
                row = Adw.ActionRow()
                row.set_title(title)
                row.set_subtitle(accel)
                group.add(row)
            box.append(group)

        self._toolbar.set_content(scrolled)
