"""A burst cue on the info card (decision #31, plan 2.4): "3 of 10" when neighbours are under a second
apart. Display only; never "1 of 1"; only real capture times."""

from pathlib import Path
from unittest.mock import MagicMock

import pytest

from photo_selector_linux.core.bursts import BURST_GAP, burst_positions, burst_spoken
from photo_selector_linux.core.models import CandidatePhoto, ExposureMetadata
from photo_selector_linux.ui import info_hud as info_hud_module
from photo_selector_linux.ui.info_hud import InfoHUD
from photo_selector_linux.viewmodels.culling_workspace import CullingWorkspaceViewModel

REAL = info_hud_module.HAS_GI and not isinstance(info_hud_module.Gtk, MagicMock)


def _photo(name: str, t):
    exif = ExposureMetadata(captured_at=t) if t is not None else None
    return CandidatePhoto(primary_path=Path(f"/t/{name}.jpg"), exif=exif)


def test_ten_frames_a_fifth_of_a_second_apart_are_one_burst_and_the_third_reads_3_of_10():
    frames = [_photo(f"IMG_{n:02d}", 1000.0 + n * 0.2) for n in range(10)]
    cues = burst_positions(frames)
    assert cues[frames[2].id] == (3, 10)
    assert {size for _pos, size in cues.values()} == {10}
    assert burst_spoken(3, 10) == "Burst, photo 3 of 10"


def test_a_gap_of_one_and_a_half_seconds_splits_the_burst():
    times = [0.0, 0.3, 0.6, 2.1, 2.4]
    frames = [_photo(f"a{n}", t) for n, t in enumerate(times)]
    cues = burst_positions(frames)
    assert [cues[f.id] for f in frames] == [(1, 3), (2, 3), (3, 3), (1, 2), (2, 2)]
    assert BURST_GAP == 1.0


def test_a_lone_photo_a_photo_without_a_capture_time_and_a_gap_of_exactly_a_second_have_no_cue():
    lone, undated = _photo("lone", 100.0), _photo("undated", None)
    far = _photo("far", 105.0)
    assert burst_positions([lone, undated, far]) == {}
    assert burst_positions([_photo("a", 0.0), _photo("b", 1.0)]) == {}  # "less than a second"


def test_the_burst_follows_time_not_the_view_order():
    shuffled = [_photo("b", 10.2), _photo("z", 50.0), _photo("a", 10.0), _photo("c", 10.4)]
    cues = burst_positions(shuffled)
    assert cues[shuffled[2].id] == (1, 3) and cues[shuffled[0].id] == (2, 3) and cues[shuffled[3].id] == (3, 3)
    assert shuffled[1].id not in cues


def test_the_view_model_hands_out_the_cue_and_follows_new_metadata():
    vm = CullingWorkspaceViewModel()
    a, b = _photo("a", None), _photo("b", None)
    vm.candidates = [a, b]
    assert vm.burst_cue(a) is None
    a.exif, b.exif = ExposureMetadata(captured_at=5.0), ExposureMetadata(captured_at=5.4)
    assert vm.burst_cue(b) == (2, 2)


@pytest.mark.skipif(not REAL, reason="needs real Gtk under a display server")
def test_the_info_card_shows_the_cue_first_with_its_spoken_text_and_hides_it_for_a_lone_photo():
    vm = CullingWorkspaceViewModel()
    frames = [_photo(f"IMG_{n}", 1000.0 + n * 0.2) for n in range(10)]
    lone = _photo("lone", 9999.0)
    vm.candidates = frames + [lone]
    hud = InfoHUD(viewmodel=vm)
    hud.update_photo(frames[2])
    assert hud.burst_row.get_visible() and hud.lbl_burst.get_label() == "3 of 10"
    assert hud.get_first_child() is hud.burst_row  # the first row of the card
    assert hud.burst_row.get_tooltip_text() == "Burst, photo 3 of 10"
    assert hud.burst_icon.get_icon_name() in ("view-continuous-symbolic", "view-list-symbolic")
    hud.update_photo(lone)
    assert not hud.burst_row.get_visible() and hud.burst_text == ""
    hud.update_photo(None)
    assert hud.burst_text == ""
