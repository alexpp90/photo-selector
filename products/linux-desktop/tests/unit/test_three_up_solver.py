"""3-Up is one over two with the split chosen by a solver (decision #30, R-LINUX-UI-03, R-LINUX-UI-06).

What is measured, and where: the **picture share** is the sum of the fitted picture areas of the photos
on screen divided by the canvas area. The pure solver is checked at 1920x1080 and 1280x720 for 3:2 and
2:3 photos; the real widgets are checked for the same quantity in a shown window. The **canvas share**
is the canvas area divided by the window area (header bar and action bar are what is left).
"""

from pathlib import Path
from unittest.mock import MagicMock

import pytest

from photo_selector_linux.ui import window as window_module
from photo_selector_linux.ui.layout import (
    FRAME_CHROME,
    NEIGHBOUR_MAX_SHARE,
    THREE_UP_GAP,
    Rect,
    fit_aspect_rect,
    three_up_plan,
)

REAL = window_module.HAS_GI and not isinstance(window_module.Gtk, MagicMock)
CANVASES = [(1920, 1080), (1280, 720)]
ASPECTS = [3.0 / 2.0, 2.0 / 3.0]


def _area(rect: Rect) -> int:
    return rect.width * rect.height


def _sixty_forty_share(w: int, h: int, aspect: float) -> float:
    """The picture share of the layout this replaces: a 60/40 split with the same chrome."""
    gap = THREE_UP_GAP
    top_h = int(h * 0.6)
    frames = [Rect(0, 0, w, top_h), Rect(0, top_h + gap, (w - gap) // 2, h - top_h - gap),
              Rect((w + gap) // 2, top_h + gap, (w - gap) // 2, h - top_h - gap)]
    total = 0
    for frame in frames:
        inner = Rect(frame.x, frame.y + FRAME_CHROME // 2, frame.width, frame.height - FRAME_CHROME)
        total += _area(fit_aspect_rect(inner, aspect))
    return total / (w * h)


@pytest.mark.parametrize("aspect", ASPECTS)
@pytest.mark.parametrize("w,h", CANVASES)
def test_three_photos_cover_more_of_the_canvas_than_the_60_40_layout(w, h, aspect):
    plan = three_up_plan(w, h, aspect, 2)
    share = plan.picture_area / (w * h)
    assert share >= _sixty_forty_share(w, h, aspect)
    assert share >= (0.38 if aspect > 1 else 0.17)  # what the solver reaches today; falls if it regresses


@pytest.mark.parametrize("aspect", ASPECTS)
@pytest.mark.parametrize("w,h", CANVASES)
@pytest.mark.parametrize("neighbours", [2, 1, 0])
def test_frames_are_disjoint_inside_the_canvas_with_gaps_of_at_most_eight_pixels(w, h, aspect, neighbours):
    plan = three_up_plan(w, h, aspect, neighbours)
    frames = [plan.top, *plan.neighbours]
    assert len(plan.neighbours) == neighbours and len(plan.neighbour_pictures) == neighbours
    for frame in frames:
        assert frame.width >= 1 and frame.height >= 1  # no zero-size widget takes space
        assert 0 <= frame.x and frame.right <= w and 0 <= frame.y and frame.bottom <= h
    for i, a in enumerate(frames):
        for b in frames[i + 1:]:
            assert not a.intersects(b)
    for neighbour in plan.neighbours:
        assert 0 <= neighbour.y - plan.top.bottom <= THREE_UP_GAP
    if neighbours == 2:
        assert 0 <= plan.neighbours[1].x - plan.neighbours[0].right <= THREE_UP_GAP + 1  # odd widths
    for picture, frame in zip([plan.top_picture, *plan.neighbour_pictures], frames):
        assert frame.x <= picture.x and picture.right <= frame.right
        assert frame.y <= picture.y and picture.bottom <= frame.bottom


@pytest.mark.parametrize("aspect", ASPECTS)
@pytest.mark.parametrize("w,h", CANVASES)
def test_the_active_photo_stays_the_biggest_and_the_neighbours_are_equal(w, h, aspect):
    plan = three_up_plan(w, h, aspect, 2)
    left, right = plan.neighbour_pictures
    assert _area(left) == _area(right)
    assert _area(left) <= NEIGHBOUR_MAX_SHARE * _area(plan.top_picture) + 1
    assert _area(left) >= 0.25 * _area(plan.top_picture) - 1


@pytest.mark.parametrize("aspect", ASPECTS)
@pytest.mark.parametrize("w,h", CANVASES)
def test_a_missing_neighbour_leaves_no_photo_smaller_and_none_at_all_gives_the_canvas_to_the_active_photo(w, h, aspect):
    three = three_up_plan(w, h, aspect, 2)
    one = three_up_plan(w, h, aspect, 1)
    none = three_up_plan(w, h, aspect, 0)
    assert _area(one.top_picture) >= _area(three.top_picture)
    assert _area(one.neighbour_pictures[0]) >= _area(three.neighbour_pictures[0])
    # Built, said in R-LINUX-UI-03: equal frames gain nothing for a lone neighbour, so it keeps the
    # three-card frame size and sits centred.
    assert one.neighbours[0].width == three.neighbours[0].width
    assert abs(one.neighbours[0].center_x - w / 2) <= 1
    assert _area(none.top_picture) > _area(three.top_picture) and none.top == Rect(0, 0, w, h)


def _folder(tmp_path: Path, count: int = 3) -> Path:
    from PIL import Image

    root = tmp_path / "shoot"
    root.mkdir()
    for number in range(count):
        Image.new("RGB", (900, 600), (60 + number * 40, 90, 120)).save(root / f"DSC_{number:04d}.JPG")
    return root


@pytest.fixture
def three_up(tmp_path):
    import gtk_layout_probe as probe
    from photo_selector_linux.core.settings import SettingsStore
    probe.set_text_scale(1.0)  # an earlier test may have left another scale

    settings = SettingsStore(path=tmp_path / "settings.json", auto_load=False)
    settings.view_order = "natural"
    win = window_module.MainWindow(settings=settings)
    win.viewmodel.load_directory(_folder(tmp_path))
    assert probe.pump(until=lambda: len(win.viewmodel.candidates) == 3 and not win.viewmodel.is_scanning)
    win.viewmodel.set_comparison_mode(3)
    yield win, probe
    win.destroy()
    probe.pump(0.1)


def _named(widget, name):
    import gtk_layout_probe as probe

    return [w for w in probe.walk(widget) if w.get_name() == name]


def _picture_rects(win):
    """Fitted picture rect of each photo on screen, in canvas coordinates (from the real allocations)."""
    from gi.repository import Graphene

    rects = {}
    canvas = win.canvas_view
    for slot in ("slot_previous", "slot_current", "slot_next"):
        found = _named(canvas.slots_box, f"{slot}_image")
        if not found:
            continue
        picture = found[0]
        paintable = picture.get_paintable()
        assert paintable is not None and paintable.get_intrinsic_height() > 0, "texture not loaded yet"
        ok, bounds = picture.compute_bounds(canvas)
        assert ok
        box = Rect(round(bounds.get_x()), round(bounds.get_y()), round(bounds.get_width()), round(bounds.get_height()))
        rects[slot] = fit_aspect_rect(box, paintable.get_intrinsic_aspect_ratio())
    assert Graphene  # imported for compute_bounds
    return rects


@pytest.mark.skipif(not REAL, reason="needs real Gtk under a display server")
@pytest.mark.parametrize("w,h", [(1366, 768), (1920, 1080)])
def test_the_real_frames_sit_where_the_solver_puts_them_and_cover_what_it_promises(three_up, w, h):
    win, probe = three_up
    probe.show_at(win, (w, h))
    win.viewmodel.nav_next()
    canvas = win.canvas_view

    def loaded() -> bool:
        pictures = [_named(canvas.slots_box, f"{s}_image") for s in ("slot_previous", "slot_current", "slot_next")]
        return all(p and p[0].get_paintable() is not None for p in pictures)

    assert probe.pump(until=loaded)
    probe.pump(0.3)
    panel = canvas.three_up_panel
    cw, ch = panel.get_width(), panel.get_height()
    plan = three_up_plan(cw, ch, 1.5, 2)
    assert panel.plan == plan
    frames = {c.get_name(): c for c in [panel.get_first_child(), panel.get_first_child().get_next_sibling(),
                                        panel.get_first_child().get_next_sibling().get_next_sibling()]}
    for name, expected in (("slot_current", plan.top), ("slot_previous", plan.neighbours[0]),
                           ("slot_next", plan.neighbours[1])):
        ok, bounds = frames[name].compute_bounds(panel)
        assert ok and (round(bounds.get_x()), round(bounds.get_y())) == (expected.x, expected.y)
        # Same size up to the frame's own CSS (border and padding of the studio theme, when another
        # test left it loaded): never larger than the plan, never more than 24 px smaller.
        assert 0 <= expected.width - frames[name].get_width() <= 24
        assert 0 <= expected.height - frames[name].get_height() <= 24
        # The chrome the solver budgets is at least what the real frame spends beside its picture.
        picture = _named(frames[name], f"{name}_image")[0]
        assert frames[name].get_height() - picture.get_height() <= FRAME_CHROME
    pictures = _picture_rects(win)
    share = sum(_area(r) for r in pictures.values()) / (cw * ch)
    assert share >= _sixty_forty_share(cw, ch, 1.5) - 0.01  # the real pictures cover what the plan says
    rects = list(pictures.values())
    for i, a in enumerate(rects):
        for b in rects[i + 1:]:
            assert not a.intersects(b)


@pytest.mark.skipif(not REAL, reason="needs real Gtk under a display server")
def test_a_missing_first_or_last_photo_draws_nothing_and_its_widget_does_not_exist(three_up):
    win, probe = three_up
    probe.show_at(win, (1366, 768))
    canvas = win.canvas_view
    probe.pump(0.2)
    assert [c.get_name() for c in _children(canvas.three_up_panel)] == ["slot_current", "slot_next"]
    assert not _named(canvas.slots_box, "slot_previous")
    win.viewmodel.nav_next()
    win.viewmodel.nav_next()
    probe.pump(0.2)
    assert [c.get_name() for c in _children(canvas.three_up_panel)] == ["slot_current", "slot_previous"]
    assert canvas.three_up_panel.plan.top.height > 0
    assert not any("boundary" in c.get_css_classes() for c in _children(canvas.three_up_panel))


def _children(widget):
    out, child = [], widget.get_first_child()
    while child is not None:
        out.append(child)
        child = child.get_next_sibling()
    return out


@pytest.mark.skipif(not REAL, reason="needs real Gtk under a display server")
@pytest.mark.parametrize("w,h", [(1366, 768), (1920, 1080)])
def test_the_canvas_takes_at_least_85_percent_of_the_window(three_up, w, h):
    win, probe = three_up
    probe.show_at(win, (w, h))
    canvas = win.canvas_view
    probe.pump(0.2)
    share = (canvas.get_width() * canvas.get_height()) / (win.get_width() * win.get_height())
    assert share >= 0.85, f"{canvas.get_width()}x{canvas.get_height()} in {win.get_width()}x{win.get_height()}"
