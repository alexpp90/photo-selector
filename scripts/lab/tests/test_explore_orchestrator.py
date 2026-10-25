"""The orchestrator and the `explore` CLI, against fake drivers that follow the driver contract.

Nothing here needs a device: `PST_LAB_TMP` points every lab file (locks, restore records, mock
scratch) into a temp directory, the inventory is injected, and `adb` settings go to patched
`record_device_state` / `apply_config` / `reset_config`.
"""

from __future__ import annotations

import contextlib
import dataclasses
import io
import json
import logging
import os
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _explore_fakes import ScriptedDriver, default_state  # noqa: E402

from lab import cli  # noqa: E402
from lab.explore.discovery import SYSTEM_PICKER_MODE  # noqa: E402
from lab.explore.actions import Action  # noqa: E402
from lab.android.configs import SavedDeviceState  # noqa: E402
from lab.explore import orchestrator as orch  # noqa: E402
from lab.explore.ai_adapters import AIJudgeError, AIProvider, Judgement, PacketExportAdapter  # noqa: E402
from lab.explore.driver import (  # noqa: E402
    AppCrashedError,
    AppLeftError,
    DriverError,
    DriverExecutionError,
)
from lab.inventory import AndroidDevice, Inventory, MacHost  # noqa: E402
from lab.locks import DeviceBusy, device_lock  # noqa: E402
from lab.paths import repo_root  # noqa: E402

ORIGINAL = SavedDeviceState(font_scale="1.3", night_mode="yes", stay_on="2", zen_mode="1", user_rotation=1, accelerometer_rotation=0, size_override="1080x2200", density_override=420)
CHANGED = dataclasses.replace(ORIGINAL, font_scale="1.0", night_mode="no")


def pixel(personal: bool = False, name: str = "pixel", serial: str = "PIX1", configs=None) -> AndroidDevice:
    return AndroidDevice(name=name, serial=serial, products=["phototok", "android-desktop"], personal=personal, configs=configs)


def reachable(dev: AndroidDevice):
    dev.adb_serial = dev.serial
    return True, None



def always_select(action):
    """Make the selector pick `action` on every step."""
    return patch("lab.explore.orchestrator.ActionSelector.select", return_value=action)

class LabTmpCase(unittest.TestCase):
    """A temp lab root, a temp run dir, and a quiet logger."""

    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.lab_tmp = self.root / "lab-tmp"
        self.run_dir = self.root / "run"
        env = patch.dict(os.environ, {"PST_LAB_TMP": str(self.lab_tmp)})
        env.start()
        self.addCleanup(env.stop)
        log = logging.getLogger("lab.explore")  # the warnings the tests provoke are not output
        level = log.level
        log.setLevel(logging.CRITICAL)
        self.addCleanup(log.setLevel, level)

    def make(self, product="phototok", duration=0.3, **kw) -> orch.ExploratoryOrchestrator:
        kw.setdefault("out_dir", self.run_dir)
        kw.setdefault("seed", 1)
        return orch.ExploratoryOrchestrator(product, duration=duration, **kw)


class DurationTest(unittest.TestCase):
    def test_parse(self) -> None:
        self.assertEqual([orch.parse_duration_string(s) for s in ("30s", "2m", "1h", "45", "1.5m")], [30, 120, 3600, 45, 90])

    def test_bad_durations(self) -> None:
        for bad in ("", "abc", "-5", "nan", "inf", "5x"):
            with self.assertRaises(ValueError, msg=bad):
                orch.parse_duration_string(bad)


class TargetsTest(LabTmpCase):
    def test_mock_targets_only_with_the_mock_driver(self) -> None:
        targets = self.make(driver="mock", device="a,b", config="c1,c2").resolve_targets()
        self.assertEqual([(t.device_id, t.config, t.driver_type) for t in targets], [("a", "c1", "mock"), ("a", "c2", "mock"), ("b", "c1", "mock"), ("b", "c2", "mock")])

    def test_auto_never_falls_back_to_the_mock(self) -> None:
        for mode in ("auto", "direct"):
            with self.assertRaises(orch.NoTargetError):
                self.make(driver=mode, inventory=Inventory()).resolve_targets()

    def test_unreachable_devices_are_reported_not_mocked(self) -> None:
        o = self.make(driver="auto", inventory=Inventory(android=[pixel()]), resolve_fn=lambda d: (False, "not attached"))
        with self.assertRaises(orch.NoTargetError) as ctx:
            o.resolve_targets()
        self.assertIn("pixel: not attached", str(ctx.exception))

    def test_live_targets_follow_the_inventory_and_group_by_device(self) -> None:
        inv = Inventory(android=[pixel(configs=["phone-portrait", "phone-dark"]), pixel(name="moto", serial="MOTO1")])
        targets = self.make(inventory=inv, resolve_fn=reachable).resolve_targets()
        self.assertTrue(all(t.driver_type == "live" and t.target_type == "android" for t in targets))
        groups = orch.group_targets(targets)
        self.assertEqual([[t.config for t in g] for g in groups], [["phone-portrait", "phone-dark"], ["default"]])
        self.assertEqual({t.device_key for g in groups for t in g}, {"PIX1", "MOTO1"})

    def test_personal_devices_need_the_flag(self) -> None:
        inv = Inventory(android=[pixel(personal=True)])
        with self.assertRaises(ValueError) as ctx:
            self.make(inventory=inv, device="pixel", resolve_fn=reachable).resolve_targets()
        self.assertIn("--with-personal", str(ctx.exception))
        with self.assertRaises(orch.NoTargetError):  # not named: skipped, and not the only choice
            self.make(inventory=inv, resolve_fn=reachable).resolve_targets()
        (target,) = self.make(inventory=inv, device="pixel", with_personal=True, resolve_fn=reachable).resolve_targets()
        self.assertTrue(target.is_personal)

    def test_unknown_android_config_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            self.make(inventory=Inventory(android=[pixel()]), config="no-such", resolve_fn=reachable).resolve_targets()

    def test_desktop_products_take_no_device_or_config(self) -> None:
        with self.assertRaises(ValueError):
            self.make("linux-desktop", device="x").resolve_targets()
        (target,) = self.make("linux-desktop").resolve_targets()
        self.assertEqual((target.target_type, target.driver_type), ("linux", "live"))

    def test_legacy_desktop_is_excluded(self) -> None:
        with self.assertRaises(ValueError):
            self.make("desktop")


class DeviceOrderTest(LabTmpCase):
    """setup -> record + persist -> prepare -> apply config -> loop -> restore, per device."""

    def build(self, *, personal=False, configs=None, duration=0.3, adb=None, **driver_kw):
        self.events: list[str] = []
        self.adb = adb or MagicMock(name="adb")
        self.record_file_seen_in_prepare = []
        self.drivers: list[ScriptedDriver] = []

        def on_prepare():
            self.record_file_seen_in_prepare.append(any(self.lab_tmp.glob("device-*.explore-restore.json")))

        def factory(target, out_dir):
            d = ScriptedDriver(out_dir, calls=self.events, adb=self.adb, on_prepare=on_prepare, **driver_kw)
            self.drivers.append(d)
            return d

        inv = Inventory(android=[pixel(personal=personal, configs=configs)])
        return self.make(inventory=inv, resolve_fn=reachable, driver_factory=factory, duration=duration, with_personal=personal)

    def patches(self, *, readback=None):
        self.events = getattr(self, "events", [])
        self.record = MagicMock(side_effect=lambda adb: (self.events.append("record"), readback.pop(0) if readback else ORIGINAL)[1])
        self.apply = MagicMock(side_effect=lambda adb, cfg: self.events.append(f"apply:{cfg}"))
        self.reset = MagicMock(side_effect=lambda adb, saved=None: self.events.append("reset"))
        stack = contextlib.ExitStack()
        self.addCleanup(stack.close)
        stack.enter_context(patch.object(orch, "record_device_state", self.record))
        stack.enter_context(patch.object(orch, "apply_config", self.apply))
        stack.enter_context(patch.object(orch, "reset_config", self.reset))
        stack.enter_context(patch.object(orch, "RESTORE_POLL_S", 0))

    def record_files(self):
        return list(self.lab_tmp.glob("device-*.explore-restore.json"))

    def test_the_order_and_the_persisted_record(self) -> None:
        self.patches()
        o = self.build(configs=["phone-portrait"])
        summary = o.run()
        ev = self.events
        self.assertEqual(ev[:4], ["setup", "record", "prepare", "apply:phone-portrait"])
        self.assertEqual(ev[-3:], ["reset", "record", "teardown"])  # restored, read back, then the lock is released
        self.assertIn("restart", ev)  # the app is restarted under the new layout
        self.assertEqual(self.record_file_seen_in_prepare, [True])  # persisted before anything is changed
        self.assertEqual(self.record_files(), [])  # deleted after the read-back matched
        self.reset.assert_called_once_with(self.adb, ORIGINAL)
        self.assertEqual(summary.status, "completed")
        self.assertGreater(summary.total_steps, 0)

    def test_the_default_config_is_not_applied(self) -> None:
        self.patches()
        self.build(configs=None).run()
        self.apply.assert_not_called()

    def test_a_read_back_that_differs_keeps_the_record_and_fails_the_run(self) -> None:
        self.patches(readback=[ORIGINAL, CHANGED, CHANGED])
        with patch.object(orch, "RESTORE_POLLS", 2):
            summary = self.build().run()
        (worker,) = summary.worker_results
        self.assertEqual(worker.status, "error")
        self.assertIn("did not read back as recorded", worker.error)
        self.assertEqual(len(self.record_files()), 1)  # the next run restores from it
        self.assertEqual(summary.status, "error")

    def test_an_earlier_runs_record_wins_over_a_fresh_read(self) -> None:
        self.patches(readback=[ORIGINAL])
        path = orch.restore_record_path("PIX1")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"state": dataclasses.asdict(ORIGINAL)}), encoding="utf-8")
        self.build().run()
        self.reset.assert_called_once_with(self.adb, ORIGINAL)
        self.assertEqual(self.record.call_count, 1)  # only the read-back; the originals were not re-read
        self.assertEqual(self.record_files(), [])

    def test_an_unreadable_record_stops_before_the_device_is_touched(self) -> None:
        self.patches()
        path = orch.restore_record_path("PIX1")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("{not json", encoding="utf-8")
        summary = self.build().run()
        self.assertEqual(summary.worker_results[0].status, "error")
        self.assertNotIn("prepare", self.events)
        self.reset.assert_not_called()
        self.assertTrue(path.is_file())

    def test_a_usability_walks_unrestored_record_blocks_the_run(self) -> None:
        self.patches()
        self.lab_tmp.mkdir(parents=True, exist_ok=True)
        (self.lab_tmp / "device-PIX1.restore.json").write_text("{}", encoding="utf-8")
        summary = self.build().run()
        self.assertIn("usability", summary.worker_results[0].error)
        self.assertNotIn("prepare", self.events)
        self.record.assert_not_called()

    def test_restore_runs_even_when_prepare_fails(self) -> None:
        self.patches()
        o = self.build()
        o._driver_factory = lambda t, d: ScriptedDriver(d, calls=self.events, adb=self.adb, on_prepare=lambda: (_ for _ in ()).throw(DriverError("app not running")))
        summary = o.run()
        self.assertIn("the app did not start: app not running", summary.worker_results[0].error)
        self.assertEqual(self.events[-3:], ["reset", "record", "teardown"])

    def test_a_personal_device_in_use_is_left_alone(self) -> None:
        self.patches()
        self.adb = MagicMock(name="adb")
        self.adb.in_use_by.return_value = "com.whatsapp"
        o = self.build(personal=True, adb=self.adb)
        summary = o.run()
        (worker,) = summary.worker_results
        self.assertEqual(worker.status, "skipped_in_use")
        self.assertIn("com.whatsapp", worker.error)
        self.assertEqual(self.events, ["setup", "teardown"])
        self.record.assert_not_called()
        self.assertEqual(self.record_files(), [])

    def test_a_busy_device_is_skipped(self) -> None:
        self.patches()
        o = self.build()
        o._driver_factory = lambda t, d: ScriptedDriver(d, on_setup=lambda: (_ for _ in ()).throw(DeviceBusy("held by another run")))
        with self.assertRaises(DeviceBusy):  # every target busy: the run is refused
            o.run()
        self.assertEqual(json.loads((self.run_dir / "run.json").read_text(encoding="utf-8"))["workers"][0]["status"], "skipped_busy")

    def test_a_config_the_device_cannot_run_is_skipped_not_failed(self) -> None:
        from lab.android.configs import ConfigNotRunError

        self.patches()
        o = self.build(configs=["phone-portrait", "phone-dark"])
        self.apply.side_effect = lambda adb, cfg: (_ for _ in ()).throw(ConfigNotRunError("needs AVD")) if cfg == "phone-portrait" else None
        summary = o.run()
        self.assertEqual([w.status for w in summary.worker_results], ["skipped_config", "completed"])

    def test_emergency_restore_restores_from_the_persisted_record(self) -> None:
        self.patches(readback=[ORIGINAL])
        adb = MagicMock()
        orch.load_or_record_original(adb, "PIX1")
        o = self.make(driver="mock")
        o._active_restorations["PIX1"] = (adb, ORIGINAL)
        o._emergency_restore_all()
        self.reset.assert_called_once_with(adb, ORIGINAL)
        self.assertEqual(self.record_files(), [])
        self.assertEqual(o._active_restorations, {})


class WorkerCase(LabTmpCase):
    """Runs mock-typed targets with scripted drivers."""

    def run_with(self, driver_kw=None, *, duration=0.3, devices="mock-device", config=None, **kw):
        self.drivers: list[ScriptedDriver] = []
        driver_kw = driver_kw or {}

        def factory(target, out_dir):
            d = ScriptedDriver(out_dir, serial=target.device_id, **driver_kw)
            self.drivers.append(d)
            return d

        o = self.make(driver="mock", device=devices, config=config, duration=duration, driver_factory=factory, **kw)
        self.o = o
        return o.run()


class LoopTest(WorkerCase):
    def test_a_normal_run_checks_every_step(self) -> None:
        summary = self.run_with()
        self.assertEqual(summary.status, "completed")
        self.assertGreater(summary.total_steps, 3)
        self.assertEqual(summary.failed_steps, 0, [s.findings for s in summary.step_results if not s.passed])
        self.assertTrue((self.run_dir / "run.json").is_file())
        target_dir = self.run_dir / "phototok" / "mock-device" / "default"
        self.assertEqual(len((target_dir / "steps.jsonl").read_text(encoding="utf-8").splitlines()), summary.total_steps)

    def test_capture_failing_at_step_one_is_an_error(self) -> None:
        summary = self.run_with({"captures": [DriverError("uiautomator dump failed")]})
        (worker,) = summary.worker_results
        self.assertEqual((worker.status, worker.total_steps), ("error", 0))
        self.assertIn("cannot capture the screen at step 1", worker.error)
        self.assertNotIn("restart", self.drivers[0].calls)
        self.assertEqual(summary.status, "error")

    def test_leaving_the_app_is_a_deduplicated_lead_and_the_app_is_restarted(self) -> None:
        s = default_state()
        left = AppLeftError("android is in front of com.phototok.lab")  # e.g. the share sheet
        with always_select(Action("tap", target_id="btn_share", label="Share")):
            summary = self.run_with({"captures": [s, left, s, s, s, left, s]})
        (worker,) = summary.worker_results
        self.assertEqual(worker.status, "completed")
        leads = [f for f in summary.findings if f.rule == "app-left"]
        self.assertEqual(len(leads), 2)
        self.assertEqual(leads[0].severity, "minor")
        self.assertEqual(leads[0].id, leads[1].id)  # no step number in the signature
        self.assertNotEqual(leads[0].checkpoint, leads[1].checkpoint)
        self.assertEqual(self.drivers[0].calls.count("restart"), 2)
        self.assertEqual(worker.app_restarts, 2)
        self.assertNotIn("fail", [r.status for r in summary.step_results])

    def test_back_that_closes_the_app_restarts_it_without_a_finding(self) -> None:
        s = default_state()
        left = AppLeftError("com.google.android.apps.nexuslauncher is in front of com.phototok.lab")
        with always_select(Action("back", label="System Back Navigation")):
            summary = self.run_with({"captures": [s, left, s]})
        (worker,) = summary.worker_results
        self.assertEqual(worker.status, "completed")
        self.assertNotIn("app-left", [f.rule for f in summary.findings])
        self.assertEqual(summary.step_results[0].status, "pass")
        self.assertIn("restart", self.drivers[0].calls)

    def test_the_system_picker_is_only_ever_left(self) -> None:
        picker = default_state()
        picker.screen_mode = SYSTEM_PICKER_MODE
        summary = self.run_with({"captures": [picker, default_state()]})
        self.assertEqual(self.drivers[0].executed[0].action_type, "back")
        self.assertEqual(summary.step_results[0].findings, [])

    def test_an_action_the_driver_cannot_perform_is_not_offered_again(self) -> None:
        def first(candidates, screen_state=None):
            return candidates[0].action

        with patch("lab.explore.orchestrator.ActionSelector.select", side_effect=first):
            self.run_with({"actions": [DriverExecutionError("rotation is locked"), None]})
        executed = [a.signature for a in self.drivers[0].executed]
        self.assertGreater(len(executed), 2)
        self.assertNotIn(executed[0], executed[1:])

    def test_the_after_screen_is_the_next_before_screen(self) -> None:
        summary = self.run_with()
        self.assertGreater(summary.total_steps, 3)
        self.assertEqual(self.drivers[0].capture_count, summary.total_steps + 1)

    def test_a_crash_is_recorded_and_restarted(self) -> None:
        s = default_state()
        summary = self.run_with({"captures": [s, AppCrashedError("process died"), s]})
        self.assertIn("crash", [f.rule for f in summary.findings])
        self.assertIn("restart", self.drivers[0].calls)

    def test_a_second_failure_in_a_row_ends_the_worker_as_error(self) -> None:
        s = default_state()
        summary = self.run_with({"captures": [s, AppCrashedError("died"), AppCrashedError("died again")]})
        (worker,) = summary.worker_results
        self.assertEqual(worker.status, "error")
        self.assertIn("failed twice in a row", worker.error)
        self.assertEqual(self.drivers[0].calls.count("restart"), 1)
        self.assertEqual(summary.status, "error")

    def test_a_failed_restart_ends_the_worker(self) -> None:
        s = default_state()

        class NoRestart(ScriptedDriver):
            def restart_app(self) -> None:
                raise DriverError("am start failed")

        o = self.make(driver="mock", driver_factory=lambda t, d: NoRestart(d, captures=[s, AppLeftError("x")]))
        (worker,) = o.run().worker_results
        self.assertEqual(worker.status, "error")
        self.assertIn("could not be restarted", worker.error)

    def test_a_failed_execution_is_an_error_step_with_a_finding(self) -> None:
        summary = self.run_with({"actions": [DriverExecutionError("input tap exited 1"), None]})
        first = summary.step_results[0]
        self.assertEqual(first.status, "error")
        self.assertEqual([f.rule for f in first.findings], ["action-failed"])
        self.assertIn("input tap exited 1", first.findings[0].message)
        self.assertGreater(summary.total_steps, 1)  # later steps ran and were verified

    def test_a_driver_that_cannot_execute_anything_ends_as_error(self) -> None:
        summary = self.run_with({"actions": [DriverExecutionError("unsupported action")]})
        (worker,) = summary.worker_results
        self.assertEqual((worker.status, worker.total_steps), ("error", orch.MAX_EXEC_FAILURES))
        self.assertTrue(all(not s.passed for s in worker.step_results))

    def test_an_unreadable_app_log_makes_the_step_an_error(self) -> None:
        class NoLog(ScriptedDriver):
            def read_app_log(self):
                raise DriverError("logcat failed")

        o = self.make(driver="mock", driver_factory=lambda t, d: NoLog(d))
        summary = o.run()
        self.assertGreater(summary.total_steps, 0)
        self.assertTrue(all(s.status == "error" for s in summary.step_results))
        self.assertEqual({f.rule for f in summary.findings}, {"log-unavailable"})

    def test_the_log_oracle_uses_the_drivers_app_log(self) -> None:
        summary = self.run_with({"log": ["E AndroidRuntime: FATAL EXCEPTION: main"]})
        crashes = [f for f in summary.findings if f.rule == "crash"]
        self.assertEqual(len(crashes), 1)  # the lines were handed out once
        self.assertEqual(summary.step_results[0].status, "fail")

    def test_the_selector_hears_every_outcome(self) -> None:
        with patch.object(orch.ActionSelector, "record_outcome", autospec=True) as spy:
            summary = self.run_with()
        self.assertEqual(spy.call_count, summary.total_steps)
        self.assertEqual(set(spy.call_args.kwargs), {"before_state", "after_state"})

    def test_the_seed_makes_the_run_reproducible(self) -> None:
        a = [s.action for s in self.run_with(duration=0.2, seed=7).step_results[:5]]
        shutil_rmtree(self.run_dir)
        b = [s.action for s in self.run_with(duration=0.2, seed=7).step_results[:5]]
        self.assertEqual(a, b)

    def test_everything_stays_in_the_run_directory(self) -> None:
        self.run_with()
        self.assertEqual(sorted(p.name for p in self.root.iterdir() if p.name != "lab-tmp"), ["run"])
        leftovers = [p for p in self.lab_tmp.rglob("*") if p.is_file()] if self.lab_tmp.exists() else []
        self.assertEqual(leftovers, [])


def shutil_rmtree(path: Path) -> None:
    import shutil

    shutil.rmtree(path)


class SchedulingTest(WorkerCase):
    def test_one_worker_per_device_runs_its_configs_one_after_the_other(self) -> None:
        # Two configs on one device share its lock; two workers would have raised DeviceBusy.
        stacks: list[contextlib.ExitStack] = []

        class Locking(ScriptedDriver):
            def setup(self):
                stack = contextlib.ExitStack()
                stack.enter_context(device_lock("SER1", "test", lock_dir=SchedulingTest.lock_dir))
                stacks.append(stack)
                super().setup()

            def teardown(self):
                stacks.pop().close()
                super().teardown()

        SchedulingTest.lock_dir = self.lab_tmp
        o = self.make(driver="mock", device="dev", config="c1,c2", duration=0.4, driver_factory=lambda t, d: Locking(d))
        summary = o.run()
        self.assertEqual([w.status for w in summary.worker_results], ["completed", "completed"])
        self.assertEqual(stacks, [])

    def test_a_device_gets_one_driver_for_all_its_configs(self) -> None:
        self.run_with(devices="a,b", config="c1,c2")
        self.assertEqual(sorted(d.serial for d in self.drivers), ["a", "b"])

    def test_devices_run_in_parallel(self) -> None:
        barrier = threading.Barrier(3, timeout=5)  # three devices: the pool is as large as the groups
        summary = self.run_with({"on_prepare": barrier.wait}, devices="a,b,c")
        self.assertEqual([w.status for w in summary.worker_results], ["completed"] * 3)

    def test_the_configs_of_a_device_share_the_time_box(self) -> None:
        summary = self.run_with(duration=1.0, devices="dev", config="c1,c2")
        first, second = summary.worker_results
        self.assertLessEqual(first.end_time - first.start_time, 0.65)
        self.assertGreater(second.end_time - second.start_time, 0.2)

    def test_one_deadline_covers_setup_and_exploration(self) -> None:
        t0 = time.monotonic()
        summary = self.run_with({"on_setup": lambda: time.sleep(0.5), "capture_delay_s": 0.01}, duration=0.8)
        self.assertLess(time.monotonic() - t0, 1.2)
        (worker,) = summary.worker_results
        self.assertLessEqual(worker.end_time - summary.start_time, 0.8 + 0.15)

    def test_a_slow_setup_that_eats_the_budget_leaves_no_steps_and_a_failing_exit(self) -> None:
        summary = self.run_with({"on_setup": lambda: time.sleep(0.4)}, duration=0.3)
        self.assertEqual(summary.total_steps, 0)
        self.assertEqual(self.run_cli_exit(summary, 0.3), 1)

    def run_cli_exit(self, summary, duration_s):
        with patch.object(orch.ExploratoryOrchestrator, "run", return_value=summary), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return cli.main(["explore", "--product", "phototok", "--driver", "mock", "--duration", f"{duration_s}s", "--out", str(self.run_dir)])

    def test_sigint_finishes_the_step_writes_the_run_and_restores(self) -> None:
        before = signal.getsignal(signal.SIGINT)
        events: list[str] = []
        adb = MagicMock()
        with patch.object(orch, "record_device_state", return_value=ORIGINAL), patch.object(orch, "reset_config") as reset, patch.object(orch, "RESTORE_POLL_S", 0):
            o = self.make(inventory=Inventory(android=[pixel()]), resolve_fn=reachable, duration=30,
                          driver_factory=lambda t, d: ScriptedDriver(d, adb=adb, calls=events, capture_delay_s=0.02))
            threading.Timer(0.4, lambda: os.kill(os.getpid(), signal.SIGINT)).start()
            t0 = time.monotonic()
            with contextlib.redirect_stderr(io.StringIO()):
                summary = o.run()
        self.assertLess(time.monotonic() - t0, 5)
        self.assertEqual(summary.status, "interrupted")
        self.assertGreater(summary.total_steps, 0)
        self.assertEqual(json.loads((self.run_dir / "run.json").read_text(encoding="utf-8"))["status"], "interrupted")
        reset.assert_called_once_with(adb, ORIGINAL)
        self.assertEqual(events[-1], "teardown")
        self.assertEqual(signal.getsignal(signal.SIGINT), before)


class FakeJudge(AIProvider):
    name = "fake"

    def __init__(self, error=None) -> None:
        self.error, self.timeouts = error, []

    def judge(self, before_state, action, expectation, after_state, ctx):
        self.timeouts.append(ctx.timeout_s)
        if self.error:
            raise self.error
        return Judgement([], None)

    def is_available(self) -> bool:
        return True


class AiWiringTest(WorkerCase):
    def setUp(self) -> None:
        super().setUp()
        patcher = patch.object(orch, "AI_STEP_RESERVE_S", 0.0)
        patcher.start()
        self.addCleanup(patcher.stop)

    def run_ai(self, provider, duration=1.0, **kw):
        def factory(target, out_dir):
            return ScriptedDriver(out_dir)

        o = self.make(driver="mock", duration=duration, driver_factory=factory, **kw)
        o.ai = provider
        return o.run()

    def test_packets_go_to_the_target_directory_only(self) -> None:
        summary = self.run_ai(PacketExportAdapter(), duration=0.5)
        packet = self.run_dir / "phototok" / "mock-device" / "default" / "packets" / "step_01"
        self.assertTrue((packet / "manifest.json").is_file())
        self.assertEqual(summary.step_results[0].packet_dir, packet)
        self.assertFalse((self.lab_tmp / "packets").exists())
        self.assertEqual(summary.failed_steps, 0)

    def test_the_judge_never_gets_more_time_than_is_left(self) -> None:
        judge = FakeJudge()
        self.run_ai(judge, duration=1.0, ai_timeout_s=100.0)
        self.assertTrue(judge.timeouts)
        self.assertLessEqual(max(judge.timeouts), 1.0)

    def test_a_failing_judge_is_an_error_finding_and_a_failed_run(self) -> None:
        summary = self.run_ai(FakeJudge(AIJudgeError("claude exited with 1")), duration=0.5)
        self.assertGreater(summary.total_steps, 0)
        self.assertTrue(all(s.status == "error" for s in summary.step_results))
        self.assertEqual({f.rule for f in summary.findings}, {"ai-judge-error"})
        self.assertEqual({f.severity for f in summary.findings}, {"minor"})
        self.assertEqual(len({f.id for f in summary.findings}), 1)  # one defect, however many steps
        self.assertEqual(SchedulingTest.run_cli_exit(self, summary, 0.5), 1)

    def test_an_ai_step_is_not_started_when_the_judge_could_not_finish(self) -> None:
        with patch.object(orch, "AI_STEP_RESERVE_S", 50.0):
            summary = self.run_ai(FakeJudge(), duration=1.0)
        self.assertEqual(summary.total_steps, 0)

    def test_provider_availability_is_checked_up_front(self) -> None:
        with patch("shutil.which", return_value=None), self.assertRaises(ValueError):
            self.make(ai_provider="claude")


def fingerprint() -> dict:
    """What a run must not touch: the backlog/report places the old explore wrote to, and git's view."""
    root = repo_root()
    paths = [root / p for p in ("private/ai/memory/code_health.md", "ai/memory/code_health.md", "docs/build/lab-reports", "private/docs/build/lab-reports")]
    state: dict = {str(p): (p.exists(), p.stat().st_mtime_ns if p.exists() else 0) for p in paths}
    try:
        state["git"] = subprocess.run(["git", "-C", str(root), "status", "--porcelain", "--untracked-files=all", "--ignored"], capture_output=True, text=True, timeout=60).stdout
    except (OSError, subprocess.SubprocessError):
        state["git"] = ""
    return state


class CliTest(LabTmpCase):
    def run_cli(self, *argv, inventory=None):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.ExitStack() as stack:
            stack.enter_context(patch.object(orch, "load_inventory", return_value=inventory or Inventory()))
            stack.enter_context(contextlib.redirect_stdout(out))
            stack.enter_context(contextlib.redirect_stderr(err))
            code = cli.main(["explore", *argv])
        return code, out.getvalue(), err.getvalue()

    def test_auto_without_a_device_exits_2_and_creates_nothing(self) -> None:
        code, out, err = self.run_cli("--product", "phototok", "--duration", "1s", "--out", str(self.run_dir))
        self.assertEqual(code, 2)
        self.assertIn("no lab device", err)
        self.assertFalse(self.run_dir.exists())
        self.assertNotIn("Exploration", out)

    def test_auto_does_not_mock_even_for_linux_without_a_session_tool(self) -> None:
        # The live Linux target is chosen; whether it can start is the driver's business (setup).
        (target,) = self.make("linux-desktop").resolve_targets()
        self.assertEqual(target.driver_type, "live")

    def test_mock_runs_offline_and_writes_only_into_the_run_directory(self) -> None:
        before = fingerprint()
        code, out, err = self.run_cli("--product", "phototok", "--driver", "mock", "--duration", "1s", "--seed", "3", "--out", str(self.run_dir))
        self.assertEqual(code, 0, err)
        self.assertEqual(before, fingerprint())
        self.assertEqual({p.name for p in self.run_dir.iterdir()}, {"phototok", "run.json", "findings.json", "report.md", "report.html"})
        self.assertIn("Report:", out)
        self.assertEqual([p for p in self.lab_tmp.rglob("*") if p.is_file()], [])

    def test_a_personal_device_needs_the_flag_hermetically(self) -> None:
        inv = Inventory(android=[pixel(personal=True)])
        code, _, err = self.run_cli("--product", "phototok", "--device", "pixel", "--out", str(self.run_dir), inventory=inv)
        self.assertEqual(code, 2)
        self.assertIn("--with-personal", err)

    def test_lensjet_is_refused_with_the_drivers_message(self) -> None:
        inv = Inventory(mac=MacHost(host="mac.local", personal=False))
        code, _, err = self.run_cli("--product", "lensjet", "--duration", "1s", "--out", str(self.run_dir), inventory=inv)
        self.assertEqual(code, 2)
        self.assertIn("needs a live-app driver; not implemented yet", err)

    def test_bad_arguments_exit_2(self) -> None:
        for argv in (["--product", "phototok", "--duration", "soon"], ["--product", "linux", "--device", "x", "--duration", "1s"], ["--product", "phototok", "--config", "nope", "--driver", "auto"]):
            code, _, err = self.run_cli(*argv, "--out", str(self.run_dir))
            self.assertEqual(code, 2, argv)

    def test_a_judge_cli_that_is_not_installed_exits_2(self) -> None:
        with patch("shutil.which", return_value=None):
            code, _, err = self.run_cli("--product", "phototok", "--driver", "mock", "--ai-provider", "claude", "--out", str(self.run_dir))
        self.assertEqual(code, 2)
        self.assertIn("CLI is not on PATH", err)
        self.assertFalse(self.run_dir.exists())

    def test_without_out_a_mock_run_gets_its_directory_under_the_lab_tmp_root(self) -> None:
        code, out, err = self.run_cli("--product", "phototok", "--driver", "mock", "--duration", "1s")
        self.assertEqual(code, 0, err)
        (run_dir,) = [p for p in self.lab_tmp.iterdir() if p.name.startswith("explore-mock-phototok-")]
        self.assertTrue((run_dir / "report.md").is_file())

    def test_known_findings_are_read_by_id_only(self) -> None:
        review = self.root / "repo" / "private" / "lab" / "review"
        review.mkdir(parents=True)
        (review / "known_findings.yaml").write_text(
            "- id: aaaaaaaaaaaa\n  reason: fine\n  until: 2999-01-01\n"
            "- id: bbbbbbbbbbbb\n  reason: old\n  until: 2000-01-01\n"
            "- product: phototok\n  rule: crash\n  reason: a product+rule pair suppresses nothing\n",
            encoding="utf-8",
        )
        with patch("lab.paths.repo_root", return_value=self.root / "repo"):
            self.assertEqual(cli._known_finding_ids(), {"aaaaaaaaaaaa"})
        with patch("lab.paths.repo_root", return_value=self.root / "no-overlay"):
            self.assertEqual(cli._known_finding_ids(), set())

    def test_a_busy_device_exits_3(self) -> None:
        with patch.object(orch.ExploratoryOrchestrator, "run", side_effect=DeviceBusy("held by lab run")):
            code, _, err = self.run_cli("--product", "phototok", "--driver", "mock", "--out", str(self.run_dir))
        self.assertEqual(code, 3)
        self.assertIn("held by lab run", err)

    def make(self, product, **kw):
        return orch.ExploratoryOrchestrator(product, duration=1, out_dir=self.run_dir, inventory=Inventory(), **kw)


def summary_of(run_dir: Path, steps=(), workers=(), findings=(), status="completed"):
    step_results = [SimpleNamespace(passed=p, status="pass" if p else "fail", step_index=i + 1, config_label="c@d", findings=[],
                                    action=SimpleNamespace(summary=lambda: "tap"), expectation=SimpleNamespace(description="e"))
                    for i, p in enumerate(steps)]
    return SimpleNamespace(run_id="r", run_dir=run_dir, start_time=0.0, end_time=1.0, product="phototok", seed=1, status=status,
                           findings=list(findings), step_results=step_results, worker_results=list(workers), skipped_devices=[],
                           total_steps=len(steps), passed_steps=sum(steps), failed_steps=len(steps) - sum(steps))


class ExitCodeTest(LabTmpCase):
    def code(self, summary, duration="5s") -> int:
        with patch.object(orch.ExploratoryOrchestrator, "run", return_value=summary), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return cli.main(["explore", "--product", "phototok", "--driver", "mock", "--duration", duration, "--out", str(self.run_dir)])

    def worker(self, status, error=None):
        return SimpleNamespace(target=SimpleNamespace(label="t"), status=status, error=error, total_steps=0)

    def finding(self, severity="major", fid="a" * 12):
        from lab.model import Finding

        return Finding(fid, "flow", "phototok", "default@d", "explore", "step_01", "r", severity, "high", None, "m")

    def test_exit_codes(self) -> None:
        ok = self.worker("completed")
        cases = [
            ("clean", summary_of(self.run_dir, [True, True], [ok]), 0),
            ("no steps in a real time box", summary_of(self.run_dir, [], [ok]), 1),
            ("a failed step", summary_of(self.run_dir, [True, False], [ok]), 1),
            ("a blocking finding", summary_of(self.run_dir, [True], [ok], [self.finding()]), 1),
            ("a minor finding", summary_of(self.run_dir, [True], [ok], [self.finding("minor")]), 0),
            ("a worker error", summary_of(self.run_dir, [True], [self.worker("error", "boom")]), 1),
            ("unavailable", summary_of(self.run_dir, [], [self.worker("unavailable", "refused")]), 2),
            ("personal in use", summary_of(self.run_dir, [], [self.worker("skipped_in_use", "busy")]), 2),
            ("interrupted", summary_of(self.run_dir, [True], [ok], status="interrupted"), 130),
        ]
        for name, summary, expected in cases:
            self.assertEqual(self.code(summary), expected, name)

    def test_a_zero_second_time_box_may_take_no_steps(self) -> None:
        self.assertEqual(self.code(summary_of(self.run_dir, [], [self.worker("completed")]), duration="0s"), 0)

    def test_triaged_findings_do_not_fail_the_run(self) -> None:
        with patch.object(cli, "_known_finding_ids", return_value={"a" * 12}):
            self.assertEqual(self.code(summary_of(self.run_dir, [True], [self.worker("completed")], [self.finding()])), 0)


if __name__ == "__main__":
    unittest.main()
