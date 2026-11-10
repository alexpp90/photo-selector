"""Layout probe for real GTK4 widgets: what is clipped, broken, overlapping or outside the window.

Test helper of Blitzlens only (not a test module). Everything here needs a real display (Xvfb);
callers skip when ``conftest.py`` stubbed Gtk.

The probe reads the *allocated* geometry after GTK laid the window out, so it sees what the user
sees: a label given less width than its text needs, a word the wrap broke in the middle, two
buttons on top of each other, a widget sticking out of the window.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Iterator, List, Optional, Tuple

from gi.repository import GLib, Gtk

#: Pixels of slack for rounding between allocation and measure.
TOLERANCE = 2

#: Windows are shown at these sizes (besides the minimum GTK computes for the window).
SIZES = ((1366, 768), (1920, 1080))
SCALES = (1.0, 1.5, 2.0)


@dataclass(frozen=True)
class Finding:
    kind: str
    widget: str
    detail: str

    def __str__(self) -> str:
        return f"{self.kind}: {self.widget} {self.detail}"


def pump(seconds: float = 0.0, until=None, budget: float = 3.0) -> bool:
    """Run the main loop. With ``until``: poll it for up to ``budget`` seconds, return its truth.

    Without: run for ``seconds`` (layout and animations need loop iterations, not sleeps on the
    calling thread).
    """
    context = GLib.MainContext.default()
    end = time.monotonic() + (budget if until is not None else seconds)
    while time.monotonic() < end:
        while context.iteration(False):
            pass
        if until is not None and until():
            return True
        time.sleep(0.005)
    return bool(until()) if until is not None else True


def set_text_scale(scale: float) -> None:
    """GNOME's text-scaling factor reaches GTK as the resolution of the font (``gtk-xft-dpi``)."""
    settings = Gtk.Settings.get_default()
    settings.set_property("gtk-xft-dpi", int(96 * 1024 * scale))
    pump(0.15)


def current_text_scale() -> float:
    return Gtk.Settings.get_default().get_property("gtk-xft-dpi") / (96 * 1024)


def minimum_size(window: Gtk.Window) -> Tuple[int, int]:
    """The smallest size the window can be laid out at (what GTK computes from its children)."""
    horizontal = window.measure(Gtk.Orientation.HORIZONTAL, -1).minimum
    vertical = window.measure(Gtk.Orientation.VERTICAL, -1).minimum
    return horizontal, vertical


def show_at(window: Gtk.Window, size: Optional[Tuple[int, int]]) -> None:
    """Present a not yet shown window at ``size`` (None: as small as it can be) and let it settle.

    A mapped window does not follow ``set_default_size`` without a window manager, and a default
    size is clamped to the screen (Xvfb is 1280x1024), so every size is a fresh window and a size
    request makes it that large.
    """
    window.set_default_size(1, 1)
    if size is not None:
        window.set_size_request(*size)
    window.present()
    pump(until=lambda: window.get_mapped() and window.get_width() > 0 and window.get_height() > 0)
    pump(0.3)


def walk(widget: Gtk.Widget) -> Iterator[Gtk.Widget]:
    yield widget
    child = widget.get_first_child()
    while child is not None:
        yield from walk(child)
        child = child.get_next_sibling()


def describe(widget: Gtk.Widget) -> str:
    name = widget.get_name()
    kind = type(widget).__name__
    text = ""
    if isinstance(widget, Gtk.Label):
        text = f" {widget.get_text()[:40]!r}"
    elif isinstance(widget, Gtk.Button) and widget.get_label():
        text = f" {widget.get_label()!r}"
    ident = f"{kind}#{name}" if name and name != kind else kind
    return ident + text


def _visible(widget: Gtk.Widget) -> bool:
    return widget.get_mapped() and widget.get_width() > 0 and widget.get_height() > 0


def _in_scroller(widget: Gtk.Widget) -> bool:
    parent = widget.get_parent()
    while parent is not None:
        if isinstance(parent, Gtk.ScrolledWindow):
            return True
        parent = parent.get_parent()
    return False


def own_size(widget: Gtk.Widget) -> Tuple[int, int]:
    """Allocated size including the CSS padding (``get_width`` is the content box only)."""
    ok, rect = widget.compute_bounds(widget)
    return (round(rect.get_width()), round(rect.get_height())) if ok else (0, 0)


def bounds(widget: Gtk.Widget, root: Gtk.Widget) -> Optional[Tuple[float, float, float, float]]:
    """x, y, width, height of ``widget`` in ``root`` coordinates (None: not related)."""
    ok, rect = widget.compute_bounds(root)
    if not ok:
        return None
    return rect.get_x(), rect.get_y(), rect.get_width(), rect.get_height()


def visible_bounds(widget: Gtk.Widget, root: Gtk.Widget):
    """``bounds`` clipped to every scroller and every overflow-hidden parent: what can be seen."""
    box = bounds(widget, root)
    parent = widget.get_parent()
    while box is not None and parent is not None:
        if isinstance(parent, Gtk.ScrolledWindow) or (
            parent is not root and parent.get_overflow() == Gtk.Overflow.HIDDEN
        ):
            port = bounds(parent, root)
            width, height = intersection(box, port) if port else (0, 0)
            if width <= 0 or height <= 0:
                return None
            left, top = max(box[0], port[0]), max(box[1], port[1])
            box = (left, top, width, height)
        parent = parent.get_parent()
    return box


def intersection(a, b) -> Tuple[float, float]:
    """Width and height of the overlap of two boxes (0 when they do not meet)."""
    width = min(a[0] + a[2], b[0] + b[2]) - max(a[0], b[0])
    height = min(a[1] + a[3], b[1] + b[3]) - max(a[1], b[1])
    return max(0.0, width), max(0.0, height)


#: Linked buttons share a border; two controls on top of each other overlap by more than this.
OVERLAP_PX = 4


# ── label checks ─────────────────────────────────────────────────────────────


def label_findings(label: Gtk.Label) -> List[Finding]:
    """Clipped text, a word broken in the middle, text taller than its allocation."""
    found: List[Finding] = []
    who = describe(label)
    text = label.get_text()
    if not text.strip():
        return found
    ellipsizes = label.get_ellipsize() != 0  # Pango.EllipsizeMode.NONE
    wraps = label.get_wrap()
    width, height = own_size(label)

    if not wraps and not ellipsizes:
        # The text's own width against the content box it was given. A width cap (max-width-chars,
        # a size request, a parent that hands out less) cuts it at the edge without a trace.
        text_width = label.get_layout().get_pixel_size().width
        if label.get_width() + TOLERANCE < text_width:
            found.append(Finding("clipped", who, f"allocated {label.get_width()}px, its text needs {text_width}px"))

    if wraps:
        needed = (
            label.measure(Gtk.Orientation.VERTICAL, width + label.get_margin_start() + label.get_margin_end()).minimum
            - label.get_margin_top()
            - label.get_margin_bottom()
        )
        if height + TOLERANCE < needed:
            found.append(Finding("clipped", who, f"allocated {height}px high, its text needs {needed}px"))
        broken = broken_words(label)
        if broken:
            found.append(Finding("broken-word", who, f"split across lines: {broken}"))
    return found


def broken_words(label: Gtk.Label) -> List[str]:
    """Words the layout split across two lines (a line break inside a word, not at a space)."""
    layout = label.get_layout()
    raw = layout.get_text().encode("utf-8")
    pieces = []
    iterator = layout.get_iter()
    previous_end = None
    while True:
        line = iterator.get_line_readonly()
        start, length = line.get_start_index(), line.get_length()
        if previous_end is not None and previous_end == start and start < len(raw):
            before = raw[start - 1:start]
            after = raw[start:start + 1]
            if before not in (b" ", b"\n", b"-", b"/") and after not in (b" ", b"\n"):
                left = raw[:start].decode("utf-8", "ignore").split(" ")[-1]
                right = raw[start:].decode("utf-8", "ignore").split(" ")[0]
                pieces.append(f"{left}|{right}")
        previous_end = start + length
        if not iterator.next_line():
            break
    return pieces


# ── window-wide checks ───────────────────────────────────────────────────────

INTERACTIVE = (Gtk.Button, Gtk.Switch, Gtk.CheckButton, Gtk.Scale, Gtk.Entry, Gtk.SpinButton)


def interactive_widgets(window: Gtk.Widget) -> List[Gtk.Widget]:
    # A MenuButton wraps a ToggleButton: keep the outer widget only.
    found = [w for w in walk(window) if isinstance(w, INTERACTIVE) and _visible(w) and w.get_sensitive()]
    return [w for w in found if not any(o is not w and w.is_ancestor(o) for o in found)]


def layout_findings(window: Gtk.Widget, root: Optional[Gtk.Widget] = None) -> List[Finding]:
    """Every finding of one laid-out window: clipped/broken labels, overlapping controls, escapes."""
    root = root or window
    findings: List[Finding] = []
    root_width, root_height = root.get_width(), root.get_height()

    for widget in walk(window):
        if not _visible(widget):
            continue
        if isinstance(widget, Gtk.Label):
            findings.extend(label_findings(widget))
        if _in_scroller(widget) or isinstance(widget, (Gtk.Popover, Gtk.Window)):
            continue
        box = bounds(widget, root)
        if box is None:
            continue
        x, y, w, h = box
        if x < -TOLERANCE or y < -TOLERANCE or x + w > root_width + TOLERANCE or y + h > root_height + TOLERANCE:
            if isinstance(widget, (Gtk.Label, Gtk.Button, Gtk.Switch, Gtk.Scale, Gtk.Entry)):
                findings.append(
                    Finding(
                        "outside-window",
                        describe(widget),
                        f"at {x:.0f},{y:.0f} {w:.0f}x{h:.0f} in a {root_width}x{root_height} window",
                    )
                )

    controls = interactive_widgets(window)
    boxes = [(c, visible_bounds(c, root)) for c in controls]
    for index, (first, a) in enumerate(boxes):
        for second, b in boxes[index + 1:]:
            if a is None or b is None or first.is_ancestor(second) or second.is_ancestor(first):
                continue
            overlap_w, overlap_h = intersection(a, b)
            if overlap_w > OVERLAP_PX and overlap_h > OVERLAP_PX:
                findings.append(Finding("overlap", describe(first), f"covers {describe(second)}"))
    return findings
