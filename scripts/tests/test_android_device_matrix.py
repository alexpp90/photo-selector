"""Unit tests for scripts/android_device_matrix.py (no device needed)."""

from __future__ import annotations

from pathlib import Path
import re
import sys
import tempfile
import threading
import unittest

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import android_device_matrix as adm  # noqa: E402

LISTING = """List of devices attached
13171JEC200817         device usb:1-2.1 product:sunfish model:Pixel_4a device:sunfish transport_id:1058
192.168.0.221:41765    device product:sunfish model:Pixel_4a device:sunfish transport_id:72
adb-3620105H804AS8-BswZ64 (2)._adb-tls-connect._tcp device product:tangorpro model:Pixel_Tablet transport_id:56
adb-3620105H804AS8-BswZ64._adb-tls-connect._tcp device product:tangorpro model:Pixel_Tablet transport_id:55
emulator-5554          device product:sdk_gphone_x86_64 model:sdk_gphone_x86_64 transport_id:94
R5CY62D2QDH            unauthorized usb:1-3
ZZ0000                 offline
"""
HW = {"13171JEC200817": "13171JEC200817", "192.168.0.221:41765": "13171JEC200817",
      "adb-3620105H804AS8-BswZ64 (2)._adb-tls-connect._tcp": "3620105H804AS8",
      "adb-3620105H804AS8-BswZ64._adb-tls-connect._tcp": "3620105H804AS8",
      "emulator-5554": "EMULATOR35"}


class TransportTest(unittest.TestCase):
    def test_kinds_and_spaced_mdns_serials(self):
        transports = adm.parse_transports(LISTING)
        self.assertEqual([(t.serial, t.kind) for t in transports], [
            ("13171JEC200817", "usb"), ("192.168.0.221:41765", "tcp"),
            ("adb-3620105H804AS8-BswZ64 (2)._adb-tls-connect._tcp", "mdns"),
            ("adb-3620105H804AS8-BswZ64._adb-tls-connect._tcp", "mdns"),
            ("emulator-5554", "emulator")])

    def test_one_device_per_hardware_serial_usb_first(self):
        devices = adm.dedupe(adm.parse_transports(LISTING), HW.get)
        by_hw = {d.hw_serial: d for d in devices}
        self.assertEqual(len(devices), 3)
        self.assertEqual(by_hw["13171JEC200817"].serial, "13171JEC200817")
        self.assertEqual(by_hw["13171JEC200817"].other_serials, ["192.168.0.221:41765"])
        self.assertEqual(by_hw["13171JEC200817"].label, "Pixel 4a")

    def test_same_model_twice_gets_distinct_labels(self):
        devices = adm.dedupe([adm.Transport("A1111", "usb", "Pixel_4a"), adm.Transport("B2222", "usb", "Pixel_4a")],
                             lambda s: s)
        self.assertEqual(sorted(d.label for d in devices), ["Pixel 4a #1111", "Pixel 4a #2222"])

    def test_registry_names_and_personal_flags(self):
        text = """# inventory
android:
  pixel-4a:
    model: "Pixel 4a"
    serial: "13171JEC200817"
    personal: false
  s25-ultra:
    model: "Galaxy S25 Ultra"   # day-to-day phone
    serial: "R5CY62D2QDH"
    personal: true
mac:
  host: "x.local"
  personal: true
"""
        self.assertEqual(adm.read_registry(text), {"13171JEC200817": ("Pixel 4a", False),
                                                   "R5CY62D2QDH": ("Galaxy S25 Ultra", True)})

    def test_designated_serials_from_properties_and_env(self):
        props = {"pst.testDevice.serial": "CQ1", "pst.testDevice.extraSerials": "A, B,"}
        self.assertEqual(adm.designated_serials(props, {"ANDROID_SERIAL": "E"}), {"CQ1", "A", "B", "E"})


def feed(parser, text):
    events = []
    for line in text.splitlines():
        event = parser.feed(line)
        if event is not None:
            events.append(event)
    return events


def status(cls, test, code, current=1, numtests=3, extra=""):
    return (f"INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: current={current}\n"
            f"INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\nINSTRUMENTATION_STATUS: numtests={numtests}\n"
            f"{extra}INSTRUMENTATION_STATUS: stream=\nINSTRUMENTATION_STATUS: test={test}\n"
            f"INSTRUMENTATION_STATUS_CODE: {code}\n")


class ParserTest(unittest.TestCase):
    def setUp(self):
        ticks = iter(range(0, 1000, 2))
        self.parser = adm.InstrumentationParser("phototok", clock=lambda: float(next(ticks)))

    def test_pass_fail_ignored_and_finish(self):
        stack = "INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: expected 3\n\tat com.x.FooTest.b(FooTest.kt:9)\n"
        out = (status("com.x.FooTest", "a", 1) + status("com.x.FooTest", "a", 0)
               + status("com.x.FooTest", "b", 1, 2) + status("com.x.FooTest", "b", -2, 2, extra=stack)
               + status("com.x.BarTest", "c", 1, 3) + status("com.x.BarTest", "c", -3, 3)
               + "INSTRUMENTATION_RESULT: stream=\nTime: 1\n\nFAILURES!!!\nINSTRUMENTATION_CODE: -1\n")
        events = feed(self.parser, out)
        self.assertEqual(events[0], ("start", "com.x.FooTest", "a", 1, 3))
        results = self.parser.results
        self.assertEqual([(r.name, r.status) for r in results], [("a", "passed"), ("b", "failed"), ("c", "skipped")])
        self.assertEqual(results[1].message, "java.lang.AssertionError: expected 3")
        self.assertIn("FooTest.kt:9", results[1].stack)
        self.assertEqual(results[0].seconds, 2.0)
        self.assertEqual(results[1].key, "phototok:com.x.FooTest#b")
        self.assertTrue(self.parser.finished)
        self.assertEqual(self.parser.failure, "")

    def test_process_crash_fails_the_running_test(self):
        out = (status("com.x.FooTest", "a", 1) + "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n"
               + "INSTRUMENTATION_CODE: 0\n")
        feed(self.parser, out)
        self.assertEqual(self.parser.failure, "Process crashed.")
        crashed = self.parser.close("")
        self.assertEqual((crashed.name, crashed.status, crashed.message), ("a", "failed", "Process crashed."))

    def test_missing_instrumentation_is_a_failure(self):
        feed(self.parser, "INSTRUMENTATION_STATUS: Error=Unable to find instrumentation info for: x\n"
                          "INSTRUMENTATION_STATUS_CODE: -1\nINSTRUMENTATION_FAILED: x/y\n")
        self.assertIn("Unable to find instrumentation", self.parser.failure)
        self.assertIsNone(self.parser.close(""))

    def test_hang_closes_with_the_reason(self):
        feed(self.parser, status("com.x.FooTest", "a", 1))
        hung = self.parser.close("hung — no output for 10m00s")
        self.assertEqual(hung.message, "hung — no output for 10m00s")


class HelpersTest(unittest.TestCase):
    def test_instrumentation_component(self):
        listing = ("instrumentation:com.phototok.test/androidx.test.runner.AndroidJUnitRunner (target=com.phototok)\n"
                   "instrumentation:com.photoselectortoolbox.test/com.photoselectortoolbox.HiltTestRunner "
                   "(target=com.photoselectortoolbox)\n")
        self.assertEqual(adm.instrumentation_component(listing, "com.photoselectortoolbox"),
                         "com.photoselectortoolbox.test/com.photoselectortoolbox.HiltTestRunner")
        self.assertIsNone(adm.instrumentation_component(listing, "com.other"))

    def test_resume_skips_finished_classes_and_methods(self):
        results = [adm.TestResult("m", "A", "x", "passed"), adm.TestResult("m", "A", "y", "passed"),
                   adm.TestResult("m", "B", "z", "passed"), adm.TestResult("m", "C", "q", "failed")]
        self.assertEqual(adm.exclusion_for_resume(results, "C"), ["A", "B", "C#q"])

    def test_first_line_takes_the_explanation_after_a_colon(self):
        stack = "x.AccessibilityViewCheckException: There was 1 accessibility result:\nSpeakableTextPresentCheck x\n\tat y"
        self.assertEqual(adm.first_line(stack),
                         "x.AccessibilityViewCheckException: There was 1 accessibility result: SpeakableTextPresentCheck x")
        self.assertEqual(adm.first_line("java.lang.Error: boom\n\tat y"), "java.lang.Error: boom")

    def test_fmt_secs(self):
        self.assertEqual(adm.fmt_secs(4.25), "4.2s")
        self.assertEqual(adm.fmt_secs(125), "2m05s")
        self.assertEqual(adm.fmt_secs(3725), "1h02m")

    def test_modules_match_gradle_application_ids(self):
        for module in adm.MODULES.values():
            gradle = (adm.ANDROID_DIR / module.name / "build.gradle.kts").read_text(encoding="utf-8")
            self.assertRegex(gradle, r'applicationId = "' + re.escape(module.target) + '"')


def device(label, hw):
    return adm.Device(serial=hw, hw_serial=hw, model=label, kind="usb", label=label)


def device_run(label, hw, results=(), status="ran", install_error=""):
    dr = adm.DeviceRun(device(label, hw), status=status, install_error=install_error)
    dr.modules.append(adm.ModuleRun("phototok", results=list(results)))
    return dr


def result(name, state, retry="", message=""):
    return adm.TestResult("phototok", "com.x.T", name, state, 1.0, message, message, retry)


class LedgerTest(unittest.TestCase):
    def test_first_run_records_new_issues(self):
        ledger = adm.empty_ledger()
        runs = [device_run("Pixel", "P1", [result("a", "failed", "failed", "boom"), result("b", "passed")]),
                device_run("Moto", "M1", [result("a", "passed"), result("b", "failed", "passed", "flake")])]
        verdicts = adm.update_ledger(ledger, runs, "r1")
        self.assertEqual(sorted(e["key"] for e in verdicts["new"]), ["phototok:com.x.T#a", "phototok:com.x.T#b"])
        self.assertEqual(ledger["open"]["phototok:com.x.T#a"]["kind"], "test")
        self.assertEqual(ledger["open"]["phototok:com.x.T#b"]["kind"], "flaky")
        self.assertEqual(list(ledger["open"]["phototok:com.x.T#a"]["devices"]), ["P1"])

    def test_next_run_validates_fixed_still_and_unverified(self):
        ledger = adm.empty_ledger()
        adm.update_ledger(ledger, [
            device_run("Pixel", "P1", [result("a", "failed", "failed", "boom"), result("b", "failed", "failed")]),
            device_run("Tab", "T1", [result("c", "failed", "failed")])], "r1")
        verdicts = adm.update_ledger(ledger, [
            device_run("Pixel", "P1", [result("a", "passed"), result("b", "failed", "failed", "again")])], "r2")
        self.assertEqual([e["key"] for e in verdicts["fixed"]], ["phototok:com.x.T#a"])
        self.assertEqual([e["key"] for e in verdicts["still"]], ["phototok:com.x.T#b"])
        self.assertEqual([e["key"] for e in verdicts["unverified"]], ["phototok:com.x.T#c"])
        self.assertEqual(sorted(ledger["open"]), ["phototok:com.x.T#b", "phototok:com.x.T#c"])
        self.assertEqual(ledger["open"]["phototok:com.x.T#b"]["runs_seen"], 2)
        self.assertEqual(ledger["open"]["phototok:com.x.T#b"]["first_seen"], "r1")
        self.assertEqual(ledger["resolved"][-1]["key"], "phototok:com.x.T#a")

    def test_device_that_now_passes_drops_out_of_a_still_failing_issue(self):
        ledger = adm.empty_ledger()
        adm.update_ledger(ledger, [device_run("Pixel", "P1", [result("a", "failed", "failed")]),
                                   device_run("Moto", "M1", [result("a", "failed", "failed")])], "r1")
        adm.update_ledger(ledger, [device_run("Pixel", "P1", [result("a", "failed", "failed")]),
                                   device_run("Moto", "M1", [result("a", "passed")])], "r2")
        self.assertEqual(list(ledger["open"]["phototok:com.x.T#a"]["devices"]), ["P1"])

    def test_install_issue_is_fixed_when_the_device_installs(self):
        ledger = adm.empty_ledger()
        adm.update_ledger(ledger, [device_run("Old", "O1", [], status="failed-setup",
                                              install_error="INSTALL_FAILED_NO_MATCHING_ABIS")], "r1")
        self.assertIn("install:O1", ledger["open"])
        verdicts = adm.update_ledger(ledger, [device_run("Old", "O1", [result("a", "passed")])], "r2")
        self.assertEqual([e["key"] for e in verdicts["fixed"]], ["install:O1"])
        self.assertEqual(ledger["open"], {})

    def test_open_issue_filter_and_brief(self):
        ledger = adm.empty_ledger()
        self.assertEqual(adm.brief_issues(ledger), "")
        adm.update_ledger(ledger, [device_run("Pixel", "P1", [result("a", "failed", "failed")])], "r1")
        self.assertEqual(adm.open_issue_filter(ledger), {"phototok": ["com.x.T#a"]})
        self.assertIn("1 open from run r1", adm.brief_issues(ledger))

    def test_ledger_round_trip_and_unknown_schema(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "issues.json"
            self.assertEqual(adm.load_ledger(path), adm.empty_ledger())
            path.write_text('{"schema": 99}', encoding="utf-8")
            self.assertEqual(adm.load_ledger(path), adm.empty_ledger())


class ReportTest(unittest.TestCase):
    def test_gate_lines_and_report(self):
        runs = [device_run("Pixel", "P1", [result("a", "failed", "failed", "No compose hierarchies found")]),
                device_run("Moto", "M1", [result("a", "failed", "passed")]),
                adm.DeviceRun(device("Tab", "T1"), status="skipped", reason="lock screen showing")]
        lines = adm.gate_lines(runs)
        self.assertEqual([line.split("\t")[0] for line in lines], ["fail", "pass", "skip"])
        ledger = adm.empty_ledger()
        verdicts = adm.update_ledger(ledger, runs, "r1")
        meta = {"run_id": "r1", "started_at": "now", "wall_seconds": 61.0, "build_seconds": 20.0, "tree": "main @ abc",
                "scope": "full", "run_dir": "/tmp/x", "ledger": "/tmp/issues.json"}
        report = adm.render_report(meta, runs, verdicts, ledger)
        self.assertIn("❌ issues found", report)
        self.assertIn("wall time **1m01s**", report)
        self.assertIn("### 1. 1 test(s) on 1 device(s)", report)
        self.assertIn("### 2. 1 test(s) on 1 device(s) — flaky (passed on retry)", report)
        self.assertIn("- `phototok: T#a` — 1/2 device(s) that ran it", report)
        self.assertIn("(no message)", report)
        self.assertIn("screen was locked", report)
        self.assertIn("--only-open-issues", report)


class ClusterTest(unittest.TestCase):
    def test_same_cause_on_different_screens_is_one_cluster(self):
        hits = [("Tab", adm.TestResult("m", "p.C", "a", "failed", message="View with bounds: [2812,96][2932,184]: 44dp")),
                ("Pixel Tablet", adm.TestResult("m", "p.C", "b", "failed", message="View with bounds: [10,9][1,1]: 44dp")),
                ("Tab", adm.TestResult("m", "p.D", "c", "failed", message="other"))]
        groups = adm.clusters(hits)
        self.assertEqual(groups[0]["tests"], ["m:p.C#a", "m:p.C#b"])
        self.assertEqual(groups[0]["devices"], ["Tab", "Pixel Tablet"])
        self.assertEqual(adm.short_key("phototok:com.x.FooTest#bar"), "phototok: FooTest#bar")


class FakeRunner:
    def __init__(self):
        self.calls = []

    def __call__(self, cmd, timeout=120):
        self.calls.append(list(cmd))
        return 0, "Success"


class ScriptedRunner:
    """adb fake: ``pm path`` answers from ``present``; ``install`` answers from ``install_out``."""

    def __init__(self, present, install_out):
        self.present, self.install_out, self.calls = set(present), list(install_out), []

    def __call__(self, cmd, timeout=120):
        self.calls.append(list(cmd))
        args = cmd[3:]
        if args[:1] == ["shell"] and args[1].startswith("pm path "):
            pkg = args[1].split()[-1]
            return (0, f"package:/data/app/{pkg}/base.apk") if pkg in self.present else (1, "")
        if args[:1] == ["shell"] and args[1].startswith("dumpsys package"):
            return 0, "    versionName=0.5.0"
        if args[:1] == ["install"]:
            return self.install_out.pop(0)
        return 0, "Success"


def worker_with(runner, tmp, replace=False):
    opts = adm.Options([adm.MODULES["android-desktop"]], {}, replace_installed=replace)
    return adm.DeviceWorker(adm.Device("S1", "S1", "Tab", "mdns", label="Tab"), opts, Path(tmp),
                            adm.Progress(Path(tmp) / "p.log", 0.0, echo=lambda _: None), threading.Event(),
                            lambda: True, threading.Event(), adm.RestoreBook(Path(tmp) / "r.json"), runner)


class InstallTest(unittest.TestCase):
    MISMATCH = (1, "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]")

    def test_a_differently_signed_install_is_left_alone_by_default(self):
        with tempfile.TemporaryDirectory() as tmp:
            runner = ScriptedRunner({"com.photoselectortoolbox"}, [self.MISMATCH])
            worker = worker_with(runner, tmp)
            self.assertEqual(worker._install_one(Path("app.apk"), "com.photoselectortoolbox"), adm.KEPT_EXISTING)
            self.assertNotIn("uninstall", [c[3] for c in runner.calls])
            self.assertEqual(worker.installed, [])

    def test_an_app_updated_in_place_is_never_uninstalled(self):
        with tempfile.TemporaryDirectory() as tmp:
            runner = ScriptedRunner({"com.photoselectortoolbox"}, [(0, "Success")])
            worker = worker_with(runner, tmp)
            self.assertEqual(worker._install_one(Path("app.apk"), "com.photoselectortoolbox"), "")
            self.assertEqual(worker.installed, [])
            worker._cleanup()
            self.assertNotIn("uninstall", [c[3] for c in runner.calls])

    def test_replace_installed_removes_it_and_says_so(self):
        with tempfile.TemporaryDirectory() as tmp:
            runner = ScriptedRunner({"com.photoselectortoolbox"}, [self.MISMATCH, (0, "Success")])
            worker = worker_with(runner, tmp, replace=True)
            self.assertEqual(worker._install_one(Path("app.apk"), "com.photoselectortoolbox"), "")
            self.assertEqual(worker.installed, ["com.photoselectortoolbox"])
            self.assertIn("0.5.0", worker.result.notes[-1])

    def test_a_fresh_install_is_removed_at_the_end(self):
        with tempfile.TemporaryDirectory() as tmp:
            runner = ScriptedRunner(set(), [(0, "Success")])
            worker = worker_with(runner, tmp)
            worker._install_one(Path("app.apk"), "com.photoselectortoolbox")
            worker._cleanup()
            self.assertIn(["uninstall", "com.photoselectortoolbox"], [c[3:] for c in runner.calls])


class DisconnectTest(unittest.TestCase):
    def test_a_disconnect_is_not_a_failure_and_the_run_resumes_with_the_rest(self):
        passes = [status("p.C", "a", 1, 1, 2) + status("p.C", "a", 0, 1, 2) + status("p.C", "b", 1, 2, 2),
                  status("p.C", "b", 1, 1, 1) + status("p.C", "b", 0, 1, 1) + "INSTRUMENTATION_CODE: -1\n"]
        commands = []

        def fake_stream(cmd, on_line, idle_timeout, stop):
            commands.append(cmd)
            for line in passes.pop(0).splitlines():
                on_line(line)
            return ""

        states = iter([(1, "error: device not found"), (0, "device")])

        class Runner(ScriptedRunner):
            def __call__(self, cmd, timeout=120):
                if cmd[3:] == ["get-state"]:
                    return next(states)
                if cmd[3:5] == ["shell", "pm list instrumentation"]:
                    return 0, "instrumentation:com.photoselectortoolbox.test/x.Runner (target=com.photoselectortoolbox)"
                return super().__call__(cmd, timeout)

        original = adm.stream_command
        adm.stream_command = fake_stream
        try:
            with tempfile.TemporaryDirectory() as tmp:
                worker = worker_with(Runner(set(), []), tmp)
                worker.opts.only = {"android-desktop": ["p.C#a", "p.C#b"]}
                worker.dir.mkdir(parents=True)
                mrun = worker._run_module(adm.MODULES["android-desktop"])
        finally:
            adm.stream_command = original
        self.assertEqual([(r.name, r.status) for r in mrun.results], [("a", "passed"), ("b", "passed")])
        self.assertIn(adm.DISCONNECTED, mrun.problems)
        self.assertEqual(commands[1][commands[1].index("class") + 1], "p.C#b")


class WorkerCleanupTest(unittest.TestCase):
    def test_cleanup_uninstalls_only_what_this_run_installed_and_restores(self):
        with tempfile.TemporaryDirectory() as tmp:
            runner = FakeRunner()
            book = adm.RestoreBook(Path(tmp) / "restore.json")
            book.save("S1", {"system/screen_off_timeout": "30000", "global/stay_on_while_plugged_in": "null"})
            opts = adm.Options(list(adm.MODULES.values()), {})
            worker = adm.DeviceWorker(adm.Device("S1", "S1", "Phone", "usb", label="Phone"), opts, Path(tmp),
                                      adm.Progress(Path(tmp) / "p.log", 0.0, echo=lambda _: None),
                                      threading.Event(), lambda: True, threading.Event(), book, runner)
            worker.installed = ["com.phototok", "com.phototok.test"]
            worker._cleanup()
            self.assertEqual([c[3:] for c in runner.calls], [
                ["uninstall", "com.phototok.test"], ["uninstall", "com.phototok"],
                ["shell", "settings put system screen_off_timeout 30000"],
                ["shell", "settings delete global stay_on_while_plugged_in"]])
            self.assertEqual(book.leftovers(), {})

    def test_restore_book_keeps_the_first_original(self):
        with tempfile.TemporaryDirectory() as tmp:
            book = adm.RestoreBook(Path(tmp) / "restore.json")
            book.save("S1", {"system/screen_off_timeout": "30000"})
            book.save("S1", {"system/screen_off_timeout": "2147483647"})
            self.assertEqual(book.pop("S1"), {"system/screen_off_timeout": "30000"})


if __name__ == "__main__":
    unittest.main()
