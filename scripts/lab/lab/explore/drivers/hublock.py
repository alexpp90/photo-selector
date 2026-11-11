"""The hub lock of one process, shared by every Android driver of a run."""

from __future__ import annotations

import threading
from contextlib import ExitStack
from pathlib import Path
from typing import Dict, Optional, Tuple

from ...locks import hub_lock_path, waiting_lock


class _SharedHubLock:
    """The hub lock for this process: one `flock` however many Android drivers are active.

    `locks.py`: a lab run holds the hub lock for as long as it drives Android devices, because
    every Maestro and adb call shares one adb server. Two drivers of one run (two devices) must
    not wait for each other on it, so the first takes the flock and the last releases it.
    """

    def __init__(self) -> None:
        self._guard = threading.Lock()
        self._held: Dict[Path, Tuple[int, ExitStack]] = {}

    def acquire(self, holder: str, lock_dir: Optional[Path], wait_s: float) -> Path:
        path = hub_lock_path(lock_dir)
        with self._guard:
            if path in self._held:
                count, stack = self._held[path]
                self._held[path] = (count + 1, stack)
                return path
            stack = ExitStack()
            stack.enter_context(waiting_lock(path, holder, wait_s))  # DeviceBusy when it stays held
            self._held[path] = (1, stack)
            return path

    def release(self, path: Path) -> None:
        with self._guard:
            if path not in self._held:
                return
            count, stack = self._held[path]
            if count > 1:
                self._held[path] = (count - 1, stack)
                return
            del self._held[path]
            stack.close()


_HUB_LOCK = _SharedHubLock()
