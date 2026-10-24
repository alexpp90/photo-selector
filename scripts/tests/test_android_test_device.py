"""Unit tests for scripts/android_test_device.py (no device needed)."""

from __future__ import annotations

from contextlib import redirect_stderr, redirect_stdout
import io
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
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


LONG_LISTING = """List of devices attached
13171JEC200817         device usb:1-2.4 product:sunfish model:Pixel_4a device:sunfish transport_id:159
CQ30012CMV             device usb:1-4 product:H3113 model:H3113 device:H3113 transport_id:41
ZY22MQCBL6             unauthorized usb:1-2.1 transport_id:160
192.168.0.221:41765    device product:sunfish model:Pixel_4a device:sunfish transport_id:72
adb-ZY22MQCBL6-M2Jjof._adb-tls-connect._tcp device product:naples_g_syseh model:moto_g77 transport_id:67
emulator-5554          device product:sdk_gphone_x86_64 model:sdk_gphone_x86_64 transport_id:94
"""


class UsbSerialsTest(unittest.TestCase):
    def test_only_usable_usb_transports_are_listed(self):
        # Wireless (host:port, mDNS) and the emulator carry no usb: field; unauthorized is unusable.
        self.assertEqual(atd.usb_serials(LONG_LISTING), ["13171JEC200817", "CQ30012CMV"])

    def test_empty_listing(self):
        self.assertEqual(atd.usb_serials("List of devices attached\n"), [])


class ResolveExtraSerialsTest(unittest.TestCase):
    on_usb = ["13171JEC200817", "CQ30012CMV"]

    def resolve(self, value, primary="CQ30012CMV"):
        return atd.resolve_extra_serials({atd.EXTRA_SERIALS_PROPERTY: value}, self.on_usb, primary)

    def test_usb_devices_are_used_and_absent_ones_reported(self):
        self.assertEqual(self.resolve("13171JEC200817, ZY22MQCBL6"), (["13171JEC200817"], ["ZY22MQCBL6"]))

    def test_the_primary_device_and_duplicates_are_dropped(self):
        self.assertEqual(self.resolve("CQ30012CMV,13171JEC200817,,13171JEC200817"), (["13171JEC200817"], []))

    def test_a_wireless_serial_never_counts_as_usb(self):
        self.assertEqual(self.resolve("192.168.0.221:41765"), ([], ["192.168.0.221:41765"]))

    def test_no_property_means_no_extra_devices(self):
        self.assertEqual(atd.resolve_extra_serials({}, self.on_usb, None), ([], []))


class ExtraSerialsCommandTest(unittest.TestCase):
    def test_usable_on_stdout_and_absent_tagged_on_stderr(self):
        out, err = io.StringIO(), io.StringIO()
        with mock.patch.object(atd, "select", return_value=("CQ30012CMV", True, "designated")), \
                mock.patch.object(atd, "extra_serials", return_value=(["13171JEC200817"], ["ZY22MQCBL6"])) as extra, \
                redirect_stdout(out), redirect_stderr(err):
            self.assertEqual(atd.main(["extra-serials"]), 0)
        extra.assert_called_once_with("CQ30012CMV")
        self.assertEqual(out.getvalue(), "13171JEC200817\n")
        self.assertEqual(err.getvalue(), "absent ZY22MQCBL6\n")

    def test_reads_the_property_and_lists_usb_devices(self):
        with tempfile.TemporaryDirectory() as tmp:
            props = Path(tmp) / "local.properties"
            props.write_text(f"{atd.EXTRA_SERIALS_PROPERTY}=13171JEC200817,ZY22MQCBL6\n", encoding="utf-8")
            calls = []

            def runner(cmd):
                calls.append(cmd)
                return 0, LONG_LISTING

            with mock.patch.object(atd, "LOCAL_PROPERTIES", props), \
                    mock.patch.object(atd.shutil, "which", return_value="/usr/bin/adb"):
                self.assertEqual(atd.extra_serials("CQ30012CMV", runner), (["13171JEC200817"], ["ZY22MQCBL6"]))
        self.assertEqual(calls, [["adb", "devices", "-l"]])


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


class KeyguardTest(unittest.TestCase):
    @staticmethod
    def runner(window):
        def run(cmd):
            return (0, window) if cmd[-2:] == ["dumpsys", "window"] else (0, "")
        return run

    def test_keyguard_state_is_read_from_dumpsys_window(self):
        self.assertTrue(atd.keyguard_showing("S", self.runner("  isKeyguardShowing=true\n")))
        self.assertFalse(atd.keyguard_showing("S", self.runner("  isKeyguardShowing=false\n")))
        self.assertIsNone(atd.keyguard_showing("S", self.runner("no such field")))

    def test_a_secure_lock_that_survives_prepare_is_a_failure(self):
        # locksettings set-disabled "succeeds" on a PIN-locked phone; the keyguard check catches it.
        out = io.StringIO()
        with redirect_stdout(out):
            failures = atd.prepare("S", self.runner("isKeyguardShowing=true"))
        self.assertEqual(failures, 1)
        self.assertIn("lock screen still showing", out.getvalue())

    def test_locked_command_exit_status(self):
        for showing, code in ((True, 0), (False, 1), (None, 1)):
            with mock.patch.object(atd, "keyguard_showing", return_value=showing):
                self.assertEqual(atd.main(["locked", "--serial", "S"]), code)


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


SCRIPT = SCRIPTS_DIR / "android_test_device.py"
HOLDER_RE = re.compile(r"^instrumented-tests (\S+) pid=(\d+) since=\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d$")


def wait_for(predicate, budget=15.0):
    deadline = time.monotonic() + budget
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(0.05)
    return False


class DeviceLockPathTest(unittest.TestCase):
    def setUp(self):
        atd._use_lab_package()
        from lab import locks

        self.locks = locks
        locks._SERIALNO.clear()
        self.addCleanup(locks._SERIALNO.clear)

    def test_lock_lives_where_the_device_lab_puts_it(self):
        with mock.patch.dict(os.environ, {"PST_LAB_TMP": "/tmp/pstlab-unit"}):
            self.assertEqual(atd.device_lock_path("CQ30012CMV"), Path("/tmp/pstlab-unit/device-CQ30012CMV.lock"))
        with mock.patch.object(self.locks, "_adb_serialno", return_value=None):  # transport not readable
            self.assertEqual(atd.device_lock_path("192.168.0.117:46567", Path("/x")),
                             Path("/x/device-192.168.0.117:46567.lock"))

    def test_lock_holder_answers_for_either_transport_of_one_phone(self):
        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(self.locks, "_adb_serialno", return_value="CQ30012CMV"):
            from lab.usability.driver import device_lock

            with device_lock("192.168.0.117:46567", "lab usability phototok", lock_dir=Path(tmp)):
                for serial in ("CQ30012CMV", "192.168.0.117:46567"):
                    held_by = atd.lock_holder(atd.device_lock_path(serial, Path(tmp)))
                    self.assertTrue(held_by and held_by.startswith("lab usability phototok pid="), held_by)
        for bad in ("", ".", "..", "a/b"):
            with self.assertRaises(ValueError):
                atd.device_lock_path(bad, Path("/x"))

    def test_with_lock_takes_the_labs_lock_file(self):
        # One implementation: with-lock goes through the lab's device_lock(), so both sides
        # lock the same file and write the same holder format.
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "holder"
            copy_lock = f"import shutil; shutil.copy({str(Path(tmp) / 'device-S.lock')!r}, {str(out)!r})"
            code = atd.with_lock([sys.executable, "-c", copy_lock], "S", "my-worktree", 0, lock_dir=Path(tmp))
            self.assertEqual(code, 0)
            m = HOLDER_RE.match(out.read_text().strip())
        self.assertIsNotNone(m)
        self.assertEqual((m.group(1), int(m.group(2))), ("my-worktree", os.getpid()))


class DeviceLockTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = Path(tmp.name)
        self.path = self.dir / "device-S.lock"

    def run_locked(self, code_or_cmd, timeout=0, **kw):
        cmd = [sys.executable, "-c", code_or_cmd] if isinstance(code_or_cmd, str) else code_or_cmd
        return atd.with_lock(cmd, "S", "unit", timeout, lock_dir=self.dir, **kw)

    def test_a_lab_walkthrough_holding_the_device_blocks_and_is_named(self):
        atd._use_lab_package()
        from lab.usability.driver import device_lock

        clock = iter([0.0, 0.0, 1.0, 2.0, 3.0])
        sleeps = []
        marker = self.dir / "ran"
        with device_lock("S", "lab usability-capture phototok", lock_dir=self.dir), \
                redirect_stderr(io.StringIO()) as err:
            code = self.run_locked(f"open({str(marker)!r}, 'w')", timeout=2.5, poll=1.0,
                                   clock=lambda: next(clock), sleep=sleeps.append)
            self.assertTrue(atd.lock_holder(self.path).startswith("lab usability-capture phototok pid="))
        self.assertEqual(code, atd.LOCK_BUSY_EXIT)
        self.assertFalse(marker.exists())
        self.assertEqual(sleeps, [1.0, 1.0, 0.5])  # never sleeps past the deadline
        self.assertEqual(err.getvalue().count("waiting up to"), 1)  # announced once per holder
        self.assertIn("is held by: lab usability-capture phototok pid=", err.getvalue())

    def test_stale_holder_text_is_not_a_holder(self):
        self.path.write_text("lab usability-capture phototok pid=1 since=2026-10-17T09:00:00\n")
        self.assertIsNone(atd.lock_holder(self.path))
        self.assertEqual(self.run_locked("pass"), 0)
        self.assertIsNone(atd.lock_holder(self.path))  # released after the command

    def test_lock_holder_of_a_missing_file_is_none_and_creates_nothing(self):
        self.assertIsNone(atd.lock_holder(self.path))
        self.assertFalse(self.path.exists())

    def test_with_lock_returns_the_command_status_and_releases(self):
        self.assertEqual(self.run_locked("raise SystemExit(3)"), 3)
        self.assertIsNone(atd.lock_holder(self.path))

    def test_a_command_exiting_75_is_not_mistaken_for_a_busy_device(self):
        self.assertEqual(self.run_locked("raise SystemExit(75)"), 1)

    def test_a_missing_command_is_reported_and_releases(self):
        with redirect_stderr(io.StringIO()):
            self.assertEqual(self.run_locked([str(self.dir / "no-such-command")]), 127)
        self.assertIsNone(atd.lock_holder(self.path))

    def test_a_process_the_command_leaves_behind_does_not_keep_the_lock(self):
        # A Gradle daemon outlives connectedDebugAndroidTest; it must not inherit the lock.
        pidfile = self.dir / "bg.pid"
        code = self.run_locked("import subprocess; p = subprocess.Popen(['sleep', '30'], start_new_session=True);"
                               f"open({str(pidfile)!r}, 'w').write(str(p.pid))")
        self.assertEqual(code, 0)
        self.addCleanup(lambda: os.kill(int(pidfile.read_text()), 15))
        self.assertIsNone(atd.lock_holder(self.path))


class WithLockContentionTest(unittest.TestCase):
    """Two `with-lock` processes contend for one device, as two agent sessions would."""

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.tmp = Path(tmp.name)
        self.env = dict(os.environ, PST_LAB_TMP=str(self.tmp / "lab"))
        self.lock = self.tmp / "lab" / "device-FAKE1.lock"

    def start(self, label, timeout, *cmd):
        return subprocess.Popen(
            [sys.executable, str(SCRIPT), "with-lock", "--serial", "FAKE1", "--timeout", str(timeout),
             "--label", label, "--", *cmd],
            env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )

    def hold_until_released(self):
        """First session: holds the lock until the test creates the `release` file."""
        ready, release = self.tmp / "ready", self.tmp / "release"
        script = (f"import os, time; open({str(ready)!r}, 'w').close()\n"
                  f"while not os.path.exists({str(release)!r}): time.sleep(0.05)")
        first = self.start("session-a", 5, sys.executable, "-c", script)
        self.addCleanup(lambda: first.poll() is None and first.kill())
        self.assertTrue(wait_for(ready.exists), "first session never started its command")
        return first, release

    def test_second_session_times_out_and_names_the_holder(self):
        first, release = self.hold_until_released()
        holder = self.lock.read_text().strip()
        m = HOLDER_RE.match(holder)
        self.assertIsNotNone(m, holder)
        self.assertEqual((m.group(1), int(m.group(2))), ("session-a", first.pid))

        ran = self.tmp / "second-ran"
        second = self.start("session-b", 0.5, sys.executable, "-c", f"open({str(ran)!r}, 'w').close()")
        _, err = second.communicate(timeout=20)
        self.assertEqual(second.returncode, atd.LOCK_BUSY_EXIT)
        self.assertIn(f"is held by: instrumented-tests session-a pid={first.pid}", err)
        self.assertFalse(ran.exists())

        holder_cmd = subprocess.run([sys.executable, str(SCRIPT), "lock-holder", "--serial", "FAKE1"],
                                    env=self.env, capture_output=True, text=True, timeout=20)
        self.assertEqual((holder_cmd.returncode, holder_cmd.stdout.strip()), (0, holder))

        release.touch()
        self.assertEqual(first.wait(timeout=20), 0)

    def test_second_session_waits_for_the_first_then_runs(self):
        first, release = self.hold_until_released()
        err_path = self.tmp / "second.err"
        with open(err_path, "w") as err_file:
            second = subprocess.Popen(
                [sys.executable, str(SCRIPT), "with-lock", "--serial", "FAKE1", "--timeout", "30",
                 "--label", "session-b", "--", sys.executable, "-c", "print('second ran')"],
                env=self.env, stdout=subprocess.PIPE, stderr=err_file, text=True,
            )
        self.addCleanup(lambda: second.poll() is None and second.kill())
        self.assertTrue(wait_for(lambda: "waiting up to" in err_path.read_text()),
                        "second session never reported waiting for the first")
        self.assertIsNone(second.poll(), "second session ran while the first held the device")
        self.assertIn("held by: instrumented-tests session-a", err_path.read_text())
        release.touch()
        out, _ = second.communicate(timeout=30)
        self.assertEqual(first.wait(timeout=20), 0)
        self.assertEqual(second.returncode, 0, err_path.read_text())
        self.assertIn("second ran", out)
        self.assertTrue(self.lock.read_text().startswith("instrumented-tests session-b"))


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
