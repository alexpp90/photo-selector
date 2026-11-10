"""Read-only readiness table for `lab.py preflight`: can each device take a run right now?

Devices and products were lost late in two of the last four campaigns (2026-10-26 a phone was not
on the USB bus; 2026-11-09 a tablet's Wi-Fi adb was gone and the Mac had no GUI session), hours
after the run began. This reads, per Android device, the transport, whether the screen is awake,
the keyguard, `stay_on_while_plugged_in`, the foreground package, the `wm size`/`wm density`
overrides and who holds the device lock; for the Mac whether a GUI session is logged in. It
changes nothing on a device: every command is a `dumpsys`, `settings get` or `wm` read, and the
lock is only tried and released at once.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from .android.adb import AdbClient
from .inventory import AndroidDevice, Inventory, resolve_android_device
from .locks import DeviceBusy, device_lock

MDNS_TRANSPORT = re.compile(r"\._adb-tls-connect\._tcp$")
NETWORK_ADDRESS = re.compile(r":\d+$")


def transport_of(serial: str | None) -> str:
    """`usb`, `wifi` (host:port), `mdns` (wireless debugging by service name), `emulator` or `none`."""
    if not serial:
        return "none"
    if serial.startswith("emulator-"):
        return "emulator"
    if MDNS_TRANSPORT.search(serial):
        return "mdns"
    if NETWORK_ADDRESS.search(serial):
        return "wifi"
    return "usb"


def parse_awake(dumpsys_power: str) -> bool | None:
    m = re.search(r"mWakefulness=(\w+)", dumpsys_power)
    return m.group(1) == "Awake" if m else None


def parse_keyguard(dumpsys_window: str) -> bool | None:
    """True when the lock screen is up (`isKeyguardShowing`, or a lockscreen dream)."""
    if "isKeyguardShowing=true" in dumpsys_window or "mDreamingLockscreen=true" in dumpsys_window:
        return True
    return False if "isKeyguardShowing=false" in dumpsys_window else None


def parse_foreground(dumpsys_activity: str) -> str | None:
    m = re.search(r"(?:topResumedActivity|mResumedActivity)[=:]\s*\S+\s+\S+\s+([\w.]+)/", dumpsys_activity)
    return m.group(1) if m else None


def parse_override(wm_output: str, what: str) -> str | None:
    m = re.search(rf"Override {what}:\s*(\S+)", wm_output)
    return m.group(1) if m else None


@dataclass
class DeviceReadiness:
    name: str
    transport: str
    awake: bool | None = None
    keyguard: bool | None = None
    stay_on: str | None = None
    foreground: str | None = None
    size_override: str | None = None
    density_override: str | None = None
    lock_holder: str | None = None
    problems: list[str] = field(default_factory=list)

    @property
    def ready(self) -> bool:
        return not self.problems


def judge(r: DeviceReadiness) -> DeviceReadiness:
    """Fill `problems`: what would stop or spoil a run on this device."""
    r.problems = []
    if r.awake is False:
        r.problems.append("screen is off")
    if r.keyguard:
        r.problems.append("keyguard showing")
    if r.stay_on in (None, "", "0", "null"):
        r.problems.append("stay-on while plugged in is off (the screen may sleep mid-run)")
    if r.lock_holder:
        r.problems.append(f"device lock held by {r.lock_holder}")
    if r.size_override or r.density_override:
        r.problems.append("wm size/density override left from an earlier run")
    return r


def _read(adb: AdbClient, *args: str) -> str:
    try:
        return adb.run(*args)
    except Exception:  # noqa: BLE001 - an unreadable value is shown as unknown, not as a crash
        return ""


def lock_holder(serial: str, lock_dir: Path | None = None) -> str | None:
    """Who holds the device lock (`DeviceBusy` text), None when it is free. The lock is released at once."""
    try:
        with device_lock(serial, "lab-preflight", lock_dir):
            return None
    except DeviceBusy as exc:
        return str(exc).split("held by:", 1)[-1].strip()


def read_android(dev: AndroidDevice, adb: AdbClient, lock_dir: Path | None = None) -> DeviceReadiness:
    r = DeviceReadiness(dev.name, transport_of(adb.serial))
    r.awake = parse_awake(_read(adb, "shell", "dumpsys", "power"))
    r.keyguard = parse_keyguard(_read(adb, "shell", "dumpsys", "window"))
    stay = _read(adb, "shell", "settings", "get", "global", "stay_on_while_plugged_in").strip()
    r.stay_on = stay or None
    r.foreground = parse_foreground(_read(adb, "shell", "dumpsys", "activity", "activities"))
    r.size_override = parse_override(_read(adb, "shell", "wm", "size"), "size")
    r.density_override = parse_override(_read(adb, "shell", "wm", "density"), "density")
    r.lock_holder = lock_holder(adb.serial or dev.serial, lock_dir)
    return judge(r)


def collect(
    inv: Inventory,
    *,
    with_personal: bool = False,
    resolve: Callable[[AndroidDevice], tuple[bool, str | None]] = resolve_android_device,
    make_adb: Callable[[str], AdbClient] = lambda serial: AdbClient(serial),
    mac_check: Callable[[str, str], tuple[bool, str | None]] | None = None,
    lock_dir: Path | None = None,
) -> list[DeviceReadiness]:
    """One row per device (personal ones only with `with_personal`) and one for the Mac."""
    rows: list[DeviceReadiness] = []
    for dev in inv.android:
        if dev.personal and not with_personal:
            continue
        ok, reason = resolve(dev)
        if not ok:
            row = DeviceReadiness(dev.name, "none", problems=[f"not reachable: {reason}"])
        else:
            row = read_android(dev, make_adb(dev.adb_serial or dev.serial), lock_dir)
        rows.append(row)
    if inv.mac and (with_personal or not inv.mac.personal):
        if mac_check is None:
            from .macos.remote import preflight_remote_mac

            mac_check = lambda host, user: preflight_remote_mac(host, user=user)  # noqa: E731
        ok, reason = mac_check(inv.mac.host, inv.mac.user)
        rows.append(DeviceReadiness("mac", "ssh", problems=[] if ok else [f"no GUI session: {reason}"]))
    return rows


def _cell(value: object) -> str:
    return "?" if value is None else ("yes" if value is True else "no" if value is False else str(value) or "-")


def render(rows: list[DeviceReadiness]) -> str:
    head = ["device", "transport", "awake", "keyguard", "stay_on", "foreground", "size/density", "lock", "ready"]
    body = []
    for r in rows:
        over = "/".join(x for x in (r.size_override, r.density_override) if x) or "-"
        body.append([r.name, r.transport, _cell(r.awake), _cell(r.keyguard), _cell(r.stay_on),
                     r.foreground or "-", over, r.lock_holder or "free", "ready" if r.ready else "NOT READY"])
    widths = [max(len(str(x[i])) for x in [head] + body) for i in range(len(head))]
    lines = ["  ".join(str(c).ljust(w) for c, w in zip(row, widths)).rstrip() for row in [head] + body]
    for r in rows:
        for p in r.problems:
            lines.append(f"  {r.name}: {p}")
    return "\n".join(lines)
