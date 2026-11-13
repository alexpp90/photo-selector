"""Panning the 100 % view: Shift+arrows, linked across the panes of 2-Up and 3-Up (plan 2.1).

The 100 % view is one ``Gtk.ScrolledWindow`` per photo on screen. Their shared position is a
normalised pan ``(x, y)`` in 0..1 of each window's scroll range, so frames of one burst show the
same spot. The model is pure arithmetic (``pan_value``, ``value_for_pan``, ``stepped_value``);
``PanGroup`` wires it to the real adjustments. A key press moves the view by 20 % of the viewport.
"""

from __future__ import annotations

from typing import Any, List, Optional, Tuple

#: One key press moves the view by this share of the viewport.
PAN_STEP = 0.2
CENTRE: Tuple[float, float] = (0.5, 0.5)


def clamp01(value: float) -> float:
    return max(0.0, min(1.0, float(value)))


def pan_value(value: float, lower: float, upper: float, page: float) -> float:
    """Where ``value`` sits in the scroll range, 0..1 (0.5 when there is nothing to scroll)."""
    span = upper - lower - page
    return 0.5 if span <= 0 else clamp01((value - lower) / span)


def value_for_pan(pan: float, lower: float, upper: float, page: float) -> float:
    span = max(0.0, upper - lower - page)
    return lower + clamp01(pan) * span


def stepped_value(value: float, direction: int, lower: float, upper: float, page: float) -> float:
    """``value`` moved by 20 % of the viewport in ``direction`` (-1, 0, 1), kept inside the range."""
    top = max(lower, upper - page)
    return max(lower, min(top, value + direction * PAN_STEP * page))


def focus_value(fraction: float, lower: float, upper: float, page: float) -> float:
    """The scroll value that centres the viewport on ``fraction`` (0..1) of the content."""
    top = max(lower, upper - page)
    return max(lower, min(top, lower + clamp01(fraction) * (upper - lower) - page / 2.0))


class PanGroup:
    """The scrolled windows of the 100 % panes on screen, kept at one linked position."""

    def __init__(self) -> None:
        self.pan: Tuple[float, float] = CENTRE
        #: Where the active photo's sharpest area is (0..1 of its width and height); the first
        #: layout of the active pane opens on it instead of the centre.
        self.focus: Optional[Tuple[float, float]] = None
        self._views: List[Any] = []
        self._applying = False

    def reset(self) -> None:
        self.pan = CENTRE
        self.focus = None

    def clear(self) -> None:
        self._views = []

    def add(self, scrolled: Any, is_active: bool = False) -> None:
        """Take a pane in: it follows the shared pan once its content has a size, and moves it when scrolled."""
        self._views.append(scrolled)
        hadj, vadj = scrolled.get_hadjustment(), scrolled.get_vadjustment()
        for adj in (hadj, vadj):
            adj.connect("changed", lambda _a, v=scrolled, act=is_active: self._on_range(v, act))
            adj.connect("value-changed", lambda _a, v=scrolled: self._on_scrolled(v))
        self._on_range(scrolled, is_active)

    def _apply_to(self, view: Any) -> None:
        for adj, pan in ((view.get_hadjustment(), self.pan[0]), (view.get_vadjustment(), self.pan[1])):
            target = value_for_pan(pan, adj.get_lower(), adj.get_upper(), adj.get_page_size())
            if abs(adj.get_value() - target) > 0.5:
                adj.set_value(target)

    def _on_range(self, view: Any, is_active: bool) -> None:
        """Content size known or changed: the active pane first opens on the focus, then all follow."""
        if self._applying:
            return
        h, v = view.get_hadjustment(), view.get_vadjustment()
        if is_active and self.focus is not None and h.get_upper() > h.get_page_size() + 1:
            fx, fy = self.focus
            self.focus = None
            self._applying = True
            try:
                h.set_value(focus_value(fx, h.get_lower(), h.get_upper(), h.get_page_size()))
                v.set_value(focus_value(fy, v.get_lower(), v.get_upper(), v.get_page_size()))
            finally:
                self._applying = False
            self._take_from(view)
            return
        self._applying = True
        try:
            self._apply_to(view)
        finally:
            self._applying = False

    def _on_scrolled(self, view: Any) -> None:
        """The wheel or a drag moved one pane: the others follow."""
        if not self._applying:
            self._take_from(view)

    def _take_from(self, view: Any) -> None:
        h, v = view.get_hadjustment(), view.get_vadjustment()
        self.pan = (
            pan_value(h.get_value(), h.get_lower(), h.get_upper(), h.get_page_size()),
            pan_value(v.get_value(), v.get_lower(), v.get_upper(), v.get_page_size()),
        )
        self._applying = True
        try:
            for other in self._views:
                if other is not view:
                    self._apply_to(other)
        finally:
            self._applying = False

    def set_pan(self, x: float, y: float) -> None:
        self.pan = (clamp01(x), clamp01(y))
        self._applying = True
        try:
            for view in self._views:
                self._apply_to(view)
        finally:
            self._applying = False

    def step(self, dx: int, dy: int) -> bool:
        """Move the active view by 20 % of its viewport and the others with it; False without a view."""
        if not self._views:
            return False
        lead = self._views[0]
        h, v = lead.get_hadjustment(), lead.get_vadjustment()
        for adj, direction in ((h, dx), (v, dy)):
            if direction:
                adj.set_value(
                    stepped_value(adj.get_value(), direction, adj.get_lower(), adj.get_upper(), adj.get_page_size())
                )
        self._take_from(lead)
        return True
