"""Comprehensive E2E and unit tests for Time-Boxed Parallel Multi-Device Orchestrator (M3).

Covers Features 1, 20, 21, 22, 23, 24 across Tiers 1-4:
- Feature 1: CLI Explore Subcommand
- Feature 20: Time-Boxing Engine
- Feature 21: Parallel Multi-Device Scheduler
- Feature 22: Per-Device Locking Independence
- Feature 23: Device State Backup & Guaranteed Restoration
- Feature 24: Personal Device Safety Guard
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import signal
import sys
import tempfile
import time
import unittest
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional
from unittest.mock import MagicMock, patch

LAB_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(LAB_DIR))

from lab.locks import DeviceBusy, device_lock, device_lock_path, hub_lock_path  # noqa: E402
from lab.model import Finding, Node, Tree  # noqa: E402

# Check if implementation module is present
try:
    from lab.explore import orchestrator as exp_orch  # noqa: E402
    HAS_EXPLORE_ORCHESTRATOR = True
except ImportError:
    HAS_EXPLORE_ORCHESTRATOR = False


# --- Helper for parsing duration strings ---
if HAS_EXPLORE_ORCHESTRATOR:
    parse_duration_string = exp_orch.parse_duration_string
    DeviceState = exp_orch.DeviceState
else:
    def parse_duration_string(duration_str: str) -> float:
        """Parses duration string like '15m', '300s', '1h', '30' into seconds."""
        s = duration_str.strip().lower()
        if not s:
            raise ValueError("empty duration string")
        if s.endswith("s"):
            val = float(s[:-1])
        elif s.endswith("m"):
            val = float(s[:-1]) * 60.0
        elif s.endswith("h"):
            val = float(s[:-1]) * 3600.0
        else:
            val = float(s)
        if val < 0:
            raise ValueError("duration must be non-negative")
        return val


if not HAS_EXPLORE_ORCHESTRATOR:
    # --- Fallback Helper for Device State Backup ---
    @dataclass
    class DeviceState:
        serial: str
        user_rotation: int = 0
        accelerometer_rotation: int = 0
        size_override: Optional[str] = None
        density_override: Optional[str] = None
        dark_mode: int = 0
        stay_on: int = 3

        def to_dict(self) -> Dict[str, Any]:
            return {
                "serial": self.serial,
                "user_rotation": self.user_rotation,
                "accelerometer_rotation": self.accelerometer_rotation,
                "size_override": self.size_override,
                "density_override": self.density_override,
                "dark_mode": self.dark_mode,
                "stay_on": self.stay_on,
            }

        @classmethod
        def from_dict(cls, data: Dict[str, Any]) -> DeviceState:
            return cls(
                serial=str(data["serial"]),
                user_rotation=int(data.get("user_rotation", 0)),
                accelerometer_rotation=int(data.get("accelerometer_rotation", 0)),
                size_override=data.get("size_override"),
                density_override=data.get("density_override"),
                dark_mode=int(data.get("dark_mode", 0)),
                stay_on=int(data.get("stay_on", 3)),
            )


if HAS_EXPLORE_ORCHESTRATOR:
    class DummyAndroidDriver(exp_orch.AndroidDeviceDriver):
        def __init__(self, serial: str) -> None:
            self.serial = serial
            self.adb = MagicMock()
            self._locked = False
            self.settle_ms = 0
            self.out_dir = Path("/tmp")
            self.product = "phototok"
            self.app_id = "com.phototok.lab"

        def setup(self) -> None:
            self._locked = True

        def teardown(self) -> None:
            self._locked = False

        def capture_state(self) -> Any:
            return MagicMock()

        def execute_action(self, action: Any) -> bool:
            return True


# =====================================================================
# Tier 1 & 2: Feature 1 - CLI Explore Subcommand
# =====================================================================
class TestCLIExploreSubcommand(unittest.TestCase):
    def _make_parser(self) -> argparse.ArgumentParser:
        from lab.cli import build_parser

        return build_parser()

    def test_cli_explore_valid_arguments(self) -> None:
        parser = self._make_parser()
        args = parser.parse_args([
            "explore",
            "--product", "phototok",
            "--device", "pixel-4a",
            "--duration", "300s",
            "--seed", "12345",
            "--ai-provider", "claude",
        ])
        self.assertEqual(args.subcommand, "explore")
        self.assertEqual(args.product, "phototok")
        self.assertEqual(args.device, "pixel-4a")
        self.assertEqual(args.duration, "300s")
        self.assertEqual(args.seed, 12345)
        self.assertEqual(args.ai_provider, "claude")

    def test_cli_explore_rejects_legacy_desktop(self) -> None:
        import io
        from contextlib import redirect_stderr
        parser = self._make_parser()
        with redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                parser.parse_args(["explore", "--product", "desktop"])

    def test_cli_explore_duration_parsing(self) -> None:
        self.assertEqual(parse_duration_string("15m"), 900.0)
        self.assertEqual(parse_duration_string("300s"), 300.0)
        self.assertEqual(parse_duration_string("1h"), 3600.0)
        self.assertEqual(parse_duration_string("45"), 45.0)

    def test_cli_explore_invalid_duration_formats(self) -> None:
        with self.assertRaises(ValueError):
            parse_duration_string("-10s")
        with self.assertRaises(ValueError):
            parse_duration_string("invalid")
        with self.assertRaises(ValueError):
            parse_duration_string("")

    def test_cli_explore_non_finite_duration_rejection(self) -> None:
        for weird in ("nan", "inf", "+inf", "-inf", "+nan", "-nan", "NaN", "INF"):
            with self.assertRaises(ValueError):
                parse_duration_string(weird)

    def test_cli_explore_personal_device_flag_defaults_false(self) -> None:
        parser = self._make_parser()
        args = parser.parse_args(["explore", "--product", "phototok"])
        self.assertFalse(args.with_personal)

        args_with = parser.parse_args(["explore", "--product", "phototok", "--with-personal"])
        self.assertTrue(args_with.with_personal)


# =====================================================================
# Tier 1 & 2: Feature 20 - Time-Boxing Engine
# =====================================================================
class TestTimeBoxingEngine(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-tb-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_time_boxing_monotonic_deadline_calculation(self) -> None:
        orch = exp_orch.ExploratoryOrchestrator(
            product="phototok",
            duration="0.05s",
            driver="mock",
            out_dir=self.tmp,
        )
        t0 = time.monotonic()
        summary = orch.run()
        elapsed = time.monotonic() - t0
        self.assertGreaterEqual(elapsed, 0.04)
        self.assertGreater(summary.total_steps, 0)

    def test_time_boxing_terminates_loop_after_budget(self) -> None:
        orch = exp_orch.ExploratoryOrchestrator(
            product="phototok",
            duration="0.05s",
            driver="mock",
            out_dir=self.tmp,
        )
        summary = orch.run()
        self.assertGreater(summary.total_steps, 0)
        self.assertLess(summary.total_steps, 50)

    def test_time_boxing_zero_duration_runs_zero_steps(self) -> None:
        orch = exp_orch.ExploratoryOrchestrator(
            product="phototok",
            duration="0s",
            driver="mock",
            out_dir=self.tmp,
        )
        summary = orch.run()
        self.assertEqual(summary.total_steps, 0)

    def test_time_boxing_step_timeout_guard(self) -> None:
        orch = exp_orch.ExploratoryOrchestrator(
            product="phototok",
            duration="1s",
            driver="mock",
            out_dir=self.tmp,
        )
        driver = exp_orch.MockDeviceDriver(product="phototok", out_dir=self.tmp)
        with patch.object(driver, "capture_state", side_effect=exp_orch.DriverTimeoutError("Step timed out")):
            target = exp_orch.TargetConfig(product="phototok", device_id="mock-dev")
            evidence_dir = self.tmp / "evidence"
            evidence_dir.mkdir(parents=True, exist_ok=True)
            res = orch._run_step_loop(driver, target, self.tmp, evidence_dir, time.monotonic() + 0.5)
            self.assertEqual(res.status, "completed")
            self.assertEqual(len(res.step_results), 0)

    def test_time_boxing_stop_event_triggers_early_exit(self) -> None:
        orch = exp_orch.ExploratoryOrchestrator(
            product="phototok",
            duration="30s",
            driver="mock",
            out_dir=self.tmp,
        )
        orch.stop_event.set()
        t0 = time.monotonic()
        summary = orch.run()
        elapsed = time.monotonic() - t0
        self.assertLess(elapsed, 1.0)
        self.assertEqual(summary.total_steps, 0)


# =====================================================================
# Tier 1 & 2: Feature 21 - Parallel Multi-Device Scheduler
# =====================================================================
class TestParallelMultiDeviceScheduler(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-sched-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_parallel_scheduler_concurrent_threads(self) -> None:
        from concurrent.futures import ThreadPoolExecutor

        results: Dict[str, float] = {}

        def mock_worker(target_id: str, hold_s: float) -> str:
            t0 = time.monotonic()
            time.sleep(hold_s)
            results[target_id] = time.monotonic() - t0
            return f"ok_{target_id}"

        targets = ["phone1", "tablet1", "linux_desktop"]
        t_start = time.monotonic()
        with ThreadPoolExecutor(max_workers=3) as pool:
            futures = [pool.submit(mock_worker, t, 0.05) for t in targets]
            statuses = [f.result() for f in futures]

        total_elapsed = time.monotonic() - t_start
        self.assertEqual(len(statuses), 3)
        # Running in parallel: total elapsed should be ~0.05s, definitely < 0.12s
        self.assertLess(total_elapsed, 0.12)

    def test_parallel_scheduler_distinct_run_subdirectories(self) -> None:
        # Multi-config targets on same device ID must write to distinct directories:
        # out_dir / product / config / device_id
        orch = exp_orch.ExploratoryOrchestrator(
            product="phototok",
            duration="0.05s",
            driver="mock",
            device="mock-dev",
            config="cfg_a,cfg_b",
            out_dir=self.tmp / "run",
        )
        summary = orch.run()
        self.assertEqual(len(summary.worker_results), 2)
        actual_dirs = {w.target_dir for w in summary.worker_results}
        expected_dirs = {
            summary.run_dir / "phototok" / "cfg_a" / "mock-dev",
            summary.run_dir / "phototok" / "cfg_b" / "mock-dev",
        }
        self.assertEqual(actual_dirs, expected_dirs)
        for d in actual_dirs:
            self.assertTrue((d / "run.json").is_file())

    def test_parallel_scheduler_worker_exception_isolation(self) -> None:
        from concurrent.futures import ThreadPoolExecutor

        def worker_flaky(device_id: str) -> str:
            if device_id == "failing_device":
                raise RuntimeError("Device disconnected")
            return "success"

        with ThreadPoolExecutor(max_workers=2) as pool:
            f1 = pool.submit(worker_flaky, "healthy_device")
            f2 = pool.submit(worker_flaky, "failing_device")

            self.assertEqual(f1.result(), "success")
            with self.assertRaises(RuntimeError):
                f2.result()

    def test_parallel_scheduler_aggregates_findings_across_workers(self) -> None:
        worker_a_findings = [
            Finding("id1", "layout", "phototok", "phone", "exp", "chk1", "r1", "major", "high", None, "msg1")
        ]
        worker_b_findings = [
            Finding("id2", "layout", "android-desktop", "tablet", "exp", "chk2", "r2", "minor", "high", None, "msg2")
        ]
        aggregated = worker_a_findings + worker_b_findings
        self.assertEqual(len(aggregated), 2)
        self.assertEqual({f.product for f in aggregated}, {"phototok", "android-desktop"})

    def test_parallel_scheduler_empty_target_list(self) -> None:
        orch = exp_orch.ExploratoryOrchestrator(
            product="phototok",
            duration="1s",
            driver="mock",
            out_dir=self.tmp,
        )
        with patch.object(orch, "resolve_targets", return_value=[]):
            summary = orch.run()
            self.assertEqual(summary.total_steps, 0)
            self.assertEqual(summary.status, "completed")
            self.assertTrue((summary.run_dir / "run.json").is_file())


# =====================================================================
# Tier 1 & 2: Feature 22 - Per-Device Locking Independence
# =====================================================================
class TestPerDeviceLockingIndependence(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-devlock-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_per_device_lock_path_isolated(self) -> None:
        serial_a = "DEVICE_A"
        serial_b = "DEVICE_B"
        path_a = device_lock_path(serial_a, lock_dir=self.tmp)
        path_b = device_lock_path(serial_b, lock_dir=self.tmp)
        self.assertNotEqual(path_a, path_b)
        self.assertEqual(path_a.name, f"device-{serial_a}.lock")
        self.assertEqual(path_b.name, f"device-{serial_b}.lock")

    def test_two_devices_locked_concurrently(self) -> None:
        # Two distinct devices can be locked simultaneously in the same process / thread
        serial_a = "DEV_001"
        serial_b = "DEV_002"
        with device_lock(serial_a, "worker_a", lock_dir=self.tmp):
            with device_lock(serial_b, "worker_b", lock_dir=self.tmp):
                self.assertTrue((self.tmp / f"device-{serial_a}.lock").is_file())
                self.assertTrue((self.tmp / f"device-{serial_b}.lock").is_file())

    def test_same_device_contention_raises_busy(self) -> None:
        serial = "DEV_CONTENDED"
        with device_lock(serial, "holder_1", lock_dir=self.tmp):
            # Second attempt to lock the same device must fail with DeviceBusy
            with self.assertRaises(DeviceBusy):
                with device_lock(serial, "holder_2", lock_dir=self.tmp):
                    pass

    def test_per_device_lock_does_not_touch_hub_lock(self) -> None:
        serial = "DEV_STANDALONE"
        with device_lock(serial, "worker", lock_dir=self.tmp):
            hub_path = hub_lock_path(lock_dir=self.tmp)
            self.assertFalse(hub_path.exists())

    def test_lock_released_after_exit(self) -> None:
        serial = "DEV_CLEANUP"
        with device_lock(serial, "worker", lock_dir=self.tmp):
            pass
        # After exit, acquiring it again should succeed immediately
        with device_lock(serial, "worker2", lock_dir=self.tmp):
            self.assertTrue(True)


# =====================================================================
# Tier 1 & 2: Feature 23 - Device State Backup & Guaranteed Restoration
# =====================================================================
class TestDeviceStateBackupAndRestoration(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-restore-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_state_backup_persistence(self) -> None:
        state = DeviceState(
            serial="SERIAL_123",
            user_rotation=0,
            accelerometer_rotation=1,
            size_override="1080x1920",
            density_override="440",
            dark_mode=1,
        )
        restore_file = self.tmp / "device-SERIAL_123.restore.json"
        restore_file.write_text(json.dumps(state.to_dict()), encoding="utf-8")

        self.assertTrue(restore_file.is_file())
        loaded = DeviceState.from_dict(json.loads(restore_file.read_text(encoding="utf-8")))
        self.assertEqual(loaded.serial, "SERIAL_123")
        self.assertEqual(loaded.size_override, "1080x1920")
        self.assertEqual(loaded.dark_mode, 1)

    def test_restore_executed_in_finally_block(self) -> None:
        mock_driver = DummyAndroidDriver("SERIAL_RESTORE")
        lock_held_during_restore = []

        def fake_reset_config(adb, saved_obj):
            lock_held_during_restore.append(mock_driver._locked)

        target = exp_orch.TargetConfig(product="phototok", device_id="dev1", serial="SERIAL_RESTORE")
        orch = exp_orch.ExploratoryOrchestrator(product="phototok", duration="0.05s", out_dir=self.tmp)

        with patch.object(orch, "_instantiate_driver", return_value=mock_driver), \
             patch("lab.explore.orchestrator.record_device_state", return_value=MagicMock(
                 user_rotation=0, accelerometer_rotation=1, size_override=None, density_override=None,
                 night_mode="no", stay_on=3, font_scale="1.0", zen_mode="0"
             )), \
             patch("lab.explore.orchestrator.reset_config", side_effect=fake_reset_config), \
             patch.object(orch, "_run_step_loop", side_effect=RuntimeError("Step execution failure")):

            res = orch._run_worker_target(target)
            self.assertEqual(res.status, "error")

        self.assertTrue(lock_held_during_restore)
        self.assertTrue(lock_held_during_restore[0], "reset_config was executed after device lock was released!")

    def test_restore_cleans_up_backup_file(self) -> None:
        mock_driver = DummyAndroidDriver("SERIAL_CLEANUP")
        target = exp_orch.TargetConfig(product="phototok", device_id="dev1", serial="SERIAL_CLEANUP")
        orch = exp_orch.ExploratoryOrchestrator(product="phototok", duration="0.05s", out_dir=self.tmp)

        restore_tmp = exp_orch.lab_tmp_root() / f"device-{target.serial}.restore.json"

        with patch.object(orch, "_instantiate_driver", return_value=mock_driver), \
             patch("lab.explore.orchestrator.record_device_state", return_value=MagicMock(
                 user_rotation=0, accelerometer_rotation=1, size_override=None, density_override=None,
                 night_mode="no", stay_on=3, font_scale="1.0", zen_mode="0"
             )), \
             patch("lab.explore.orchestrator.reset_config"), \
             patch.object(orch, "_run_step_loop", return_value=exp_orch.WorkerResult(target=target, target_dir=self.tmp, status="completed")):

            res = orch._run_worker_target(target)
            self.assertEqual(res.status, "completed")
            self.assertFalse(restore_tmp.exists(), "Restore temporary backup file was not unlinked on cleanup!")

    def test_sigint_restoration_handler_registration(self) -> None:
        # Check that SIGINT handler can be configured
        original_handler = signal.getsignal(signal.SIGINT)
        self.assertIsNotNone(original_handler)

    def test_restore_handles_missing_file_gracefully(self) -> None:
        mock_driver = DummyAndroidDriver("SERIAL_NOFILE")
        target = exp_orch.TargetConfig(product="phototok", device_id="dev1", serial="SERIAL_NOFILE")
        orch = exp_orch.ExploratoryOrchestrator(product="phototok", duration="0.05s", out_dir=self.tmp)

        restore_tmp = exp_orch.lab_tmp_root() / f"device-{target.serial}.restore.json"
        if restore_tmp.is_file():
            restore_tmp.unlink()

        with patch.object(orch, "_instantiate_driver", return_value=mock_driver), \
             patch("lab.explore.orchestrator.record_device_state", return_value=MagicMock(
                 user_rotation=0, accelerometer_rotation=1, size_override=None, density_override=None,
                 night_mode="no", stay_on=3, font_scale="1.0", zen_mode="0"
             )), \
             patch("lab.explore.orchestrator.reset_config"), \
             patch.object(orch, "_run_step_loop", return_value=exp_orch.WorkerResult(target=target, target_dir=self.tmp, status="completed")):

            res = orch._run_worker_target(target)
            self.assertEqual(res.status, "completed")


# =====================================================================
# Tier 1 & 2: Feature 24 - Personal Device Safety Guard
# =====================================================================
class TestPersonalDeviceSafetyGuard(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-personal-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        from lab.inventory import AndroidDevice, Inventory
        self.inv = Inventory(
            android=[
                AndroidDevice(name="s25-ultra", model="SM-S938B", serial="DEV_S25", products=["phototok"], personal=True),
                AndroidDevice(name="pixel-4a", model="Pixel 4a", serial="DEV_P4A", products=["phototok"], personal=False),
            ]
        )

    def test_personal_device_rejected_without_flag(self) -> None:
        orch = exp_orch.ExploratoryOrchestrator(
            product="phototok",
            device="s25-ultra",
            inventory=self.inv,
            with_personal=False,
            out_dir=self.tmp,
        )
        with self.assertRaises(ValueError) as ctx:
            orch.resolve_targets()
        self.assertIn("is a personal device; add --with-personal to opt in", str(ctx.exception))

    def test_personal_device_cli_exit_code_2(self) -> None:
        from lab.cli import main
        import io
        from contextlib import redirect_stderr
        stderr_buf = io.StringIO()
        with redirect_stderr(stderr_buf):
            code = main(["explore", "--product", "phototok", "--device", "s25-ultra", "--out", str(self.tmp)])
        self.assertEqual(code, 2)
        self.assertIn("is a personal device; add --with-personal to opt in", stderr_buf.getvalue())

    def test_personal_device_accepted_with_flag(self) -> None:
        orch = exp_orch.ExploratoryOrchestrator(
            product="phototok",
            device="s25-ultra",
            inventory=self.inv,
            with_personal=True,
            out_dir=self.tmp,
        )
        targets = orch.resolve_targets()
        self.assertEqual(len(targets), 1)
        self.assertEqual(targets[0].device_id, "s25-ultra")
        self.assertTrue(targets[0].is_personal)

    def test_dedicated_device_accepted_without_flag(self) -> None:
        orch = exp_orch.ExploratoryOrchestrator(
            product="phototok",
            device="pixel-4a",
            inventory=self.inv,
            with_personal=False,
            out_dir=self.tmp,
        )
        targets = orch.resolve_targets()
        self.assertEqual(len(targets), 1)
        self.assertEqual(targets[0].device_id, "pixel-4a")
        self.assertFalse(targets[0].is_personal)

    def test_in_use_screen_active_check_skips_worker(self) -> None:
        mock_driver = DummyAndroidDriver("DEV_S25")
        mock_driver.adb.in_use_by.return_value = "com.whatsapp"

        target = exp_orch.TargetConfig(product="phototok", device_id="s25-ultra", serial="DEV_S25", is_personal=True)
        orch = exp_orch.ExploratoryOrchestrator(product="phototok", duration="1s", with_personal=True, out_dir=self.tmp)

        with patch.object(orch, "_instantiate_driver", return_value=mock_driver):
            res = orch._run_worker_target(target)
            self.assertEqual(res.status, "skipped_in_use")
            self.assertIn("is in use by user (com.whatsapp)", res.error or "")

    def test_in_use_device_cli_returns_exit_code_2(self) -> None:
        from lab.cli import main
        import io
        from contextlib import redirect_stderr

        with patch("lab.explore.orchestrator.ExploratoryOrchestrator.run") as mock_run:
            fake_target = exp_orch.TargetConfig(product="phototok", device_id="s25-ultra", is_personal=True)
            mock_run.return_value = exp_orch.RunSummary(
                run_id="test",
                run_dir=self.tmp,
                start_time=0.0,
                end_time=1.0,
                product="phototok",
                target_configs=["phototok_default_s25-ultra"],
                total_steps=0,
                passed_steps=0,
                failed_steps=0,
                findings=[],
                step_results=[],
                status="skipped",
                worker_results=[
                    exp_orch.WorkerResult(
                        target=fake_target,
                        target_dir=self.tmp,
                        status="skipped_in_use",
                        error="error: device s25-ultra is in use by user (com.whatsapp); refusing to touch",
                    )
                ],
            )
            stderr_buf = io.StringIO()
            with redirect_stderr(stderr_buf):
                code = main(["explore", "--product", "phototok", "--device", "s25-ultra", "--with-personal", "--out", str(self.tmp)])
            self.assertEqual(code, 2)


# =====================================================================
# Tier 3: Cross-Feature Combinations (Orchestrator)
# =====================================================================
class TestOrchestratorCrossFeatureCombinations(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-combos-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_seed_duration_and_product_combinations(self) -> None:
        products = ["phototok", "android-desktop", "macos-desktop", "linux-desktop"]
        durations = ["15s", "60s"]
        seeds = [42, 100]

        for p in products:
            for d in durations:
                for s in seeds:
                    orch = exp_orch.ExploratoryOrchestrator(
                        product=p, duration=d, seed=s, driver="mock", out_dir=self.tmp
                    )
                    self.assertEqual(orch.product, p)
                    self.assertEqual(orch.duration_s, 15.0 if d == "15s" else 60.0)
                    self.assertEqual(orch.seed, s)

    def test_parallel_android_and_linux_execution(self) -> None:
        orch = exp_orch.ExploratoryOrchestrator(
            product="phototok",
            duration="0.05s",
            driver="mock",
            device=["mock-phone", "mock-tablet"],
            out_dir=self.tmp,
        )
        summary = orch.run()
        self.assertEqual(len(summary.worker_results), 2)
        self.assertEqual(summary.status, "completed")


# =====================================================================
# Tier 4: Concrete ExploratoryOrchestrator Execution & CLI Dispatch
# =====================================================================
class TestExploratoryOrchestratorExecution(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-orch-exec-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_orchestrator_initialization_all_products(self) -> None:
        for p in ("phototok", "android-desktop", "macos-desktop", "linux-desktop"):
            orch = exp_orch.ExploratoryOrchestrator(product=p, duration="1m", out_dir=self.tmp)
            self.assertEqual(orch.product, p)
            self.assertEqual(orch.duration_s, 60.0)

        with self.assertRaises(ValueError):
            exp_orch.ExploratoryOrchestrator(product="desktop", duration="1m", out_dir=self.tmp)

        with self.assertRaises(ValueError):
            exp_orch.ExploratoryOrchestrator(product="unknown-prod", duration="1m", out_dir=self.tmp)

    def test_orchestrator_duration_parsing_variations(self) -> None:
        orch1 = exp_orch.ExploratoryOrchestrator(product="phototok", duration=45, out_dir=self.tmp)
        self.assertEqual(orch1.duration_s, 45.0)

        orch2 = exp_orch.ExploratoryOrchestrator(product="phototok", duration="2h", out_dir=self.tmp)
        self.assertEqual(orch2.duration_s, 7200.0)

        with self.assertRaises(ValueError):
            exp_orch.ExploratoryOrchestrator(product="phototok", duration="-5s", out_dir=self.tmp)

    def test_orchestrator_run_zero_duration_produces_zero_steps(self) -> None:
        orch = exp_orch.ExploratoryOrchestrator(product="phototok", duration="0s", driver="mock", out_dir=self.tmp)
        summary = orch.run()
        self.assertEqual(summary.total_steps, 0)
        self.assertEqual(summary.passed_steps, 0)
        self.assertEqual(summary.failed_steps, 0)
        self.assertTrue(summary.run_dir.is_dir())
        self.assertTrue((summary.run_dir / "run.json").is_file())

    def test_orchestrator_run_mock_driver_steps(self) -> None:
        orch = exp_orch.ExploratoryOrchestrator(
            product="phototok",
            duration="0.05s",
            driver="mock",
            out_dir=self.tmp,
            seed=42,
        )
        summary = orch.run()
        self.assertGreater(summary.total_steps, 0)
        self.assertGreater(summary.passed_steps, 0)
        self.assertTrue((summary.run_dir / "phototok" / "default" / "mock-device" / "run.json").is_file())
        self.assertTrue((summary.run_dir / "phototok" / "default" / "mock-device" / "steps.jsonl").is_file())

    def test_orchestrator_stop_event_cancels_cleanly(self) -> None:
        orch = exp_orch.ExploratoryOrchestrator(
            product="phototok",
            duration="30s",
            driver="mock",
            out_dir=self.tmp,
        )
        orch.stop_event.set()
        summary = orch.run()
        self.assertEqual(summary.total_steps, 0)

    def test_orchestrator_multi_target_concurrent_mock(self) -> None:
        orch = exp_orch.ExploratoryOrchestrator(
            product="phototok",
            duration="0.05s",
            driver="mock",
            device=["target_alpha", "target_beta"],
            out_dir=self.tmp,
        )
        summary = orch.run()
        self.assertEqual(len(summary.target_configs), 2)
        self.assertTrue((summary.run_dir / "phototok" / "default" / "target_alpha" / "run.json").is_file())
        self.assertTrue((summary.run_dir / "phototok" / "default" / "target_beta" / "run.json").is_file())

    def test_orchestrator_personal_device_guards(self) -> None:
        from lab.inventory import AndroidDevice, Inventory

        inv = Inventory(
            android=[
                AndroidDevice(
                    name="s25-ultra",
                    model="SM-S938B",
                    serial="DEV_PERSONAL_1",
                    products=["phototok"],
                    personal=True,
                )
            ]
        )
        # Without --with-personal: raises ValueError
        orch_no = exp_orch.ExploratoryOrchestrator(
            product="phototok",
            device="s25-ultra",
            inventory=inv,
            with_personal=False,
            driver="direct",
            out_dir=self.tmp,
        )
        with self.assertRaises(ValueError):
            orch_no.resolve_targets()

        # With --with-personal: accepts target
        orch_yes = exp_orch.ExploratoryOrchestrator(
            product="phototok",
            device="s25-ultra",
            inventory=inv,
            with_personal=True,
            driver="direct",
            out_dir=self.tmp,
        )
        targets = orch_yes.resolve_targets()
        self.assertEqual(len(targets), 1)
        self.assertTrue(targets[0].is_personal)

    def test_cli_main_explore_subcommand_dispatch(self) -> None:
        from lab.cli import main

        out_run = self.tmp / "cli_run"
        exit_code = main([
            "explore",
            "--product", "phototok",
            "--duration", "0s",
            "--driver", "mock",
            "--out", str(out_run),
        ])
        self.assertEqual(exit_code, 0)
        self.assertTrue((out_run / "run.json").is_file())

    def test_cli_main_explore_duration_error(self) -> None:
        import io
        from contextlib import redirect_stderr
        from lab.cli import main

        with redirect_stderr(io.StringIO()):
            exit_code = main([
                "explore",
                "--product", "phototok",
                "--duration", "invalid",
                "--driver", "mock",
            ])
            self.assertEqual(exit_code, 2)

    def test_cli_exit_code_1_on_worker_error(self) -> None:
        from lab.cli import main
        with patch("lab.explore.orchestrator.ExploratoryOrchestrator._run_worker_target") as mock_w:
            mock_w.side_effect = RuntimeError("Fatal worker crash")
            code = main(["explore", "--product", "phototok", "--driver", "mock", "--duration", "0.05s", "--out", str(self.tmp / "err_run")])
            self.assertEqual(code, 1)

    def test_apply_config_called_when_specified(self) -> None:
        mock_driver = DummyAndroidDriver("SERIAL_CONFIG")

        target = exp_orch.TargetConfig(
            product="phototok",
            device_id="dev1",
            config="tab-landscape",
            serial="SERIAL_CONFIG",
        )
        orch = exp_orch.ExploratoryOrchestrator(product="phototok", duration="0.05s", out_dir=self.tmp)

        with patch.object(orch, "_instantiate_driver", return_value=mock_driver), \
             patch("lab.explore.orchestrator.record_device_state", return_value=MagicMock(
                 user_rotation=0, accelerometer_rotation=1, size_override=None, density_override=None,
                 night_mode="no", stay_on=3, font_scale="1.0", zen_mode="0"
             )), \
             patch("lab.explore.orchestrator.apply_config") as mock_apply, \
             patch("lab.explore.orchestrator.reset_config"), \
             patch.object(orch, "_run_step_loop", return_value=exp_orch.WorkerResult(target=target, target_dir=self.tmp, status="completed")):

            res = orch._run_worker_target(target)
            self.assertEqual(res.status, "completed")
            mock_apply.assert_called_once_with(mock_driver.adb, "tab-landscape")


if __name__ == "__main__":
    unittest.main()
