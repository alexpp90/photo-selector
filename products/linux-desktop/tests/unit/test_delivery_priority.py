"""Worker results must reach the main loop above GDK's redraw priority (device lab LIN-4).

While a cull runs, spinners animate and the frame clock redraws at priority 120
(G_PRIORITY_HIGH_IDLE + 20). ``GLib.idle_add`` defaults to 200, which starves behind a redraw that is
always pending under a slow software renderer: the finished job's outcome was never applied.
"""

import threading
import time
from pathlib import Path
from types import SimpleNamespace

import pytest

from photo_selector_linux.core import image_source
from photo_selector_linux.ui import file_worker, photo_loader

GDK_REDRAW_PRIORITY = 120

real_glib = pytest.mark.skipif(
    not (image_source.HAS_GEXIV2 and photo_loader.HAS_GI), reason="needs the real GLib / GTK 4 typelibs"
)


class RecordingGLib:
    PRIORITY_DEFAULT = 0

    def __init__(self):
        self.calls = []

    def idle_add(self, fn, *args, priority=200):
        self.calls.append(priority)
        return 1


def test_file_worker_default_scheduler_is_more_urgent_than_redraw(monkeypatch):
    glib = RecordingGLib()
    monkeypatch.setattr(file_worker, "GLib", glib)
    file_worker.schedule_on_main_loop(lambda: None)
    assert glib.calls and glib.calls[0] < GDK_REDRAW_PRIORITY

    worker = file_worker.FileOperationWorker()  # default scheduler
    delivered = threading.Event()
    worker(lambda: 1, lambda result, error: delivered.set())
    deadline = time.monotonic() + 10
    while len(glib.calls) < 2 and time.monotonic() < deadline:
        time.sleep(0.01)
    worker.shutdown()
    assert len(glib.calls) == 2 and glib.calls[1] < GDK_REDRAW_PRIORITY


def test_photo_loader_delivery_is_more_urgent_than_redraw(monkeypatch):
    glib = RecordingGLib()
    monkeypatch.setattr(photo_loader, "GLib", glib)
    loader = photo_loader.PhotoTextureLoader(workers=1)
    candidate = SimpleNamespace(exif=object(), scores=object(), filename="x.jpg")
    loader._work((Path("/nonexistent/x.jpg"), 100), candidate)
    loader.shutdown()
    assert glib.calls and glib.calls[0] < GDK_REDRAW_PRIORITY


def _iterate_until(predicate, seconds=10.0):
    from gi.repository import GLib

    context = GLib.MainContext.default()
    deadline = time.monotonic() + seconds
    while not predicate() and time.monotonic() < deadline:
        context.iteration(False)
        time.sleep(0.001)


@real_glib
def test_real_main_loop_delivers_file_job_despite_continuous_redraw():
    from gi.repository import GLib

    redraw = GLib.idle_add(lambda: True, priority=GDK_REDRAW_PRIORITY)  # a redraw that is always pending
    worker = file_worker.FileOperationWorker()
    delivered = []
    try:
        worker(lambda: "record", lambda result, error: delivered.append((result, error)))
        _iterate_until(lambda: bool(delivered))
    finally:
        GLib.source_remove(redraw)
        worker.shutdown()
    assert delivered == [("record", None)]


@real_glib
def test_real_main_loop_delivers_texture_despite_continuous_redraw(monkeypatch):
    from gi.repository import GLib

    loader = photo_loader.PhotoTextureLoader(workers=1)
    delivered = []
    monkeypatch.setattr(loader, "_deliver", lambda key, pixbuf: delivered.append(key) or False)
    candidate = SimpleNamespace(exif=object(), scores=object(), filename="x.jpg")
    redraw = GLib.idle_add(lambda: True, priority=GDK_REDRAW_PRIORITY)
    try:
        loader._pool.submit(loader._work, (Path("/nonexistent/x.jpg"), 100), candidate)
        _iterate_until(lambda: bool(delivered))
    finally:
        GLib.source_remove(redraw)
        loader.shutdown()
    assert delivered
