"""Boundary slot placeholder indicator for 3-Up Focus Mode.

Adheres to:
- R-LINUX-UI-07: BoundarySlotPane Indicators
"""

from __future__ import annotations

try:
    import gi
    gi.require_version("Gtk", "4.0")
    from gi.repository import Gtk
    HAS_GI = True
except (ImportError, ValueError):
    HAS_GI = False
    Gtk = None


class BoundarySlotPane(Gtk.Box if HAS_GI else object):
    """Placeholder card rendered in 3-Up Focus Mode outer slots at folder boundaries."""

    def __init__(self, slot_type: str = "first"):
        """Initialize boundary card.

        Args:
            slot_type: 'first' for folder start, 'last' for folder end.
        """
        self.slot_type = slot_type
        if HAS_GI:
            super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=8)
            self.set_valign(Gtk.Align.CENTER)
            self.set_halign(Gtk.Align.CENTER)
            self.add_css_class("boundary-slot-pane")
            self.add_css_class("card-pane")
            self.set_size_request(240, 180)
            self._build_content(slot_type)

    def _build_content(self, slot_type: str) -> None:
        if not HAS_GI:
            return

        if slot_type == "first":
            icon_name = "go-first-symbolic"
            title = "First Photo"
            subtitle = "You are at the start of the folder"
        else:
            icon_name = "go-last-symbolic"
            title = "Last Photo"
            subtitle = "All photos reviewed"

        icon = Gtk.Image.new_from_icon_name(icon_name)
        icon.set_pixel_size(48)
        icon.add_css_class("dim-label")
        self.append(icon)

        lbl_title = Gtk.Label(label=title)
        lbl_title.add_css_class("boundary-slot-title")
        self.append(lbl_title)

        lbl_sub = Gtk.Label(label=subtitle)
        lbl_sub.add_css_class("boundary-slot-subtitle")
        self.append(lbl_sub)
