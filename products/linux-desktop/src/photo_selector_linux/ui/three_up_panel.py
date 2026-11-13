"""The 3-Up container: the active photo over its neighbours, laid out by the solver (decision #30).

``layout.three_up_plan`` decides the split from the canvas size and the photo's aspect; the layout
manager below only hands each frame the rectangle the plan names, so the view follows the real
canvas at every window size (LIN-8: no size request is ever derived from a fixed viewport). A
missing first or last photo has no frame at all: nothing is drawn and no widget takes space.

The panel is a ``Gtk.Box`` with a custom layout manager, so GTK itself owns the children (appending,
removing, disposing): no Python ``dispose`` override.
"""

from __future__ import annotations

from typing import Callable, List, Optional

try:
    import gi
    gi.require_version("Gtk", "4.0")
    gi.require_version("Graphene", "1.0")
    gi.require_version("Gsk", "4.0")
    from gi.repository import Graphene, Gsk, Gtk
    HAS_GI = True
except (ImportError, ValueError):
    HAS_GI = False
    Gtk = None

from photo_selector_linux.ui.layout import THREE_UP_GAP, ThreeUpPlan, three_up_plan

if HAS_GI:

    class ThreeUpLayout(Gtk.LayoutManager):
        """First child = the active frame, the rest = the neighbours, left to right."""

        __gtype_name__ = "BlitzlensThreeUpLayout"

        def __init__(self) -> None:
            super().__init__()
            self.aspect: float = 1.5
            #: Extra height the active frame needs beyond its own minimum (the info card inside it).
            self.top_floor: Callable[[], int] = lambda: 0
            self.plan: Optional[ThreeUpPlan] = None

        @staticmethod
        def _frames(widget) -> List[Gtk.Widget]:
            out, child = [], widget.get_first_child()
            while child is not None:
                out.append(child)
                child = child.get_next_sibling()
            return out

        def do_measure(self, widget, orientation, for_size):
            frames = self._frames(widget)
            if not frames:
                return 0, 0, -1, -1
            if orientation == Gtk.Orientation.HORIZONTAL:
                top = frames[0].measure(orientation, -1).minimum
                # Two neighbours side by side, each at its own minimum width, with the gap between.
                low = max((f.measure(orientation, -1).minimum for f in frames[1:]), default=0)
                minimum = max(top, 2 * low + THREE_UP_GAP if len(frames) > 1 else 0)
                return minimum, minimum, -1, -1
            # Height: the top frame over the tallest neighbour, each measured at the width it will get.
            width = for_size if for_size > 0 else -1
            frame_width = max(1, (width - THREE_UP_GAP) // 2) if width > 0 else -1
            top = max(frames[0].measure(orientation, width).minimum, self.top_floor())
            low = max((f.measure(orientation, frame_width).minimum for f in frames[1:]), default=0)
            minimum = top + (THREE_UP_GAP + low if len(frames) > 1 else 0)
            return minimum, minimum, -1, -1

        def do_allocate(self, widget, width, height, baseline) -> None:
            frames = self._frames(widget)
            if not frames:
                return
            vertical = Gtk.Orientation.VERTICAL
            frame_width = max(1, (width - THREE_UP_GAP) // 2)
            top_min = max(frames[0].measure(vertical, width).minimum, self.top_floor())
            low_min = max((f.measure(vertical, frame_width).minimum for f in frames[1:]), default=1)
            plan = three_up_plan(
                width, height, self.aspect, len(frames) - 1, min_top=top_min, min_low=low_min
            )
            self.plan = plan
            self._place(frames[0], plan.top.x, plan.top.y, plan.top.width, plan.top.height)
            for child, rect in zip(frames[1:], plan.neighbours):
                self._place(child, rect.x, rect.y, rect.width, rect.height)

        @staticmethod
        def _place(child, x: int, y: int, width: int, height: int) -> None:
            point = Graphene.Point()
            point.init(x, y)
            child.allocate(max(1, width), max(1, height), -1, Gsk.Transform().translate(point))

    class ThreeUpPanel(Gtk.Box):
        """Frames in one-over-two; ``set_frames(top, neighbours)`` replaces them."""

        __gtype_name__ = "BlitzlensThreeUpPanel"

        def __init__(self) -> None:
            super().__init__(orientation=Gtk.Orientation.VERTICAL)
            self.set_hexpand(True)
            self.set_vexpand(True)
            self._layout = ThreeUpLayout()
            self.set_layout_manager(self._layout)

        @property
        def plan(self) -> Optional[ThreeUpPlan]:
            return self._layout.plan

        @property
        def top_floor(self) -> Callable[[], int]:
            return self._layout.top_floor

        @top_floor.setter
        def top_floor(self, value: Callable[[], int]) -> None:
            self._layout.top_floor = value

        def set_frames(self, top: Gtk.Widget, neighbours: List[Gtk.Widget]) -> None:
            while child := self.get_first_child():
                self.remove(child)
            self.append(top)
            for frame in neighbours:
                self.append(frame)
            self.queue_resize()

        def set_aspect(self, aspect: float) -> None:
            """The active photo's width / height, once its picture is known: the split follows it."""
            if aspect > 0 and abs(aspect - self._layout.aspect) > 0.001:
                self._layout.aspect = float(aspect)
                self.queue_allocate()
