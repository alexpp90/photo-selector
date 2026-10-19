"""Host-wide locks for driving Android devices: one per device, and one for the whole hub.

Device lock: `<lab tmp root>/device-<serial>.lock`. Usability walkthroughs, instrumented test
runs (`scripts/android_test_device.py with-lock`) and journey runs take it, so two of them never
drive one phone at once.

Hub lock: `<lab tmp root>/android-hub.lock`, held by a `lab.py run` for as long as it drives
Android devices. Two runs on one hub broke each other even on different devices (LAB-24,
2026-10-19: `uiautomator dump` failures, Maestro gRPC `UNAVAILABLE` and picker misses; the Pixel
Tablet went from 13 error/flow-broken in parallel to 14 pass solo). Every Maestro and adb call
goes through the one adb server, and Maestro opens every attached device to list them, so
separate devices are not separate runs. A second run waits for the first instead.

Both are `fcntl.flock` locks: the kernel releases them when the holder dies, and `os.open`
descriptors are not inherited (PEP 446), so a child process never keeps one alive.
"""

from __future__ import annotations

import fcntl
import os
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Callable, Iterator

HUB_LOCK_NAME = "android-hub.lock"


class DeviceBusy(RuntimeError):
    """Another session or person holds the lock; the work must not start."""


def _base(lock_dir: Path | None) -> Path:
    from .paths import lab_tmp_root

    base = lock_dir or lab_tmp_root()
    base.mkdir(parents=True, exist_ok=True)
    return base


def _acquire(path: Path, holder: str) -> int:
    """The open, flocked descriptor of `path`, or `DeviceBusy` naming the current holder."""
    fd = os.open(path, os.O_RDWR | os.O_CREAT, 0o644)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        held_by = os.pread(fd, 400, 0).decode("utf-8", errors="replace").strip() or "unknown holder"
        os.close(fd)
        raise DeviceBusy(f"{path} is held by: {held_by}") from None
    os.ftruncate(fd, 0)
    os.pwrite(fd, f"{holder} pid={os.getpid()} since={time.strftime('%Y-%m-%dT%H:%M:%S')}\n".encode(), 0)
    return fd


@contextmanager
def held_lock(path: Path, holder: str) -> Iterator[Path]:
    """Hold the flock at `path` or raise `DeviceBusy` naming the current holder."""
    fd = _acquire(path, holder)
    try:
        yield path
    finally:
        os.close(fd)  # closing releases the flock


@contextmanager
def device_lock(serial: str, holder: str, lock_dir: Path | None = None) -> Iterator[Path]:
    """Hold `<lock_dir>/device-<serial>.lock`; refuse at once if someone else holds it."""
    with held_lock(_base(lock_dir) / f"device-{serial}.lock", holder) as path:
        yield path


@contextmanager
def waiting_lock(
    path: Path,
    holder: str,
    timeout_s: float,
    *,
    poll_s: float = 5.0,
    on_wait: Callable[[str], None] | None = None,
    clock: Callable[[], float] = time.monotonic,
    sleep: Callable[[float], None] = time.sleep,
) -> Iterator[Path]:
    """Hold the flock at `path`, polling for up to `timeout_s`; then raise `DeviceBusy`.

    `on_wait` hears each new holder once (a waiting run says who it waits for).
    """
    deadline = clock() + timeout_s
    announced: str | None = None
    while True:
        try:
            fd = _acquire(path, holder)
            break
        except DeviceBusy as exc:
            remaining = deadline - clock()
            if remaining <= 0:
                raise DeviceBusy(f"still busy after {timeout_s:.0f}s — {exc}") from None
            if on_wait is not None and str(exc) != announced:
                on_wait(f"waiting up to {remaining:.0f}s — {exc}")
                announced = str(exc)
            sleep(min(poll_s, remaining))
    try:
        yield path
    finally:
        os.close(fd)


def hub_lock_path(lock_dir: Path | None = None) -> Path:
    return _base(lock_dir) / HUB_LOCK_NAME


def device_lock_path(serial: str, lock_dir: Path | None = None) -> Path:
    return _base(lock_dir) / f"device-{serial}.lock"
