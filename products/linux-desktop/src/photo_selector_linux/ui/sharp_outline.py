"""Where the sharpest area is (plan 2.3b): the name of the cell and an outline over the photo.

The score still measures the centre of the frame (the headline number and the thresholds are
unchanged); the 3x3 grid beside it says which of nine areas is the sharpest, so a photo whose
subject sits in the lower third is not judged by its blurry background alone.
"""

from __future__ import annotations

from typing import Optional, Sequence, Tuple

try:
    import gi
    gi.require_version("Gtk", "4.0")
    gi.require_version("Graphene", "1.0")
    gi.require_version("Gsk", "4.0")
    from gi.repository import Gdk, Graphene, Gsk, Gtk
    HAS_GI = True
except (ImportError, ValueError):
    HAS_GI = False
    Gtk = None

GRID = 3
AREA_NAMES = (
    "top left", "top", "top right",
    "left", "centre", "right",
    "bottom left", "bottom", "bottom right",
)
#: A frame is marked "Sharpest" only when it beats every other frame on screen by this many points.
SHARPEST_MARGIN = 3.0
OUTLINE_COLOUR = "#2EC27E"
OUTLINE_WIDTH = 2.0


def area_name(cell: int) -> str:
    return AREA_NAMES[cell]


def area_text(cell: int) -> str:
    """The info card's line: ``Sharpest area: lower left`` ("lower" reads better than "bottom")."""
    return f"Sharpest area: {AREA_NAMES[cell].replace('bottom', 'lower').replace('top', 'upper')}"


def cell_focus(cell: int) -> Tuple[float, float]:
    """Centre of ``cell`` as a fraction of the photo's width and height."""
    row, col = divmod(cell, GRID)
    return (col + 0.5) / GRID, (row + 0.5) / GRID


def outline_rect(width: float, height: float, aspect: float, cell: int) -> Tuple[float, float, float, float]:
    """``(x, y, w, h)`` of ``cell`` over a photo of ``aspect`` fitted and centred in ``width`` x ``height``."""
    if width <= 0 or height <= 0 or aspect <= 0:
        return 0.0, 0.0, 0.0, 0.0
    if width / height >= aspect:
        shown_h, shown_w = float(height), height * aspect
    else:
        shown_w, shown_h = float(width), width / aspect
    left, top = (width - shown_w) / 2.0, (height - shown_h) / 2.0
    row, col = divmod(cell, GRID)
    return left + col * shown_w / GRID, top + row * shown_h / GRID, shown_w / GRID, shown_h / GRID


def sharpest_of(photos: Sequence) -> Optional[object]:
    """The photo whose sharpest area beats every other photo's by ``SHARPEST_MARGIN``, or None.

    Compared by the best of the nine areas (the headline number when a photo has no grid yet).
    Needs two scored photos: one frame alone is not "the sharpest".
    """
    scored = [(p, p.scores.sharpest_area_score) for p in photos if p.scores is not None]
    if len(scored) < 2:
        return None
    scored.sort(key=lambda item: item[1], reverse=True)
    return scored[0][0] if scored[0][1] - scored[1][1] >= SHARPEST_MARGIN else None


if HAS_GI:

    class SharpAreaOutline(Gtk.Widget):
        """A transparent overlay that draws the outline of one cell of the photo beneath it."""

        __gtype_name__ = "BlitzlensSharpAreaOutline"

        def __init__(self) -> None:
            super().__init__()
            self._cell: Optional[int] = None
            self._aspect: float = 1.5
            self.set_can_target(False)
            self.set_name("sharp_area_outline")
            self.set_hexpand(True)
            self.set_vexpand(True)
            self.set_visible(False)

        def set_area(self, cell: Optional[int], aspect: Optional[float] = None) -> None:
            self._cell = cell
            if aspect:
                self._aspect = float(aspect)
            self.queue_draw()

        @property
        def cell(self) -> Optional[int]:
            return self._cell

        def rect(self) -> Tuple[float, float, float, float]:
            if self._cell is None:
                return 0.0, 0.0, 0.0, 0.0
            return outline_rect(self.get_width(), self.get_height(), self._aspect, self._cell)

        def do_snapshot(self, snapshot) -> None:
            if self._cell is None:
                return
            x, y, w, h = self.rect()
            if w <= 0 or h <= 0:
                return
            area = Graphene.Rect()
            area.init(x, y, w, h)
            box = Gsk.RoundedRect()
            box.init_from_rect(area, 0.0)
            colour = Gdk.RGBA()
            colour.parse(OUTLINE_COLOUR)
            snapshot.append_border(box, [OUTLINE_WIDTH] * 4, [colour] * 4)
