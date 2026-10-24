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


class ShortcutsDialog(Adw.Window if HAS_GI else object):
    """Cheat sheet window presenting keyboard shortcuts."""

    def __init__(self, transient_for=None):
        if HAS_GI:
            super().__init__()
            self.set_title("Keyboard Shortcuts")
            self.set_default_size(500, 480)
            if transient_for:
                self.set_transient_for(transient_for)
            self._build_ui()

    def _build_ui(self) -> None:
        if not HAS_GI:
            return

        scrolled = Gtk.ScrolledWindow()
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=16)
        box.set_margin_top(16)
        box.set_margin_bottom(16)
        box.set_margin_start(16)
        box.set_margin_end(16)
        scrolled.set_child(box)

        shortcuts = [
            ("Navigation", [
                ("Previous Photo", "Left Arrow / H / K"),
                ("Next Photo", "Right Arrow / L / J"),
            ]),
            ("Culling Actions", [
                ("Move to Selection", "M"),
                ("Copy to Selection", "C"),
                ("Move to Trash", "Delete / Backspace"),
                ("Undo Action", "Ctrl+Z"),
            ]),
            ("View Modes", [
                ("1-Up (Single View)", "1"),
                ("2-Up (Side-by-Side)", "2"),
                ("3-Up (Focus Triplet)", "3"),
                ("1:1 Pixel Zoom Toggle", "Space"),
                ("Reset Zoom", "Escape"),
                ("Toggle Metadata HUD", "I"),
                ("Lowest Sharpness First / Natural Order", "B"),
            ]),
            ("Application", [
                ("Open Folder", "Ctrl+O"),
                ("Duplicate Finder", "Ctrl+D"),
                ("Library Statistics", "Ctrl+L"),
                ("Preferences", "Ctrl+,"),
                ("Keyboard Shortcuts", "? or Ctrl+?"),
            ]),
        ]

        for section_title, items in shortcuts:
            group = Adw.PreferencesGroup()
            group.set_title(section_title)
            for title, accel in items:
                row = Adw.ActionRow()
                row.set_title(title)
                row.set_subtitle(accel)
                group.add(row)
            box.append(group)

        self.set_content(scrolled)
