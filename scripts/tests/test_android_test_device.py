"""Unit tests for scripts/android_test_device.py (no device needed)."""

from __future__ import annotations

from contextlib import redirect_stderr, redirect_stdout
import io
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import android_test_device as atd  # noqa: E402

LISTING = """List of devices attached
CQ30012CMV             device usb:1-4 product:H3113 model:H3113
192.168.0.117:46567    device product:gts11uwifieea model:SM_X930
emulator-5554          offline
R5CY62D2QDH            unauthorized
"""


class AttachedSerialsTest(unittest.TestCase):
    def test_only_usable_devices_are_listed(self):
        self.assertEqual(atd.attached_serials(LISTING), ["CQ30012CMV", "192.168.0.117:46567"])

    def test_empty_listing(self):
        self.assertEqual(atd.attached_serials("List of devices attached\n"), [])


class ResolveSerialTest(unittest.TestCase):
    attached = ["CQ30012CMV", "192.168.0.117:46567"]

    def test_environment_wins_over_local_properties(self):
        serial, designated, _ = atd.resolve_serial(
            {"ANDROID_SERIAL": "192.168.0.117:46567"}, {atd.SERIAL_PROPERTY: "CQ30012CMV"}, self.attached
        )
        self.assertEqual((serial, designated), ("192.168.0.117:46567", True))

    def test_local_properties_designates_the_device(self):
        serial, designated, reason = atd.resolve_serial({}, {atd.SERIAL_PROPERTY: "CQ30012CMV"}, self.attached)
        self.assertEqual((serial, designated), ("CQ30012CMV", True))
        self.assertIn("local.properties", reason)

    def test_designated_but_absent_device_is_not_replaced(self):
        # Never fall back to whatever else is attached: that may be a personal device.
        serial, designated, reason = atd.resolve_serial({}, {atd.SERIAL_PROPERTY: "GONE"}, self.attached)
        self.assertIsNone(serial)
        self.assertTrue(designated)
        self.assertIn("not attached", reason)

    def test_single_attached_device_is_used_undesignated(self):
        serial, designated, _ = atd.resolve_serial({}, {}, ["emulator-5554"])
        self.assertEqual((serial, designated), ("emulator-5554", False))

    def test_several_devices_without_designation_are_refused(self):
        serial, _, reason = atd.resolve_serial({}, {}, self.attached)
        self.assertIsNone(serial)
        self.assertIn(atd.SERIAL_PROPERTY, reason)

    def test_no_device(self):
        serial, _, reason = atd.resolve_serial({}, {}, [])
        self.assertIsNone(serial)
        self.assertIn("no device", reason)

    def test_blank_values_are_ignored(self):
        serial, _, _ = atd.resolve_serial({"ANDROID_SERIAL": " "}, {atd.SERIAL_PROPERTY: ""}, ["X"])
        self.assertEqual(serial, "X")


class ReadPropertiesTest(unittest.TestCase):
    def test_parses_local_properties(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "local.properties"
            path.write_text("# comment\nsdk.dir=/opt/sdk\n\npst.testDevice.serial = ABC123\n", encoding="utf-8")
            props = atd.read_properties(path)
        self.assertEqual(props, {"sdk.dir": "/opt/sdk", "pst.testDevice.serial": "ABC123"})

    def test_missing_file(self):
        self.assertEqual(atd.read_properties(Path("/nonexistent/local.properties")), {})


class DeviceSetPathTest(unittest.TestCase):
    def test_sets_stay_inside_the_lab_root(self):
        self.assertEqual(atd.device_set_path("smoke"), "/sdcard/PSTLab/smoke")
        for bad in ("", ".", "..", "../DCIM", "a/b"):
            with self.assertRaises(ValueError):
                atd.device_set_path(bad)


class PrepareTest(unittest.TestCase):
    def test_every_step_targets_the_selected_serial_and_failures_are_counted(self):
        calls = []

        def runner(cmd):
            calls.append(list(cmd))
            if cmd[-2:] == ["id", "-u"]:
                return 0, "0"
            if "svc power stayon true" in cmd:
                return 1, "svc: error"
            return 0, ""

        with redirect_stdout(io.StringIO()):
            failures = atd.prepare("SERIAL1", runner)

        self.assertEqual(failures, 1)
        self.assertTrue(all(c[:3] == ["adb", "-s", "SERIAL1"] for c in calls))
        self.assertNotIn(["adb", "-s", "SERIAL1", "root"], calls)  # already root: no adbd restart

    def test_production_build_without_root_still_prepares(self):
        def runner(cmd):
            if cmd[-2:] == ["id", "-u"]:
                return 0, "2000"
            if cmd[-1] == "root":
                return 0, "adbd cannot run as root in production builds"
            return 0, ""

        self.assertFalse(atd.ensure_root("S", runner))
        with redirect_stdout(io.StringIO()):
            self.assertEqual(atd.prepare("S", runner), 0)


class MayReconfigureTest(unittest.TestCase):
    def test_designated_device_and_emulators_may_be_prepared(self):
        self.assertTrue(atd.may_reconfigure("CQ30012CMV", True))
        self.assertTrue(atd.may_reconfigure("emulator-5554", False))

    def test_lone_undesignated_device_is_not_reconfigured(self):
        # The only attached device may be a personal phone on wireless debugging.
        self.assertFalse(atd.may_reconfigure("192.168.0.221:38579", False))

    def test_prepare_command_refuses_an_undesignated_device(self):
        with mock.patch.object(atd, "select", return_value=("PERSONAL", False, "only attached device")), \
                mock.patch.object(atd, "prepare") as prepare, redirect_stderr(io.StringIO()):
            self.assertEqual(atd.main(["prepare"]), 1)
        prepare.assert_not_called()

    def test_prepare_command_accepts_an_explicit_serial(self):
        with mock.patch.object(atd, "prepare", return_value=0) as prepare:
            self.assertEqual(atd.main(["prepare", "--serial", "X"]), 0)
        prepare.assert_called_once_with("X")


class SerialCommandTest(unittest.TestCase):
    # engineering-invariants.md exports ANDROID_SERIAL from `serial --designated`: it must
    # fail (print nothing) for a lone undesignated device, never print a personal phone.
    def test_designated_flag_refuses_an_undesignated_device(self):
        out = io.StringIO()
        with mock.patch.object(atd, "select", return_value=("PERSONAL", False, "only attached device")), \
                redirect_stdout(out), redirect_stderr(io.StringIO()):
            self.assertEqual(atd.main(["serial", "--designated"]), 1)
        self.assertEqual(out.getvalue(), "")

    def test_designated_flag_prints_the_designated_device(self):
        out = io.StringIO()
        with mock.patch.object(atd, "select", return_value=("TEST1", True, "designated")), redirect_stdout(out):
            self.assertEqual(atd.main(["serial", "--designated"]), 0)
        self.assertEqual(out.getvalue().strip(), "TEST1")


class SpecFormatsTest(unittest.TestCase):
    def test_every_fixture_spec_format_is_in_the_format_manifest(self):
        # The lab spec once asked for "heic" while the manifest calls it "heif", so the
        # formats set could never be generated. Parsed without PyYAML: only the format ids matter.
        import json
        import re

        repo = SCRIPTS_DIR.parent
        manifest = json.loads((repo / "testdata" / "formats" / "manifest.json").read_text(encoding="utf-8"))
        known = {str(s["format"]).lower() for s in manifest["samples"]}
        spec = (repo / "scripts" / "lab" / "fixtures" / "spec.yaml").read_text(encoding="utf-8")
        wanted = set()
        for m in re.finditer(r"from_corpus:\s*\[([^\]]*)\]", spec):
            wanted.update(f.strip().lower() for f in m.group(1).split(",") if f.strip())
        wanted.update(m.group(1).lower() for m in re.finditer(r"raw_companion:\s*(\w+)", spec))
        self.assertTrue(wanted, "spec.yaml names no corpus formats — has its syntax changed?")
        self.assertEqual(wanted - known, set())


if __name__ == "__main__":
    unittest.main()
