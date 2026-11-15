"""The culling view model at the size users have: work grows linearly with the folder (BL-P-1).

Every test times the same operation through the real ``CullingWorkspaceViewModel`` at N and at
4N photos (sizes from ``testdata/conditions/conditions.json``, rules in
``docs/shared/USAGE_CONDITIONS.md``) and fails when time(4N) / time(N) exceeds the contract's
``max_ratio``: linear work gives about 4, a whole-list sort per scan batch about 16.
Absolute milliseconds are never asserted here.

Adheres to:
- R-LINUX-CULL-01: Progressive Asynchronous Streaming Ingestion
- R-LINUX-CULL-08: Folder Size Contract (time to first photo, time to full list)
- R-LINUX-CULL-02: View orders (newest, lowest sharpness) keep the photo on screen at scale
- R-LINUX-CULL-08: Folder Size Contract (cull and navigation cost per key)
"""

import json
import random
import time
from pathlib import Path
from unittest.mock import MagicMock

from photo_selector_linux.core.models import (
    CandidatePhoto,
    CullingActionType,
    CullingRecord,
    ExposureMetadata,
    QualityScores,
)
from photo_selector_linux.core.scanner import DirectoryScanner
from photo_selector_linux.viewmodels.culling_workspace import CullingWorkspaceViewModel

REPO_ROOT = Path(__file__).resolve().parents[4]
CONDITIONS = json.loads((REPO_ROOT / "testdata" / "conditions" / "conditions.json").read_text(encoding="utf-8"))
FACTOR = CONDITIONS["growth_check"]["factor"]
MAX_RATIO = CONDITIONS["growth_check"]["max_ratio"]
LARGE = CONDITIONS["folder_sizes"]["pr"]["photos"]
SMALL = LARGE // FACTOR
RUNS = 3
KEYS = 100  # navigation / cull keys of one realistic burst of use


def _photos(count: int, seed: int = 7):
    rng = random.Random(seed)
    root = Path("/scale/folder")
    return [
        CandidatePhoto(primary_path=root / f"IMG_{i:05d}.JPG", modified_at=rng.uniform(1e9, 2e9))
        for i in range(count)
    ]


class _BatchScanner(DirectoryScanner):
    """Emits prepared photos in the batches the view model asks for (1, then 30 at a time)."""

    def __init__(self, photos):
        super().__init__()
        self._photos = photos

    def scan_stream(self, root_dir, recursive=True, batch_size=30, first_batch_size=None):
        first = max(1, first_batch_size if first_batch_size is not None else batch_size)
        batch, target = [], first
        for photo in self._photos:
            batch.append(photo)
            if len(batch) >= target:
                yield batch
                batch, target = [], batch_size
        if batch:
            yield batch


def _loaded(count: int, order: str, **kwargs):
    """A view model that has taken the whole folder in through the real scan path."""
    vm = CullingWorkspaceViewModel(scanner=_BatchScanner(_photos(count)), **kwargs)
    vm.sort_order = order
    vm.load_directory(Path("/scale/folder"))
    assert len(vm.candidates) == count
    return vm


def _best(setup, timed) -> float:
    """Best of RUNS of ``timed(setup())``, after one warm-up run."""
    timed(setup())
    best = float("inf")
    for _ in range(RUNS):
        state = setup()
        started = time.perf_counter()
        timed(state)
        best = min(best, time.perf_counter() - started)
    return best


ATTEMPTS = 3  # a loaded machine can push one linear measurement past the limit


def _assert_linear(operation: str, setup, timed) -> None:
    # The best time per size across attempts, never the best ratio: a noisy small run makes the
    # ratio look low, and taking any passing attempt let quadratic work through once (Frameduel,
    # 2026-11-15: 15.1, 16.6, then 6.79). Best times converge on the noise-free figures.
    small = large = float("inf")
    for _ in range(ATTEMPTS):
        small = min(small, _best(lambda: setup(SMALL), timed))
        large = min(large, _best(lambda: setup(LARGE), timed))
        ratio = large / max(small, 1e-9)
        if ratio <= MAX_RATIO:
            return
    assert ratio <= MAX_RATIO, (
        f"{operation}: {small * 1000:.1f} ms at {SMALL} photos, {large * 1000:.1f} ms at {LARGE} "
        f"(x{ratio:.1f}, limit x{MAX_RATIO:g})"
    )


def test_the_contract_sizes_are_read_from_the_conditions_file():
    assert (SMALL, LARGE) == (LARGE // FACTOR, CONDITIONS["folder_sizes"]["pr"]["photos"])
    assert SMALL * FACTOR == LARGE and MAX_RATIO > FACTOR


def test_discovery_newest_first_is_linear_and_ordered():
    """Scale: pr. R-LINUX-CULL-01 / R-LINUX-CULL-02: scan batches of 30 merged into newest-first order.

    The main loop merges one batch per callback, so the total of all callbacks is what a user waits
    for (time to full list); the first batch is one photo (time to first photo).
    """

    def setup(count):
        vm = CullingWorkspaceViewModel()
        vm.sort_order = "newest"
        return vm, _photos(count)

    def timed(state):
        vm, photos = state
        batches = list(_BatchScanner(photos).scan_stream(Path("/x"), batch_size=30, first_batch_size=1))
        assert len(batches[0]) == 1
        for batch in batches:
            vm._add_scanned(batch)

    _assert_linear("newest-first discovery", setup, timed)
    vm = _loaded(SMALL, "newest")
    times = [c.order_time for c in vm.candidates]
    assert len(times) == SMALL and times == sorted(times, reverse=True)
    assert vm.current_index == 0


def test_discovery_in_name_order_is_linear():
    """Scale: pr. R-LINUX-CULL-01: the default order appends each batch."""

    def setup(count):
        return CullingWorkspaceViewModel(), _photos(count)

    def timed(state):
        vm, photos = state
        for batch in _BatchScanner(photos).scan_stream(Path("/x"), batch_size=30, first_batch_size=1):
            vm._add_scanned(batch)

    _assert_linear("name-order discovery", setup, timed)


def _navigating_vm(count):
    vm = _loaded(count, "newest")
    for i, photo in enumerate(vm.candidates):  # bursts of 5 frames, so cues exist
        photo.exif = ExposureMetadata(captured_at=1000.0 + (i // 5) * 60 + (i % 5) * 0.2)
    vm.current_index = len(vm.candidates) - 1
    seen = []

    def on_state(model):  # what the window does on every state change
        photo = model.current_photo
        seen.append((model.canvas_state, model.card_summary()["remaining"], model.burst_cue(photo)))

    vm.connect("state-changed", on_state)
    return vm


def test_navigation_at_the_end_of_the_list_is_linear_per_key():
    """Scale: pr. R-LINUX-CULL-08: 100 keys back from the last photo, with the window's state reads."""

    def timed(vm):
        for _ in range(KEYS):
            vm.nav_previous()

    _assert_linear("navigation at the end", _navigating_vm, timed)


def test_culling_at_the_end_of_the_list_is_linear():
    """Scale: pr. R-LINUX-CULL-08: 100 Trash culls queued from the last photo, undo kept."""

    def setup(count):
        vm = _loaded(count, "natural")
        vm.file_manager = MagicMock()
        vm.file_manager.trash_candidate.side_effect = lambda photo, **kw: CullingRecord(
            action_type=CullingActionType.TRASH, original_paths=[photo.primary_path], new_paths=[]
        )
        vm.dispatcher = lambda job, done: None  # the worker is not under test: queue only
        vm.current_index = len(vm.candidates) - 1
        return vm

    def timed(vm):
        for _ in range(KEYS):
            vm.cull_trash()

    _assert_linear("culling at the end", setup, timed)


class _Runner:
    """Collects a background pass; ``deliver`` plays the main loop receiving each result."""

    def __init__(self, result):
        self.result = result
        self.pending = []

    def __call__(self, photos, callback):
        self.pending = [(p, callback) for p in photos]
        return lambda: self.pending.clear()

    def deliver(self):
        for photo, callback in self.pending:
            callback(photo, self.result(photo))
        self.pending = []


def test_scoring_pass_is_linear_in_the_folder_size():
    """Scale: pr. R-LINUX-CULL-02: lowest-sharpness-first scores the whole folder, resorting as results arrive."""

    def setup(count):
        runner = _Runner(
            lambda p: QualityScores(
                sharpness=float(int(p.filename[4:9]) * 7919 % 1000),
                noise=1.0,
                highlight_clipping=0.0,
                shadow_clipping=0.0,
            )
        )
        vm = _loaded(count, "natural", score_runner=runner)
        vm.set_sort_order("lowest_sharpness_first")
        return vm, runner

    def timed(state):
        vm, runner = state
        runner.deliver()
        assert not vm.is_scoring

    _assert_linear("scoring pass", setup, timed)


def test_capture_pass_is_linear_in_the_folder_size():
    """Scale: pr. R-LINUX-CULL-02: newest-first reads every capture time, resorting as results arrive."""

    def setup(count):
        runner = _Runner(
            lambda p: ExposureMetadata(captured_at=1e9 + int(p.filename[4:9]) * 7919 % 100000 * 0.5)
        )
        vm = _loaded(count, "newest")
        vm.capture_runner = runner
        vm._begin_capture_pass()
        # the window asks the burst cue on every state change while times arrive
        vm.connect("state-changed", lambda m: m.burst_cue(m.current_photo))
        return vm, runner

    def timed(state):
        vm, runner = state
        runner.deliver()
        assert not vm._capture.reading

    _assert_linear("capture pass", setup, timed)


class _CountingList(list):
    """A candidate list that counts how often a whole pass over it starts."""

    passes = 0

    def __iter__(self):
        type(self).passes += 1
        return super().__iter__()


def test_a_navigation_key_makes_at_most_one_pass_over_the_list():
    """Scale: pr. R-LINUX-CULL-08: the window reads the tallies and the burst cue on every key; each
    used to walk all photos (about 4 passes, 7 ms per key at 10,000), now at most one."""
    vm = _navigating_vm(LARGE)
    vm.candidates = _CountingList(vm.candidates)
    vm.nav_previous()  # the swapped list is read once afresh
    _CountingList.passes = 0
    for _ in range(KEYS):
        vm.nav_previous()
    assert _CountingList.passes <= KEYS, f"{_CountingList.passes} passes for {KEYS} keys"


def test_the_cue_of_an_unchanged_list_is_not_recomputed_per_call():
    """Scale: pr. R-LINUX-CULL-08: the info card asks the cue on every redraw."""
    vm = _navigating_vm(LARGE)
    photo = vm.current_photo
    vm.burst_cue(photo)
    vm.candidates = _CountingList(vm.candidates)
    _CountingList.passes = 0
    for _ in range(200):
        vm.burst_cue(photo)
    assert _CountingList.passes <= 1


def test_opening_on_a_photo_the_scan_reaches_late_is_linear():
    """Scale: pr. R-LINUX-CULL-01: a folder opened on a named photo looks for it in each batch.

    The name here is never found, the worst case: every batch must cost its own size, not the
    list's (same class as the Jackpix F-TOK-098: a helper that walks the folder inside a loop
    over the folder).
    """

    def setup(count):
        vm = CullingWorkspaceViewModel()
        vm.current_folder = Path("/scale/folder")
        vm._select_name = "never-there.jpg"
        return vm, _photos(count)

    def timed(state):
        vm, photos = state
        for batch in _BatchScanner(photos).scan_stream(Path("/x"), batch_size=30, first_batch_size=1):
            vm._add_scanned(batch)
            vm._select_requested_photo(batch)

    _assert_linear("select-by-name during discovery", setup, timed)
