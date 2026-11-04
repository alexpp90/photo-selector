"""Closing the window finishes every queued file operation first (BL-D-2).

Adheres to:
- R-LINUX-UI-10: Queued non-blocking background file operations
"""

import threading
import time
from pathlib import Path

import pytest

from photo_selector_linux.core import image_source
from photo_selector_linux.core.models import CandidatePhoto, CullingActionType, CullingRecord
from photo_selector_linux.ui import photo_loader
from photo_selector_linux.ui import window as window_module

real_glib = pytest.mark.skipif(
    not (image_source.HAS_GEXIV2 and photo_loader.HAS_GI), reason="needs the real GLib / GTK 4 typelibs"
)


class RecordingManager:
    def __init__(self, gate: threading.Event):
        self.moved = []
        self.gate = gate

    def move_candidate(self, photo, folder, selection_folder="Selection", **_kwargs):
        self.gate.wait(timeout=5)
        self.moved.append(photo.filename)
        return CullingRecord(
            photo_id=photo.id,
            action_type=CullingActionType.MOVE,
            original_primary_path=photo.primary_path,
            affected_paths=[(photo.primary_path, folder / selection_folder / photo.filename)],
        )


def _iterate_until(predicate, seconds=10.0):
    from gi.repository import GLib

    context = GLib.MainContext.default()
    deadline = time.monotonic() + seconds
    while not predicate() and time.monotonic() < deadline:
        context.iteration(False)
        time.sleep(0.001)


@real_glib
def test_close_with_queued_culls_runs_them_all_then_closes(tmp_path: Path, monkeypatch):
    gate = threading.Event()
    manager = RecordingManager(gate)
    win = window_module.MainWindow()
    monkeypatch.setattr(win, "show_toast", lambda *_a: None)
    closed = []
    monkeypatch.setattr(win, "close", lambda: closed.append(len(manager.moved)))
    vm = win.viewmodel
    vm.file_manager = manager
    vm.current_folder = tmp_path
    vm.candidates = [CandidatePhoto(primary_path=tmp_path / f"IMG_{i}.JPG") for i in range(4)]

    for _ in range(4):
        assert vm.cull_move()
    assert vm.queued_count == 4

    assert win._on_close_request() is True  # held: three culls are still queued
    assert closed == []
    gate.set()
    _iterate_until(lambda: bool(closed))

    assert manager.moved == [f"IMG_{i}.JPG" for i in range(4)]
    assert closed == [4], "the window closes after the fourth operation, not before"
    assert vm.queued_count == 0
    assert win._on_close_request() is False  # idle now: the real close goes through


@real_glib
def test_second_close_request_quits_anyway(tmp_path: Path, monkeypatch):
    gate = threading.Event()
    win = window_module.MainWindow()
    monkeypatch.setattr(win, "show_toast", lambda *_a: None)
    vm = win.viewmodel
    vm.file_manager = RecordingManager(gate)
    vm.current_folder = tmp_path
    vm.candidates = [CandidatePhoto(primary_path=tmp_path / f"IMG_{i}.JPG") for i in range(2)]
    vm.cull_move()
    vm.cull_move()

    assert win._on_close_request() is True
    gate.set()  # lets the worker finish so the test does not hang on shutdown
    assert win._on_close_request() is False
    assert win.file_worker._closed


def test_worker_ignores_jobs_after_shutdown():
    from photo_selector_linux.ui.file_worker import FileOperationWorker

    worker = FileOperationWorker(idle_add=lambda fn, *a: fn(*a))
    worker.shutdown()
    ran = []
    worker(lambda: ran.append(1), lambda *_a: ran.append(2))  # no RuntimeError, nothing runs
    assert ran == []
