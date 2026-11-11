"""Go to photo: a small dialog taking a position or a file name (G).

Adheres to:
- R-LINUX-NAV-01: Go to photo by number or name
"""

from __future__ import annotations

from typing import Callable, Optional

try:
    import gi
    gi.require_version("Gtk", "4.0")
    gi.require_version("Adw", "1")
    from gi.repository import Gtk, Adw
    HAS_GI = True
except (ImportError, ValueError):
    HAS_GI = False
    Gtk = Adw = None

TITLE = "Go to photo"
PLACEHOLDER = "Number or name"
NO_MATCH = "No match"
PREVIEW_HINT = "Position in the order, or part of a file name"


def preview_text(text: str, find: Callable[[str], Optional[int]], name_at: Callable[[int], str]) -> tuple[str, bool]:
    """``(line under the field, is_error)`` for what is typed so far."""
    if not text.strip():
        return PREVIEW_HINT, False
    index = find(text)
    if index is None:
        return NO_MATCH, True
    return f"{index + 1}: {name_at(index)}", False


class GoToPhotoDialog(Adw.Dialog if HAS_GI else object):
    """Enter goes to the match, Escape cancels; the line under the field names the target."""

    def __init__(self, viewmodel):
        self.viewmodel = viewmodel
        self.preview = PREVIEW_HINT
        self.has_error = False
        if not HAS_GI:
            return
        super().__init__()
        self.set_title(TITLE)
        self.set_content_width(360)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        for margin in ("top", "bottom", "start", "end"):
            getattr(box, f"set_margin_{margin}")(16)
        self.entry = Gtk.Entry()
        self.entry.set_name("goto_entry")
        self.entry.set_placeholder_text(PLACEHOLDER)
        self.entry.update_property([Gtk.AccessibleProperty.LABEL], [TITLE])
        self.entry.set_activates_default(False)
        self.entry.connect("changed", self._on_changed)
        self.entry.connect("activate", self._on_activate)
        box.append(self.entry)
        self.lbl_preview = Gtk.Label(label=PREVIEW_HINT)
        self.lbl_preview.set_name("goto_preview")
        self.lbl_preview.set_xalign(0.0)
        self.lbl_preview.set_ellipsize(3)  # middle: a long file name keeps its start and its extension
        box.append(self.lbl_preview)
        toolbar = Adw.ToolbarView()
        toolbar.add_top_bar(Adw.HeaderBar())
        toolbar.set_content(box)
        self.set_child(toolbar)
        self.set_focus(self.entry)

    def update_preview(self, text: str) -> None:
        self.preview, self.has_error = preview_text(
            text,
            self.viewmodel.find_photo,
            lambda i: self.viewmodel.candidates[i].filename,
        )
        if HAS_GI:
            self.lbl_preview.set_label(self.preview)
            self.lbl_preview.set_tooltip_text(self.preview)
            if self.has_error:
                self.lbl_preview.add_css_class("error")
            else:
                self.lbl_preview.remove_css_class("error")

    def go(self, text: str) -> bool:
        """Go to the match and close; "No match" stays on screen for a miss."""
        if self.viewmodel.go_to_photo(text):
            if HAS_GI:
                self.close()
            return True
        self.update_preview(text)
        return False

    def _on_changed(self, entry) -> None:
        self.update_preview(entry.get_text())

    def _on_activate(self, entry) -> None:
        self.go(entry.get_text())
