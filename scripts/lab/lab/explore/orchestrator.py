"""Time-boxed exploratory orchestrator: one worker per device, one deadline for the run.

Per device, in this order (the lab's device-settings rule):

1. `driver.setup()` takes the locks and checks the device answers; it changes nothing.
2. The device's original settings are recorded and persisted to a per-device file (an earlier
   run's file wins: it holds the true originals if that run died before restoring).
3. `driver.prepare()` makes the device ready and launches the app on the lab fixture.
4. Per config: apply it, explore until the config's share of the remaining time is used.
5. Restore the recorded settings, read them back, and delete the record only when they match.

A step passes only when something was done and checked; a broken run is a failed run:
capture failing at step 1, a driver that cannot execute, a second app failure in a row all end
as `error`, and a run that took no steps in a non-zero time box exits non-zero.
"""

from __future__ import annotations

import concurrent.futures
import dataclasses
import json
import logging
import math
import re
import signal
import subprocess
import sys
import tempfile
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Set, Tuple

from ..android.adb import AdbClient
from ..android.configs import (
    ConfigNotRunError,
    SavedDeviceState,
    apply_config,
    load_android_configs,
    record_device_state,
    reset_config,
)
from ..inventory import AndroidDevice, Inventory, load_inventory, resolve_android_device, select_android_devices
from ..locks import DeviceBusy
from ..model import Finding
from ..paths import ensure_under, lab_tmp_root, new_run_dir, repo_root
from .actions import Action, ScreenState
from .ai_adapters import AIProvider, create_ai_adapter
from .discovery import SYSTEM_PICKER_MODE, inspect_screen, load_discovery_rules, normalize_product_name
from .driver import (
    AndroidDeviceDriver,
    AppCrashedError,
    AppLeftError,
    DeviceDriver,
    DriverError,
    DriverExecutionError,
    LinuxDeviceDriver,
    MacOSDeviceDriver,
    MockDeviceDriver,
)
from .expectations import ExpectationRecorder
from .reporter import compute_root_signature
from .selector import ActionSelector
from .verifier import (
    SEVERITY_BLOCKER,
    SEVERITY_MAJOR,
    SEVERITY_MINOR,
    STATUS_ERROR,
    STATUS_FAIL,
    STATUS_PASS,
    StepResult,
    normalize_product_for_verifier,
    verify_step,
)

logger = logging.getLogger(__name__)

RESTORE_POLLS = 6
RESTORE_POLL_S = 0.5
MAX_EXEC_FAILURES = 3  # steps in a row the driver may fail to execute before the target ends
AI_STEP_RESERVE_S = 20.0  # a step is not started when an AI judge could not finish within the time box
PRODUCTS = ("phototok", "android-desktop", "macos-desktop", "linux-desktop")


def parse_duration_string(duration_str: str) -> float:
    """`15m`, `300s`, `1h` or `45` (seconds) as seconds; raises ValueError for anything else."""
    s = str(duration_str).strip().lower()
    if not s:
        raise ValueError("empty duration string")
    try:
        if s.endswith("s"):
            val = float(s[:-1])
        elif s.endswith("m"):
            val = float(s[:-1]) * 60.0
        elif s.endswith("h"):
            val = float(s[:-1]) * 3600.0
        else:
            val = float(s)
    except ValueError as exc:
        raise ValueError(f"invalid duration format: {duration_str!r}") from exc
    if math.isnan(val) or math.isinf(val):
        raise ValueError(f"duration must be a finite number: {duration_str!r}")
    if val < 0:
        raise ValueError("duration must be non-negative")
    return val


class NoTargetError(ValueError):
    """No device or target can run the requested product; nothing was started."""


class RestoreRecordError(RuntimeError):
    """The device's persisted original settings cannot be trusted; the device is left alone."""


# ======================================================================
# Targets and results
# ======================================================================

@dataclass(frozen=True)
class TargetConfig:
    """One config on one device: what a worker explores."""

    product: str
    device_id: str
    config: str = "default"
    serial: Optional[str] = None
    is_personal: bool = False
    driver_type: str = "live"  # live | mock
    app_id: str = "com.phototok.lab"
    target_type: str = "android"  # android | linux | macos | mock

    @property
    def label(self) -> str:
        return f"{self.product}_{self.config}_{self.device_id}"

    @property
    def config_label(self) -> str:
        """The `config` of this target's findings, as the lab labels them: `<config>@<device>`."""
        return f"{self.config}@{self.device_id}"

    @property
    def device_key(self) -> str:
        return self.serial or self.device_id


def group_targets(targets: List[TargetConfig]) -> List[List[TargetConfig]]:
    """Targets grouped by device, in first-seen order: one device is driven by one worker."""
    groups: Dict[str, List[TargetConfig]] = {}
    for t in targets:
        groups.setdefault(t.device_key, []).append(t)
    return list(groups.values())


@dataclass
class WorkerResult:
    """What one target (a config on a device) did."""

    target: TargetConfig
    target_dir: Path
    # completed | skipped_busy | skipped_personal | skipped_in_use | skipped_config | unavailable | error
    status: str
    step_results: List[StepResult] = field(default_factory=list)
    findings: List[Finding] = field(default_factory=list)
    start_time: float = 0.0
    end_time: float = 0.0
    error: Optional[str] = None
    app_restarts: int = 0

    @property
    def total_steps(self) -> int:
        return len(self.step_results)

    @property
    def passed_steps(self) -> int:
        return sum(1 for s in self.step_results if s.passed)

    @property
    def failed_steps(self) -> int:
        return sum(1 for s in self.step_results if not s.passed)


@dataclass
class RunSummary:
    """The whole run, as written to `run.json` and read by the reporter."""

    run_id: str
    run_dir: Path
    start_time: float
    end_time: float
    product: str
    seed: int
    target_configs: List[str]
    findings: List[Finding]
    step_results: List[StepResult]
    status: str = "completed"  # completed | interrupted | error
    worker_results: List[WorkerResult] = field(default_factory=list)
    skipped_devices: List[str] = field(default_factory=list)

    @property
    def total_steps(self) -> int:
        return len(self.step_results)

    @property
    def passed_steps(self) -> int:
        return sum(1 for s in self.step_results if s.passed)

    @property
    def failed_steps(self) -> int:
        return self.total_steps - self.passed_steps

    def to_dict(self) -> Dict[str, Any]:
        return {
            "run_id": self.run_id,
            "run_dir": str(self.run_dir),
            "product": self.product,
            "seed": self.seed,
            "status": self.status,
            "duration_s": max(0.0, self.end_time - self.start_time),
            "target_configs": list(self.target_configs),
            "total_steps": self.total_steps,
            "passed_steps": self.passed_steps,
            "failed_steps": self.failed_steps,
            "findings_count": len(self.findings),
            "findings": [f.to_dict() for f in self.findings],
            "skipped_devices": list(self.skipped_devices),
            "workers": [
                {
                    "target": w.target.label,
                    "status": w.status,
                    "steps": w.total_steps,
                    "app_restarts": w.app_restarts,
                    "error": w.error,
                }
                for w in self.worker_results
            ],
        }


# ======================================================================
# Device settings: record, persist, restore
# ======================================================================

def restore_record_path(device_key: str) -> Path:
    """Where a device's original settings are persisted while a run changes them."""
    safe = re.sub(r"[^A-Za-z0-9._-]", "_", device_key)
    root = lab_tmp_root()
    return ensure_under(root / f"device-{safe}.explore-restore.json", root)


def _usability_record_path(device_key: str) -> Path:
    """The record a usability walk keeps (`lab/usability/capture.py`); a leftover one blocks us."""
    return lab_tmp_root() / f"device-{device_key}.restore.json"


def load_or_record_original(adb: AdbClient, device_key: str) -> Tuple[SavedDeviceState, bool]:
    """The device's original settings, persisted before anything is changed.

    A record left by an earlier run wins over a fresh read: that run died before restoring, so
    the device now shows its changed values. Returns `(state, adopted_from_earlier_run)`.
    """
    path = restore_record_path(device_key)
    leftover = _usability_record_path(device_key)
    if leftover.is_file():
        raise RestoreRecordError(
            f"{leftover} is a usability walk's record of unrestored settings; "
            "run a usability capture on this device (it restores them) or remove the file once checked"
        )
    if path.is_file():
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            return SavedDeviceState(**data["state"]), True
        except (ValueError, KeyError, TypeError) as exc:
            raise RestoreRecordError(f"{path} is unreadable ({exc}); check the device's settings, then remove it") from exc
    saved = record_device_state(adb)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(
        json.dumps({"device": device_key, "state": dataclasses.asdict(saved), "taken": time.strftime("%Y-%m-%dT%H:%M:%S")}, indent=2),
        encoding="utf-8",
    )
    tmp.replace(path)
    return saved, False


def restore_device(adb: AdbClient, saved: SavedDeviceState, device_key: str) -> Optional[str]:
    """Put the recorded settings back and read them back after the change.

    The record is deleted only when the read-back equals it. Returns None on success, else why
    the device may still be changed (the record stays, so the next run restores from it).
    """
    path = restore_record_path(device_key)
    try:
        reset_config(adb, saved)
        mismatch: Dict[str, Tuple[Any, Any]] = {}
        for attempt in range(RESTORE_POLLS):
            now = dataclasses.asdict(record_device_state(adb))
            want = dataclasses.asdict(saved)
            mismatch = {k: (want[k], now[k]) for k in want if want[k] != now[k]}
            if not mismatch:
                break
            if attempt < RESTORE_POLLS - 1:
                time.sleep(RESTORE_POLL_S)
    except Exception as exc:  # noqa: BLE001 - reported to the caller, the record stays
        return f"restoring {device_key} failed: {exc}; the record {path} stays for the next run"
    if mismatch:
        detail = ", ".join(f"{k}: wanted {w!r}, device has {n!r}" for k, (w, n) in sorted(mismatch.items()))
        return f"settings of {device_key} did not read back as recorded ({detail}); the record {path} stays"
    path.unlink(missing_ok=True)
    return None


# ======================================================================
# Orchestrator
# ======================================================================

DriverFactory = Callable[[TargetConfig, Path], DeviceDriver]


class ExploratoryOrchestrator:
    """Runs exploratory loops on every resolved device until one global deadline."""

    def __init__(
        self,
        product: str,
        duration: str | float = "15m",
        seed: int = 42,
        out_dir: Optional[Path | str] = None,
        device: Optional[str | List[str]] = None,
        config: Optional[str | List[str]] = None,
        ai_provider: str = "none",
        driver: str = "auto",
        with_personal: bool = False,
        settle_ms: int = 450,
        ai_timeout_s: float = 120.0,
        inventory: Optional[Inventory] = None,
        resolve_fn: Callable[[AndroidDevice], Tuple[bool, Optional[str]]] = resolve_android_device,
        driver_factory: Optional[DriverFactory] = None,
    ) -> None:
        self.product = normalize_product_name(product)  # rejects the legacy desktop
        if self.product not in PRODUCTS:
            raise ValueError(f"unsupported product for exploratory testing: {product!r}")
        self.duration_s = float(duration) if isinstance(duration, (int, float)) else parse_duration_string(str(duration))
        if self.duration_s < 0:
            raise ValueError("duration must be non-negative")
        if driver not in ("auto", "direct", "mock"):
            raise ValueError(f"unknown driver mode {driver!r}")

        self.seed = int(seed)
        self.ai_provider_name = ai_provider
        self.ai: AIProvider = create_ai_adapter(ai_provider)
        if not self.ai.is_available():
            raise ValueError(f"--ai-provider {ai_provider}: its CLI is not on PATH")
        self.driver_type = driver
        self.with_personal = bool(with_personal)
        self.settle_ms = int(settle_ms)
        self.ai_timeout_s = float(ai_timeout_s)
        self.out_dir: Optional[Path] = Path(out_dir).expanduser().resolve() if out_dir else None
        self.device_filter = _split(device)
        self.config_filter = _split(config)
        self._inventory = inventory
        self._resolve_fn = resolve_fn
        self._driver_factory = driver_factory
        self.skipped_devices: List[str] = []

        self.stop_event = threading.Event()
        self._active_restorations: Dict[str, Tuple[AdbClient, SavedDeviceState]] = {}
        self._lock = threading.Lock()
        self._interrupted = False
        self._sig_count = 0
        self._orig_handlers: Dict[int, Any] = {}
        self._deadline = 0.0

    @property
    def inventory(self) -> Inventory:
        if self._inventory is None:
            self._inventory = load_inventory()
        return self._inventory

    # ------------------------------------------------------------------ targets

    def resolve_targets(self) -> List[TargetConfig]:
        """Targets for the requested product. Raises `ValueError` (`NoTargetError`) when there are none.

        Whether a run is live or mock follows from this: `--driver mock` yields mock targets,
        anything else yields real ones or an error; there is no silent fallback to the mock.
        """
        self.skipped_devices = []
        app_id = "com.phototok.lab" if self.product == "phototok" else "com.photoselectortoolbox.lab"
        if self.driver_type == "mock":
            return [
                TargetConfig(self.product, d, c, serial=d, driver_type="mock", app_id=app_id, target_type="mock")
                for d in (self.device_filter or ["mock-device"])
                for c in (self.config_filter or ["default"])
            ]

        if self.product in ("phototok", "android-desktop"):
            return self._resolve_android(app_id)

        if self.device_filter or self.config_filter:
            raise ValueError("--device and --config apply to Android products only")
        if self.product == "linux-desktop":
            return [TargetConfig(self.product, "xvfb", "default", app_id="app.alpatix.Blitzlens", target_type="linux")]

        mac = self.inventory.mac
        personal = mac.personal if mac else True
        if personal and not self.with_personal:
            raise ValueError("device 'mac' is a personal device; add --with-personal to opt in")
        return [TargetConfig(self.product, "mac", "default", is_personal=personal, app_id="app.alpatix.lensjet", target_type="macos")]

    def _resolve_android(self, app_id: str) -> List[TargetConfig]:
        inventory = self.inventory
        for name in self.device_filter or []:
            dev = inventory.find(name)
            if dev is not None and dev.personal and not self.with_personal:
                raise ValueError(f"device '{dev.name}' is a personal device; add --with-personal to opt in")
        known_configs = load_android_configs()
        for cfg in self.config_filter or []:
            if cfg not in known_configs:
                raise ValueError(f"unknown Android config {cfg!r}; choices: {', '.join(sorted(known_configs))}")

        devices = select_android_devices(inventory, self.product, with_personal=self.with_personal, only=self.device_filter)
        if not devices:
            hidden = [d.name for d in inventory.android if d.runs(self.product) and d.personal]
            hint = f" (personal devices skipped: {', '.join(hidden)}; add --with-personal)" if hidden and not self.device_filter else ""
            raise NoTargetError(f"no lab device in devices.yaml runs {self.product}{hint}")

        targets: List[TargetConfig] = []
        for dev in devices:
            ok, reason = self._resolve_fn(dev)
            if not ok:
                self.skipped_devices.append(f"{dev.name}: {reason}")
                continue
            for cfg in self.config_filter or dev.configs or ["default"]:
                if cfg != "default" and not dev.allows_config(cfg):
                    self.skipped_devices.append(f"{dev.name}: config {cfg} is not available on this device")
                    continue
                targets.append(
                    TargetConfig(
                        self.product, dev.name, cfg, serial=dev.adb_serial or dev.serial,
                        is_personal=dev.personal, driver_type="live", app_id=app_id, target_type="android",
                    )
                )
        if not targets:
            raise NoTargetError("no reachable device: " + "; ".join(self.skipped_devices))
        return targets

    # ------------------------------------------------------------------ run

    def run(self) -> RunSummary:
        """Explore on every target until the deadline, then restore, summarize and write `run.json`.

        A SIGINT/SIGTERM finishes the current step of each worker, restores the devices and
        returns a summary with status `interrupted`; a second one restores at once and exits.
        """
        targets = self.resolve_targets()
        self.out_dir = self._create_run_dir()
        run_id = self.out_dir.name
        start_time = time.monotonic()
        self._deadline = start_time + self.duration_s
        groups = group_targets(targets)
        self._setup_signals()
        try:
            results = self._run_groups(groups)
        finally:
            self._restore_signals()

        worker_results = [r for group_results in results for r in group_results]
        step_results = [s for w in worker_results for s in w.step_results]
        findings = [f for w in worker_results for f in w.findings]
        if self._interrupted:
            status = "interrupted"
        elif any(w.status in ("error", "unavailable") for w in worker_results):
            status = "error"
        else:
            status = "completed"
        summary = RunSummary(
            run_id=run_id, run_dir=self.out_dir, start_time=start_time, end_time=time.monotonic(),
            product=self.product, seed=self.seed, target_configs=[t.label for t in targets],
            findings=findings, step_results=step_results, status=status,
            worker_results=worker_results, skipped_devices=list(self.skipped_devices),
        )
        self._write("run.json", json.dumps(summary.to_dict(), indent=2))
        if worker_results and all(w.status == "skipped_busy" for w in worker_results):
            raise DeviceBusy(worker_results[0].error or "all target devices are busy")
        return summary

    def _create_run_dir(self) -> Path:
        if self.out_dir is not None:
            self.out_dir.mkdir(parents=True, exist_ok=True)
            return self.out_dir
        if self.driver_type == "mock":
            root = lab_tmp_root()
            root.mkdir(parents=True, exist_ok=True)
            return Path(tempfile.mkdtemp(prefix=f"explore-mock-{self.product}-", dir=root))
        sha = subprocess.run(
            ["git", "-C", str(repo_root()), "rev-parse", "--short", "HEAD"], capture_output=True, text=True, check=False
        ).stdout.strip()
        return new_run_dir(sha or "unknown", label=f"explore-{self.product}")

    def _run_groups(self, groups: List[List[TargetConfig]]) -> List[List[WorkerResult]]:
        results: Dict[int, List[WorkerResult]] = {}
        with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, len(groups))) as pool:
            futures = {pool.submit(self._run_device_group, g): i for i, g in enumerate(groups)}
            for future in concurrent.futures.as_completed(futures):
                i = futures[future]
                try:
                    results[i] = future.result()
                except Exception as exc:  # noqa: BLE001 - a worker bug is an error result, not a lost run
                    logger.exception("worker for %s failed", groups[i][0].device_key)
                    results[i] = [self._result(t, "error", f"{type(exc).__name__}: {exc}") for t in groups[i]]
        return [results[i] for i in sorted(results)]

    # ------------------------------------------------------------------ one device

    def _target_dir(self, target: TargetConfig) -> Path:
        assert self.out_dir is not None
        return self.out_dir / target.product / target.device_id / target.config

    def _result(self, target: TargetConfig, status: str, error: Optional[str] = None) -> WorkerResult:
        return WorkerResult(target=target, target_dir=self._target_dir(target), status=status, error=error)

    def _make_driver(self, target: TargetConfig, out_dir: Path) -> DeviceDriver:
        if self._driver_factory is not None:
            return self._driver_factory(target, out_dir)
        if target.driver_type == "mock":
            return MockDeviceDriver(product=target.product, app_id=target.app_id, out_dir=out_dir)
        if target.target_type == "android":
            return AndroidDeviceDriver(
                serial=target.device_key, app_id=target.app_id, product=target.product,
                out_dir=out_dir, settle_ms=self.settle_ms,
            )
        if target.target_type == "linux":
            return LinuxDeviceDriver(out_dir=out_dir, settle_ms=self.settle_ms)
        return MacOSDeviceDriver(out_dir=out_dir, settle_ms=self.settle_ms)

    def _run_device_group(self, group: List[TargetConfig]) -> List[WorkerResult]:
        """Drive one device through all its configs, restoring its settings at the end."""
        first = group[0]
        if first.is_personal and not self.with_personal:
            msg = f"device '{first.device_id}' is a personal device; add --with-personal to opt in"
            return [self._result(t, "skipped_personal", msg) for t in group]

        device_dir = self._target_dir(first).parent
        driver = self._make_driver(first, device_dir / "driver")
        try:
            driver.setup()
        except DeviceBusy as exc:
            logger.warning("Device %s is busy: %s", first.device_id, exc)
            return [self._result(t, "skipped_busy", str(exc)) for t in group]
        except DriverError as exc:
            return [self._result(t, "unavailable", str(exc)) for t in group]

        adb: Optional[AdbClient] = getattr(driver, "adb", None) if first.target_type == "android" else None
        restore_error: Optional[str] = None
        try:
            results = self._drive_device(driver, adb, group)
        finally:
            # Inside the lock window: the settings go back before the device is released.
            with self._lock:
                entry = self._active_restorations.pop(first.device_key, None)
            if entry is not None:
                restore_error = restore_device(entry[0], entry[1], first.device_key)
                if restore_error:
                    logger.error(restore_error)
            driver.teardown()
        if restore_error:
            for r in results:
                r.status = "error"
                r.error = "; ".join(filter(None, [r.error, restore_error]))
        return results

    def _drive_device(self, driver: DeviceDriver, adb: Optional[AdbClient], group: List[TargetConfig]) -> List[WorkerResult]:
        first = group[0]
        if adb is not None:
            if first.is_personal:
                busy = self._in_use_by(adb)
                if busy:
                    msg = f"device {first.device_id} is in use by a person ({busy}); refusing to touch it"
                    return [self._result(t, "skipped_in_use", msg) for t in group]
            try:
                saved, adopted = load_or_record_original(adb, first.device_key)
            except RestoreRecordError as exc:
                return [self._result(t, "error", str(exc)) for t in group]
            if adopted:
                logger.warning("Restoring %s from an earlier run's record of its original settings", first.device_key)
            with self._lock:
                self._active_restorations[first.device_key] = (adb, saved)
        try:
            driver.prepare()
        except DriverError as exc:
            return [self._result(t, "error", f"the app did not start: {exc}") for t in group]

        results: List[WorkerResult] = []
        try:
            for i, target in enumerate(group):
                if self.stop_event.is_set():
                    break
                now = time.monotonic()
                share = max(0.0, self._deadline - now) / (len(group) - i)
                results.append(self._run_config(driver, adb, target, now + share))
        except Exception as exc:  # noqa: BLE001 - recorded on the targets that did not finish
            logger.exception("Worker failed for %s", first.device_id)
            done = {r.target for r in results}
            results += [self._result(t, "error", f"{type(exc).__name__}: {exc}") for t in group if t not in done]
        return results

    def _in_use_by(self, adb: AdbClient) -> Optional[str]:
        try:
            return adb.in_use_by()
        except Exception as exc:  # noqa: BLE001 - an unreadable state is not proof of use (as lab/orchestrate.py)
            logger.warning("Could not tell whether a person uses the device: %s", exc)
            return None

    def _run_config(self, driver: DeviceDriver, adb: Optional[AdbClient], target: TargetConfig, deadline: float) -> WorkerResult:
        target_dir = self._target_dir(target)
        self._mkdir(target_dir)
        if adb is not None and target.config != "default":
            try:
                logger.info("Applying config %s to device %s", target.config, target.device_key)
                apply_config(adb, target.config)
            except ConfigNotRunError as exc:
                return self._result(target, "skipped_config", str(exc))
            try:
                driver.restart_app()  # the window changed size: start the app on the new layout
            except DriverError as exc:
                return self._result(target, "error", f"the app did not restart under config {target.config}: {exc}")
        return self._explore_loop(driver, target, target_dir, deadline)

    # ------------------------------------------------------------------ the loop

    def _min_step_s(self, driver: DeviceDriver) -> float:
        reserve = AI_STEP_RESERVE_S if self.ai.name != "none" else 0.0
        return 2 * (driver.settle_ms or 0) / 1000.0 + reserve

    def _driver_finding(self, target: TargetConfig, checkpoint: str, exc: Exception, screen: str, action_sig: str, what: str) -> Finding:
        """A finding for an app failure; its signature has no step number, so repeats dedup."""
        if isinstance(exc, AppCrashedError):
            rule, severity = "crash", SEVERITY_BLOCKER
        elif isinstance(exc, AppLeftError):
            # Often meant (Share, a link to the browser): a lead for triage, not a defect.
            rule, severity = "app-left", SEVERITY_MINOR
        elif isinstance(exc, DriverExecutionError):
            rule, severity = "action-failed", SEVERITY_MAJOR
        else:
            rule, severity = "capture-failed", SEVERITY_MAJOR
        product = normalize_product_for_verifier(target.product)
        return Finding(
            id=compute_root_signature(product, screen, action_sig, rule, ""),
            source="flow", product=product, config=target.config_label, journey="explore",
            checkpoint=checkpoint, rule=rule, severity=severity, confidence="high", region=None,
            message=f"{what}: {exc}", evidence=[], new_since_last_run=True,
        )

    def _explore_loop(self, driver: DeviceDriver, target: TargetConfig, target_dir: Path, deadline: float) -> WorkerResult:
        steps: List[StepResult] = []
        findings: List[Finding] = []
        status, error = "completed", None
        restarts = 0
        t_start = time.monotonic()
        try:
            status, error, restarts = self._loop_body(driver, target, target_dir, deadline, steps, findings)
        except Exception as exc:  # noqa: BLE001 - a tool bug ends this target as an error, with its steps kept
            logger.exception("Exploration loop failed for %s", target.label)
            status, error = "error", f"{type(exc).__name__}: {exc}"
        t_end = time.monotonic()
        result = WorkerResult(
            target=target, target_dir=target_dir, status=status, step_results=steps, findings=findings,
            start_time=t_start, end_time=t_end, error=error, app_restarts=restarts,
        )
        self._write(
            target_dir / "run.json",
            json.dumps(
                {
                    "target": target.label, "status": status, "error": error, "total_steps": result.total_steps,
                    "passed_steps": result.passed_steps, "failed_steps": result.failed_steps,
                    "findings_count": len(findings), "app_restarts": restarts, "duration_s": t_end - t_start,
                },
                indent=2,
            ),
        )
        return result

    def _loop_body(
        self,
        driver: DeviceDriver,
        target: TargetConfig,
        target_dir: Path,
        deadline: float,
        steps: List[StepResult],
        findings: List[Finding],
    ) -> Tuple[str, Optional[str], int]:
        rules = load_discovery_rules(target.product)
        selector = ActionSelector(seed=self.seed)
        recorder = ExpectationRecorder(target.product)
        step = 0
        app_failures = 0  # app failures in a row; the second ends the target
        exec_failures = 0  # steps in a row the driver could not execute
        restarts = 0
        captured = False
        next_before: Optional[ScreenState] = None  # the last step's after-screen, while nothing has happened since
        last_screen, last_action_sig = "unknown", ""
        min_step = self._min_step_s(driver)
        unexecutable: Set[str] = set()  # actions the driver could not perform here: not offered again

        def failed_step(status: str, finding: Finding, action: Action, expectation: Any, before: Any) -> None:
            result = StepResult(step, action, expectation, status, [finding], before, before, config_label=target.config_label)
            steps.append(result)
            findings.append(finding)
            self._log_step(target_dir, result)

        def app_failed(exc: Exception, what: str) -> Optional[str]:
            """Restart the app after a failure; the error that ends the target, if any."""
            nonlocal app_failures, restarts, next_before
            next_before = None
            app_failures += 1
            if app_failures >= 2:
                return f"the app failed twice in a row ({what}: {exc}); giving up on this target"
            try:
                driver.restart_app()
            except DriverError as restart_exc:
                return f"the app could not be restarted after {what}: {restart_exc}"
            restarts += 1
            return None

        while not self.stop_event.is_set() and deadline - time.monotonic() > min_step:
            step += 1
            checkpoint = f"step_{step:02d}"

            try:
                before = next_before if next_before is not None else driver.capture_state()
                next_before = None
                captured = True
            except DriverError as exc:
                if not captured:
                    return "error", f"cannot capture the screen at step {step}: {exc}", restarts
                findings.append(self._driver_finding(target, checkpoint, exc, last_screen, last_action_sig, "capturing the screen"))
                err = app_failed(exc, "capturing the screen")
                if err:
                    return "error", err, restarts
                continue

            if before.screen_mode == SYSTEM_PICKER_MODE:
                action = Action("back", label="Leave the system folder picker")
            else:
                candidates = [
                    c for c in inspect_screen(before.tree, target.product, rules).candidate_interactions
                    if c.action.signature not in unexecutable
                ]
                action = selector.select(candidates, screen_state=before) if candidates else Action("back", label="No candidate interaction: back")
            expectation = recorder.record(before, action)
            last_screen, last_action_sig = before.screen_mode or "unknown", action.signature

            try:
                driver.execute_action(action)
            except (AppCrashedError, AppLeftError) as exc:
                what = f"{action.summary()} ended the session"
                failed_step(STATUS_FAIL, self._driver_finding(target, checkpoint, exc, last_screen, last_action_sig, what), action, expectation, before)
                err = app_failed(exc, what)
                if err:
                    return "error", err, restarts
                continue
            except DriverError as exc:
                failed_step(STATUS_ERROR, self._driver_finding(target, checkpoint, exc, last_screen, last_action_sig, f"could not execute {action.summary()}"), action, expectation, before)
                unexecutable.add(action.signature)  # e.g. rotation on a docked tablet: once is the finding
                exec_failures += 1
                if exec_failures >= MAX_EXEC_FAILURES:
                    return "error", f"the driver could not execute {exec_failures} steps in a row ({exc})", restarts
                continue
            exec_failures = 0

            self.stop_event.wait(min((driver.settle_ms or 0) / 1000.0, max(0.0, deadline - time.monotonic())))

            try:
                after = driver.capture_state()
            except AppLeftError as exc:
                # The action took the user out of the app. Back on the root screen does that by
                # design (no finding); any other way out is a lead to triage. Either way the app
                # is relaunched and the run goes on.
                left: List[Finding] = []
                if action.action_type != "back":
                    left = [self._driver_finding(target, checkpoint, exc, last_screen, last_action_sig, f"{action.summary()} left the app")]
                steps.append(StepResult(step, action, expectation, STATUS_PASS, left, before, before, config_label=target.config_label))
                findings.extend(left)
                self._log_step(target_dir, steps[-1])
                try:
                    driver.restart_app()
                except DriverError as restart_exc:
                    return "error", f"the app could not be restarted after {action.summary()} left it: {restart_exc}", restarts
                restarts += 1
                continue
            except DriverError as exc:
                what = f"capturing the screen after {action.summary()}"
                finding = self._driver_finding(target, checkpoint, exc, last_screen, last_action_sig, what)
                failed_step(STATUS_FAIL if finding.severity == SEVERITY_BLOCKER else STATUS_ERROR, finding, action, expectation, before)
                err = app_failed(exc, what)
                if err:
                    return "error", err, restarts
                continue

            try:
                app_log: Optional[List[str]] = driver.read_app_log()
            except DriverError as exc:
                logger.warning("Could not read the app log at %s: %s", checkpoint, exc)
                app_log = None

            result = verify_step(
                step_index=step, action=action, expectation=expectation, before_state=before, after_state=after,
                app_log=app_log, product=target.product, config=target.config_label, rules=rules,
                ai_provider=self.ai, run_dir=self.out_dir, evidence_dir=target_dir / "evidence",
                packet_dir=target_dir / "packets" / checkpoint,
                remaining_s=deadline - time.monotonic(), ai_timeout_s=self.ai_timeout_s,
            )
            selector.record_outcome(action, before_state=before, after_state=after)
            next_before = after
            steps.append(result)
            findings.extend(result.findings)
            self._log_step(target_dir, result)
            app_failures = 0
        return "completed", None, restarts

    def _log_step(self, target_dir: Path, result: StepResult) -> None:
        path = ensure_under(target_dir / "steps.jsonl", self.out_dir)  # type: ignore[arg-type]
        with path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(result.to_dict()) + "\n")

    # ------------------------------------------------------------------ files

    def _mkdir(self, path: Path) -> None:
        ensure_under(path, self.out_dir).mkdir(parents=True, exist_ok=True)  # type: ignore[arg-type]

    def _write(self, path: Path | str, text: str) -> None:
        assert self.out_dir is not None
        target = ensure_under(self.out_dir / path, self.out_dir)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    # ------------------------------------------------------------------ signals

    def _setup_signals(self) -> None:
        if threading.current_thread() is not threading.main_thread():
            return

        def handler(signum: int, frame: Any) -> None:
            name = signal.Signals(signum).name
            if self._sig_count == 0:
                print(f"\n[lab] {name}: finishing the current steps, then restoring the devices...", file=sys.stderr)
                self._sig_count += 1
                self._interrupted = True
                self.stop_event.set()
                return
            print(f"\n[lab] {name} again: restoring the devices now.", file=sys.stderr)
            self._emergency_restore_all()
            sys.exit(130 if signum == signal.SIGINT else 143)

        for sig in (signal.SIGINT, signal.SIGTERM):
            self._orig_handlers[sig] = signal.signal(sig, handler)

    def _restore_signals(self) -> None:
        if threading.current_thread() is not threading.main_thread():
            return
        for sig, old in self._orig_handlers.items():
            signal.signal(sig, old)
        self._orig_handlers.clear()

    def _emergency_restore_all(self) -> None:
        with self._lock:
            for key, (adb, saved) in list(self._active_restorations.items()):
                problem = restore_device(adb, saved, key)
                if problem:
                    print(f"[lab] {problem}", file=sys.stderr)
            self._active_restorations.clear()


def _split(value: Optional[str | List[str]]) -> Optional[List[str]]:
    if isinstance(value, str):
        items = [v.strip() for v in value.split(",") if v.strip()]
    elif isinstance(value, (list, tuple)):
        items = [str(v).strip() for v in value if str(v).strip()]
    else:
        return None
    return items or None
