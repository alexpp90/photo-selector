"""Device inventory: which lab devices exist, which are reachable, and which a run may use.

The inventory lives in the maintainer-private `private/lab/devices.yaml`. Android devices are
identified by their hardware serial (`ro.serialno`), not by an `adb` address, because wireless
debugging hands out a new TLS port after every reconnect. Resolution order per device:

1. already in `adb devices` (USB, or an earlier wireless connection) — matched by serial;
2. advertised over mDNS as `adb-<serial>-<suffix>` (`adb mdns services`) — connected;
3. a fixed `address:` (`adb tcpip 5555`, Chromebook ARC) — connected.

Devices marked `personal: true` are used only when the run opts in (`--with-personal` or
`--device <name>`), so a default run never takes over a device the maintainer may be holding.
"""

from __future__ import annotations

import re
import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

import yaml

from .paths import repo_root

ANDROID_PRODUCTS = ("android-desktop", "phototok")

# Which Android product a device role runs when the inventory does not say.
ROLE_DEFAULT_PRODUCTS: dict[str, list[str]] = {
    "phone": ["phototok"],
    "tablet": ["android-desktop"],
    "chromebook": ["android-desktop"],
}

# Android configs that need a device feature (see `supports:` in devices.yaml).
CONFIG_FEATURES: dict[str, str] = {
    "dex-on-device": "dex",
    "chromebook-window": "arc",
    "chromebook-large": "arc",
    "chromebook-medium": "arc",
    "chromebook-small": "arc",
}

RunFn = Callable[[list[str], int], subprocess.CompletedProcess[str]]


def _default_run(cmd: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, check=False)
    except subprocess.TimeoutExpired:
        return subprocess.CompletedProcess(cmd, 124, "", f"timeout after {timeout}s")


@dataclass
class AndroidDevice:
    """One Android lab device as declared in `devices.yaml`."""

    name: str
    serial: str
    role: str = "phone"
    model: str = ""
    products: list[str] = field(default_factory=list)
    configs: list[str] | None = None
    personal: bool = False
    address: str | None = None
    host: str | None = None  # IP only: scanned for the wireless-debugging port when mDNS is silent
    lab_user_id: int = 0
    supports: list[str] = field(default_factory=list)  # device features, e.g. "dex"
    adb_serial: str | None = None  # resolved transport id once reachable

    def runs(self, product: str) -> bool:
        return product in self.products

    def allows_config(self, config: str) -> bool:
        needs = CONFIG_FEATURES.get(config)
        if needs and needs not in self.supports:
            return False
        return self.configs is None or config in self.configs

    def label(self, config: str) -> str:
        """Directory/finding label for one config on this device, e.g. `phone-portrait@pixel-4a`."""
        return f"{config}@{self.name}"


@dataclass
class MacHost:
    host: str
    user: str = "pst-lab"
    repo_path: str = ""
    personal: bool = True


@dataclass
class Inventory:
    android: list[AndroidDevice] = field(default_factory=list)
    mac: MacHost | None = None

    def find(self, name: str) -> AndroidDevice | None:
        for dev in self.android:
            if dev.name == name or dev.serial == name:
                return dev
        return None


def default_inventory_path(root: Path | None = None) -> Path:
    return (root or repo_root()) / "private" / "lab" / "devices.yaml"


def parse_inventory(data: dict[str, Any]) -> Inventory:
    """Build an `Inventory` from the parsed YAML mapping."""
    inv = Inventory()
    for name, spec in (data.get("android") or {}).items():
        if not isinstance(spec, dict) or not spec.get("serial"):
            continue
        role = str(spec.get("role", "phone"))
        products = [str(p) for p in spec.get("products") or ROLE_DEFAULT_PRODUCTS.get(role, [])]
        configs = spec.get("configs")
        inv.android.append(
            AndroidDevice(
                name=str(name),
                serial=str(spec["serial"]),
                role=role,
                model=str(spec.get("model", "")),
                products=products,
                configs=[str(c) for c in configs] if configs else None,
                personal=bool(spec.get("personal", False)),
                address=str(spec["address"]) if spec.get("address") else None,
                host=str(spec["host"]) if spec.get("host") else None,
                lab_user_id=int(spec.get("lab_user_id", 0)),
                supports=[str(x) for x in spec.get("supports") or []],
            )
        )
    mac = data.get("mac")
    if isinstance(mac, dict) and mac.get("host"):
        inv.mac = MacHost(
            host=str(mac["host"]),
            user=str(mac.get("user", "pst-lab")),
            repo_path=str(mac.get("repo_path", "")),
            personal=bool(mac.get("personal", True)),
        )
    return inv


def load_inventory(path: Path | None = None) -> Inventory:
    target = path or default_inventory_path()
    if not target.is_file():
        return Inventory()
    data = yaml.safe_load(target.read_text(encoding="utf-8"))
    return parse_inventory(data if isinstance(data, dict) else {})


def select_android_devices(
    inv: Inventory,
    product: str,
    *,
    with_personal: bool = False,
    only: list[str] | None = None,
) -> list[AndroidDevice]:
    """Devices that may run `product` in this run (before reachability is checked)."""
    chosen: list[AndroidDevice] = []
    for dev in inv.android:
        if not dev.runs(product):
            continue
        if only:
            if dev.name not in only and dev.serial not in only:
                continue
        elif dev.personal and not with_personal:
            continue
        chosen.append(dev)
    return chosen


# `_adb-tls-connect._tcp` is Android 11+ wireless debugging (name `adb-<serial>-<suffix>`);
# `_adb._tcp` is classic network adb on port 5555 (ChromeOS ARC; name `adb-<serial>`).
_MDNS_RE = re.compile(
    r"^adb-(?P<serial>[A-Za-z0-9]+)(?:-\S*)?\s+_adb(?:-tls-connect)?\._tcp\.?\s+(?P<addr>\S+)$"
)


def parse_mdns_services(text: str) -> dict[str, str]:
    """Map hardware serial -> `ip:port` from `adb mdns services` output."""
    found: dict[str, str] = {}
    for line in text.splitlines():
        m = _MDNS_RE.match(line.strip())
        if m:
            found[m.group("serial")] = m.group("addr")
    return found


def parse_adb_devices(text: str) -> list[tuple[str, str]]:
    """`(transport, state)` rows of `adb devices`.

    adb separates the fields with a tab; a second mDNS registration of one phone is named
    `adb-<serial>-<suffix> (2)._adb-tls-connect._tcp`, with a space (seen 2026-10-19).
    """
    rows: list[tuple[str, str]] = []
    for line in text.splitlines()[1:]:
        parts = line.strip().split("\t") if "\t" in line else line.strip().split()
        if len(parts) >= 2:
            rows.append((parts[0].strip(), parts[1].split()[0] if parts[1].split() else ""))
    return rows


def _serial_of(transport: str, run: RunFn) -> str:
    res = run(["adb", "-s", transport, "shell", "getprop", "ro.serialno"], 10)
    return (res.stdout or "").strip() if res.returncode == 0 else ""


# Android picks the wireless-debugging TLS port from the ephemeral range.
TLS_PORT_RANGE = "30000-49999"


def scan_adb_tls_ports(host: str, run: RunFn) -> list[int]:
    """Open TCP ports in the wireless-debugging range on `host` (needs `nmap`).

    Some devices (seen: Pixel 4a, Android 13) pair fine but never advertise
    `_adb-tls-connect._tcp`, so the port that changes on every reconnect has to be found.
    """
    import shutil

    if not shutil.which("nmap"):
        return []
    res = run(["nmap", "-Pn", "-n", "-T4", "--open", "-p", TLS_PORT_RANGE, "-oG", "-", host], 120)
    return [int(m) for m in re.findall(r"(\d+)/open/tcp", res.stdout or "")]


def resolve_android_device(dev: AndroidDevice, run: RunFn | None = None) -> tuple[bool, str | None]:
    """Make `dev` reachable over adb and set `dev.adb_serial`. Returns `(ok, reason)`."""
    runner = run or _default_run

    def attached() -> str | None:
        """The device's transport: USB first, then the wireless-debugging mDNS name, then `ip:port`.

        A device connected both ways shows up twice in `adb devices`; Maestro keeps only one
        transport per device (the mDNS one) and rejects the other id as "not connected". USB is
        the steadier link: the Moto G77 was also listed as `adb-ZY22MQCBL6-M2Jjof
        (2)._adb-tls-connect._tcp` (2026-10-19), a row this parser used to misread and skip.
        """
        res = runner(["adb", "devices"], 15)
        matches: list[str] = []
        for transport, state in parse_adb_devices(res.stdout or ""):
            if state != "device":
                continue
            if transport == dev.serial or transport.startswith(f"adb-{dev.serial}-"):
                matches.append(transport)
            elif dev.address and transport == dev.address:
                matches.append(transport)
            elif ":" in transport and _serial_of(transport, runner) == dev.serial:
                matches.append(transport)
        if not matches:
            return None
        return sorted(matches, key=lambda t: (t != dev.serial, not t.startswith("adb-"), t))[0]

    transport = attached()
    if transport is None:
        targets: list[str] = []
        mdns = runner(["adb", "mdns", "services"], 15)
        addr = parse_mdns_services(mdns.stdout or "").get(dev.serial)
        if addr:
            targets.append(addr)
        if dev.address:
            targets.append(dev.address)
        for target in targets:
            runner(["adb", "connect", target], 20)
        if targets:
            transport = attached()
        if transport is None and dev.host:
            for port in scan_adb_tls_ports(dev.host, runner):
                target = f"{dev.host}:{port}"
                targets.append(target)
                runner(["adb", "connect", target], 20)
                transport = attached()
                if transport is not None:
                    break
        if transport is None:
            if not targets:
                where = f"no open port on {dev.host}" if dev.host else "no fixed address"
                return False, f"{dev.name} not attached, not advertised over mDNS, {where}"
            return False, f"{dev.name} advertised at {', '.join(targets)} but adb connect failed (pair this host?)"

    unauthorized = runner(["adb", "-s", transport, "get-state"], 10)
    if unauthorized.returncode != 0:
        return False, f"{dev.name} at {transport}: {(unauthorized.stderr or '').strip() or 'not in device state'}"
    dev.adb_serial = transport
    return True, None


_PAIRING_RE = re.compile(r"^(?P<name>\S+)\s+_adb-tls-pairing\._tcp\.?\s+(?P<addr>\S+)$")


def parse_pairing_services(text: str) -> list[tuple[str, str]]:
    """`(service name, ip:port)` for every device currently showing a pairing code."""
    return [
        (m.group("name"), m.group("addr"))
        for m in (_PAIRING_RE.match(line.strip()) for line in text.splitlines())
        if m
    ]


def pair_advertised_device(
    code: str,
    *,
    address: str | None = None,
    serial: str | None = None,
    run: RunFn | None = None,
    wait_s: float = 30.0,
) -> tuple[bool, str]:
    """Pair this host with the device showing `code` under Wireless debugging -> pairing code.

    The device advertises `_adb-tls-pairing._tcp` only while that dialog is open, so the
    address is discovered here; pass `address` when mDNS does not reach the device, and
    `serial` when several devices show a pairing dialog at once.
    """
    import time

    runner = run or _default_run
    deadline = time.monotonic() + wait_s
    targets: list[str] = [address] if address else []
    while not targets and time.monotonic() < deadline:
        res = runner(["adb", "mdns", "services"], 15)
        targets = [
            addr
            for name, addr in parse_pairing_services(res.stdout or "")
            if serial is None or name.startswith(f"adb-{serial}-")
        ]
        if not targets:
            time.sleep(2)
    if not targets:
        return False, "no device is showing a pairing code (Wireless debugging -> Pair device with pairing code)"
    out: list[str] = []
    for target in targets:
        res = runner(["adb", "pair", target, code], 30)
        text = ((res.stdout or "") + (res.stderr or "")).strip()
        out.append(f"{target}: {text}")
        if res.returncode == 0 and "Successfully paired" in text:
            return True, text
    return False, "; ".join(out)
