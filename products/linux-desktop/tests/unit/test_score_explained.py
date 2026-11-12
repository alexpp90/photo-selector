"""The score says what it is (plan 2.3a): a tooltip on the chip and the bar, no band word in the chip."""

from pathlib import Path
from unittest.mock import MagicMock

import pytest

from photo_selector_linux.core.models import CandidatePhoto, QualityScores
from photo_selector_linux.ui import info_hud as info_hud_module
from photo_selector_linux.ui.info_hud import SCORE_TOOLTIP, InfoHUD
from photo_selector_linux.viewmodels.culling_workspace import CullingWorkspaceViewModel

REAL = info_hud_module.HAS_GI and not isinstance(info_hud_module.Gtk, MagicMock)


def test_the_tooltip_names_the_measure_and_what_it_compares():
    assert "centre of the frame" in SCORE_TOOLTIP and "burst" in SCORE_TOOLTIP


@pytest.mark.skipif(not REAL, reason="needs real Gtk under a display server")
def test_the_chip_shows_the_number_and_the_tooltip_explains_it():
    vm = CullingWorkspaceViewModel()
    hud = InfoHUD(viewmodel=vm)
    photo = CandidatePhoto(primary_path=Path("/tmp/test_dir/a.jpg"))
    photo.scores = QualityScores(sharpness=18.4, noise=1.0, highlight_clipping=0.0, shadow_clipping=0.0)
    hud.update_photo(photo)
    assert hud.lbl_score_badge.get_label() == "Sharpness: 18.4"
    assert "Blurry" not in hud.lbl_score_badge.get_label()
    assert hud.score_css_class == "score-badge-blurry"  # the colour still carries the band
    assert hud.lbl_score_badge.get_tooltip_text() == SCORE_TOOLTIP
    assert hud.sharpness_meter.get_tooltip_text() == SCORE_TOOLTIP
