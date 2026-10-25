"""Time-Boxed Parallel Multi-Device Orchestrator (M3).

Coordinates exploratory interaction testing across all active products:
- Jackpix (phototok)
- Frameduel (android-desktop)
- Lensjet (macos-desktop)
- Blitzlens (linux-desktop)

Features supported:
- Feature 1: CLI Explore Subcommand & dispatch
- Feature 20: Time-Boxing Engine with monotonic deadline and stop event
- Feature 21: Parallel Multi-Device Scheduler via ThreadPoolExecutor
- Feature 22: Per-Device Locking Independence (device-<serial>.lock, never android-hub.lock)
- Feature 23: Device State Backup & Guaranteed Restoration (device-<serial>.restore.json)
- Feature 24: Personal Device Safety Guard (--with-personal & screen in_use_by check)
"""

from __future__ import annotations

import concurrent.futures
import json
import logging
import math
import os
import signal
import sys
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from ..android.adb import AdbClient
from ..android.configs import (
    SavedDeviceState,
    ZEN_TO_DND,
    apply_config,
    record_device_state,
    reset_config,
    set_dnd,
)
from ..inventory import AndroidDevice, Inventory, load_inventory, select_android_devices
from ..locks import DeviceBusy, device_lock, device_lock_path
from ..model import Finding, Tree
from ..paths import ensure_under, lab_tmp_root, repo_root
from .actions import Action, ScreenState
from .ai_adapters import AIProvider, auto_detect_ai_provider, create_ai_adapter
from .discovery import inspect_screen, load_discovery_rules, normalize_product_name
from .driver import (
    AndroidDeviceDriver,
    AppCrashedError,
    DeviceDriver,
    DriverError,
    DriverExecutionError,
    DriverSettlingError,
    DriverTimeoutError,
    LinuxDeviceDriver,
    MacOSDeviceDriver,
    MockDeviceDriver,
)
from .expectations import Expectation, ExpectationRecorder
from .selector import ActionSelector
from .verifier import StepResult, verify_step

logger = logging.getLogger(__name__)


def parse_duration_string(duration_str: str) -> float:
    """Parses duration string like '15m', '300s', '1h', '30' into seconds.

    Supports:
    - 's': seconds (e.g. '300s' -> 300.0)
    - 'm': minutes (e.g. '15m' -> 900.0)
    - 'h': hours (e.g. '1h' -> 3600.0)
    - raw numeric string (e.g. '45' -> 45.0)

    Raises:
    - ValueError if string is empty, negative, or invalid format.
    """
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


@dataclass
class DeviceState:
    """Serializable snapshot of hardware device display and system settings."""

    serial: str
    user_rotation: int = 0
    accelerometer_rotation: int = 0
    size_override: Optional[str] = None
    density_override: Optional[str] = None
    dark_mode: int = 0
    night_mode: str = "no"
    font_scale: str = "1.0"
    stay_on: int = 3
    zen_mode: str = "0"
    timestamp: str = ""
    pid: int = 0

    def to_dict(self) -> Dict[str, Any]:
        return {
            "serial": self.serial,
            "user_rotation": self.user_rotation,
            "accelerometer_rotation": self.accelerometer_rotation,
            "size_override": self.size_override,
            "density_override": str(self.density_override) if self.density_override is not None else None,
            "dark_mode": self.dark_mode,
            "night_mode": self.night_mode,
            "font_scale": self.font_scale,
            "stay_on": self.stay_on,
            "zen_mode": self.zen_mode,
            "timestamp": self.timestamp or time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "pid": self.pid or os.getpid(),
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> DeviceState:
        dens = data.get("density_override")
        dark = data.get("dark_mode")
        if dark is None and "night_mode" in data:
            dark = 1 if data["night_mode"] == "yes" else 0
        night = data.get("night_mode")
        if night is None and dark is not None:
            night = "yes" if int(dark) == 1 else "no"
        stay = data.get("stay_on", 3)
        try:
            stay_int = int(stay)
        except (ValueError, TypeError):
            stay_int = 3

        return cls(
            serial=str(data["serial"]),
            user_rotation=int(data.get("user_rotation", 0)),
            accelerometer_rotation=int(data.get("accelerometer_rotation", 0)),
            size_override=data.get("size_override"),
            density_override=str(dens) if dens is not None else None,
            dark_mode=int(dark if dark is not None else 0),
            night_mode=str(night or "no"),
            font_scale=str(data.get("font_scale", "1.0")),
            stay_on=stay_int,
            zen_mode=str(data.get("zen_mode", "0")),
            timestamp=str(data.get("timestamp", "")),
            pid=int(data.get("pid", 0)),
        )


def to_saved_device_state(state: DeviceState) -> SavedDeviceState:
    """Convert DeviceState dataclass to SavedDeviceState for reset_config."""
    try:
        dens = int(state.density_override) if state.density_override is not None else None
    except (ValueError, TypeError):
        dens = None
    return SavedDeviceState(
        user_rotation=state.user_rotation,
        accelerometer_rotation=state.accelerometer_rotation,
        size_override=state.size_override,
        density_override=dens,
        font_scale=state.font_scale,
        night_mode=state.night_mode,
        stay_on=str(state.stay_on),
        zen_mode=str(state.zen_mode),
    )


@dataclass(frozen=True)
class TargetConfig:
    """Execution target descriptor for a single worker thread."""

    product: str
    device_id: str
    config: str = "default"
    serial: Optional[str] = None
    is_personal: bool = False
    driver_type: str = "auto"
    app_id: str = "com.phototok.lab"
    target_type: str = "android"

    @property
    def label(self) -> str:
        return f"{self.product}_{self.config}_{self.device_id}"


@dataclass
class WorkerResult:
    """Result emitted by an individual target worker thread."""

    target: TargetConfig
    target_dir: Path
    status: str  # "completed", "skipped_busy", "skipped_personal", "skipped_in_use", "error"
    step_results: List[StepResult] = field(default_factory=list)
    findings: List[Finding] = field(default_factory=list)
    start_time: float = 0.0
    end_time: float = 0.0
    error: Optional[str] = None

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
    """Aggregated exploratory test run summary conforming to M3 <-> M4 contract."""

    run_id: str
    run_dir: Path
    start_time: float
    end_time: float
    product: str
    target_configs: List[str]
    total_steps: int
    passed_steps: int
    failed_steps: int
    findings: List[Finding]
    step_results: List[StepResult]
    status: str = "completed"
    worker_results: List[WorkerResult] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "run_id": self.run_id,
            "run_dir": str(self.run_dir),
            "start_time": self.start_time,
            "end_time": self.end_time,
            "duration_s": max(0.0, self.end_time - self.start_time),
            "product": self.product,
            "target_configs": list(self.target_configs),
            "total_steps": self.total_steps,
            "passed_steps": self.passed_steps,
            "failed_steps": self.failed_steps,
            "findings_count": len(self.findings),
            "findings": [f.__dict__ if hasattr(f, "__dict__") else dict(f) for f in self.findings],
            "status": self.status,
        }


class ExploratoryOrchestrator:
    """Core multi-device time-boxed exploratory orchestrator."""

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
        step_timeout_s: float = 30.0,
        lock_timeout_s: float = 0.0,
        max_workers: Optional[int] = None,
        settle_ms: int = 450,
        inventory: Optional[Inventory] = None,
    ) -> None:
        # Normalize product; rejects legacy desktop with ValueError
        self.product = normalize_product_name(product)
        if self.product not in ("phototok", "android-desktop", "macos-desktop", "linux-desktop"):
            raise ValueError(f"unsupported product for exploratory testing: {product!r}")

        # Parse duration
        if isinstance(duration, (int, float)):
            duration_s = float(duration)
            if duration_s < 0:
                raise ValueError("duration must be non-negative")
            self.duration_s = duration_s
        else:
            self.duration_s = parse_duration_string(str(duration))

        self.seed = int(seed)
        self.ai_provider_name = ai_provider
        self.driver_type = driver
        self.with_personal = bool(with_personal)
        self.step_timeout_s = float(step_timeout_s)
        self.lock_timeout_s = float(lock_timeout_s)
        self.max_workers = max_workers
        self.settle_ms = int(settle_ms)

        # Output directory
        self.out_dir = Path(out_dir).expanduser().resolve() if out_dir else (lab_tmp_root() / f"explore_{int(time.time())}")

        # Filters
        if isinstance(device, str):
            self.device_filter = [d.strip() for d in device.split(",") if d.strip()]
        elif isinstance(device, (list, tuple, set)):
            self.device_filter = [str(d).strip() for d in device if str(d).strip()]
        else:
            self.device_filter = None

        if isinstance(config, str):
            self.config_filter = [c.strip() for c in config.split(",") if c.strip()]
        elif isinstance(config, (list, tuple, set)):
            self.config_filter = [str(c).strip() for c in config if str(c).strip()]
        else:
            self.config_filter = None

        self.inventory = inventory or load_inventory()
        self.stop_event = threading.Event()
        self._active_restorations: Dict[str, Tuple[AdbClient, DeviceState, Path]] = {}
        self._lock = threading.Lock()
        self._interrupted = False
        self._sig_count = 0
        self._orig_sigint = None
        self._orig_sigterm = None

    def resolve_targets(self) -> List[TargetConfig]:
        """Resolves target devices and layouts according to product, inventory, and filters."""
        if self.driver_type == "mock":
            devices = self.device_filter or ["mock-device"]
            configs = self.config_filter or ["default"]
            return [
                TargetConfig(
                    product=self.product,
                    device_id=d,
                    config=c,
                    serial=d,
                    is_personal=False,
                    driver_type="mock",
                    app_id="com.phototok.lab" if self.product == "phototok" else "com.photoselectortoolbox.lab",
                    target_type="mock",
                )
                for d in devices
                for c in configs
            ]

        if self.product in ("phototok", "android-desktop"):
            # If explicit device filter provided, verify personal opt-in
            if self.device_filter:
                for name in self.device_filter:
                    dev = self.inventory.find(name)
                    if dev is not None and dev.personal and not self.with_personal:
                        raise ValueError(f"device '{dev.name}' is a personal device; add --with-personal to opt in")

            devices = select_android_devices(
                self.inventory,
                self.product,
                with_personal=self.with_personal,
                only=self.device_filter,
            )

            # In auto mode, if no devices found in inventory or attached, fallback to mock driver
            if not devices and self.driver_type == "auto":
                logger.info("No Android devices found in inventory for %s, falling back to mock driver", self.product)
                return [
                    TargetConfig(
                        product=self.product,
                        device_id=self.device_filter[0] if self.device_filter else "mock-device",
                        config=self.config_filter[0] if self.config_filter else "default",
                        serial="mock-device",
                        is_personal=False,
                        driver_type="mock",
                        app_id="com.phototok.lab" if self.product == "phototok" else "com.photoselectortoolbox.lab",
                        target_type="mock",
                    )
                ]

            targets: List[TargetConfig] = []
            for d in devices:
                cfgs = self.config_filter or (d.configs or ["default"])
                for c in cfgs:
                    targets.append(
                        TargetConfig(
                            product=self.product,
                            device_id=d.name,
                            config=c,
                            serial=d.adb_serial or d.serial,
                            is_personal=d.personal,
                            driver_type=self.driver_type,
                            app_id="com.phototok.lab" if self.product == "phototok" else "com.photoselectortoolbox.lab",
                            target_type="android",
                        )
                    )
            return targets

        elif self.product == "linux-desktop":
            return [
                TargetConfig(
                    product=self.product,
                    device_id="xvfb",
                    config=self.config_filter[0] if self.config_filter else "fhd",
                    driver_type=self.driver_type,
                    target_type="linux",
                )
            ]

        elif self.product == "macos-desktop":
            is_personal = self.inventory.mac.personal if self.inventory.mac else True
            if is_personal and not self.with_personal:
                raise ValueError("device 'mac' is a personal device; add --with-personal to opt in")
            return [
                TargetConfig(
                    product=self.product,
                    device_id="mac",
                    config=self.config_filter[0] if self.config_filter else "default",
                    is_personal=is_personal,
                    driver_type=self.driver_type,
                    target_type="macos",
                )
            ]

        return []

    def run(self) -> RunSummary:
        """Execute parallel exploratory loops across resolved targets until duration deadline."""
        start_time = time.monotonic()
        run_id = f"explore_{int(time.time())}"
        self.out_dir.mkdir(parents=True, exist_ok=True)
        self._setup_signals()

        try:
            targets = self.resolve_targets()
            if not targets:
                logger.warning("No targets available to run for product %s", self.product)
                summary = RunSummary(
                    run_id=run_id,
                    run_dir=self.out_dir,
                    start_time=start_time,
                    end_time=time.monotonic(),
                    product=self.product,
                    target_configs=[],
                    total_steps=0,
                    passed_steps=0,
                    failed_steps=0,
                    findings=[],
                    step_results=[],
                    status="completed",
                )
                (self.out_dir / "run.json").write_text(json.dumps(summary.to_dict(), indent=2), encoding="utf-8")
                return summary

            worker_results: List[WorkerResult] = []
            max_threads = self.max_workers or min(len(targets), 8)
            with concurrent.futures.ThreadPoolExecutor(max_workers=max_threads) as executor:
                future_to_target = {
                    executor.submit(self._run_worker_target, target): target
                    for target in targets
                }
                for future in concurrent.futures.as_completed(future_to_target):
                    target = future_to_target[future]
                    try:
                        res = future.result()
                        worker_results.append(res)
                    except Exception as exc:
                        logger.error("Unhandled worker error for %s: %s", target.label, exc)
                        worker_results.append(
                            WorkerResult(
                                target=target,
                                target_dir=self.out_dir / target.product / target.config / target.device_id,
                                status="error",
                                error=str(exc),
                            )
                        )

            # Check if all workers failed or skipped
            busy_count = sum(1 for w in worker_results if w.status == "skipped_busy")
            if busy_count == len(worker_results) and worker_results:
                raise DeviceBusy(worker_results[0].error or "all target devices are busy")

            # Check if interrupted by SIGINT
            if self._interrupted:
                raise KeyboardInterrupt("exploration interrupted by user (SIGINT)")

            # Aggregate summary
            end_time = time.monotonic()
            all_step_results: List[StepResult] = []
            all_findings: List[Finding] = []
            for w in worker_results:
                all_step_results.extend(w.step_results)
                all_findings.extend(w.findings)

            has_error = any(w.status == "error" for w in worker_results)
            summary = RunSummary(
                run_id=run_id,
                run_dir=self.out_dir,
                start_time=start_time,
                end_time=end_time,
                product=self.product,
                target_configs=[t.label for t in targets],
                total_steps=len(all_step_results),
                passed_steps=sum(1 for s in all_step_results if s.passed),
                failed_steps=sum(1 for s in all_step_results if not s.passed),
                findings=all_findings,
                step_results=all_step_results,
                status="error" if has_error else ("completed" if not self._interrupted else "interrupted"),
                worker_results=worker_results,
            )
            (self.out_dir / "run.json").write_text(json.dumps(summary.to_dict(), indent=2), encoding="utf-8")
            return summary

        finally:
            self._restore_signals()

    def _setup_signals(self) -> None:
        if threading.current_thread() is threading.main_thread():
            def _sig_handler(signum: int, frame: Any) -> None:
                sig_name = "SIGINT" if signum == signal.SIGINT else "SIGTERM"
                print(f"\n[lab] Received {sig_name}. Stopping exploratory workers cleanly...", file=sys.stderr)
                self._interrupted = True
                self.stop_event.set()
                if self._sig_count > 0:
                    print("\n[lab] Second interrupt received. Forcing emergency restore...", file=sys.stderr)
                    self._emergency_restore_all()
                    sys.exit(130 if signum == signal.SIGINT else 143)
                self._sig_count += 1

            try:
                self._orig_sigint = signal.signal(signal.SIGINT, _sig_handler)
                self._orig_sigterm = signal.signal(signal.SIGTERM, _sig_handler)
            except (ValueError, AttributeError):
                pass

    def _restore_signals(self) -> None:
        if threading.current_thread() is threading.main_thread():
            try:
                if self._orig_sigint is not None:
                    signal.signal(signal.SIGINT, self._orig_sigint)
                if self._orig_sigterm is not None:
                    signal.signal(signal.SIGTERM, self._orig_sigterm)
            except (ValueError, AttributeError):
                pass

    def _emergency_restore_all(self) -> None:
        with self._lock:
            for serial, (adb, state, restore_file) in list(self._active_restorations.items()):
                try:
                    saved_obj = to_saved_device_state(state)
                    reset_config(adb, saved_obj)
                    if restore_file.is_file():
                        restore_file.unlink()
                except Exception as exc:
                    logger.warning("Emergency restore failed on %s: %s", serial, exc)
            self._active_restorations.clear()

    def _instantiate_driver(self, target: TargetConfig, out_dir: Path) -> DeviceDriver:
        if target.driver_type == "mock":
            return MockDeviceDriver(product=target.product, app_id=target.app_id, out_dir=out_dir)
        elif target.product in ("phototok", "android-desktop"):
            return AndroidDeviceDriver(
                serial=target.serial or target.device_id,
                app_id=target.app_id,
                product=target.product,
                out_dir=out_dir,
                settle_ms=self.settle_ms,
            )
        elif target.product == "linux-desktop":
            return LinuxDeviceDriver(out_dir=out_dir, settle_ms=self.settle_ms)
        elif target.product == "macos-desktop":
            return MacOSDeviceDriver(out_dir=out_dir, settle_ms=self.settle_ms)
        raise ValueError(f"Unknown product/driver combination: {target.product} / {target.driver_type}")

    def _run_worker_target(self, target: TargetConfig) -> WorkerResult:
        # Isolated target directory
        target_dir = self.out_dir / target.product / target.config / target.device_id
        target_dir.mkdir(parents=True, exist_ok=True)
        evidence_dir = target_dir / "evidence"
        evidence_dir.mkdir(parents=True, exist_ok=True)

        # Personal device guard check
        if target.is_personal and not self.with_personal:
            msg = f"device '{target.device_id}' is a personal device; add --with-personal to opt in"
            logger.warning(msg)
            return WorkerResult(target=target, target_dir=target_dir, status="skipped_personal", error=msg)

        driver = self._instantiate_driver(target, target_dir)
        restore_tmp = lab_tmp_root() / f"device-{target.serial or target.device_id}.restore.json"
        restore_target = target_dir / f"device-{target.serial or target.device_id}.restore.json"
        saved_state: Optional[DeviceState] = None

        t0 = time.monotonic()
        try:
            # Personal device screen check prior to entering driver
            if target.is_personal and isinstance(driver, AndroidDeviceDriver):
                busy = driver.adb.in_use_by()
                if busy:
                    msg = f"error: device {target.device_id} is in use by user ({busy}); refusing to touch"
                    logger.warning(msg)
                    return WorkerResult(target=target, target_dir=target_dir, status="skipped_in_use", error=msg)

            # Enter driver context: AndroidDeviceDriver.setup() automatically acquires device_lock
            with driver:
                try:
                    # Personal in-use check after launch
                    if target.is_personal and isinstance(driver, AndroidDeviceDriver):
                        busy = driver.adb.in_use_by()
                        if busy:
                            msg = f"error: device {target.device_id} is in use by user ({busy}); refusing to touch"
                            logger.warning(msg)
                            return WorkerResult(target=target, target_dir=target_dir, status="skipped_in_use", error=msg)

                    # State backup for hardware Android devices
                    if isinstance(driver, AndroidDeviceDriver):
                        # True Original Rule: Check if a pre-existing restore file exists on disk
                        if restore_tmp.is_file():
                            try:
                                saved_state = DeviceState.from_dict(json.loads(restore_tmp.read_text(encoding="utf-8")))
                                logger.info("Adopted pre-existing restore state for %s from %s", driver.serial, restore_tmp)
                            except Exception as e:
                                logger.warning("Failed reading existing restore file %s: %s", restore_tmp, e)

                        if saved_state is None:
                            raw_saved = record_device_state(driver.adb)
                            saved_state = DeviceState(
                                serial=driver.serial,
                                user_rotation=raw_saved.user_rotation,
                                accelerometer_rotation=raw_saved.accelerometer_rotation,
                                size_override=raw_saved.size_override,
                                density_override=str(raw_saved.density_override) if raw_saved.density_override is not None else None,
                                dark_mode=1 if raw_saved.night_mode == "yes" else 0,
                                night_mode=raw_saved.night_mode,
                                stay_on=int(raw_saved.stay_on) if str(raw_saved.stay_on).isdigit() else 3,
                                font_scale=raw_saved.font_scale,
                                zen_mode=raw_saved.zen_mode,
                                timestamp=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                                pid=os.getpid(),
                            )
                            restore_tmp.write_text(json.dumps(saved_state.to_dict(), indent=2), encoding="utf-8")

                        restore_target.write_text(json.dumps(saved_state.to_dict(), indent=2), encoding="utf-8")
                        with self._lock:
                            self._active_restorations[driver.serial] = (driver.adb, saved_state, restore_tmp)

                        # Apply target config if specified and not "default"
                        if target.config and target.config != "default":
                            logger.info("Applying config %s to device %s", target.config, driver.serial)
                            apply_config(driver.adb, target.config)

                    # Run time-boxed step loop
                    deadline = time.monotonic() + self.duration_s
                    return self._run_step_loop(driver, target, target_dir, evidence_dir, deadline)
                finally:
                    # Guaranteed device state restoration inside device_lock window
                    if saved_state is not None and isinstance(driver, AndroidDeviceDriver):
                        try:
                            saved_obj = to_saved_device_state(saved_state)
                            reset_config(driver.adb, saved_obj)
                            if restore_tmp.is_file():
                                restore_tmp.unlink()
                        except Exception as exc:
                            logger.warning("Failed restoring settings for %s: %s", driver.serial, exc)
                        finally:
                            with self._lock:
                                self._active_restorations.pop(driver.serial, None)

        except DeviceBusy as exc:
            logger.warning("Device %s is busy: %s; skipping", target.device_id, exc)
            return WorkerResult(target=target, target_dir=target_dir, status="skipped_busy", error=str(exc))
        except Exception as exc:
            logger.error("Worker failed for %s: %s", target.label, exc)
            return WorkerResult(target=target, target_dir=target_dir, status="error", error=str(exc))

    def _run_step_loop(
        self,
        driver: DeviceDriver,
        target: TargetConfig,
        target_dir: Path,
        evidence_dir: Path,
        deadline: float,
    ) -> WorkerResult:
        step_results: List[StepResult] = []
        findings: List[Finding] = []
        rules = load_discovery_rules(target.product)
        selector = ActionSelector(seed=self.seed)
        recorder = ExpectationRecorder(target.product)
        ai_provider = create_ai_adapter(self.ai_provider_name, out_dir=target_dir / "packets")

        step = 0
        t_start = time.monotonic()

        while time.monotonic() < deadline and not self.stop_event.is_set():
            now = time.monotonic()
            remaining_s = max(0.0, deadline - now)
            if remaining_s <= 0.0:
                break

            step += 1

            # 1. Capture pre-action state
            try:
                before_state = driver.capture_state()
            except Exception as exc:
                logger.warning("Pre-action capture failed at step %d: %s", step, exc)
                break

            # 2. Enumerate candidates & select action
            inspection = inspect_screen(before_state.tree, target.product, rules)
            candidates = inspection.actions
            if not candidates:
                action = Action(action_type="back", label="Fallback Back Navigation")
            else:
                action = selector.select(candidates, screen_state=before_state)

            # 3. Record structured pre-interaction expectation
            expectation = recorder.record(before_state, action)

            # 4. Execute action on device
            try:
                driver.execute_action(action)
            except AppCrashedError as exc:
                logger.error("App crashed executing action at step %d: %s", step, exc)
                f_crash = Finding(
                    id=Finding.make_id(target.product, "explore", f"step_{step:02d}", "crash", [action.action_type]),
                    source="driver",
                    product=target.product,
                    config=target.config,
                    journey="explore",
                    checkpoint=f"step_{step:02d}",
                    rule="crash",
                    severity="blocker",
                    confidence="high",
                    region=None,
                    message=f"Application crashed during action '{action.action_type}': {exc}",
                )
                step_res = StepResult(
                    step_index=step,
                    action=action,
                    expectation=expectation,
                    passed=False,
                    findings=[f_crash],
                    before_state=before_state,
                    after_state=before_state,
                )
                step_results.append(step_res)
                findings.append(f_crash)
                break
            except Exception as exc:
                logger.warning("Action execution error at step %d: %s", step, exc)

            # Cooperative settle using stop_event
            settle_s = (driver.settle_ms if driver.settle_ms is not None else self.settle_ms) / 1000.0
            if settle_s > 0:
                self.stop_event.wait(settle_s)

            # 5. Capture post-action state
            try:
                after_state = driver.capture_state()
            except Exception as exc:
                logger.warning("Post-action capture failed at step %d: %s", step, exc)
                after_state = before_state

            # 6. Verify outcome and generate findings
            res = verify_step(
                step_index=step,
                action=action,
                expectation=expectation,
                before_state=before_state,
                after_state=after_state,
                product=target.product,
                config=target.config,
                rules=rules,
                ai_provider=ai_provider,
                evidence_dir=evidence_dir,
                packet_dir=target_dir / "packets" / f"step_{step:02d}",
            )
            step_results.append(res)
            findings.extend(res.findings)

            # Append to stream log
            with (target_dir / "steps.jsonl").open("a", encoding="utf-8") as f:
                f.write(json.dumps(res.to_dict()) + "\n")

        t_end = time.monotonic()
        target_summary = {
            "target": target.label,
            "total_steps": len(step_results),
            "passed_steps": sum(1 for s in step_results if s.passed),
            "failed_steps": sum(1 for s in step_results if not s.passed),
            "findings_count": len(findings),
            "duration_s": max(0.0, t_end - t_start),
        }
        (target_dir / "run.json").write_text(json.dumps(target_summary, indent=2), encoding="utf-8")

        return WorkerResult(
            target=target,
            target_dir=target_dir,
            status="completed",
            step_results=step_results,
            findings=findings,
            start_time=t_start,
            end_time=t_end,
        )
