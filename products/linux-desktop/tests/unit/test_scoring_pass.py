"""'Lowest sharpness first' orders the whole folder, not only the photos already viewed (BL-B-1).

Adheres to:
- R-LINUX-CULL-02: Lowest-Sharpness View Order
"""

from pathlib import Path

import pytest

from photo_selector_linux.core import image_source
from photo_selector_linux.core.models import CandidatePhoto, QualityScores
from photo_selector_linux.ui import photo_loader
from photo_selector_linux.viewmodels.culling_workspace import CullingWorkspaceViewModel

LOWEST = "lowest_sharpness_first"


def _scores(value: float) -> QualityScores:
    return QualityScores(sharpness=value, noise=1.0, highlight_clipping=0.0, shadow_clipping=0.0)


class FakeRunner:
    """Collects the pass; ``deliver`` plays the main loop receiving the scores."""

    def __init__(self, sharpness_of):
        self.sharpness_of = sharpness_of
        self.batches = []
        self.pending = []
        self.cancelled = 0

    def __call__(self, photos, on_scored):
        self.batches.append(list(photos))
        self.pending.extend((p, on_scored) for p in photos)
        return self._cancel

    def _cancel(self):
        self.cancelled += 1
        self.pending.clear()

    def deliver(self, count=None):
        count = len(self.pending) if count is None else count
        for photo, on_scored in [self.pending.pop(0) for _ in range(min(count, len(self.pending)))]:
            on_scored(photo, _scores(self.sharpness_of(photo)))


def _vm(tmp_path: Path, count: int = 50, runner=None, scored=()):
    runner = runner or FakeRunner(lambda p: float(p.filename[4:8]) % 97)
    vm = CullingWorkspaceViewModel(score_runner=runner)
    vm.current_folder = tmp_path
    vm.candidates = [CandidatePhoto(primary_path=tmp_path / f"IMG_{i:04d}.JPG") for i in range(count)]
    for i in scored:
        vm.candidates[i].scores = _scores(float(i) % 97)
    return vm, runner


def _names(vm):
    return [c.filename for c in vm.candidates]


def test_choosing_the_order_scores_every_unscored_photo_and_sorts_the_folder(tmp_path):
    vm, runner = _vm(tmp_path, scored=(3, 4))
    messages = []
    vm.connect("notification", lambda _vm, m: messages.append(m))

    vm.set_sort_order(LOWEST)
    assert [len(b) for b in runner.batches] == [48], "the two photos scored by viewing are not scored again"
    assert vm.is_scoring and messages == ["Scoring 48 photos…"]

    runner.deliver()

    assert not vm.is_scoring
    expected = sorted(range(50), key=lambda i: (float(i) % 97, i))
    assert _names(vm) == [f"IMG_{i:04d}.JPG" for i in expected]
    assert messages[-1] == "Scored 48 photos, lowest sharpness first"


def test_the_photo_on_screen_stays_on_screen_while_scores_arrive(tmp_path):
    vm, runner = _vm(tmp_path)
    vm.set_sort_order(LOWEST)
    vm.current_index = 20
    shown = vm.current_photo  # the user moved on while the pass runs

    runner.deliver(count=25)  # re-sorts partway (every 10 results)
    assert vm.current_photo is shown
    runner.deliver()
    assert vm.current_photo is shown, "navigated meanwhile: the view is not yanked away"


def test_an_unnavigated_sweep_starts_at_the_lowest_photo(tmp_path):
    vm, runner = _vm(tmp_path)
    vm.set_sort_order(LOWEST)
    runner.deliver()
    assert vm.current_index == 0 and vm.current_photo.filename == "IMG_0000.JPG"


def test_back_to_natural_order_cancels_the_pass(tmp_path):
    vm, runner = _vm(tmp_path)
    vm.set_sort_order(LOWEST)
    vm.set_sort_order("natural")
    assert runner.cancelled == 1 and not vm.is_scoring
    assert _names(vm) == [f"IMG_{i:04d}.JPG" for i in range(50)]


def test_a_folder_change_cancels_the_pass(tmp_path):
    vm, runner = _vm(tmp_path)
    vm.set_sort_order(LOWEST)
    (tmp_path / "next").mkdir()
    vm.load_directory(tmp_path / "next")
    assert runner.cancelled == 1 and not vm.is_scoring
    late = runner.pending[:] if runner.pending else []
    assert late == []


def test_photos_scanned_after_the_order_was_chosen_are_scored_when_the_scan_ends(tmp_path):
    for i in range(6):
        (tmp_path / f"IMG_{i:04d}.JPG").write_bytes(b"x")
    runner = FakeRunner(lambda p: 100.0 - float(p.filename[4:8]))
    vm = CullingWorkspaceViewModel(score_runner=runner)
    vm.sort_order = LOWEST
    vm.load_directory(tmp_path)  # synchronous scan: all six arrive, then the pass starts
    assert [len(b) for b in runner.batches] == [6]
    runner.deliver()
    assert _names(vm)[0] == "IMG_0005.JPG"


def test_remote_folder_needs_the_order_to_be_chosen_twice(tmp_path):
    vm, runner = _vm(tmp_path)
    vm.is_remote_folder = True
    messages = []
    vm.connect("notification", lambda _vm, m: messages.append(m))

    vm.set_sort_order(LOWEST)
    assert runner.batches == [] and vm.scoring_confirmation_pending
    assert "choose this order again" in messages[-1]

    vm.set_sort_order(LOWEST)
    assert [len(b) for b in runner.batches] == [50] and not vm.scoring_confirmation_pending


def test_without_a_runner_the_order_still_works_on_the_scored_photos(tmp_path):
    vm = CullingWorkspaceViewModel()
    vm.candidates = [CandidatePhoto(primary_path=tmp_path / f"IMG_{i}.JPG") for i in range(3)]
    vm.candidates[2].scores = _scores(5.0)
    vm.set_sort_order(LOWEST)
    assert vm.candidates[0].filename == "IMG_2.JPG"


real_stack = pytest.mark.skipif(
    not (image_source.HAS_GEXIV2 and photo_loader.HAS_GI), reason="needs the real GLib / GTK 4 typelibs"
)


@real_stack
def test_window_wires_the_loader_pass_and_it_scores_real_images(tmp_path):
    """The shipped wiring: MainWindow -> viewmodel.score_runner -> PhotoTextureLoader, real pixels."""
    import time

    from PIL import Image, ImageFilter

    from gi.repository import GLib
    from photo_selector_linux.ui import window as window_module

    import numpy as np

    y, x = np.indices((256, 256))
    checker = np.where(((x // 16) + (y // 16)) % 2 == 0, 20, 235).astype(np.uint8)
    sharp = Image.fromarray(checker).convert("RGB")
    blurry = sharp.filter(ImageFilter.GaussianBlur(10))
    for name, image in (("IMG_0001.jpg", sharp), ("IMG_0002.jpg", blurry), ("IMG_0003.jpg", sharp)):
        image.save(tmp_path / name, quality=95)

    win = window_module.MainWindow()
    vm = win.viewmodel
    assert vm.score_runner == win.canvas_view.loader.run_scoring_pass
    vm.current_folder = tmp_path
    names = ("IMG_0001.jpg", "IMG_0002.jpg", "IMG_0003.jpg")
    vm.candidates = [CandidatePhoto(primary_path=tmp_path / n) for n in names]
    vm.set_sort_order(LOWEST)

    context = GLib.MainContext.default()
    deadline = time.monotonic() + 20
    while vm.is_scoring and time.monotonic() < deadline:
        context.iteration(False)
        time.sleep(0.005)
    win.canvas_view.loader.shutdown()

    assert all(c.scores is not None for c in vm.candidates)
    assert vm.candidates[0].filename == "IMG_0002.jpg", "the blurry photo sorts first"
