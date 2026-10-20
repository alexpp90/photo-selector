#!/usr/bin/env python3
"""Run the Android instrumented suites on every attached device at once, and report.

``./gradlew connectedDebugAndroidTest`` works one device at a time from ``run_tests.sh``, prints
little while it runs, and leaves the next session to rediscover what failed. This runner builds
the debug and androidTest APKs once, then drives every device in its own thread — lock, keep
awake, install, ``am instrument -r`` for both products, retry failures once, restore, uninstall —
so a fleet of phones, tablets, a Chromebook and an emulator finishes in the time of the slowest
one, before any of them auto-locks. While it runs it prints each device's phase and every
failure as it happens (``progress.log`` in the run directory has every test event).

Each run writes ``<git-common-dir>/pst-device-runs/<run-id>/``: ``report.md`` (timing per
device, module and test; failures grouped by test across devices; the previous run's issues
re-validated), ``results.json``, per-device instrumentation output and logcat for failures.
``issues.json`` next to the runs is the open-issue ledger: the next run — in any worktree, any
session — validates every open issue and reports it fixed, still failing or not verified.

Devices: every attached device by default, one per physical device (``ro.serialno``; a phone on
USB and on wireless debugging counts once, USB preferred). ``--configured`` limits the run to
the test devices named in ``products/android/local.properties`` (what ``run_tests.sh
--android-device`` uses); ``--serial`` picks devices explicitly. A designated test device (and
an emulator) gets ``android_test_device.py prepare``'s persistent setup; any other device gets
animations off, a maximal screen-off timeout and stay-awake for the run only, restored after.
An app the device already has under the same package id (``com.photoselectortoolbox``,
``com.phototok``) is never removed: a compatible one is updated in place and left installed; one
signed differently (a release build) is left alone and that product is not tested there, unless
``--replace-installed`` replaces it (wiping its data). Packages the run installed are uninstalled
afterwards, like Gradle does.

Subcommands::

    python3 scripts/android_device_matrix.py devices            # what a run would cover, lock state
    python3 scripts/android_device_matrix.py run                # full run on every attached device
    python3 scripts/android_device_matrix.py run --only-open-issues   # re-run just the open issues
    python3 scripts/android_device_matrix.py report             # print the latest report
    python3 scripts/android_device_matrix.py issues [--brief]   # the open-issue ledger
"""

from __future__ import annotations

import argparse
import json
import os
import queue
import re
import subprocess
import sys
import threading
import time
from dataclasses import asdict, dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Callable, Dict, Iterable, List, Optional, Sequence, Set, Tuple

SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import android_test_device as atd  # noqa: E402

REPO_ROOT = SCRIPTS_DIR.parent
ANDROID_DIR = REPO_ROOT / "products" / "android"
RUNS_DIRNAME = "pst-device-runs"
LEDGER_NAME = "issues.json"
RESTORE_NAME = "restore-pending.json"
SCHEMA = 1
RESOLVED_KEEP = 100
MAX_RESUMES = 5

Runner = Callable[[Sequence[str]], Tuple[int, str]]


@dataclass(frozen=True)
class Module:
    name: str     # Gradle module, also the directory under products/android/
    target: str   # applicationId of the debug build — the instrumentation's target package

    @property
    def test_package(self) -> str:
        return self.target + ".test"

    def apks(self, root: Path = ANDROID_DIR) -> Tuple[Optional[Path], Optional[Path]]:
        out = root / self.name / "build" / "outputs" / "apk"
        app = sorted((out / "debug").glob("*-debug.apk"))
        test = sorted((out / "androidTest" / "debug").glob("*-debug-androidTest.apk"))
        return (app[0] if app else None), (test[0] if test else None)


# applicationId per module; test_android_device_matrix.py checks it against build.gradle.kts.
MODULES: Dict[str, Module] = {
    "android-desktop": Module("android-desktop", "com.photoselectortoolbox"),
    "phototok": Module("phototok", "com.phototok"),
}


def run(cmd: Sequence[str], timeout: int = 120) -> Tuple[int, str]:
    return atd.run(cmd, timeout)


def fmt_secs(seconds: float) -> str:
    seconds = max(0.0, seconds)
    if seconds < 60:
        return f"{seconds:.1f}s"
    minutes, secs = divmod(int(round(seconds)), 60)
    if minutes < 60:
        return f"{minutes}m{secs:02d}s"
    hours, minutes = divmod(minutes, 60)
    return f"{hours}h{minutes:02d}m"


def runs_root() -> Path:
    """``<git-common-dir>/pst-device-runs`` — shared by every worktree, never tracked."""
    override = os.environ.get("PST_DEVICE_RUNS")
    if override:
        return Path(override)
    code, out = run(["git", "-C", str(REPO_ROOT), "rev-parse", "--git-common-dir"])
    common = Path(out.strip()) if code == 0 and out.strip() else Path(".git")
    if not common.is_absolute():
        common = REPO_ROOT / common
    return common.resolve() / RUNS_DIRNAME


# ── Devices ───────────────────────────────────────────────────────────────────

ADB_STATES = ("device", "offline", "unauthorized", "authorizing", "connecting", "recovery",
              "sideload", "bootloader", "no permissions", "unknown", "host", "rescue")
_DEVICE_LINE = re.compile(r"^(?P<serial>.+?)\s+(?P<state>" + "|".join(ADB_STATES) + r")(?:\s+(?P<rest>.*))?$")
TRANSPORT_RANK = {"usb": 0, "emulator": 1, "tcp": 2, "mdns": 3}


@dataclass
class Transport:
    serial: str
    kind: str    # usb | emulator | tcp | mdns
    model: str


def parse_transports(long_listing: str) -> List[Transport]:
    """Usable transports from ``adb devices -l``. An mDNS serial may contain a space."""
    found = []
    for line in long_listing.splitlines()[1:]:
        match = _DEVICE_LINE.match(line.strip())
        if not match or match.group("state") != "device":
            continue
        serial, fields = match.group("serial"), (match.group("rest") or "").split()
        attrs = dict(f.split(":", 1) for f in fields if ":" in f)
        if any(f.startswith("usb:") for f in fields):
            kind = "usb"
        elif serial.startswith("emulator-"):
            kind = "emulator"
        elif "._adb-tls-connect._tcp" in serial or "._adb._tcp" in serial:
            kind = "mdns"
        else:
            kind = "tcp"
        found.append(Transport(serial, kind, attrs.get("model", "unknown")))
    return found


@dataclass
class Device:
    serial: str                  # the adb serial this run uses
    hw_serial: str               # ro.serialno — the physical device's identity across transports
    model: str
    kind: str
    other_serials: List[str] = field(default_factory=list)
    label: str = ""
    manufacturer: str = ""
    android: str = ""
    sdk: str = ""
    abi: str = ""
    screen: str = ""
    density: str = ""
    designated: bool = False
    personal: bool = False


def dedupe(transports: Iterable[Transport], hw_of: Callable[[str], str]) -> List[Device]:
    """One Device per physical device, on its best transport (USB, emulator, IP, mDNS)."""
    groups: Dict[str, List[Transport]] = {}
    for t in transports:
        groups.setdefault(hw_of(t.serial) or t.serial, []).append(t)
    devices = []
    for hw, members in groups.items():
        members.sort(key=lambda t: (TRANSPORT_RANK[t.kind], t.serial))
        best = members[0]
        devices.append(Device(best.serial, hw, best.model, best.kind, [m.serial for m in members[1:]]))
    devices.sort(key=lambda d: (d.model.lower(), d.hw_serial))
    assign_labels(devices)
    return devices


def assign_labels(devices: List[Device]) -> None:
    counts: Dict[str, int] = {}
    for d in devices:
        counts[d.model] = counts.get(d.model, 0) + 1
    for d in devices:
        base = d.model.replace("_", " ")
        d.label = base if counts[d.model] == 1 else f"{base} #{d.hw_serial[-4:]}"


FACT_PROPS = ("ro.serialno", "ro.product.manufacturer", "ro.build.version.release",
              "ro.build.version.sdk", "ro.product.cpu.abi")


def device_facts(serial: str, runner: Runner = run) -> Dict[str, str]:
    script = "; ".join(f"echo {p}=$(getprop {p})" for p in FACT_PROPS)
    script += "; echo size=$(wm size | tail -1); echo density=$(wm density | tail -1)"
    code, out = runner(["adb", "-s", serial, "shell", script])
    facts: Dict[str, str] = {}
    if code == 0:
        for line in out.splitlines():
            if "=" in line:
                key, value = line.split("=", 1)
                facts[key.strip()] = value.strip().split(": ")[-1]
    return facts


def registry_path() -> Path:
    return Path(os.environ.get("PST_DEVICE_REGISTRY") or REPO_ROOT / "private" / "lab" / "devices.yaml")


def read_registry(text: str) -> Dict[str, Tuple[str, bool]]:
    """hardware serial -> (name, personal) from the lab inventory's ``android:`` section.

    The subset ``private/lab/devices.yaml`` uses (two-space device keys, four-space fields), read
    without PyYAML so the runner needs nothing beyond the standard library."""
    devices: Dict[str, Tuple[str, bool]] = {}
    section, fields = "", {}  # type: ignore[var-annotated]

    def flush() -> None:
        if fields.get("serial"):
            devices[fields["serial"]] = (fields.get("model", fields["serial"]), fields.get("personal") == "true")

    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        indent = len(line) - len(line.lstrip())
        key, _, value = line.strip().partition(":")
        value = value.split(" #")[0].strip().strip('"').strip("'")
        if indent == 0:
            flush()
            section, fields = key, {}
        elif section == "android" and indent == 2:
            flush()
            fields = {}
        elif section == "android" and indent == 4:
            fields[key] = value
    flush()
    return devices


def designated_serials(props: Dict[str, str], env: Dict[str, str]) -> Set[str]:
    named = {env.get("ANDROID_SERIAL", "").strip(), props.get(atd.SERIAL_PROPERTY, "").strip()}
    named.update(s.strip() for s in props.get(atd.EXTRA_SERIALS_PROPERTY, "").split(","))
    return {s for s in named if s}


def discover(runner: Runner = run, only: Optional[Sequence[str]] = None, configured: bool = False,
             no_personal: bool = False) -> Tuple[List[Device], str]:
    """Return ``(devices, note)``. ``note`` explains an empty or narrowed selection."""
    code, listing = runner(["adb", "devices", "-l"])
    if code != 0:
        return [], f"adb devices failed: {listing}"
    facts: Dict[str, Dict[str, str]] = {}

    def hw_of(serial: str) -> str:
        facts[serial] = device_facts(serial, runner)
        return facts[serial].get("ro.serialno", "")

    devices = dedupe(parse_transports(listing), hw_of)
    try:
        registry = read_registry(registry_path().read_text(encoding="utf-8"))
    except OSError:
        registry = {}
    for d in devices:
        if d.hw_serial in registry:
            d.model, d.personal = registry[d.hw_serial]
    assign_labels(devices)
    props = atd.read_properties(atd.LOCAL_PROPERTIES)
    named = designated_serials(props, dict(os.environ))
    for d in devices:
        f = facts.get(d.serial, {})
        d.manufacturer = f.get("ro.product.manufacturer", "")
        d.android = f.get("ro.build.version.release", "")
        d.sdk = f.get("ro.build.version.sdk", "")
        d.abi = f.get("ro.product.cpu.abi", "")
        d.screen = f.get("size", "")
        d.density = f.get("density", "")
        d.designated = bool({d.serial, d.hw_serial, *d.other_serials} & named)
    note = ""
    if only:
        wanted = set(only)
        devices = [d for d in devices if {d.serial, d.hw_serial, *d.other_serials} & wanted]
        note = "devices named by --serial"
    elif configured:
        named_devices = [d for d in devices if d.designated]
        if named_devices or len(devices) <= 1:
            devices, note = named_devices or devices, "configured test devices (local.properties)"
        else:
            return [], (f"{len(devices)} devices attached and none designated — set {atd.SERIAL_PROPERTY} "
                        f"(and {atd.EXTRA_SERIALS_PROPERTY}) in products/android/local.properties")
    if no_personal:
        dropped = [d.label for d in devices if d.personal]
        devices = [d for d in devices if not d.personal]
        if dropped:
            note = (note + "; " if note else "") + f"personal devices left out: {', '.join(dropped)}"
    if not devices:
        note = (note + ": none attached") if note else "no device or emulator attached"
    return devices, note


# ── Instrumentation output ────────────────────────────────────────────────────

STATUS_START, STATUS_OK, STATUS_ERROR, STATUS_FAILURE, STATUS_IGNORED, STATUS_ASSUMPTION = 1, 0, -1, -2, -3, -4
COMPOSE_NO_HIERARCHY = "No compose hierarchies found"


@dataclass
class TestResult:
    module: str
    cls: str
    name: str
    status: str            # passed | failed | skipped
    seconds: float = 0.0
    message: str = ""
    stack: str = ""
    retry: str = ""        # "" (not retried) | passed | failed

    @property
    def key(self) -> str:
        return f"{self.module}:{self.cls}#{self.name}"

    @property
    def flaky(self) -> bool:
        return self.status == "failed" and self.retry == "passed"


def first_line(text: str, limit: int = 300) -> str:
    """The first non-empty line; a line ending in ':' ("There was 1 accessibility result:")
    takes the next one with it, since that is where the explanation is."""
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if not lines:
        return ""
    head = lines[0]
    if head.endswith(":") and len(lines) > 1 and not lines[1].startswith("at "):
        head = f"{head} {lines[1]}"
    return head[:limit]


class InstrumentationParser:
    """Turns ``am instrument -r`` raw output into start events and TestResults, line by line."""

    def __init__(self, module: str, clock: Callable[[], float] = time.monotonic):
        self.module = module
        self.clock = clock
        self.results: List[TestResult] = []
        self.numtests: Optional[int] = None
        self.current: Optional[Tuple[str, str, float]] = None
        self.finished = False
        self.failure = ""         # why instrumentation itself failed or ended early
        self._status: Dict[str, str] = {}
        self._result: Dict[str, str] = {}
        self._target: Optional[Dict[str, str]] = None
        self._key: Optional[str] = None

    def feed(self, raw: str) -> Optional[object]:
        line = raw.rstrip("\r\n")
        for prefix, target in (("INSTRUMENTATION_STATUS: ", self._status), ("INSTRUMENTATION_RESULT: ", self._result)):
            if line.startswith(prefix):
                key, _, value = line[len(prefix):].partition("=")
                target[key] = value
                self._target, self._key = target, key
                return None
        if line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = _int(line.split(": ", 1)[1])
            status, self._status = self._status, {}
            self._target = None
            return self._on_status(code, status)
        if line.startswith("INSTRUMENTATION_CODE: "):
            self._target = None
            self.finished = True
            short = self._result.get("shortMsg", "") or self._result.get("longMsg", "")
            if short and not self.failure:
                self.failure = first_line(short)
            return None
        if line.startswith(("INSTRUMENTATION_FAILED: ", "INSTRUMENTATION_ABORTED: ")) or "commandError=true" in line:
            self.failure = self.failure or line.strip()[:300]
            return None
        if self._target is not None and self._key is not None:
            self._target[self._key] += "\n" + line
        return None

    def _on_status(self, code: Optional[int], status: Dict[str, str]) -> Optional[object]:
        if "numtests" in status and self.numtests is None:
            self.numtests = _int(status["numtests"])
        cls, name = status.get("class", ""), status.get("test", "")
        if code == STATUS_START:
            self.current = (cls, name, self.clock())
            return ("start", cls, name, _int(status.get("current", "")), self.numtests)
        if code is None or not cls:
            if status.get("Error"):
                self.failure = self.failure or first_line(status["Error"])
            return None
        seconds = 0.0
        if self.current and self.current[:2] == (cls, name):
            seconds = self.clock() - self.current[2]
            self.current = None
        stack = status.get("stack", "")
        state = {STATUS_OK: "passed", STATUS_ERROR: "failed", STATUS_FAILURE: "failed"}.get(code, "skipped")
        result = TestResult(self.module, cls, name, state, round(seconds, 2), first_line(stack), stack[:6000])
        self.results.append(result)
        return result

    def close(self, why: str) -> Optional[TestResult]:
        """End the stream; a test still running when it ends is a failure for ``why``."""
        reason = why or self.failure or "instrumentation ended before the test finished"
        if not self.finished and not self.failure:
            self.failure = reason
        if not self.current:
            return None
        cls, name, started = self.current
        self.current = None
        result = TestResult(self.module, cls, name, "failed", round(self.clock() - started, 2),
                            reason, reason)
        self.results.append(result)
        return result


def _int(text: str) -> Optional[int]:
    try:
        return int(text.strip())
    except (ValueError, AttributeError):
        return None


def instrumentation_component(listing: str, target: str) -> Optional[str]:
    """``pkg/runner`` from ``pm list instrumentation`` for the instrumentation targeting ``target``."""
    for line in listing.splitlines():
        match = re.match(r"instrumentation:(\S+)\s+\(target=([^)]+)\)", line.strip())
        if match and match.group(2) == target:
            return match.group(1)
    return None


def exclusion_for_resume(results: Sequence[TestResult], current_cls: str) -> List[str]:
    """``notClass`` entries that skip what already ran: whole classes the runner moved past,
    single methods of the class it was in when the stream ended."""
    finished_classes: List[str] = []
    for r in results:
        if r.cls != current_cls and r.cls not in finished_classes:
            finished_classes.append(r.cls)
    methods = [f"{r.cls}#{r.name}" for r in results if r.cls == current_cls]
    return finished_classes + list(dict.fromkeys(methods))


# ── Progress ──────────────────────────────────────────────────────────────────


class Progress:
    """Thread-safe live state: phase per device, a timestamped event log, the board."""

    def __init__(self, log_path: Path, started: float, echo: Callable[[str], None] = print):
        self.lock = threading.Lock()
        self.started = started
        self.echo = echo
        self.log_path = log_path
        self.phase: Dict[str, str] = {}
        self.failed: Dict[str, int] = {}

    def _write(self, text: str, show: bool) -> None:
        stamp = f"[{fmt_secs(time.monotonic() - self.started):>7}]"
        with self.lock:
            with self.log_path.open("a", encoding="utf-8") as fh:
                fh.write(f"{datetime.now():%H:%M:%S} {text}\n")
            if show:
                self.echo(f"{stamp} {text}")

    def set_phase(self, label: str, phase: str, show: bool = True) -> None:
        """``show=False`` for the per-test counter: the board and the log carry it."""
        with self.lock:
            changed = self.phase.get(label) != phase
            self.phase[label] = phase
        if changed and show:
            self._write(f"{label}: {phase}", show=True)

    def event(self, label: str, text: str, show: bool = False) -> None:
        self._write(f"{label}: {text}", show)

    def tally(self, label: str, failed: int) -> None:
        with self.lock:
            self.failed[label] = failed

    def board(self) -> str:
        with self.lock:
            return " · ".join(f"{label}: {phase}" + (f" ✗{self.failed[label]}" if self.failed.get(label) else "")
                              for label, phase in self.phase.items())


# ── One device ────────────────────────────────────────────────────────────────

TEMP_SETTINGS = (
    ("global", "window_animation_scale", "0"),
    ("global", "transition_animation_scale", "0"),
    ("global", "animator_duration_scale", "0"),
    ("global", "stay_on_while_plugged_in", "7"),
    ("system", "screen_off_timeout", "2147483647"),
)
KEPT_EXISTING = "kept-existing"
DISCONNECTED = "device disconnected (adb lost it)"
REPLACE_MARKERS = ("INSTALL_FAILED_UPDATE_INCOMPATIBLE", "signatures do not match", "INSTALL_FAILED_VERSION_DOWNGRADE",
                   "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE")


@dataclass
class ModuleRun:
    module: str
    seconds: float = 0.0
    planned: Optional[int] = None
    results: List[TestResult] = field(default_factory=list)
    problems: List[str] = field(default_factory=list)   # crash, hang, resume notes
    log: str = ""


@dataclass
class DeviceRun:
    device: Device
    status: str = "pending"      # ran | skipped | failed-setup
    reason: str = ""
    seconds: float = 0.0
    setup_seconds: float = 0.0
    install_seconds: float = 0.0
    modules: List[ModuleRun] = field(default_factory=list)
    notes: List[str] = field(default_factory=list)
    install_error: str = ""

    def results(self) -> List[TestResult]:
        return [r for m in self.modules for r in m.results]


@dataclass
class Options:
    modules: List[Module]
    only: Dict[str, List[str]]          # module -> "cls#name" filter (empty: everything)
    retries: int = 1
    test_timeout: float = 600.0
    lock_timeout: float = 300.0
    unlock_wait: float = 120.0
    keep_installed: bool = False
    replace_installed: bool = False


class DeviceWorker:
    def __init__(self, device: Device, opts: Options, run_dir: Path, progress: Progress,
                 build_done: threading.Event, build_ok: Callable[[], bool], stop: threading.Event,
                 restore: "RestoreBook", runner: Runner = run):
        self.d = device
        self.opts = opts
        self.dir = run_dir / safe_name(device.label)
        self.progress = progress
        self.build_done = build_done
        self.build_ok = build_ok
        self.stop = stop
        self.restore = restore
        self.runner = runner
        self.result = DeviceRun(device)
        self.installed: List[str] = []   # packages absent before this run — the only ones it removes
        self.modules: List[Module] = []   # products installed and tested on this device

    # adb helpers
    def adb(self, *args: str) -> Tuple[int, str]:
        return self.runner(["adb", "-s", self.d.serial, *args])

    def phase(self, text: str, show: bool = True) -> None:
        self.progress.set_phase(self.d.label, text, show)

    def run_device(self) -> DeviceRun:
        started = time.monotonic()
        self.dir.mkdir(parents=True, exist_ok=True)
        try:
            self._locked_run()
        except Exception as exc:  # a worker must never take the whole run down
            self.result.status = "failed-setup"
            self.result.reason = f"runner error: {exc!r}"
            self.phase(f"error — {exc!r}")
        self.result.seconds = round(time.monotonic() - started, 1)
        return self.result

    def _locked_run(self) -> None:
        atd._use_lab_package()
        from lab.usability.driver import DeviceBusy, device_lock

        deadline = time.monotonic() + self.opts.lock_timeout
        while True:
            try:
                with device_lock(self.d.serial, f"device-matrix {REPO_ROOT.name}"):
                    self._run_holding_lock()
                    return
            except DeviceBusy as exc:
                if time.monotonic() >= deadline or self.stop.is_set():
                    self._skip(f"busy — {exc}")
                    return
                self.phase("waiting for the device lock")
                time.sleep(3)

    def _skip(self, reason: str) -> None:
        self.result.status = "skipped"
        self.result.reason = reason
        self.phase(f"skipped — {reason}")

    def _run_holding_lock(self) -> None:
        t0 = time.monotonic()
        self.phase("setting up")
        try:
            if self.d.designated or self.d.kind == "emulator":
                failed_steps = self._prepare()
                self.result.notes.append("prepared as a test device"
                                         + (f" (failed: {', '.join(failed_steps)})" if failed_steps else ""))
            else:
                self._apply_temp_settings()
            self.adb("shell", "input keyevent KEYCODE_WAKEUP && wm dismiss-keyguard")
            self.result.setup_seconds = round(time.monotonic() - t0, 1)
            if not self._wait_build():
                return
            if not self._wait_unlocked():
                return
            if not self._install():
                return
            self.result.status = "ran"
            for module in self.modules:
                if self.stop.is_set():
                    self.result.notes.append("interrupted")
                    break
                if not self._wait_unlocked():
                    self.result.notes.append(f"{module.name} not run: device locked")
                    break
                self.result.modules.append(self._run_module(module))
            self.phase(self._done_text())
        finally:
            self._cleanup()

    def _prepare(self) -> List[str]:
        """Apply android_test_device.py's PREPARE_STEPS; return the names of the steps that failed."""
        root = atd.ensure_root(self.d.serial, self.runner)
        failed = []
        for name, command in atd.PREPARE_STEPS:
            code, out = self.adb("shell", command)
            ok = code == 0 and "Exception" not in out and "Error" not in out
            if not ok:
                failed.append(name)
            self.progress.event(self.d.label, f"prepare {'✓' if ok else '✗'} {name}")
        self.progress.event(self.d.label, f"adb {'root' if root else 'shell (no root)'}")
        return failed

    def _apply_temp_settings(self) -> None:
        saved: Dict[str, str] = {}
        for ns, key, _ in TEMP_SETTINGS:
            code, old = self.adb("shell", f"settings get {ns} {key}")
            if code == 0:
                saved[f"{ns}/{key}"] = old.strip()
        self.restore.save(self.d.serial, saved)   # on disk before anything changes
        self.progress.event(self.d.label, "saved settings " + ", ".join(f"{k}={v}" for k, v in saved.items()))
        for ns, key, value in TEMP_SETTINGS:
            if f"{ns}/{key}" in saved:
                self.adb("shell", f"settings put {ns} {key} {value}")
        self.result.notes.append("animations off, stay awake and screen timeout set for the run (restored after)")

    def _restore_temp_settings(self) -> None:
        saved = self.restore.pop(self.d.serial)
        if saved:
            ok = restore_settings(self.d.serial, saved, self.runner)
            self.progress.event(self.d.label, f"restored settings {'✓' if ok else '✗ (some failed)'} "
                                + ", ".join(f"{k}={v}" for k, v in saved.items()))
            if not ok:
                self.result.notes.append("settings restore failed — see progress.log")

    def _wait_build(self) -> bool:
        if not self.build_done.is_set():
            self.phase("awake, waiting for the build")
        while not self.build_done.wait(1):
            if self.stop.is_set():
                self._skip("interrupted")
                return False
        if not self.build_ok():
            self._skip("build failed")
            return False
        return True

    def _wait_unlocked(self) -> bool:
        if atd.keyguard_showing(self.d.serial, self.runner) is not True:
            return True
        self.phase(f"LOCKED — unlock it (waiting {fmt_secs(self.opts.unlock_wait)})")
        deadline = time.monotonic() + self.opts.unlock_wait
        while time.monotonic() < deadline and not self.stop.is_set():
            time.sleep(3)
            self.adb("shell", "input keyevent KEYCODE_WAKEUP")
            if atd.keyguard_showing(self.d.serial, self.runner) is not True:
                return True
        if not self.result.modules:
            self._skip("lock screen showing — unlock it, or remove its screen lock")
        return False

    def _install(self) -> bool:
        """Install each product's app and test APK; a product whose package is already installed
        with another signature is left alone (unless --replace-installed) and not tested here."""
        t0 = time.monotonic()
        self.phase("installing")
        for module in self.opts.modules:
            app, test = module.apks()
            for apk, package in ((app, module.target), (test, module.test_package)):
                if apk is None:
                    return self._install_failed(f"{module.name}: APK missing (build it, or drop --no-build)")
                error = self._install_one(apk, package)
                if error == KEPT_EXISTING:
                    self.result.notes.append(
                        f"{module.name} not tested: this device has its own {package} "
                        f"{self._version(package)} (another signature) — left untouched; "
                        f"--replace-installed replaces it, wiping its data")
                    break
                if error:
                    return self._install_failed(f"{apk.name}: {error}")
            else:
                self.modules.append(module)
        self.result.install_seconds = round(time.monotonic() - t0, 1)
        if not self.modules:
            self._skip("every product's app is installed here with another signature (--replace-installed)")
            return False
        return True

    def _install_failed(self, error: str) -> bool:
        self.result.status = "failed-setup"
        self.result.install_error = error
        self.result.reason = f"install failed — {error}"
        self.phase(f"INSTALL FAILED — {error}")
        return False

    def _present(self, package: str) -> bool:
        code, out = self.adb("shell", f"pm path {package}")
        return code == 0 and "package:" in out

    def _version(self, package: str) -> str:
        _, out = self.adb("shell", f"dumpsys package {package}")
        match = re.search(r"versionName=(\S+)", out)
        return match.group(1) if match else "(unknown version)"

    def _install_one(self, apk: Path, package: str) -> str:
        """"" when installed; KEPT_EXISTING when a differently signed install was left alone;
        otherwise the install error. Only a package that was absent before is recorded for the
        uninstall at the end — an app the device already had is never removed by the run."""
        existed = self._present(package)
        code, out = self.runner(["adb", "-s", self.d.serial, "install", "-r", "-t", str(apk)], 900)  # type: ignore[call-arg]
        if code == 0 and "Success" in out:
            if existed:
                self.result.notes.append(f"updated the existing {package} in place and left it installed")
            else:
                self.installed.append(package)
            return ""
        if any(marker in out for marker in REPLACE_MARKERS):
            if not self.opts.replace_installed:
                return KEPT_EXISTING
            version = self._version(package)
            self.adb("uninstall", package)
            self.result.notes.append(f"--replace-installed: removed this device's {package} {version} and its data; "
                                     f"reinstall it after the run")
            code, out = self.runner(["adb", "-s", self.d.serial, "install", "-r", "-t", str(apk)], 900)  # type: ignore[call-arg]
            if code == 0 and "Success" in out:
                self.installed.append(package)
                return ""
        return first_line(out.splitlines()[-1] if out else f"exit {code}")

    def _component(self, module: Module) -> Optional[str]:
        code, listing = self.adb("shell", "pm list instrumentation")
        return instrumentation_component(listing, module.target) if code == 0 else None

    def _run_module(self, module: Module) -> ModuleRun:
        t0 = time.monotonic()
        mrun = ModuleRun(module.name, log=str((self.dir / f"instrument-{module.name}.txt").relative_to(self.dir.parent)))
        component = self._component(module)
        if not component:
            mrun.problems.append("no instrumentation registered after install")
            return mrun
        only = self.opts.only.get(module.name)
        if self.opts.only and not only:
            return mrun  # filtered run with nothing to validate in this module
        extra: List[str] = ["-e", "class", ",".join(only)] if only else []
        log_path = self.dir / f"instrument-{module.name}.txt"
        resumes = 0
        while True:
            parser, inflight_cls = self._instrument(module, component, extra, log_path, mrun)
            if mrun.planned is None:
                mrun.planned = parser.numtests
            if parser.finished and not parser.failure:
                break
            problem = parser.failure or "instrumentation ended early"
            mrun.problems.append(problem)
            self.progress.event(self.d.label, f"{module.name}: {problem}", show=True)
            if self.stop.is_set() or resumes >= MAX_RESUMES:
                break
            if problem == DISCONNECTED:
                if not self._wait_reconnect():
                    mrun.problems.append("device did not come back within 2 min — the rest of this suite did not run")
                    break
            elif not parser.results:
                break  # it died before any test: resuming would repeat the same failure
            resumes += 1
            self.adb("shell", f"am force-stop {module.target}")
            if only:
                done = {f"{r.cls}#{r.name}" for r in mrun.results}
                remaining = [t for t in only if t not in done]
                if not remaining:
                    break
                extra = ["-e", "class", ",".join(remaining)]
            else:
                current = inflight_cls or (mrun.results[-1].cls if mrun.results else "")
                extra = ["-e", "notClass", ",".join(exclusion_for_resume(mrun.results, current))]
            mrun.problems.append(f"resumed (pass {resumes + 1}), skipping what already ran")
        self._retry_failures(module, component, mrun, log_path)
        mrun.seconds = round(time.monotonic() - t0, 1)
        if any(r.status == "failed" for r in mrun.results):
            self._capture_logcat(module)
        return mrun

    def _connected(self) -> bool:
        code, out = self.adb("get-state")
        return code == 0 and out.strip() == "device"

    def _wait_reconnect(self, timeout: float = 120) -> bool:
        self.phase(f"DISCONNECTED — waiting up to {fmt_secs(timeout)} for it to come back")
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline and not self.stop.is_set():
            if self._connected():
                self.phase("reconnected, resuming")
                return True
            time.sleep(3)
        return False

    def _instrument(self, module: Module, component: str, extra: List[str], log_path: Path,
                    mrun: ModuleRun, retry: bool = False) -> Tuple[InstrumentationParser, str]:
        """One ``am instrument`` pass; returns the parser and the class of a test that was still
        running when the stream ended ("" if none). A test cut off because adb lost the device is
        not a result: the run resumes with it once the device is back."""
        parser = InstrumentationParser(module.name)
        cmd = ["adb", "-s", self.d.serial, "shell", "am", "instrument", "-w", "-r", *extra, component]
        base_done = len(mrun.results)
        total_hint = mrun.planned

        def on_line(line: str) -> None:
            log.write(line + "\n")
            event = parser.feed(line)
            if isinstance(event, tuple) and not retry:
                _, cls, name, current, numtests = event
                total = (total_hint or numtests or 0)
                self.phase(f"{module.name} {base_done + (current or 0)}/{total}", show=False)
                self.progress.event(self.d.label, f"start {cls}#{name}")
            elif isinstance(event, TestResult):
                self._report(event, retry)
                if not retry:
                    mrun.results.append(event)
                    self._tally(mrun)

        with log_path.open("a", encoding="utf-8") as log:
            log.write(f"$ {' '.join(cmd)}\n")
            why = stream_command(cmd, on_line, self.opts.test_timeout, self.stop)
        inflight = ""
        if why or not parser.finished:
            if not why and not self._connected():
                why = parser.failure = DISCONNECTED
            inflight = parser.current[0] if parser.current else ""
            hung = parser.close(why)
            if hung is not None and why == DISCONNECTED:
                parser.results.remove(hung)
                self.progress.event(self.d.label, f"{hung.key} cut off — {DISCONNECTED}", show=True)
            elif hung is not None:
                self._report(hung, retry)
                if not retry:
                    mrun.results.append(hung)
            if why and why != DISCONNECTED:
                self.adb("shell", f"am force-stop {module.target}")
        return parser, inflight

    def _retry_failures(self, module: Module, component: str, mrun: ModuleRun, log_path: Path) -> None:
        failed = [r for r in mrun.results if r.status == "failed"]
        if not failed or self.opts.retries < 1 or self.stop.is_set():
            return
        self.phase(f"{module.name} retrying {len(failed)} failure(s)")
        names = list(dict.fromkeys(f"{r.cls}#{r.name}" for r in failed))
        parser, _ = self._instrument(module, component, ["-e", "class", ",".join(names)], log_path, mrun, retry=True)
        outcome = {f"{r.cls}#{r.name}": r.status for r in parser.results}
        for r in failed:
            r.retry = "passed" if outcome.get(f"{r.cls}#{r.name}") == "passed" else "failed"
        self._tally(mrun)

    def _report(self, result: TestResult, retry: bool) -> None:
        tag = "retry " if retry else ""
        text = f"{tag}{result.status} {result.key} ({fmt_secs(result.seconds)})"
        if result.status == "failed":
            text += f" — {result.message}"
        self.progress.event(self.d.label, text, show=result.status == "failed")

    def _tally(self, mrun: ModuleRun) -> None:
        failed = sum(1 for m in [*self.result.modules, mrun] for r in m.results if r.status == "failed" and not r.flaky)
        self.progress.tally(self.d.label, failed)

    def _capture_logcat(self, module: Module) -> None:
        code, out = run(["adb", "-s", self.d.serial, "logcat", "-d", "-t", "4000"], timeout=60)
        (self.dir / f"logcat-{module.name}.txt").write_text(out if code == 0 else f"logcat failed: {out}", encoding="utf-8")
        code, out = run(["adb", "-s", self.d.serial, "logcat", "-d", "-b", "crash"], timeout=60)
        if code == 0 and out.strip():
            (self.dir / f"logcat-crash-{module.name}.txt").write_text(out, encoding="utf-8")

    def _done_text(self) -> str:
        results = self.result.results()
        failed = [r for r in results if r.status == "failed" and not r.flaky]
        flaky = [r for r in results if r.flaky]
        text = f"done — {len(results)} tests, {len(failed)} failed"
        return text + (f", {len(flaky)} flaky" if flaky else "")

    def _cleanup(self) -> None:
        if not self.opts.keep_installed:
            for package in reversed(self.installed):
                self.adb("uninstall", package)
        self._restore_temp_settings()


def safe_name(label: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "_", label).strip("_") or "device"


def stream_command(cmd: Sequence[str], on_line: Callable[[str], None], idle_timeout: float,
                   stop: threading.Event) -> str:
    """Run ``cmd``, feeding stdout lines to ``on_line``; kill it after ``idle_timeout`` seconds
    without output or when ``stop`` is set. Returns why it was killed ("" when it ended)."""
    try:
        proc = subprocess.Popen(list(cmd), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                errors="replace", bufsize=1)
    except OSError as exc:
        return f"cannot run {cmd[0]}: {exc}"
    lines: "queue.Queue[Optional[str]]" = queue.Queue()

    def pump() -> None:
        assert proc.stdout is not None
        for line in proc.stdout:
            lines.put(line)
        lines.put(None)

    threading.Thread(target=pump, daemon=True).start()
    last = time.monotonic()
    why = ""
    while True:
        try:
            line = lines.get(timeout=1)
        except queue.Empty:
            if stop.is_set():
                why = "interrupted"
            elif time.monotonic() - last > idle_timeout:
                why = f"hung — no output for {fmt_secs(idle_timeout)}"
            if why:
                proc.kill()
                break
            continue
        if line is None:
            break
        last = time.monotonic()
        on_line(line.rstrip("\n"))
    proc.wait()
    return why


# ── Settings restore that survives a killed run ───────────────────────────────


class RestoreBook:
    """Original settings of non-test devices, on disk until restored, so an interrupted run's
    changes are undone by the next one."""

    def __init__(self, path: Path):
        self.path = path
        self.lock = threading.Lock()

    def _load(self) -> Dict[str, Dict[str, str]]:
        try:
            return json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {}

    def _store(self, data: Dict[str, Dict[str, str]]) -> None:
        if data:
            self.path.write_text(json.dumps(data, indent=2), encoding="utf-8")
        elif self.path.exists():
            self.path.unlink()

    def save(self, serial: str, saved: Dict[str, str]) -> None:
        with self.lock:
            data = self._load()
            data.setdefault(serial, saved)   # never overwrite an older, true original
            self._store(data)

    def pop(self, serial: str) -> Dict[str, str]:
        with self.lock:
            data = self._load()
            saved = data.pop(serial, {})
            self._store(data)
            return saved

    def leftovers(self) -> Dict[str, Dict[str, str]]:
        with self.lock:
            return self._load()


def restore_settings(serial: str, saved: Dict[str, str], runner: Runner = run) -> bool:
    """Put the saved values back; False when the device was not reachable."""
    ok = True
    for ns_key, value in saved.items():
        ns, key = ns_key.split("/", 1)
        command = f"settings delete {ns} {key}" if value in ("", "null") else f"settings put {ns} {key} {value}"
        code, _ = runner(["adb", "-s", serial, "shell", command])
        ok = ok and code == 0
    return ok


# ── Issues ledger ─────────────────────────────────────────────────────────────


def empty_ledger() -> Dict[str, object]:
    return {"schema": SCHEMA, "updated_run": "", "open": {}, "resolved": []}


def load_ledger(path: Path) -> Dict[str, object]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return empty_ledger()
    if not isinstance(data, dict) or data.get("schema") != SCHEMA:
        return empty_ledger()
    return data


def observations(runs: Sequence[DeviceRun]) -> Tuple[Dict[str, Dict[str, Tuple[str, str]]], Dict[str, str]]:
    """``(tests, installs)``: per test key, hw serial -> (outcome, message); per hw serial, the
    install error ("" when it installed). Outcome is passed | failed | flaky | skipped."""
    tests: Dict[str, Dict[str, Tuple[str, str]]] = {}
    installs: Dict[str, str] = {}
    for dr in runs:
        hw = dr.device.hw_serial
        if dr.status == "ran" or dr.install_error:
            installs[hw] = dr.install_error
        for r in dr.results():
            outcome = "flaky" if r.flaky else r.status
            tests.setdefault(r.key, {})[hw] = (outcome, r.message)
    return tests, installs


def update_ledger(ledger: Dict[str, object], runs: Sequence[DeviceRun], run_id: str) -> Dict[str, List[Dict[str, object]]]:
    """Validate the open issues against this run and fold its failures in.

    Returns the verdicts: ``fixed`` (passed on every device that failed before and ran it now,
    failing nowhere), ``still`` (failed or flaky again), ``unverified`` (no affected device ran
    it), ``new``. ``ledger`` is updated in place.
    """
    labels = {dr.device.hw_serial: dr.device.label for dr in runs}
    tests, installs = observations(runs)
    open_issues: Dict[str, Dict[str, object]] = dict(ledger.get("open", {}))  # type: ignore[arg-type]
    verdicts: Dict[str, List[Dict[str, object]]] = {"fixed": [], "still": [], "unverified": [], "new": []}
    now_failing = current_issues(tests, installs, labels, run_id)

    for key, issue in open_issues.items():
        before: Dict[str, Dict[str, str]] = issue["devices"]  # type: ignore[assignment]
        if issue["kind"] == "install":
            seen = {hw: ("failed" if installs[hw] else "passed") for hw in before if hw in installs}
        else:
            seen = {hw: tests.get(str(issue["test_key"]), {})[hw][0]
                    for hw in before if hw in tests.get(str(issue["test_key"]), {})}
        entry = {"key": key, "issue": issue, "seen": {labels.get(hw, before[hw]["label"]): o for hw, o in seen.items()}}
        if key in now_failing:
            verdicts["still"].append(entry)
        elif seen and all(o in ("passed", "skipped") for o in seen.values()):
            verdicts["fixed"].append(entry)
        else:
            verdicts["unverified"].append(entry)

    new_open: Dict[str, Dict[str, object]] = {}
    for entry in verdicts["unverified"]:
        new_open[entry["key"]] = entry["issue"]  # type: ignore[index]
    for key, issue in now_failing.items():
        if key in open_issues:
            old = open_issues[key]
            issue["first_seen"] = old.get("first_seen", run_id)
            issue["runs_seen"] = int(old.get("runs_seen", 1)) + 1  # type: ignore[arg-type]
            merged = dict(old["devices"])  # type: ignore[arg-type]
            merged.update(issue["devices"])  # type: ignore[arg-type]
            # A device that ran the test cleanly this time is no longer affected.
            for hw in list(merged):
                if hw not in issue["devices"] and hw in tests.get(str(issue.get("test_key", "")), {}):  # type: ignore[operator]
                    del merged[hw]
            issue["devices"] = merged
        else:
            verdicts["new"].append({"key": key, "issue": issue, "seen": {}})
        new_open[key] = issue
    resolved: List[Dict[str, object]] = list(ledger.get("resolved", []))  # type: ignore[arg-type]
    for entry in verdicts["fixed"]:
        issue = entry["issue"]  # type: ignore[assignment]
        resolved.append({"key": entry["key"], "title": issue["title"], "first_seen": issue.get("first_seen"),  # type: ignore[index]
                         "resolved_run": run_id})
    ledger["open"] = new_open
    ledger["resolved"] = resolved[-RESOLVED_KEEP:]
    ledger["updated_run"] = run_id
    return verdicts


def current_issues(tests: Dict[str, Dict[str, Tuple[str, str]]], installs: Dict[str, str],
                   labels: Dict[str, str], run_id: str) -> Dict[str, Dict[str, object]]:
    issues: Dict[str, Dict[str, object]] = {}
    for test_key, per_device in sorted(tests.items()):
        bad = {hw: v for hw, v in per_device.items() if v[0] in ("failed", "flaky")}
        if not bad:
            continue
        kind = "flaky" if all(o == "flaky" for o, _ in bad.values()) else "test"
        issues[test_key] = {
            "kind": kind, "title": test_key, "test_key": test_key, "first_seen": run_id, "last_seen": run_id,
            "runs_seen": 1,
            "devices": {hw: {"label": labels.get(hw, hw), "outcome": o, "message": m} for hw, (o, m) in bad.items()},
        }
    for hw, error in installs.items():
        if error:
            key = f"install:{hw}"
            issues[key] = {"kind": "install", "title": f"install fails on {labels.get(hw, hw)}", "test_key": "",
                           "first_seen": run_id, "last_seen": run_id, "runs_seen": 1,
                           "devices": {hw: {"label": labels.get(hw, hw), "outcome": "failed", "message": error}}}
    return issues


def open_issue_filter(ledger: Dict[str, object]) -> Dict[str, List[str]]:
    """module -> ``cls#name`` list of every open test issue (for ``--only-open-issues``)."""
    only: Dict[str, List[str]] = {}
    for issue in ledger.get("open", {}).values():  # type: ignore[union-attr]
        test_key = str(issue.get("test_key", ""))
        if ":" in test_key:
            module, test = test_key.split(":", 1)
            only.setdefault(module, []).append(test)
    return only


# ── Report ────────────────────────────────────────────────────────────────────


def normalise(message: str) -> str:
    """A failure message with its numbers blanked, so the same cause on different screens
    (bounds, measured dp) groups together."""
    return re.sub(r"\d+(?:\.\d+)?", "#", message)


def short_key(key: str) -> str:
    """``module:pkg.Class#test`` -> ``module: Class#test``."""
    module, _, test = key.partition(":")
    cls, _, name = test.partition("#")
    return f"{module}: {cls.rsplit('.', 1)[-1]}#{name}"


def clusters(hits: Sequence[Tuple[str, TestResult]]) -> List[Dict[str, object]]:
    """Failures grouped by normalised message, largest first: one cluster is one likely cause."""
    groups: Dict[str, Dict[str, object]] = {}
    for label, r in hits:
        g = groups.setdefault(normalise(r.message), {"tests": [], "devices": [], "messages": [], "hits": []})
        for name, value in (("tests", r.key), ("devices", label), ("messages", r.message)):
            if value not in g[name]:  # type: ignore[operator]
                g[name].append(value)  # type: ignore[union-attr]
        g["hits"].append((label, r))  # type: ignore[union-attr]
    return sorted(groups.values(), key=lambda g: (-len(g["hits"]), str(g["tests"])))  # type: ignore[arg-type]


def ledger_lines(open_issues: Dict[str, Dict[str, object]]) -> List[str]:
    """Open issues grouped by likely cause, one bullet per cause."""
    groups: Dict[str, List[Dict[str, object]]] = {}
    for issue in open_issues.values():
        message = next((v["message"] for v in issue["devices"].values() if v["message"]), "")  # type: ignore[union-attr]
        groups.setdefault(normalise(str(message)), []).append(issue)
    lines = []
    for issues in sorted(groups.values(), key=len, reverse=True):
        devices = sorted({v["label"] for i in issues for v in i["devices"].values()})  # type: ignore[union-attr]
        message = next((v["message"] for v in issues[0]["devices"].values() if v["message"]), "")  # type: ignore[union-attr]
        tests = ", ".join(f"`{short_key(str(i['title']))}`" if i["kind"] != "install" else str(i["title"])
                          for i in issues)
        runs_seen = max(int(i.get("runs_seen", 1)) for i in issues)  # type: ignore[arg-type]
        lines.append(f"- **{len(issues)} issue(s) on {', '.join(devices)}** (seen in {runs_seen} run(s)) — {message}")
        lines.append(f"  {tests}")
    return lines


def tree_state() -> str:
    _, branch = run(["git", "-C", str(REPO_ROOT), "rev-parse", "--abbrev-ref", "HEAD"])
    _, sha = run(["git", "-C", str(REPO_ROOT), "rev-parse", "--short", "HEAD"])
    _, dirty = run(["git", "-C", str(REPO_ROOT), "status", "--porcelain", "--", "products/android"])
    return f"{branch.strip()} @ {sha.strip()}" + (" (uncommitted Android changes)" if dirty.strip() else "")


def render_report(meta: Dict[str, object], runs: Sequence[DeviceRun],
                  verdicts: Dict[str, List[Dict[str, object]]], ledger: Dict[str, object]) -> str:
    all_results = [(dr, r) for dr in runs for r in dr.results()]
    failed = [(dr, r) for dr, r in all_results if r.status == "failed" and not r.flaky]
    flaky = [(dr, r) for dr, r in all_results if r.flaky]
    ran = [dr for dr in runs if dr.status == "ran"]
    not_ran = [dr for dr in runs if dr.status != "ran"]
    failing_tests = sorted({r.key for _, r in failed})
    verdict = "✅ all green" if not failed and not any(dr.install_error for dr in runs) and ran else "❌ issues found"
    if not ran:
        verdict = "⊘ no device ran the suites"

    lines = [f"# Android device test run {meta['run_id']}", ""]
    lines += [f"**{verdict}** — {len(failing_tests)} failing test(s), {len({r.key for _, r in flaky})} flaky, "
              f"{len(ran)}/{len(runs)} device(s) ran"
              + (f", {len(not_ran)} did not ({', '.join(dr.device.label for dr in not_ran)})" if not_ran else ""), ""]
    lines += [f"- Started {meta['started_at']}, wall time **{fmt_secs(float(meta['wall_seconds']))}** "  # type: ignore[arg-type]
              f"(build {fmt_secs(float(meta['build_seconds']))}{', skipped' if meta.get('no_build') else ''})",  # type: ignore[arg-type]
              f"- Tree: {meta['tree']}",
              f"- Scope: {meta['scope']}", ""]

    if any(verdicts.values()):
        lines += ["## Previous issues — validation", ""]
        if verdicts["fixed"] or verdicts["still"] or verdicts["unverified"]:
            lines += ["| Issue | Verdict | This run |", "|---|---|---|"]
            for name, icon in (("still", "❌ still failing"), ("fixed", "✅ fixed"), ("unverified", "⚠ not verified")):
                for entry in verdicts[name]:
                    seen = entry["seen"] or {}
                    detail = ", ".join(f"{label}: {o}" for label, o in seen.items()) or "no affected device ran it"  # type: ignore[union-attr]
                    lines.append(f"| `{short_key(str(entry['issue']['title']))}` | {icon} | {detail} |")  # type: ignore[index]
            lines.append("")
        if verdicts["new"]:
            lines += [f"**New this run:** {len(verdicts['new'])} issue(s) — see *Issues found*.", ""]

    lines += ["## Devices", "",
              "| Device | Android | Screen | Via | Total | Setup | Install | Per module | Passed | Failed | Flaky | Skipped | Notes |",
              "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for dr in sorted(runs, key=lambda x: -x.seconds):
        d, res = dr.device, dr.results()
        per_module = ", ".join(f"{m.module} {fmt_secs(m.seconds)}" for m in dr.modules) or "—"
        notes = "; ".join([*([dr.reason] if dr.reason else []), *dr.notes,
                           *(f"{m.module}: {p}" for m in dr.modules for p in m.problems)])
        lines.append(
            f"| {d.label} | {d.android} (API {d.sdk}) | {d.screen} @{d.density} | {d.kind} | {fmt_secs(dr.seconds)} | "
            f"{fmt_secs(dr.setup_seconds)} | {fmt_secs(dr.install_seconds)} | {per_module} | "
            f"{sum(r.status == 'passed' for r in res)} | {sum(r.status == 'failed' and not r.flaky for r in res)} | "
            f"{sum(r.flaky for r in res)} | {sum(r.status == 'skipped' for r in res)} | {notes or '—'} |")
    lines.append("")

    if failed or flaky or any(dr.install_error for dr in runs):
        lines += ["## Issues found", ""]
        for dr in runs:
            if dr.install_error:
                lines += [f"### Install fails on {dr.device.label}", "", f"    {dr.install_error}", ""]
        for n, cluster in enumerate(clusters([(dr.device.label, r) for dr, r in failed + flaky]), 1):
            tests, devices, messages = cluster["tests"], cluster["devices"], cluster["messages"]
            all_flaky = all(r.flaky for _, r in cluster["hits"])
            lines += [f"### {n}. {len(tests)} test(s) on {len(devices)} device(s)"
                      + (" — flaky (passed on retry)" if all_flaky else ""), "",
                      f"Devices: {', '.join(devices)}", "", "Tests:"]
            for key in tests:
                ran_it = sum(1 for dr in ran if any(r.key == key for r in dr.results()))
                hit = [label for label, r in cluster["hits"] if r.key == key]
                lines.append(f"- `{short_key(key)}` — {len(hit)}/{ran_it} device(s) that ran it")
            lines += ["", "Failure:", ""] + [f"    {m or '(no message)'}" for m in messages[:3]]
            if any(COMPOSE_NO_HIERARCHY in m for m in messages):
                lines += ["", "_No Compose hierarchy usually means the screen was locked or off during the test._"]
            lines += ["", f"Logs: {', '.join(safe_name(d) + '/' for d in devices)} (instrument-*.txt, logcat-*.txt)", ""]

    timed = sorted(((r.seconds, dr.device.label, r.key) for dr, r in all_results if r.status != "skipped"), reverse=True)
    if timed:
        lines += ["## Slowest tests", "", "| Time | Device | Test |", "|---|---|---|"]
        lines += [f"| {fmt_secs(s)} | {label} | `{key}` |" for s, label, key in timed[:10]]
        lines.append("")

    open_issues = ledger.get("open", {})
    lines += ["## For the next session", ""]
    if open_issues:
        lines += [f"{len(open_issues)} open issue(s) are in the ledger (`{meta['ledger']}`). Fix them, then validate:", "",
                  "    python3 scripts/android_device_matrix.py run --only-open-issues   # quick check",
                  "    python3 scripts/android_device_matrix.py run                      # full confirmation", ""]
        lines += ledger_lines(open_issues) + [""]  # type: ignore[arg-type]
    else:
        lines += ["No open issues.", ""]
    lines += [f"Run directory: `{meta['run_dir']}`", ""]
    return "\n".join(lines)


def brief_issues(ledger: Dict[str, object]) -> str:
    open_issues = ledger.get("open", {})
    if not open_issues:
        return ""
    names = [f"{i['title']} ({', '.join(v['label'] for v in i['devices'].values())})"  # type: ignore[index]
             for i in open_issues.values()]  # type: ignore[union-attr]
    shown = "; ".join(names[:6]) + (f"; +{len(names) - 6} more" if len(names) > 6 else "")
    return (f"DEVICE TEST ISSUES — {len(names)} open from run {ledger.get('updated_run')}: {shown}. "
            f"Details: python3 scripts/android_device_matrix.py issues; validate after fixing: "
            f"python3 scripts/android_device_matrix.py run --only-open-issues")


# ── Orchestration ─────────────────────────────────────────────────────────────


def build(modules: Sequence[Module], log_path: Path) -> bool:
    tasks = [t for m in modules for t in (f":{m.name}:assembleDebug", f":{m.name}:assembleDebugAndroidTest")]
    with log_path.open("w", encoding="utf-8") as log:
        proc = subprocess.run(["./gradlew", *tasks, "--console=plain"], cwd=ANDROID_DIR, stdout=log,
                              stderr=subprocess.STDOUT, check=False)
    return proc.returncode == 0


def gate_lines(runs: Sequence[DeviceRun]) -> List[str]:
    """``pass|fail|skip <TAB> serial <TAB> gate [<TAB> reason]`` per device, for run_tests.sh
    (no empty field: bash's ``read`` collapses adjacent tabs)."""
    out = []
    for dr in runs:
        head = f"{dr.device.serial}\tinstrumented tests on {dr.device.label}"
        hard = [r for r in dr.results() if r.status == "failed" and not r.flaky]
        if dr.status == "skipped":
            out.append(f"skip\t{head}\t{dr.reason}")
        elif dr.status != "ran" or hard or any(m.problems and not m.results for m in dr.modules):
            out.append(f"fail\t{head}")
        else:
            out.append(f"pass\t{head}")
    return out


def cmd_run(args: argparse.Namespace) -> int:
    root = runs_root()
    root.mkdir(parents=True, exist_ok=True)
    run_id = datetime.now().strftime("%Y%m%d-%H%M%S")
    run_dir = root / run_id
    run_dir.mkdir()
    started_wall = time.monotonic()
    progress = Progress(run_dir / "progress.log", started_wall)
    restore = RestoreBook(root / RESTORE_NAME)

    for serial, saved in restore.leftovers().items():
        if restore_settings(serial, saved):
            print(f"restored the settings an interrupted run left on {serial}")
            restore.pop(serial)

    ledger_path = root / LEDGER_NAME
    ledger = load_ledger(ledger_path)
    modules = [MODULES[m] for m in (args.module or MODULES)]
    only: Dict[str, List[str]] = {}
    if args.only_open_issues:
        only = {m: tests for m, tests in open_issue_filter(ledger).items() if m in {x.name for x in modules}}
        if not only:
            print("no open test issues to validate — run without --only-open-issues")
            return 0
        modules = [m for m in modules if m.name in only]

    devices, note = discover(only=args.serial, configured=args.configured, no_personal=args.no_personal)
    scope = ("open issues only: " + ", ".join(f"{m} ({len(t)})" for m, t in only.items())) if only \
        else "full suites: " + ", ".join(m.name for m in modules)
    scope += f"; devices: {note or 'every attached device'}"
    print(f"Android device run {run_id} — {len(devices)} device(s); {scope}")
    print(f"  run directory: {run_dir}")
    print(f"  live log:      tail -f {run_dir / 'progress.log'}")
    open_count = len(ledger.get("open", {}))  # type: ignore[arg-type]
    if open_count:
        print(f"  validating {open_count} open issue(s) from run {ledger.get('updated_run')}")
    for d in devices:
        print(f"  • {d.label:<24} {d.manufacturer} Android {d.android} · {d.abi} · {d.screen} · via {d.kind}"
              + (" · test device" if d.designated else "") + (" · personal" if d.personal else ""))
    if not devices:
        print(f"  {note}")
        if args.gate_lines:
            Path(args.gate_lines).write_text(f"skip\t-\tinstrumented tests\t{note}\n", encoding="utf-8")
        return 2

    opts = Options(modules, only, args.retries, args.test_timeout, args.lock_timeout, args.unlock_wait,
                   args.keep_installed, args.replace_installed)
    build_done, stop = threading.Event(), threading.Event()
    build_state = {"ok": bool(args.no_build), "seconds": 0.0}
    workers = [DeviceWorker(d, opts, run_dir, progress, build_done, lambda: bool(build_state["ok"]), stop, restore)
               for d in devices]
    threads = [threading.Thread(target=w.run_device, name=w.d.label, daemon=True) for w in workers]
    for t in threads:
        t.start()
    def do_build() -> None:
        if not args.no_build:
            t0 = time.monotonic()
            build_state["ok"] = build(modules, run_dir / "build.log")
            build_state["seconds"] = round(time.monotonic() - t0, 1)
            print(f"  build {'done' if build_state['ok'] else 'FAILED'} in {fmt_secs(float(build_state['seconds']))}",
                  flush=True)
            if not build_state["ok"]:
                tail = (run_dir / "build.log").read_text(encoding="utf-8", errors="replace").splitlines()[-25:]
                print("\n".join("    " + line for line in tail), flush=True)
        build_done.set()

    if not args.no_build:
        print(f"  building APKs once (log: {run_dir / 'build.log'}) — devices are kept awake meanwhile")
    builder = threading.Thread(target=do_build, name="build", daemon=True)
    builder.start()
    try:
        last_board = time.monotonic()
        while builder.is_alive() or any(t.is_alive() for t in threads):
            for t in [builder, *threads]:
                t.join(0.5)
            if time.monotonic() - last_board >= args.board_interval:
                last_board = time.monotonic()
                print(f"[{fmt_secs(time.monotonic() - started_wall):>7}] ── {progress.board()}", flush=True)
    except KeyboardInterrupt:
        print("\ninterrupted — stopping devices, restoring settings, writing a partial report…")
        stop.set()
        build_done.set()
        for t in threads:
            t.join(120)

    runs = [w.result for w in workers]
    verdicts = update_ledger(ledger, runs, run_id) if not stop.is_set() else \
        {"fixed": [], "still": [], "unverified": [], "new": []}
    meta = {"run_id": run_id, "started_at": datetime.fromtimestamp(time.time() - (time.monotonic() - started_wall))
            .strftime("%Y-%m-%d %H:%M:%S"), "wall_seconds": round(time.monotonic() - started_wall, 1),
            "build_seconds": build_state["seconds"], "no_build": bool(args.no_build), "tree": tree_state(),
            "scope": scope + (" (interrupted)" if stop.is_set() else ""), "run_dir": str(run_dir),
            "ledger": str(ledger_path)}
    if not stop.is_set():
        ledger_path.write_text(json.dumps(ledger, indent=2), encoding="utf-8")
    report = render_report(meta, runs, verdicts, ledger)
    (run_dir / "report.md").write_text(report, encoding="utf-8")
    (run_dir / "results.json").write_text(json.dumps({"meta": meta, "devices": [asdict(r) for r in runs]}, indent=2),
                                          encoding="utf-8")
    latest = root / "latest"
    if latest.is_symlink() or latest.exists():
        latest.unlink()
    latest.symlink_to(run_id)
    if args.gate_lines:
        Path(args.gate_lines).write_text("\n".join(gate_lines(runs)) + "\n", encoding="utf-8")
    print("\n" + report)
    print(f"report: {run_dir / 'report.md'}")
    lines = gate_lines(runs)
    if any(line.startswith("fail") for line in lines):
        return 1
    return 0 if any(line.startswith("pass") for line in lines) else 2


def cmd_devices(args: argparse.Namespace) -> int:
    devices, note = discover(only=args.serial, configured=args.configured, no_personal=args.no_personal)
    if note:
        print(note)
    for d in devices:
        locked = atd.keyguard_showing(d.serial)
        holder = atd.lock_holder(atd.device_lock_path(d.serial))
        state = "LOCKED" if locked else ("unlocked" if locked is False else "lock state unknown")
        print(f"{d.label:<24} {d.serial:<40} {d.manufacturer} Android {d.android} (API {d.sdk}) {d.abi} "
              f"{d.screen} via {d.kind}{' [test device]' if d.designated else ''}{' [personal]' if d.personal else ''} — {state}"
              + (f"; busy: {holder}" if holder else "")
              + (f"; also {', '.join(d.other_serials)}" if d.other_serials else ""))
    return 0 if devices else 1


def cmd_report(args: argparse.Namespace) -> int:
    root = runs_root()
    target = root / (args.run or "latest") / "report.md"
    if not target.is_file():
        print(f"no report at {target}", file=sys.stderr)
        return 1
    print(target.read_text(encoding="utf-8"))
    return 0


def cmd_issues(args: argparse.Namespace) -> int:
    ledger = load_ledger(runs_root() / LEDGER_NAME)
    if args.brief:
        text = brief_issues(ledger)
        if text:
            print(text)
        return 0
    if args.json:
        print(json.dumps(ledger, indent=2))
        return 0
    open_issues = ledger.get("open", {})
    if not open_issues:
        print("no open device test issues" + (f" (last run {ledger['updated_run']})" if ledger.get("updated_run") else ""))
        return 0
    print(f"{len(open_issues)} open device test issue(s) from run {ledger.get('updated_run')}:")  # type: ignore[arg-type]
    for issue in open_issues.values():  # type: ignore[union-attr]
        print(f"\n• {issue['title']}  [{issue['kind']}; first seen {issue['first_seen']}, in {issue['runs_seen']} run(s)]")
        for v in issue["devices"].values():
            print(f"    {v['label']}: {v['outcome']} — {v['message']}")
    print("\nvalidate: python3 scripts/android_device_matrix.py run --only-open-issues")
    return 0


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("run", "devices"):
        p = sub.add_parser(name)
        p.add_argument("--serial", action="append", help="limit to this device (adb or hardware serial; repeatable)")
        p.add_argument("--configured", action="store_true",
                       help="only the test devices named in products/android/local.properties")
        p.add_argument("--no-personal", action="store_true",
                       help="leave out devices marked personal in private/lab/devices.yaml")
        if name == "run":
            p.add_argument("--module", action="append", choices=sorted(MODULES), help="limit to one product (repeatable)")
            p.add_argument("--only-open-issues", action="store_true", help="run only the tests open in the issue ledger")
            p.add_argument("--no-build", action="store_true", help="use the APKs already built")
            p.add_argument("--retries", type=int, default=1, help="re-runs of each failed test, to tell flaky from broken")
            p.add_argument("--test-timeout", type=float, default=600, help="seconds without output before a test counts as hung")
            p.add_argument("--lock-timeout", type=float, default=float(os.environ.get("PST_DEVICE_LOCK_TIMEOUT", 300)), help="seconds to wait for a device another session holds")
            p.add_argument("--unlock-wait", type=float, default=120, help="seconds to wait for you to unlock a locked device")
            p.add_argument("--board-interval", type=float, default=30, help="seconds between progress summaries")
            p.add_argument("--keep-installed", action="store_true", help="leave the app and test APKs installed")
            p.add_argument("--replace-installed", action="store_true",
                           help="replace an app of the same package id installed with another signature (wipes its data)")
            p.add_argument("--gate-lines", help="write pass/fail/skip lines per device here (run_tests.sh)")
    p_report = sub.add_parser("report")
    p_report.add_argument("run", nargs="?", help="run id (default: the latest)")
    p_issues = sub.add_parser("issues")
    p_issues.add_argument("--brief", action="store_true", help="one line, nothing when there are no open issues")
    p_issues.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    return {"run": cmd_run, "devices": cmd_devices, "report": cmd_report, "issues": cmd_issues}[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
