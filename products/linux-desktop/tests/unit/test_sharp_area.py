"""The 3 x 3 sharpness grid: the sharpest area is named, outlined, opened at 100 % and marked among
the frames on screen (plan 2.3b, 2.3c). The headline score and its thresholds are unchanged."""

from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock

import numpy as np
import pytest

from photo_selector_linux.core import scoring
from photo_selector_linux.core.models import QualityScores
from photo_selector_linux.ui import sharp_outline
from photo_selector_linux.ui import window as window_module

REAL = window_module.HAS_GI and not isinstance(window_module.Gtk, MagicMock)


def _subject_in(cell: int, h: int = 600, w: int = 900, seed: int = 1) -> np.ndarray:
    """Flat grey with sparse bright lines (edges, not noise: noise is what the score subtracts)
    filling exactly one cell of the 3 x 3 grid."""
    rng = np.random.default_rng(seed)
    image = np.full((h, w, 3), 120, dtype=np.uint8)
    row, col = divmod(cell, 3)
    ch, cw = h // 3, w // 3
    patch = np.full((ch, cw), 60, dtype=np.uint8)
    patch[rng.choice(ch, 10, replace=False), :] = 230
    patch[:, rng.choice(cw, 14, replace=False)] = 230
    image[row * ch:(row + 1) * ch, col * cw:(col + 1) * cw] = patch[:, :, None]
    return image


@pytest.mark.parametrize("cell", range(9))
def test_the_grid_finds_the_cell_the_detail_is_in(cell):
    scores = scoring.compute_quality_scores(_subject_in(cell))
    assert scores.grid is not None and len(scores.grid) == 9
    assert scores.sharpest_cell == cell
    assert scores.sharpest_area_score == max(scores.grid)


def test_the_headline_score_is_still_the_centre_and_the_old_scores_have_no_grid():
    image = _subject_in(6)  # detail in the lower left: the centre is flat
    scores = scoring.compute_quality_scores(image)
    centre = scoring.evaluate_focus_score(image)["score"]
    assert scores.sharpness == centre
    assert scores.sharpest_area_score > scores.sharpness + 20
    old = QualityScores(sharpness=12.0, noise=1.0, highlight_clipping=0.0, shadow_clipping=0.0)
    assert old.grid is None and old.sharpest_cell is None and old.sharpest_area_score == 12.0
    assert scoring.evaluate_focus_grid(np.zeros((8, 8, 3), dtype=np.uint8)) is None  # too small to divide


def test_a_flat_or_evenly_sharp_photo_points_nowhere_and_a_tie_goes_to_the_first_cell():
    def make(grid):
        return QualityScores(sharpness=1.0, noise=0.0, highlight_clipping=0.0, shadow_clipping=0.0, grid=grid)

    assert make((0.0,) * 9).sharpest_cell is None  # flat: no area stands out
    assert make((70.0,) * 9).sharpest_cell is None  # sharp everywhere: nothing to point at
    assert make((0.0, 60.0, 0.0, 0.0, 0.0, 0.0, 0.0, 60.0, 0.0)).sharpest_cell == 1  # first of a tie
    assert make((0.0,) * 8 + (4.0,)).sharpest_cell is None  # inside the margin


def test_the_card_names_the_area_in_plain_words():
    assert sharp_outline.area_text(6) == "Sharpest area: lower left"
    assert sharp_outline.area_text(4) == "Sharpest area: centre"
    assert sharp_outline.area_text(2) == "Sharpest area: upper right"
    assert sharp_outline.cell_focus(8) == pytest.approx((5 / 6, 5 / 6))


def test_the_outline_follows_the_fitted_photo_not_the_widget():
    # A 3:2 photo in a square widget: 300 x 200 centred, so the lower right cell starts at (200, 183.3).
    x, y, w, h = sharp_outline.outline_rect(300, 300, 1.5, 8)
    assert (x, w) == (pytest.approx(200.0), pytest.approx(100.0))
    assert y == pytest.approx(50 + 2 * 200 / 3) and h == pytest.approx(200 / 3)
    assert sharp_outline.outline_rect(0, 300, 1.5, 0) == (0.0, 0.0, 0.0, 0.0)


def _photo(name, sharpness, grid=None):
    scores = QualityScores(sharpness=sharpness, noise=0.0, highlight_clipping=0.0, shadow_clipping=0.0, grid=grid)
    return SimpleNamespace(id=name, scores=scores)


def test_only_a_clear_winner_among_two_scored_frames_is_marked():
    a, b = _photo("a", 10.0, (5.0,) * 8 + (60.0,)), _photo("b", 30.0)
    assert sharp_outline.sharpest_of([a, b]) is a  # compared by the best area: 60 against 30
    assert sharp_outline.sharpest_of([_photo("c", 30.0), _photo("d", 31.0)]) is None  # inside the margin
    assert sharp_outline.sharpest_of([a]) is None  # one frame is not "the sharpest"
    assert sharp_outline.sharpest_of([a, SimpleNamespace(id="x", scores=None)]) is None


@pytest.fixture
def window(tmp_path):
    from PIL import Image

    import gtk_layout_probe as probe
    from photo_selector_linux.core.settings import SettingsStore
    probe.set_text_scale(1.0)  # an earlier test may have left another scale

    root = tmp_path / "shoot"
    root.mkdir()
    Image.fromarray(_subject_in(6, h=1200, w=1800)).save(root / "DSC_0001.JPG")  # detail lower left
    Image.new("RGB", (1800, 1200), (120, 120, 120)).save(root / "DSC_0002.JPG")  # flat
    settings = SettingsStore(path=tmp_path / "settings.json", auto_load=False)
    settings.view_order = "natural"
    win = window_module.MainWindow(settings=settings)
    win.viewmodel.load_directory(root)
    assert probe.pump(until=lambda: len(win.viewmodel.candidates) == 2 and not win.viewmodel.is_scanning)
    probe.show_at(win, (1366, 768))
    yield win, probe
    win.destroy()
    probe.pump(0.1)


def _scored(win):
    return all(c.scores is not None and c.scores.grid for c in win.viewmodel.candidates)


@pytest.mark.skipif(not REAL, reason="needs real Gtk under a display server")
def test_the_card_the_outline_and_the_100_percent_view_all_point_at_the_sharpest_area(window):
    win, probe = window
    canvas = win.canvas_view
    assert probe.pump(until=lambda: win.viewmodel.current_photo.scores is not None)
    probe.pump(0.2)
    assert win.canvas_view.hud.lbl_sharp_area.get_label() == "Sharpest area: lower left"
    outline = canvas._active_outline
    assert outline.cell == 6 and outline.get_visible()
    x, y, w, h = outline.rect()
    assert w > 0 and h > 0 and x < outline.get_width() / 2 and y > outline.get_height() / 2  # lower left
    canvas.toggle_hud()  # the info card off: no outline either
    assert not outline.get_visible()
    canvas.toggle_hud()
    assert outline.get_visible()
    canvas.toggle_zoom()  # 100 % opens on that area, not the centre
    ranges = lambda: [(v.get_hadjustment(), v.get_vadjustment()) for v in canvas.pan_group._views]  # noqa: E731
    assert probe.pump(until=lambda: bool(ranges()) and ranges()[0][0].get_upper() > ranges()[0][0].get_page_size() + 1)
    h_adj, v_adj = ranges()[0]
    assert h_adj.get_value() == pytest.approx(0.0, abs=2)  # left edge
    assert v_adj.get_value() == pytest.approx(v_adj.get_upper() - v_adj.get_page_size(), abs=2)  # bottom edge


@pytest.mark.skipif(not REAL, reason="needs real Gtk under a display server")
def test_the_sharpest_frame_on_screen_is_marked_in_2_up_and_never_in_1_up(window):
    win, probe = window
    win.viewmodel.set_comparison_mode(2)
    assert probe.pump(until=lambda: _scored(win))
    probe.pump(0.3)
    pills = {pid: p for pid, p in win.canvas_view._sharp_pills.items()}
    first, second = win.viewmodel.candidates
    assert pills[first.id].get_visible() and not pills[second.id].get_visible()
    win.viewmodel.set_comparison_mode(1)
    probe.pump(0.2)
    assert win.canvas_view._sharp_pills == {}


def test_the_score_tooltip_still_names_the_measure_and_now_the_areas():
    from photo_selector_linux.ui.info_hud import SCORE_TOOLTIP

    assert "centre of the frame" in SCORE_TOOLTIP and "nine areas" in SCORE_TOOLTIP
    assert Path  # keep the import used when the Gtk tests are skipped
