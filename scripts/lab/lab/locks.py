"""Host-wide locks for driving Android devices: one per device, and one for the whole hub.

Device lock: `<lab tmp root>/device-<key>.lock`. Usability walkthroughs, instrumented test
runs (`scripts/android_test_device.py with-lock`) and journey runs take it, so two of them never
drive one phone at once. The key is the phone, not the adb transport (`lock_key`): a phone on
USB and on wireless debugging shows up as two serials (`CQ30012CMV` and `192.168.0.117:46567`
or `adb-CQ30012CMV-…._adb-tls-connect._tcp`), so a network transport is keyed by the
phone's `ro.serialno`, which is what its USB transport is called.

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
import re
import subprocess
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Callable, Iterator

HUB_LOCK_NAME = "android-hub.lock"

# `host:port` (adb connect) or an mDNS name, possibly with adb's " (2)" duplicate suffix.
NETWORK_TRANSPORT = re.compile(r":\d+$|\._adb-tls-connect\._tcp$")
SAFE_KEY = re.compile(r"[A-Za-z0-9._-]+")
_SERIALNO: dict[str, str] = {}  # transport serial -> ro.serialno, read once per process


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


def _adb_serialno(serial: str) -> str | None:
    try:
        out = subprocess.run(["adb", "-s", serial, "shell", "getprop", "ro.serialno"],
                             capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.SubprocessError):
        return None
    return out.stdout.strip() if out.returncode == 0 else None


def lock_key(serial: str) -> str:
    """The name a device's lock is keyed by: the phone's `ro.serialno` for a network transport.

    A USB (or emulator) serial is returned as it is: it already is the device's serial, and two
    emulators of one image can share an `ro.serialno` while being different devices. When the
    network transport cannot be read, its own serial is the key (and nothing is cached).
    """
    if not NETWORK_TRANSPORT.search(serial):
        return serial
    if serial not in _SERIALNO:
        hardware = _adb_serialno(serial)
        if not hardware or not SAFE_KEY.fullmatch(hardware):
            return serial
        _SERIALNO[serial] = hardware
    return _SERIALNO[serial]


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
    """Hold `<lock_dir>/device-<lock_key(serial)>.lock`; refuse at once if someone else holds it."""
    with held_lock(device_lock_path(serial, lock_dir), holder) as path:
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
    return _base(lock_dir) / f"device-{lock_key(serial)}.lock"
