"""Background worker for culling file operations; outcomes are delivered on the GTK main loop.

A Move, Copy, Trash or Undo on a cloud mount uploads or downloads whole files (16 s for a 25 MB
RAW on Google Drive); run on the main loop it froze the window. One worker thread keeps the
operations in order, which the undo stack relies on.
"""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from typing import Callable

try:
    import gi
    gi.require_version("GLib", "2.0")
    from gi.repository import GLib
    HAS_GI = True
except (ImportError, ValueError):
    HAS_GI = False
    GLib = None

from photo_selector_linux.viewmodels.culling_workspace import (  # noqa: F401
    Job,
    JobDone,
    run_inline,
)


# Worker results are delivered at GLib.PRIORITY_DEFAULT (0), not ``GLib.idle_add``'s DEFAULT_IDLE (200).
# While an operation runs, spinners animate and GDK redraws at priority 120 (HIGH_IDLE + 20); under
# a slow software renderer a redraw is always pending, so a priority-200 delivery starves and the
# cull is never applied (device lab LIN-4).

def schedule_on_main_loop(fn: Callable[..., object], *args) -> int:
    """``GLib.idle_add`` at PRIORITY_DEFAULT: above the frame clock's redraw."""
    return GLib.idle_add(fn, *args, priority=GLib.PRIORITY_DEFAULT)


class FileOperationWorker:
    """A ``CullingWorkspaceViewModel`` dispatcher: job on a worker thread, ``done`` on the main loop."""

    def __init__(self, idle_add: Callable[..., object] = None) -> None:
        self._pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="culling-file-ops")
        self._idle_add = idle_add or schedule_on_main_loop

    def __call__(self, job: Job, done: JobDone) -> None:
        self._pool.submit(self._work, job, done)

    def _work(self, job: Job, done: JobDone) -> None:
        try:
            result, error = job(), None
        except Exception as e:
            result, error = None, e
        self._idle_add(self._deliver, done, result, error)

    @staticmethod
    def _deliver(done: JobDone, result, error) -> bool:
        done(result, error)
        return False

    def shutdown(self) -> None:
        """Drain running and queued operations cleanly without abandoning pending culls (R-LINUX-UI-10)."""
        self._pool.shutdown(wait=True, cancel_futures=False)
