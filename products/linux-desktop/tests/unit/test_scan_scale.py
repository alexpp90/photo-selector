"""A big folder must neither freeze the window nor take quadratic time to list (BL-P-1).

Adheres to:
- R-LINUX-CULL-01: Progressive Asynchronous Streaming Ingestion
- R-LINUX-META-04: Companion RAW+JPEG, Sidecar & Edit Binding
"""

import time
from pathlib import Path

import pytest

from photo_selector_linux.core import image_source, scanner as scanner_module
from photo_selector_linux.core.scanner import DirectoryScanner
from photo_selector_linux.ui import file_worker, photo_loader
from photo_selector_linux.ui import window as window_module
from photo_selector_linux.viewmodels.culling_workspace import CullingWorkspaceViewModel

real_glib = pytest.mark.skipif(
    not (image_source.HAS_GEXIV2 and photo_loader.HAS_GI), reason="needs the real GLib / GTK 4 typelibs"
)

PAIRS = 2000


def _make_pairs(folder: Path, count: int) -> None:
    for i in range(count):
        (folder / f"IMG_{i:05d}.NEF").write_bytes(b"raw")
        (folder / f"IMG_{i:05d}.JPG").write_bytes(b"jpg")


def test_companion_pass_is_linear_in_the_folder_size(tmp_path, monkeypatch):
    """2000 RAW+JPEG pairs took 46 s because every photo scanned the whole listing again."""
    _make_pairs(tmp_path, PAIRS)
    calls = 0
    real = scanner_module._extension_of

    def counting(name):
        nonlocal calls
        calls += 1
        return real(name)

    monkeypatch.setattr(scanner_module, "_extension_of", counting)
    started = time.monotonic()
    candidates = [c for batch in DirectoryScanner().scan_stream(tmp_path) for c in batch]
    elapsed = time.monotonic() - started

    assert len(candidates) == PAIRS
    assert all(len(c.companion_paths) == 1 for c in candidates)
    # The quadratic pass made ~1 200 extension look-ups per photo; a listing-wide index needs a handful.
    assert calls < 20 * 2 * PAIRS, f"{calls} look-ups for {2 * PAIRS} files"
    assert elapsed < 10, f"{elapsed:.1f} s for {PAIRS} pairs"


def test_edit_derivatives_do_not_make_the_pass_quadratic(tmp_path):
    for i in range(PAIRS):
        (tmp_path / f"IMG_{i:05d}.JPG").write_bytes(b"jpg")
        (tmp_path / f"IMG_{i:05d}-Edit.JPG").write_bytes(b"edit")
    started = time.monotonic()
    candidates = [c for batch in DirectoryScanner().scan_stream(tmp_path) for c in batch]
    assert len(candidates) == PAIRS
    assert all(len(c.companion_paths) == 1 for c in candidates)
    assert time.monotonic() - started < 10


class _SlowDescribing(DirectoryScanner):
    """Stands in for GVFS: resolving the folder's display name and listing it take a while."""

    def __init__(self, delay: float):
        super().__init__()
        self.delay = delay
        self.resolver_calls = 0

        outer = self

        class _Resolver:
            def names_in(self, directory):
                outer.resolver_calls += 1
                time.sleep(outer.delay)
                return {}

        self.name_resolver = _Resolver()


def _iterate_until(predicate, seconds=10.0, tick=None):
    from gi.repository import GLib

    context = GLib.MainContext.default()
    deadline = time.monotonic() + seconds
    while not predicate() and time.monotonic() < deadline:
        context.iteration(False)
        if tick:
            tick()
        time.sleep(0.001)


@real_glib
def test_main_loop_keeps_running_while_the_folder_is_scanned(tmp_path):
    """With the window's wiring the main loop services a 10 ms timer during a slow scan."""
    from gi.repository import GLib

    (tmp_path / "IMG_0001.JPG").write_bytes(b"jpg")
    scanner = _SlowDescribing(delay=0.6)
    vm = CullingWorkspaceViewModel(scanner=scanner, scan_idle_add=file_worker.schedule_on_main_loop)

    ticks = []
    timer = GLib.timeout_add(10, lambda: ticks.append(time.monotonic()) or True)
    try:
        started = time.monotonic()
        vm.load_directory(tmp_path)
        returned_after = time.monotonic() - started
        _iterate_until(lambda: not vm.is_scanning)
    finally:
        GLib.source_remove(timer)

    assert returned_after < 0.2, "load_directory must not wait for the mount table or GVFS"
    assert not vm.is_scanning and [c.filename for c in vm.candidates] == ["IMG_0001.JPG"]
    assert vm.current_folder == tmp_path.resolve()
    # The 0.6 s of slow listing on the scan thread leaves the loop free for ~50 timer ticks.
    assert len(ticks) >= 20, f"main loop ran only {len(ticks)} timer ticks during the scan"


def test_window_wires_the_scan_to_the_main_loop():
    """The production wiring: without scan_idle_add the view model scans on the GTK thread."""
    win = window_module.MainWindow()
    assert win.viewmodel.scan_idle_add is file_worker.schedule_on_main_loop


def test_view_model_describes_the_folder_on_the_scan_thread(tmp_path):
    (tmp_path / "IMG_0001.JPG").write_bytes(b"jpg")
    scanner = _SlowDescribing(delay=0.3)
    pending = []
    vm = CullingWorkspaceViewModel(scanner=scanner, scan_idle_add=lambda fn, *a: pending.append((fn, a)))

    started = time.monotonic()
    vm.load_directory(tmp_path)
    assert time.monotonic() - started < 0.15
    assert vm.is_scanning
    vm._scan_thread.join(timeout=5)
    for fn, args in pending:  # the main loop delivering what the thread queued
        fn(*args)
    assert scanner.resolver_calls >= 1
    assert vm.current_folder_name == tmp_path.name and not vm.is_scanning


def test_scan_failure_ends_the_scan_and_says_so(tmp_path):
    class Broken(DirectoryScanner):
        def scan_stream(self, *args, **kwargs):
            raise PermissionError("denied")
            yield  # pragma: no cover

    pending = []
    vm = CullingWorkspaceViewModel(scanner=Broken(), scan_idle_add=lambda fn, *a: pending.append((fn, a)))
    messages = []
    vm.connect("notification", lambda _vm, message: messages.append((message, vm.last_notification_severity)))
    vm.load_directory(tmp_path)
    vm._scan_thread.join(timeout=5)
    for fn, args in pending:
        fn(*args)
    assert not vm.is_scanning
    assert messages and messages[-1][1] == "error" and "denied" in messages[-1][0]
