"""Unit tests for scripts/android_device_matrix.py (no device needed)."""

from __future__ import annotations

from pathlib import Path
import re
import sys
import tempfile
import threading
import unittest
from unittest import mock
from datetime import datetime

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


class ValidationVerdictTest(unittest.TestCase):
    """The shape of run 20261105-202303: the ledger had the issue on the Pixel 4a only; this run it
    passed there and failed (retry failed too) on three other devices, one of which also had a flake."""

    NAME = "aTreeDocumentResolvesAndItsFlagIsSetClearedAndReadBack"
    GRANT = "java.lang.AssertionError: no persisted, writable primary-storage tree grant: pick a folder in the app once"

    def pixel_4a_failed(self):
        return device_run("Pixel 4a", "13171JEC200817", [result(self.NAME, "failed", "failed", "x")])

    def runs(self):
        return [device_run("Pixel 4a", "13171JEC200817", [result(self.NAME, "passed")]),
                device_run("Sony Xperia XA2 (H3113)", "CQ30012CMV",
                           [result(self.NAME, "failed", "failed", self.GRANT)]),
                device_run("Moto G77", "ZY22MQCBL6", [result(self.NAME, "failed", "failed", self.GRANT)]),
                device_run("Pixel Tablet", "3620105H804AS8", [result(self.NAME, "failed", "passed", self.GRANT)])]

    def validation_row(self, ledger, runs):
        verdicts = adm.update_ledger(ledger, runs, "r2")
        meta = {"run_id": "r2", "started_at": "now", "wall_seconds": 1.0, "build_seconds": 1.0, "tree": "main @ abc",
                "scope": "full", "run_dir": "/tmp/x", "ledger": "/tmp/issues.json"}
        report = adm.render_report(meta, runs, verdicts, ledger)
        return verdicts, [line for line in report.splitlines() if "still failing" in line or "fixed" in line]

    def test_the_this_run_cell_names_every_device_that_failed_and_the_one_that_passed(self):
        ledger = adm.empty_ledger()
        adm.update_ledger(ledger, [self.pixel_4a_failed()], "r1")
        verdicts, rows = self.validation_row(ledger, self.runs())
        self.assertEqual([e["key"] for e in verdicts["still"]], [f"phototok:com.x.T#{self.NAME}"])
        seen = verdicts["still"][0]["seen"]
        self.assertEqual(seen, {"Sony Xperia XA2 (H3113)": "failed", "Moto G77": "failed", "Pixel Tablet": "flaky",
                                "Pixel 4a": "passed"})
        self.assertEqual(list(seen)[-1], "Pixel 4a")   # failures first, then the devices that passed
        self.assertEqual(len(rows), 1)
        for fragment in ("❌ still failing", "Sony Xperia XA2 (H3113): failed", "Moto G77: failed",
                         "Pixel Tablet: flaky", "Pixel 4a: passed"):
            self.assertIn(fragment, rows[0])

    def test_the_ledger_keeps_its_semantics_the_passing_device_drops_out_and_the_new_ones_come_in(self):
        ledger = adm.empty_ledger()
        adm.update_ledger(ledger, [self.pixel_4a_failed()], "r1")
        adm.update_ledger(ledger, self.runs(), "r2")
        issue = ledger["open"][f"phototok:com.x.T#{self.NAME}"]
        self.assertEqual(sorted(issue["devices"]), ["3620105H804AS8", "CQ30012CMV", "ZY22MQCBL6"])
        self.assertEqual((issue["first_seen"], issue["runs_seen"]), ("r1", 2))

    def test_a_fixed_issue_still_lists_only_the_devices_that_ran_it(self):
        ledger = adm.empty_ledger()
        adm.update_ledger(ledger, [self.pixel_4a_failed()], "r1")
        passed = device_run("Pixel 4a", "13171JEC200817", [result(self.NAME, "passed")])
        verdicts, rows = self.validation_row(ledger, [passed])
        self.assertEqual([e["key"] for e in verdicts["fixed"]], [f"phototok:com.x.T#{self.NAME}"])
        self.assertIn("Pixel 4a: passed", rows[0])
        self.assertIn("✅ fixed", rows[0])

    def test_an_install_issue_that_persists_names_the_device(self):
        ledger = adm.empty_ledger()
        adm.update_ledger(ledger, [device_run("Old", "O1", [], status="failed-setup", install_error="E1")], "r1")
        verdicts = adm.update_ledger(ledger, [
            device_run("Old", "O1", [], status="failed-setup", install_error="E1"),
            device_run("New", "N1", [], status="failed-setup", install_error="E2")], "r2")
        self.assertEqual(verdicts["still"][0]["seen"], {"Old": "failed"})


HUNG = "hung — no output for 10m00s"


def hung_result(name="goToPhoto_keyG", retry="passed"):
    return adm.TestResult("android-desktop", "com.x.SelectorScreenTest", name, "failed", 600.0, HUNG, HUNG, retry,
                          hung=True)


class HungThenPassedTest(unittest.TestCase):
    """Run 20261105-202303 reported the Moto G77's hung goToPhoto_keyG… as "flaky (passed on retry)"."""

    def runs(self):
        dr = adm.DeviceRun(device("Moto G77", "M1"), status="ran")
        dr.modules.append(adm.ModuleRun("android-desktop", results=[
            hung_result(), result("plain", "failed", "passed", "boom")]))
        return [dr]

    def test_the_matrix_marks_a_killed_test_as_hung(self):
        with tempfile.TemporaryDirectory() as tmp:
            worker, _ = display_worker(FakeDisplayDevice(), tmp)
            original = adm.stream_command
            adm.stream_command = hung_stream(lambda: None)
            try:
                mrun = adm.ModuleRun("android-desktop")
                worker._instrument(adm.MODULES["android-desktop"], "x/Runner", [], worker.dir / "i.txt", mrun)
            finally:
                adm.stream_command = original
            self.assertTrue(mrun.results[-1].hung)
            parser = adm.InstrumentationParser("m")
            feed(parser, status("p.C", "t", 1, 1, 1))
            self.assertFalse(parser.close("instrumentation crashed").hung)

    def test_properties_hung_is_not_flaky_and_both_are_recovered(self):
        hung, flaky = hung_result(), result("plain", "failed", "passed")
        self.assertTrue(hung.hung_then_passed and hung.recovered and not hung.flaky)
        self.assertTrue(flaky.flaky and flaky.recovered and not flaky.hung_then_passed)
        self.assertFalse(hung_result(retry="failed").hung_then_passed)   # still hung, never recovered: a plain failure

    def test_ledger_outcome_and_kind_say_hung(self):
        ledger = adm.empty_ledger()
        adm.update_ledger(ledger, self.runs(), "r1")
        hung_issue = ledger["open"]["android-desktop:com.x.SelectorScreenTest#goToPhoto_keyG"]
        self.assertEqual(hung_issue["kind"], "hung")
        self.assertEqual(hung_issue["devices"]["M1"]["outcome"], "hung")
        self.assertEqual(ledger["open"]["phototok:com.x.T#plain"]["kind"], "flaky")

    def test_report_names_the_hang_in_the_heading_headline_and_table(self):
        runs = self.runs()
        ledger = adm.empty_ledger()
        verdicts = adm.update_ledger(ledger, runs, "r1")
        meta = {"run_id": "r1", "started_at": "now", "wall_seconds": 1.0, "build_seconds": 1.0, "tree": "main @ abc",
                "scope": "full", "run_dir": "/tmp/x", "ledger": "/tmp/issues.json"}
        report = adm.render_report(meta, runs, verdicts, ledger)
        self.assertIn("1 hung (passed on retry)", report)
        self.assertIn("1 flaky", report)
        self.assertIn("— HUNG, then passed on retry (killed after no output; not flakiness)", report)
        self.assertIn("— flaky (passed on retry)", report)
        self.assertIn("| Flaky | Hung |", report)
        self.assertIn("| 0 | 0 | 1 | 1 | 0 |", report)   # passed, failed, flaky, hung, skipped
        sections = report.split("### ")
        hung_section = next(x for x in sections if "HUNG" in x)
        self.assertNotIn("flaky (passed on retry)", hung_section)
        self.assertIn(HUNG, hung_section)

    def test_gate_and_ledger_validation(self):
        self.assertEqual(adm.gate_lines(self.runs())[0].split("\t")[0], "pass")   # recovered on retry: as flaky was
        ledger = adm.empty_ledger()
        adm.update_ledger(ledger, self.runs(), "r1")
        clean = adm.DeviceRun(device("Moto G77", "M1"), status="ran")
        clean.modules.append(adm.ModuleRun("android-desktop", results=[
            adm.TestResult("android-desktop", "com.x.SelectorScreenTest", "goToPhoto_keyG", "passed")]))
        verdicts = adm.update_ledger(ledger, [clean], "r2")
        self.assertEqual([e["key"] for e in verdicts["fixed"]],
                         ["android-desktop:com.x.SelectorScreenTest#goToPhoto_keyG"])

    def test_an_old_ledger_without_hung_still_loads_and_validates(self):
        old = {"schema": 1, "updated_run": "20261104-061934", "resolved": [], "open": {
            "phototok:com.x.T#a": {"kind": "flaky", "title": "phototok:com.x.T#a", "test_key": "phototok:com.x.T#a",
                                   "first_seen": "r0", "last_seen": "r0", "runs_seen": 1,
                                   "devices": {"P1": {"label": "Pixel", "outcome": "flaky", "message": "m"}}}}}
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "issues.json"
            path.write_text(__import__("json").dumps(old), encoding="utf-8")
            ledger = adm.load_ledger(path)
        self.assertEqual(list(ledger["open"]), ["phototok:com.x.T#a"])
        verdicts = adm.update_ledger(ledger, [device_run("Pixel", "P1", [result("a", "failed", "passed", "m")])], "r1")
        self.assertEqual([e["key"] for e in verdicts["still"]], ["phototok:com.x.T#a"])
        self.assertEqual(ledger["open"]["phototok:com.x.T#a"]["runs_seen"], 2)


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


# ── Display state after a killed or lost test ────────────────────────────────

# Verbatim from the Moto G77 after run 20261105-202303 (read back by hand), and the same device
# before WindowGeometryRule touched it.
FIXED_MODES_FOR_TEST = ("default", "enabled", "disabled", "enabled_if_no_auto_rotation")
WM_SIZE_LEAKED = "Physical size: 1080x2352\nOverride size: 1848x2960\n"
WM_DENSITY_LEAKED = "Physical density: 390\nOverride density: 320\n"
WM_SIZE_CLEAN = "Physical size: 1080x2352\n"
WM_DENSITY_CLEAN = "Physical density: 390\n"


class FakeDisplayDevice:
    """adb fake that keeps display state and answers ``wm`` / ``settings`` like the real device.

    ``ignore_wm_reset`` makes ``wm size reset`` a silent no-op (exit 0, nothing changes): a step
    whose exit status says nothing about its effect. ``lag`` is the number of ``wm size`` reads
    before a new size shows. ``rotation_resaved`` makes the window manager put the old rotation
    back once after the first ``user_rotation`` put."""

    def __init__(self, size_override="1848x2960", density_override="320", user_rotation="1",
                 accelerometer_rotation="0", ignore_wm_reset=False, lag=0, rotation_resaved=False,
                 serial="S1", fixed=None, fixed_command="fixed-to-user-rotation"):
        self.size, self.density = size_override, density_override
        self.settings = {"system/user_rotation": user_rotation,
                         "system/accelerometer_rotation": accelerometer_rotation}
        self.ignore_wm_reset, self.lag, self.rotation_resaved = ignore_wm_reset, lag, rotation_resaved
        self.pending_size = None
        # `fixed` is what the display holds: a mode word on API 31+ ("default", "enabled", "disabled",
        # "enabled_if_no_auto_rotation"), "true" | "false" up to API 30 (dumpsys only), None when the
        # build reports nothing. `fixed_command` is the spelling this Android version knows:
        # "fixed-to-user-rotation" (API 31+), "set-fix-to-user-rotation" (up to API 30), or neither.
        self.fixed, self.fixed_command = fixed, fixed_command
        self.calls = []
        self.connected = True
        self.serial = serial

    def shell_commands(self):
        return [c[4] for c in self.calls if c[3:4] == ["shell"]]

    def __call__(self, cmd, timeout=120):
        self.calls.append(list(cmd))
        if cmd[3:] == ["get-state"]:
            return (0, "device") if self.connected else (1, "error: device not found")
        if cmd[3:4] != ["shell"] or len(cmd) < 5:
            return 0, "Success"
        out, self.exit_code = "", 0
        for part in cmd[4].split(" && "):
            out = self._one(part.strip())
        return self.exit_code, out

    def _one(self, command):
        words = command.split()
        if command == "wm size":
            shown = self.size
            if self.pending_size is not None:
                self.lag -= 1
                if self.lag < 0:
                    self.size, self.pending_size = self.pending_size[0], None
                    shown = self.size
            return WM_SIZE_CLEAN + (f"Override size: {shown}\n" if shown else "")
        if command == "wm density":
            return WM_DENSITY_CLEAN + (f"Override density: {self.density}\n" if self.density else "")
        if command == "dumpsys window displays":
            # Pixel Tablet, API 37: `grep -o mFixedToUserRotation=[a-z]*` gave `false` then `null`
            # (two displays); the boolean is what the mode makes of it, `default` included.
            boolean = "true" if self.fixed in ("true", "enabled") else "false"
            return ("WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n"
                    "  Display: mDisplayId=0 (organized)\n"
                    "    init=1080x2352 390dpi cur=1080x2352 app=1080x2352 rng=1080x973-2352x2245\n"
                    + (f"    mFixedToUserRotation={boolean}\n" if self.fixed else "")
                    + "    mUserRotationMode=USER_ROTATION_LOCKED mUserRotation=ROTATION_0\n"
                    + ("  Display: mDisplayId=2 (organized)\n    mFixedToUserRotation=null\n"
                       if self.fixed in FIXED_MODES_FOR_TEST else ""))
        if words[:2] == ["cmd", "window"]:
            if len(words) < 3 or words[2] != self.fixed_command or self.fixed is None:
                self.exit_code = 255
                return f"Unknown command: {words[2] if len(words) > 2 else ''}\n"   # verbatim, exit 255
            if len(words) == 3:                      # no argument: print the mode
                if self.fixed_command == "fixed-to-user-rotation":
                    return f"{self.fixed}\n"
                self.exit_code = 255
                return "Error: expecting enabled or disabled\n"
            if self.fixed_command == "fixed-to-user-rotation":
                self.fixed = words[3]
            else:
                self.fixed = "true" if words[3] == "enabled" else "false"
            return ""
        if words[:2] == ["wm", "size"]:
            if words[2] == "reset":
                if not self.ignore_wm_reset:
                    self._set_size(None)
            else:
                self._set_size(words[2])
        elif words[:2] == ["wm", "density"]:
            if words[2] == "reset":
                if not self.ignore_wm_reset:
                    self.density = None
            else:
                self.density = words[2]
        elif words[:2] == ["settings", "get"]:
            return self.settings.get(f"{words[2]}/{words[3]}", "null") + "\n"
        elif words[:2] == ["settings", "put"]:
            key = f"{words[2]}/{words[3]}"
            old = self.settings.get(key)
            self.settings[key] = words[4]
            if key == "system/user_rotation" and self.rotation_resaved:
                self.rotation_resaved = False
                self.settings[key] = old   # the window manager writes the old value back, once
        elif words[:2] == ["settings", "delete"]:
            self.settings.pop(f"{words[2]}/{words[3]}", None)
        return ""

    def _set_size(self, value):
        if self.lag > 0:
            self.pending_size = (value,)
        else:
            self.size = value


def display_worker(runner, tmp, designated=True, kind="usb", book_entry=None):
    book = adm.RestoreBook(Path(tmp) / "r.json")
    if book_entry:
        book.save("S1", book_entry)
    opts = adm.Options([adm.MODULES["android-desktop"]], {})
    dev = adm.Device("S1", "S1", "Moto G77", kind, label="Moto G77", designated=designated)
    worker = adm.DeviceWorker(dev, opts, Path(tmp), adm.Progress(Path(tmp) / "p.log", 0.0, echo=lambda _: None),
                              threading.Event(), lambda: True, threading.Event(), book, runner)
    worker.sleep = lambda seconds: None
    worker.dir.mkdir(parents=True, exist_ok=True)
    return worker, book


PHONE_ORIGINALS = {"display/size": "reset", "display/density": "reset",
                   "system/user_rotation": "0", "system/accelerometer_rotation": "0"}


class ReadDisplayTest(unittest.TestCase):
    def test_reads_the_real_wm_output_with_and_without_override_lines(self):
        leaked = adm.read_display("S1", FakeDisplayDevice())
        self.assertEqual(leaked, {"display/size": "1848x2960", "display/density": "320",
                                  "system/user_rotation": "1", "system/accelerometer_rotation": "0"})
        clean = adm.read_display("S1", FakeDisplayDevice(size_override=None, density_override=None,
                                                         user_rotation="0"))
        self.assertEqual(clean["display/size"], "reset")
        self.assertEqual(clean["display/density"], "reset")
        self.assertEqual(clean["system/user_rotation"], "0")

    def test_an_unreachable_device_reads_as_nothing(self):
        self.assertEqual(adm.read_display("S1", lambda cmd, timeout=120: (1, "error: device offline")), {})

    def test_the_fake_replays_the_text_the_coordinator_read_back(self):
        dev = FakeDisplayDevice()
        self.assertEqual(dev(["adb", "-s", "S1", "shell", "wm size"])[1], WM_SIZE_LEAKED)
        self.assertEqual(dev(["adb", "-s", "S1", "shell", "wm density"])[1], WM_DENSITY_LEAKED)


class RestoreDisplayTest(unittest.TestCase):
    def test_prepared_device_gets_the_prepare_steps_and_reads_back_clean(self):
        dev = FakeDisplayDevice()
        out = adm.restore_display("S1", adm.PREPARED_DISPLAY, dev, prepare=True, sleep=lambda s: None)
        self.assertTrue(out.ok and out.changed, out.problems)
        steps = dict(adm.atd.PREPARE_STEPS)
        sent = dev.shell_commands()
        self.assertIn(steps[adm.GEOMETRY_STEP], sent)
        self.assertIn(steps[adm.ROTATION_STEP], sent)
        self.assertLess(sent.index(steps[adm.GEOMETRY_STEP]), sent.index(steps[adm.ROTATION_STEP]))
        after = adm.read_display("S1", dev)
        self.assertEqual(after, PHONE_ORIGINALS)
        self.assertEqual(out.before["display/size"], "1848x2960")

    def test_nothing_is_sent_when_the_display_is_already_right(self):
        dev = FakeDisplayDevice(size_override=None, density_override=None, user_rotation="0")
        out = adm.restore_display("S1", adm.PREPARED_DISPLAY, dev, prepare=True, sleep=lambda s: None)
        self.assertTrue(out.ok)
        self.assertFalse(out.changed)
        reads = ("wm size", "wm density", "settings get", "dumpsys window displays")
        self.assertTrue(all((c.startswith(reads) or c == "cmd window fixed-to-user-rotation") and "reset" not in c
                            for c in dev.shell_commands()), dev.shell_commands())

    def test_a_recorded_override_is_restored_not_reset(self):
        # A Chromebook the owner had set to 1080x1920 / 280 before the run.
        dev = FakeDisplayDevice()
        wanted = {"display/size": "1080x1920", "display/density": "280",
                  "system/user_rotation": "null", "system/accelerometer_rotation": "1"}
        out = adm.restore_display("S1", wanted, dev, sleep=lambda s: None)
        self.assertTrue(out.ok, out.problems)
        state = adm.read_display("S1", dev)
        self.assertEqual(state["display/size"], "1080x1920")
        self.assertEqual(state["display/density"], "280")
        self.assertEqual(state["system/user_rotation"], "null")      # settings delete
        self.assertEqual(state["system/accelerometer_rotation"], "1")
        self.assertNotIn("wm size reset", dev.shell_commands())

    def test_a_restore_whose_command_does_nothing_is_a_failure_after_polling(self):
        dev = FakeDisplayDevice(ignore_wm_reset=True)
        sleeps = []
        out = adm.restore_display("S1", adm.PREPARED_DISPLAY, dev, prepare=True, sleep=sleeps.append)
        self.assertFalse(out.ok)
        self.assertIn("size is 1848x2960 after the restore, wanted reset", " ".join(out.problems))
        self.assertGreaterEqual(len([s for s in sleeps if s == adm.DISPLAY_POLL_SECONDS]), adm.DISPLAY_POLLS)

    def test_a_size_that_lands_late_is_waited_for(self):
        dev = FakeDisplayDevice(lag=3)
        out = adm.restore_display("S1", adm.PREPARED_DISPLAY, dev, prepare=True, sleep=lambda s: None)
        self.assertTrue(out.ok, out.problems)
        self.assertEqual(adm.read_display("S1", dev)["display/size"], "reset")

    def test_a_rotation_the_window_manager_writes_back_is_put_again(self):
        dev = FakeDisplayDevice(rotation_resaved=True)
        out = adm.restore_display("S1", adm.PREPARED_DISPLAY, dev, prepare=True, sleep=lambda s: None)
        self.assertTrue(out.ok, out.problems)
        puts = [c for c in dev.shell_commands() if "user_rotation 0" in c]
        self.assertEqual(len(puts), 2)

    def test_an_unreachable_device_is_a_problem_not_a_silent_success(self):
        out = adm.restore_display("S1", adm.PREPARED_DISPLAY, lambda cmd, timeout=120: (1, "offline"),
                                  prepare=True, sleep=lambda s: None)
        self.assertFalse(out.ok)
        self.assertIn("unreadable", out.problems[0])

    def test_restore_settings_restores_the_display_keys_and_reports_a_failed_readback(self):
        dev = FakeDisplayDevice()
        saved = {"system/screen_off_timeout": "30000", **PHONE_ORIGINALS}
        self.assertTrue(adm.restore_settings("S1", saved, dev, sleep=lambda s: None))
        self.assertEqual(adm.read_display("S1", dev), PHONE_ORIGINALS)
        stuck = FakeDisplayDevice(ignore_wm_reset=True)
        self.assertFalse(adm.restore_settings("S1", saved, stuck, sleep=lambda s: None))


class FixedToUserRotationTest(unittest.TestCase):
    """WindowGeometryRule's `cmd window … enabled` is skipped by a kill: the display stays fixed to
    the user rotation. Real Pixel Tablet (API 37) output: `cmd window set-fix-to-user-rotation
    enabled` -> "Unknown command: set-fix-to-user-rotation", exit 255; `cmd window
    fixed-to-user-rotation` -> "default", exit 0; dumpsys prints `mFixedToUserRotation=false` for
    display 0 and `=null` for a second one."""

    OLD = "set-fix-to-user-rotation"

    def wanted(self, mode):
        return dict(adm.PREPARED_DISPLAY, **{adm.FIXED_ROTATION: mode})

    def test_the_real_mode_is_recorded_not_the_dumpsys_boolean(self):
        dev = FakeDisplayDevice(fixed="default")
        self.assertEqual(dev(["adb", "-s", "S1", "shell", "cmd window fixed-to-user-rotation"]), (0, "default\n"))
        self.assertEqual(dev(["adb", "-s", "S1", "shell", "cmd window set-fix-to-user-rotation enabled"]),
                         (255, "Unknown command: set-fix-to-user-rotation\n"))
        self.assertIn("mFixedToUserRotation=false\n", dev(["adb", "-s", "S1", "shell", "dumpsys window displays"])[1])
        self.assertEqual(adm.read_display("S1", dev)[adm.FIXED_ROTATION], "default")
        for mode in ("enabled", "disabled", "enabled_if_no_auto_rotation"):
            self.assertEqual(adm.read_display("S1", FakeDisplayDevice(fixed=mode))[adm.FIXED_ROTATION], mode)

    def test_older_android_falls_back_to_the_first_dumpsys_boolean(self):
        for value in ("true", "false"):
            dev = FakeDisplayDevice(fixed=value, fixed_command=self.OLD)
            self.assertEqual(adm.read_display("S1", dev)[adm.FIXED_ROTATION], value)
        self.assertNotIn(adm.FIXED_ROTATION, adm.read_display("S1", FakeDisplayDevice(fixed=None)))

    def test_a_default_mode_is_restored_as_default_not_disabled(self):
        dev = FakeDisplayDevice(fixed="enabled")     # WindowGeometryRule's leftover
        out = adm.restore_display("S1", self.wanted("default"), dev, prepare=True, sleep=lambda s: None)
        self.assertTrue(out.ok, out.problems)
        self.assertEqual(dev.fixed, "default")
        self.assertEqual(out.after[adm.FIXED_ROTATION], "default")
        sent = dev.shell_commands()
        self.assertIn("cmd window fixed-to-user-rotation default", sent)
        self.assertNotIn("cmd window fixed-to-user-rotation disabled", sent)
        self.assertFalse([c for c in sent if "set-fix-to-user-rotation" in c])
        # the mode goes last, after the rotation was put while the display was still fixed to it
        self.assertGreater(sent.index("cmd window fixed-to-user-rotation default"),
                           max(i for i, c in enumerate(sent) if "user_rotation 0" in c))

    def test_every_mode_round_trips(self):
        for original in ("default", "enabled", "disabled", "enabled_if_no_auto_rotation"):
            dev = FakeDisplayDevice(fixed="enabled" if original != "enabled" else "disabled")
            out = adm.restore_display("S1", self.wanted(original), dev, prepare=True, sleep=lambda s: None)
            self.assertTrue(out.ok, (original, out.problems))
            self.assertEqual(dev.fixed, original)

    def test_an_old_android_is_restored_with_the_old_spelling_and_read_back(self):
        dev = FakeDisplayDevice(fixed="true", fixed_command=self.OLD)
        out = adm.restore_display("S1", self.wanted("false"), dev, prepare=True, sleep=lambda s: None)
        self.assertTrue(out.ok, out.problems)
        self.assertEqual(dev.fixed, "false")
        self.assertIn("cmd window set-fix-to-user-rotation disabled", dev.shell_commands())

    def test_a_mode_that_is_already_right_is_not_touched(self):
        dev = FakeDisplayDevice(fixed="default")
        out = adm.restore_display("S1", self.wanted("default"), dev, prepare=True, sleep=lambda s: None)
        self.assertTrue(out.ok)
        self.assertFalse([c for c in dev.shell_commands() if c.startswith("cmd window") and c.count(" ") > 2])

    def test_a_restore_that_does_not_take_is_a_failure_after_the_readback(self):
        dev = FakeDisplayDevice(fixed="enabled")
        original = dev._one
        dev._one = lambda c: "" if c.startswith("cmd window fixed-to-user-rotation ") else original(c)  # no-op set
        out = adm.restore_display("S1", self.wanted("default"), dev, prepare=True, sleep=lambda s: None)
        self.assertFalse(out.ok)
        self.assertIn("fixed_to_user_rotation is enabled after the restore, wanted default", out.problems[-1])

    def test_a_recorded_boolean_on_a_device_that_lost_the_old_command_is_a_failure(self):
        dev = FakeDisplayDevice(fixed="enabled")     # upgraded since it was recorded: no old spelling
        out = adm.restore_display("S1", self.wanted("false"), dev, prepare=True, sleep=lambda s: None)
        self.assertFalse(out.ok)

    def test_nothing_recorded_means_the_mode_is_not_touched(self):
        dev = FakeDisplayDevice(fixed="enabled")
        out = adm.restore_display("S1", adm.PREPARED_DISPLAY, dev, prepare=True, sleep=lambda s: None)
        self.assertTrue(out.ok)
        self.assertEqual(dev.fixed, "enabled")

    def test_designated_device_records_it_before_prepare_and_restores_it_after_a_hang(self):
        with tempfile.TemporaryDirectory() as tmp:
            dev = FakeDisplayDevice(size_override=None, density_override=None, user_rotation="0", fixed="default")
            worker, book = display_worker(dev, tmp, designated=True)
            worker._record_fixed_rotation()
            self.assertEqual(book.peek("S1"), {adm.FIXED_ROTATION: "default"})
            dev.fixed = "enabled"   # WindowGeometryRule enabled it, then the process was killed
            worker._reset_display("after a hang")
            self.assertEqual(dev.fixed, "default")
            self.assertIn("fixed_to_user_rotation enabled", worker.result.notes[0])
            worker._cleanup()       # and the end of the run pops the record
            self.assertEqual(book.leftovers(), {})
            self.assertEqual(dev.fixed, "default")

    def test_non_designated_device_records_it_with_the_other_originals(self):
        with tempfile.TemporaryDirectory() as tmp:
            dev = FakeDisplayDevice(size_override=None, density_override=None, user_rotation="0", fixed="default")
            worker, book = display_worker(dev, tmp, designated=False)
            worker._apply_temp_settings()
            self.assertEqual(book.peek("S1")[adm.FIXED_ROTATION], "default")
            dev.fixed = "enabled"
            worker._cleanup()
            self.assertEqual(dev.fixed, "default")
            self.assertEqual(book.leftovers(), {})

    def test_a_device_that_reports_nothing_records_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            dev = FakeDisplayDevice(fixed=None)
            worker, book = display_worker(dev, tmp, designated=True)
            worker._record_fixed_rotation()
            self.assertEqual(book.peek("S1"), {})


class RestoreBookDisplayTest(unittest.TestCase):
    def test_older_original_wins_per_key_and_new_keys_are_added(self):
        with tempfile.TemporaryDirectory() as tmp:
            book = adm.RestoreBook(Path(tmp) / "restore.json")
            book.save("S1", {"system/screen_off_timeout": "30000"})
            book.save("S1", {"system/screen_off_timeout": "2147483647", "display/size": "1848x2960"})
            self.assertEqual(book.peek("S1"), {"system/screen_off_timeout": "30000", "display/size": "1848x2960"})
            self.assertEqual(book.peek("S1"), book.pop("S1"))   # peek leaves it; pop takes it
            self.assertEqual(book.peek("S1"), {})

    def test_temp_settings_record_the_display_on_disk_before_the_first_change(self):
        with tempfile.TemporaryDirectory() as tmp:
            dev = FakeDisplayDevice(size_override=None, density_override=None, user_rotation="0")
            worker, book = display_worker(dev, tmp, designated=False)
            on_disk_at_first_put = []
            original = dev._one

            def spy(command):
                if command.startswith("settings put") and not on_disk_at_first_put:
                    on_disk_at_first_put.append(book.peek("S1"))
                return original(command)
            dev._one = spy
            worker._apply_temp_settings()
            self.assertEqual(on_disk_at_first_put[0]["display/size"], "reset")
            self.assertEqual(on_disk_at_first_put[0]["system/user_rotation"], "0")
            self.assertEqual(on_disk_at_first_put[0]["system/accelerometer_rotation"], "0")
            self.assertIn("global/window_animation_scale", on_disk_at_first_put[0])


def hung_stream(leave):
    """A stream_command stand-in: one test starts, the instrumentation is silent, the matrix kills it."""
    def fake(cmd, on_line, idle_timeout, stop):
        for line in (status("p.C", "keyG", 1, 1, 2)).splitlines():
            on_line(line)
        leave()
        return "hung — no output for 10m00s"
    return fake


class DisplayAfterHangTest(unittest.TestCase):
    def run_instrument(self, worker, stream):
        original = adm.stream_command
        adm.stream_command = stream
        try:
            mrun = adm.ModuleRun("android-desktop")
            return worker._instrument(adm.MODULES["android-desktop"], "x/Runner", [],
                                      worker.dir / "i.txt", mrun)
        finally:
            adm.stream_command = original

    def test_a_designated_device_is_put_back_after_the_hung_instrumentation_is_killed(self):
        with tempfile.TemporaryDirectory() as tmp:
            dev = FakeDisplayDevice()   # the leak from the Moto G77
            worker, _ = display_worker(dev, tmp, designated=True)
            self.run_instrument(worker, hung_stream(lambda: None))
            self.assertEqual(adm.read_display("S1", dev), PHONE_ORIGINALS)
            sent = dev.shell_commands()
            self.assertLess(sent.index("am force-stop com.photoselectortoolbox"),
                            sent.index(dict(adm.atd.PREPARE_STEPS)[adm.GEOMETRY_STEP]))
            self.assertTrue(any(n.startswith("display reset after a hang: was size 1848x2960")
                                for n in worker.result.notes), worker.result.notes)

    def test_a_non_designated_device_gets_its_recorded_original_back(self):
        with tempfile.TemporaryDirectory() as tmp:
            dev = FakeDisplayDevice(user_rotation="1")
            originals = {"display/size": "reset", "display/density": "reset", "system/user_rotation": "null",
                         "system/accelerometer_rotation": "1", "system/screen_off_timeout": "1800000"}
            worker, book = display_worker(dev, tmp, designated=False, book_entry=originals)
            self.run_instrument(worker, hung_stream(lambda: None))
            state = adm.read_display("S1", dev)
            self.assertEqual(state["display/size"], "reset")
            self.assertEqual(state["display/density"], "reset")
            self.assertEqual(state["system/user_rotation"], "null")
            self.assertEqual(state["system/accelerometer_rotation"], "1")
            self.assertEqual(book.peek("S1"), originals)   # still on disk: the run is not over
            self.assertIn("after a hang", worker.result.notes[0])

    def test_nothing_recorded_for_a_non_designated_device_is_logged_not_guessed(self):
        with tempfile.TemporaryDirectory() as tmp:
            dev = FakeDisplayDevice()
            worker, _ = display_worker(dev, tmp, designated=False)
            self.run_instrument(worker, hung_stream(lambda: None))
            self.assertEqual(adm.read_display("S1", dev)["display/size"], "1848x2960")
            self.assertEqual(worker.result.notes, [])
            self.assertIn("no original recorded", Path(tmp, "p.log").read_text())

    def test_a_restore_that_does_not_take_is_a_note_in_the_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            dev = FakeDisplayDevice(ignore_wm_reset=True)
            worker, _ = display_worker(dev, tmp, designated=True)
            self.run_instrument(worker, hung_stream(lambda: None))
            self.assertTrue(worker.result.notes[0].startswith("DISPLAY RESTORE FAILED after a hang"),
                            worker.result.notes)

    def test_a_crash_without_a_kill_also_resets_the_display(self):
        with tempfile.TemporaryDirectory() as tmp:
            dev = FakeDisplayDevice()
            worker, _ = display_worker(dev, tmp, designated=True)

            def crash(cmd, on_line, idle_timeout, stop):
                for line in status("p.C", "keyG", 1, 1, 2).splitlines():
                    on_line(line)
                return ""   # the stream just ends: process died, adb still there
            self.run_instrument(worker, crash)
            self.assertEqual(adm.read_display("S1", dev), PHONE_ORIGINALS)
            self.assertIn("after the instrumentation died", worker.result.notes[0])


class DisplayAfterDisconnectTest(unittest.TestCase):
    def test_the_display_is_reset_once_the_device_is_back_and_not_while_it_is_gone(self):
        passes = [status("p.C", "a", 1, 1, 2) + status("p.C", "a", 0, 1, 2) + status("p.C", "b", 1, 2, 2),
                  status("p.C", "b", 1, 1, 1) + status("p.C", "b", 0, 1, 1) + "INSTRUMENTATION_CODE: -1\n"]

        def fake_stream(cmd, on_line, idle_timeout, stop):
            for line in passes.pop(0).splitlines():
                on_line(line)
            return ""

        class Gone(FakeDisplayDevice):
            # get-state answers: lost when the stream ended, back on the first poll of the reconnect wait.
            states = iter([False, True])

            def __call__(self, cmd, timeout=120):
                if cmd[3:] == ["get-state"]:
                    self.calls.append(list(cmd))
                    return (0, "device") if next(self.states) else (1, "error: device not found")
                if cmd[3:5] == ["shell", "pm list instrumentation"]:
                    return 0, "instrumentation:com.photoselectortoolbox.test/x.Runner (target=com.photoselectortoolbox)"
                return super().__call__(cmd, timeout)

        original = adm.stream_command
        adm.stream_command = fake_stream
        try:
            with tempfile.TemporaryDirectory() as tmp:
                dev = Gone()
                worker, _ = display_worker(dev, tmp, designated=True)
                worker.opts.only = {"android-desktop": ["p.C#a", "p.C#b"]}
                mrun = worker._run_module(adm.MODULES["android-desktop"])
        finally:
            adm.stream_command = original
        self.assertIn(adm.DISCONNECTED, mrun.problems)
        self.assertEqual(adm.read_display("S1", dev), PHONE_ORIGINALS)
        self.assertTrue(any("after the device reconnected" in n for n in worker.result.notes), worker.result.notes)


class FailingAttemptLogTest(unittest.TestCase):
    """Run 20261109-225923: the log was read once, after the retry, so the failing attempt was gone."""

    def test_the_failing_attempt_is_captured_before_the_retry_and_named_in_the_report(self):
        order = []
        passes = [status("p.C", "flaky", 1, 1, 1) + status("p.C", "flaky", -2, 1, 1, "INSTRUMENTATION_STATUS: stack=boom\n")
                  + "INSTRUMENTATION_CODE: -1\n",
                  status("p.C", "flaky", 1, 1, 1) + status("p.C", "flaky", 0, 1, 1) + "INSTRUMENTATION_CODE: -1\n"]

        def fake_stream(cmd, on_line, idle_timeout, stop):
            order.append("retry" if len(passes) == 1 else "first")
            for line in passes.pop(0).splitlines():
                on_line(line)
            return ""

        class Dev(FakeDisplayDevice):
            def __call__(self, cmd, timeout=120):
                if cmd[3:5] == ["shell", "date '+%m-%d %H:%M:%S'"]:
                    self.calls.append(list(cmd))
                    return 0, datetime.now().strftime("%m-%d %H:%M:%S") + "\n"
                if cmd[3:5] == ["logcat", "-d"]:
                    self.calls.append(list(cmd))
                    order.append("logcat -d " + " ".join(cmd[5:]))
                    return 0, "11-09 23:11:18.393  100  100 E Tag: failing attempt\n"
                if cmd[3:5] == ["shell", "pm list instrumentation"]:
                    return 0, "instrumentation:com.photoselectortoolbox.test/x.Runner (target=com.photoselectortoolbox)"
                return super().__call__(cmd, timeout)

        original = adm.stream_command
        adm.stream_command = fake_stream
        try:
            with tempfile.TemporaryDirectory() as tmp:
                worker, _ = display_worker(Dev(), tmp)
                worker.opts.retries = 1
                mrun = worker._run_module(adm.MODULES["android-desktop"])
                kept = sorted(p.name for p in worker.dir.glob("logcat-*attempt*"))
                self.assertEqual(kept, ["logcat-android-desktop-C_flaky-attempt1.txt"])
                self.assertIn("failing attempt", (worker.dir / kept[0]).read_text(encoding="utf-8"))
        finally:
            adm.stream_command = original
        self.assertEqual(order[0], "first")
        self.assertTrue(order[1].startswith("logcat -d -T "), order)
        self.assertRegex(order[1], r"-T \d\d-\d\d \d\d:\d\d:\d\d\.\d{3}$")
        self.assertEqual(order[2], "retry")   # captured BEFORE the retry ran
        self.assertTrue(mrun.results[0].flaky)
        dr = adm.DeviceRun(adm.Device("S1", "S1", "Moto G77", "usb", label="Moto G77"), status="ran")
        dr.modules.append(mrun)
        ledger = adm.empty_ledger()
        verdicts = adm.update_ledger(ledger, [dr], "r1")
        meta = {"run_id": "r1", "started_at": "now", "wall_seconds": 1.0, "build_seconds": 1.0, "tree": "main @ abc",
                "scope": "full", "run_dir": "/tmp/x", "ledger": "/tmp/issues.json"}
        self.assertIn("Moto_G77/logcat-android-desktop-C_flaky-attempt1.txt", adm.render_report(meta, [dr], verdicts, ledger))


class DisplayAtCleanupTest(unittest.TestCase):
    def test_designated_cleanup_resets_the_display_even_when_nothing_else_ran(self):
        with tempfile.TemporaryDirectory() as tmp:
            dev = FakeDisplayDevice()
            worker, _ = display_worker(dev, tmp, designated=True)
            worker._cleanup()
            self.assertEqual(adm.read_display("S1", dev), PHONE_ORIGINALS)
            self.assertIn("at the end of the run", worker.result.notes[0])

    def test_emulators_count_as_prepared_devices(self):
        with tempfile.TemporaryDirectory() as tmp:
            dev = FakeDisplayDevice()
            worker, _ = display_worker(dev, tmp, designated=False, kind="emulator")
            worker._cleanup()
            self.assertEqual(adm.read_display("S1", dev), PHONE_ORIGINALS)

    def test_non_designated_cleanup_restores_the_recorded_display_and_clears_the_book(self):
        with tempfile.TemporaryDirectory() as tmp:
            dev = FakeDisplayDevice(user_rotation="1")
            originals = {"system/screen_off_timeout": "1800000", **PHONE_ORIGINALS}
            worker, book = display_worker(dev, tmp, designated=False, book_entry=originals)
            worker._cleanup()
            self.assertEqual(adm.read_display("S1", dev), PHONE_ORIGINALS)
            self.assertEqual(book.leftovers(), {})
            self.assertEqual(worker.result.notes, [])

    def test_a_failed_display_restore_stays_in_the_book_for_the_next_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            dev = FakeDisplayDevice(ignore_wm_reset=True)
            worker, book = display_worker(dev, tmp, designated=False, book_entry=dict(PHONE_ORIGINALS))
            worker._cleanup()
            self.assertEqual(book.peek("S1"), PHONE_ORIGINALS)
            self.assertIn("settings restore failed", worker.result.notes[0])

# ── --changed: which suites a diff can affect ────────────────────────────────

class ClassifyChangedTest(unittest.TestCase):
    def modules(self, *paths):
        return adm.classify_changed(paths)[0]

    def test_a_product_directory_selects_that_product_only(self):
        self.assertEqual(self.modules("products/android/phototok/src/a/B.kt"), ["phototok"])
        self.assertEqual(self.modules("products/android/android-desktop/tests/instrumented/T.kt"), ["android-desktop"])

    def test_both_products_in_one_diff_select_both(self):
        self.assertEqual(self.modules("products/android/phototok/src/A.kt",
                                      "products/android/android-desktop/src/B.kt"), ["android-desktop", "phototok"])

    def test_shared_code_build_files_and_the_runner_select_both(self):
        for path in ("products/android/core/src/Exif.kt", "products/android/saf/src/Copy.kt",
                     "products/android/build.gradle.kts", "products/android/settings.gradle.kts",
                     "products/android/gradle.properties", "products/android/gradle/libs.versions.toml",
                     "products/android/gradle/wrapper/gradle-wrapper.properties",
                     "products/android/something/unknown.bin",
                     "scripts/android_device_matrix.py", "scripts/android_test_device.py"):
            with self.subTest(path=path):
                self.assertEqual(self.modules(path), ["android-desktop", "phototok"])

    def test_nothing_android_selects_nothing(self):
        self.assertEqual(self.modules("docs/README.md", "products/linux-desktop/src/x.py", ".github/workflows/ci.yml",
                                      "products/android/phototok/README.md", "scripts/tests/test_android_device_matrix.py"), [])
        self.assertEqual(self.modules(), [])

    def test_a_product_name_prefix_is_not_a_product_directory(self):
        # "phototok-extras" is not "phototok/": unclassifiable under products/android/ means both.
        self.assertEqual(self.modules("products/android/phototok-extras/x.kt"), ["android-desktop", "phototok"])

    def test_why_names_the_paths_that_selected_each_module(self):
        _, why = adm.classify_changed(["products/android/phototok/src/A.kt", "docs/x.txt"])
        self.assertEqual(why, {"phototok": ["products/android/phototok/src/A.kt"]})


class ChangedPathsTest(unittest.TestCase):
    @staticmethod
    def runner(outputs):
        calls = []

        def run(cmd, timeout=120):
            calls.append(list(cmd))
            for key, out in outputs.items():
                if key in cmd:
                    return out
            return 0, ""
        run.calls = calls
        return run

    def test_default_base_is_the_merge_base_and_untracked_files_count(self):
        r = self.runner({"merge-base": (0, "abc123\n"), "diff": (0, "products/android/phototok/src/A.kt\n"),
                         "ls-files": (0, "products/android/saf/New.kt\n")})
        base, paths, error = adm.changed_paths(None, r)
        self.assertEqual((base, error), ("abc123", ""))
        self.assertEqual(paths, ["products/android/phototok/src/A.kt", "products/android/saf/New.kt"])
        self.assertIn(["git", "-C", str(adm.REPO_ROOT), "diff", "--name-only", "abc123"], r.calls)

    def test_an_explicit_base_skips_the_merge_base(self):
        r = self.runner({})
        self.assertEqual(adm.changed_paths("HEAD~2", r), ("HEAD~2", [], ""))
        self.assertFalse(any("merge-base" in c for c in r.calls))

    def test_no_merge_base_is_an_error_not_an_empty_scope(self):
        base, paths, error = adm.changed_paths(None, self.runner({"merge-base": (128, "fatal: no origin/main")}))
        self.assertIsNone(base)
        self.assertIn("no merge-base", error)


class ChangedRunTest(unittest.TestCase):
    """cmd_run's scoping, stopped before any device is touched."""

    def run_with(self, argv, paths=("docs/a.txt",), error=""):
        import argparse
        import io
        from contextlib import redirect_stdout
        out = io.StringIO()
        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(adm, "runs_root", return_value=Path(tmp)), \
                mock.patch.object(adm, "changed_paths", return_value=("abcdef1234567", list(paths), error)), \
                mock.patch.object(adm, "discover", return_value=([], "none")) as discover, redirect_stdout(out):
            args = argparse.Namespace(module=None, only_open_issues=False, changed=None, serial=None, configured=False,
                                      no_personal=True, gate_lines=None, no_build=True)
            for key, value in argv.items():
                setattr(args, key, value)
            code = adm.cmd_run(args)
        return code, out.getvalue(), discover

    def test_no_android_change_runs_nothing_and_exits_zero(self):
        code, out, discover = self.run_with({"changed": ""})
        self.assertEqual(code, 0)
        self.assertIn("no Android change", out)
        discover.assert_not_called()

    def test_a_phototok_change_scopes_the_run_and_names_the_skipped_suite(self):
        code, out, _ = self.run_with({"changed": ""}, paths=["products/android/phototok/src/A.kt"])
        self.assertEqual(code, 2)   # no devices in this fake
        self.assertIn("changed since abcdef1234: phototok (1 file(s), e.g. products/android/phototok/src/A.kt)", out)
        self.assertIn("skipped android-desktop (no change under products/android/android-desktop/", out)

    def test_without_the_flag_every_suite_runs(self):
        _, out, _ = self.run_with({"changed": None}, paths=["products/android/phototok/src/A.kt"])
        self.assertIn("full suites: android-desktop, phototok", out)

    def test_explicit_module_wins(self):
        _, out, _ = self.run_with({"changed": "", "module": ["android-desktop"]}, paths=["products/android/phototok/src/A.kt"])
        self.assertIn("--module given: it wins", out)
        self.assertIn("full suites: android-desktop;", out)

    def test_open_issues_win_over_changed(self):
        code, out, _ = self.run_with({"changed": "", "only_open_issues": True})
        self.assertIn("--only-open-issues given", out)

    def test_an_undeterminable_scope_runs_everything(self):
        _, out, _ = self.run_with({"changed": ""}, error="no merge-base with origin/main (128)")
        self.assertIn("running every suite", out)
        self.assertIn("full suites: android-desktop, phototok", out)


# ── The keyguard step on a device with a screen lock ─────────────────────────

# The 11-10 02:27 run's progress.log, Pixel 4a (verbatim lines; the adb output of the failing
# step was not logged then, hence the empty output below — the step's code or text is unknown).
PIXEL_4A_PROGRESS = """02:27:00 Pixel 4a: prepare ✓ screen-off timeout at maximum
02:27:01 Pixel 4a: prepare ✗ keyguard disabled
02:27:01 Pixel 4a: prepare ✓ fixed portrait rotation
"""


class PrepareKeyguardTest(unittest.TestCase):
    @staticmethod
    def prepare_runner(code=255, out=""):
        def run(cmd, timeout=120):
            if cmd[-1] == "locksettings set-disabled true":
                return code, out
            return 0, ""
        return run

    def test_prepare_logs_which_step_failed_and_what_adb_said(self):
        with tempfile.TemporaryDirectory() as tmp:
            worker = worker_with(self.prepare_runner(255, "Error: cannot disable with credential"), tmp)
            failed = worker._prepare()
            log = (Path(tmp) / "p.log").read_text(encoding="utf-8")
        self.assertEqual(failed, ["keyguard disabled"])
        self.assertIn("prepare ✗ keyguard disabled (exit 255: Error: cannot disable with credential)", log)
        self.assertEqual(worker.prepare_outputs["keyguard disabled"], "Error: cannot disable with credential")
        self.assertIn("✗ keyguard disabled", PIXEL_4A_PROGRESS)   # the shape the real log had

    def test_a_refused_keyguard_step_on_an_unlocked_screen_is_a_note_not_a_failure(self):
        note = adm.prepare_notes(["keyguard disabled"], {"keyguard disabled": "Error: x"}, False)
        self.assertNotIn("failed", note)
        self.assertIn("screen lock not removable over adb", note)
        self.assertIn("screen kept awake", note)

    def test_unknown_lock_state_after_the_wake_up_is_not_a_failure_either(self):
        self.assertNotIn("failed", adm.prepare_notes(["keyguard disabled"], {}, None))

    def test_a_lock_screen_that_is_showing_stays_a_failure(self):
        self.assertEqual(adm.prepare_notes(["keyguard disabled"], {}, True),
                         "prepared as a test device (failed: keyguard disabled)")

    def test_other_failed_steps_stay_failures_next_to_the_keyguard_note(self):
        note = adm.prepare_notes(["light theme", "keyguard disabled"], {}, False)
        self.assertTrue(note.startswith("prepared as a test device (failed: light theme);"))

    def test_nothing_failed(self):
        self.assertEqual(adm.prepare_notes([], {}, False), "prepared as a test device")



if __name__ == "__main__":
    unittest.main()
