"""One driver per hub and per device (LAB-24, 2026-10-19 lab report).

On 2026-10-19 four `lab.py run` processes shared the hub (`real-2026-10-19-tok` 17:19-18:08,
`-ad2` 17:40-18:21, `-tab-dex` 17:35-17:49, `-tab-dex-smoke` 17:50 UTC) and broke each other;
the same devices ran clean one at a time. The locks are exercised across real processes, as the
runs were.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest
from pathlib import Path

LAB_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(LAB_DIR))

from lab.inventory import parse_inventory  # noqa: E402
from lab import locks  # noqa: E402
from lab.locks import DeviceBusy, device_lock, device_lock_path, hub_lock_path, waiting_lock  # noqa: E402
from lab.orchestrate import _run_android_product, run_lab  # noqa: E402


class Holder:
    """A separate process holding one of the lab's locks, as another lab run would."""

    def __init__(self, path: Path, holder: str, hold_s: float = 60.0) -> None:
        code = textwrap.dedent(f"""
            import sys, time
            sys.path.insert(0, {str(LAB_DIR)!r})
            from pathlib import Path
            from lab.locks import held_lock
            with held_lock(Path({str(path)!r}), {holder!r}):
                print("held", flush=True)
                time.sleep({hold_s})
        """)
        self.proc = subprocess.Popen([sys.executable, "-c", code], stdout=subprocess.PIPE, text=True)
        assert self.proc.stdout is not None
        assert self.proc.stdout.readline().strip() == "held"

    def stop(self) -> None:
        self.proc.kill()
        self.proc.wait()
        if self.proc.stdout is not None:
            self.proc.stdout.close()


class LockTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-locks-"))  # never the hub's /tmp/pstlab
        self.run_dir = self.tmp / "run"
        self.run_dir.mkdir()
        self.addCleanup(shutil.rmtree, self.tmp, True)


class TestWaitingLock(LockTestCase):
    def test_a_second_holder_waits_for_the_first_then_takes_over(self) -> None:
        path = hub_lock_path(self.tmp)
        holder = Holder(path, "lab run real-2026-10-19-tok", hold_s=0.6)
        heard: list[str] = []
        t0 = time.monotonic()
        with waiting_lock(path, "lab run real-2026-10-19-ad2", 20.0, poll_s=0.1, on_wait=heard.append):
            waited = time.monotonic() - t0
            self.assertIn("lab run real-2026-10-19-ad2", path.read_text())
        holder.stop()
        self.assertGreater(waited, 0.3)
        self.assertEqual(len(heard), 1)
        self.assertIn("lab run real-2026-10-19-tok", heard[0])

    def test_the_wait_is_bounded_and_names_the_holder(self) -> None:
        path = hub_lock_path(self.tmp)
        holder = Holder(path, "lab run real-2026-10-19-tok")
        try:
            clock = iter([0.0, 1.0, 2.0, 3.0, 4.0])
            with self.assertRaises(DeviceBusy) as ctx:
                with waiting_lock(path, "me", 2.5, clock=lambda: next(clock), sleep=lambda s: None):
                    self.fail("must not acquire a held lock")
            self.assertIn("real-2026-10-19-tok", str(ctx.exception))
        finally:
            holder.stop()

    def test_the_lock_dies_with_its_holder(self) -> None:
        path = hub_lock_path(self.tmp)
        holder = Holder(path, "lab run killed")
        holder.stop()  # kill -9: no cleanup ran
        with waiting_lock(path, "next run", 0.0):
            pass


class TestHubLock(LockTestCase):
    INVENTORY = {"android": {"pixel-tablet": {"serial": "3620105H804AS8", "role": "tablet"}}}

    def test_a_second_run_does_not_drive_android_devices_while_the_first_does(self) -> None:
        holder = Holder(hub_lock_path(self.tmp), "lab run real-2026-10-19-tok")
        try:
            out = run_lab(
                products=["android-desktop"], journey_filter="SMOKE-android-desktop",
                out_dir=self.run_dir, devices_inventory=self.INVENTORY, build=False,
                lock_dir=self.tmp, hub_wait_s=0.3,
            )
        finally:
            holder.stop()
        run = json.loads((out / "run.json").read_text())
        self.assertEqual(run["products"]["android-desktop"]["status"], "not-run")
        self.assertIn("real-2026-10-19-tok", run["products"]["android-desktop"]["reason"])
        self.assertTrue(run["journeys"])
        self.assertTrue(all(j["result"] == "not-run" for j in run["journeys"]))

    def test_a_run_without_android_products_takes_no_hub_lock(self) -> None:
        holder = Holder(hub_lock_path(self.tmp), "lab run real-2026-10-19-tok")
        try:
            run_lab(
                products=["linux"], journey_filter="SMOKE-linux", out_dir=self.run_dir,
                preflight_overrides={"linux": (False, "no display in this test")},
                lock_dir=self.tmp, hub_wait_s=0.0,
            )
        finally:
            holder.stop()
        run = json.loads((self.run_dir / "run.json").read_text())
        self.assertEqual(run["products"]["linux"]["reason"], "no display in this test")


class TestDeviceLock(LockTestCase):
    def test_a_device_in_an_instrumented_run_is_not_driven(self) -> None:
        # The text scripts/android_test_device.py with-lock writes into the device lock.
        holder = Holder(device_lock_path("CQ30012CMV", self.tmp), "instrumented-tests :phototok")
        calls: list[str] = []
        try:
            inv = parse_inventory({"android": {"xperia-xa2": {"serial": "CQ30012CMV", "role": "phone"}}})
            records, _, meta = _run_android_product(
                "phototok", [{"id": "J-TOK-02", "configs": ["phone-portrait"]}], None, self.run_dir, inv,
                with_personal=False, only_devices=None, build=False, build_state={},
                resolve_fn=lambda dev: (True, None),
                journey_fn=lambda **kw: calls.append(kw["label"]) or ("pass", []),
                apk_fn=lambda adb, product, user: (True, None),
                lock_dir=self.tmp, device_wait_s=0.2,
            )
        finally:
            holder.stop()
        self.assertEqual(calls, [])
        self.assertEqual(records[0]["result"], "not-run")
        self.assertIn("instrumented-tests", meta["devices"]["xperia-xa2"]["reason"])

    def test_the_usability_driver_and_the_runner_share_one_lock(self) -> None:
        from lab.usability import driver

        self.assertIs(driver.device_lock, device_lock)
        self.assertIs(driver.DeviceBusy, DeviceBusy)


class TestLockKeyedByPhone(LockTestCase):
    """USB and wireless debugging are two adb serials of one phone, so they share one lock."""

    USB, WIFI, MDNS = "CQ30012CMV", "192.168.0.117:46567", "adb-CQ30012CMV-fAYuME._adb-tls-connect._tcp"

    def setUp(self) -> None:
        super().setUp()
        self.reads: list[str] = []
        serialnos = {self.WIFI: self.USB, self.MDNS: self.USB}
        original = locks._adb_serialno
        locks._adb_serialno = lambda serial: self.reads.append(serial) or serialnos.get(serial)
        locks._SERIALNO.clear()
        self.addCleanup(setattr, locks, "_adb_serialno", original)
        self.addCleanup(locks._SERIALNO.clear)

    def test_both_transports_name_the_same_lock_file(self) -> None:
        paths = {device_lock_path(s, self.tmp) for s in (self.USB, self.WIFI, self.MDNS, self.WIFI)}
        self.assertEqual(paths, {self.tmp / f"device-{self.USB}.lock"})
        self.assertEqual(self.reads, [self.WIFI, self.MDNS])  # ro.serialno read once per transport

    def test_a_run_on_the_wireless_transport_waits_for_one_on_usb(self) -> None:
        holder = Holder(device_lock_path(self.USB, self.tmp), "instrumented-tests :phototok")
        heard: list[str] = []
        try:
            with self.assertRaises(DeviceBusy):
                with device_lock(self.WIFI, "lab usability phototok", self.tmp):
                    pass
            with self.assertRaises(DeviceBusy):
                with waiting_lock(device_lock_path(self.MDNS, self.tmp), "device-matrix", 0.3,
                                  poll_s=0.05, on_wait=heard.append):
                    pass
        finally:
            holder.stop()
        self.assertTrue(heard and "instrumented-tests :phototok" in heard[0], heard)
        with device_lock(self.WIFI, "lab usability phototok", self.tmp):  # free once the USB run ends
            pass

    def test_usb_and_emulator_serials_are_not_resolved(self) -> None:
        # Two emulators of one image can share an ro.serialno and still be two devices.
        self.assertEqual(locks.lock_key("emulator-5554"), "emulator-5554")
        self.assertEqual(locks.lock_key(self.USB), self.USB)
        self.assertEqual(self.reads, [])

    def test_an_unreadable_transport_keeps_its_own_serial_and_is_asked_again(self) -> None:
        self.assertEqual(locks.lock_key("10.0.0.9:5555"), "10.0.0.9:5555")
        self.assertEqual(locks.lock_key("10.0.0.9:5555"), "10.0.0.9:5555")
        self.assertEqual(self.reads, ["10.0.0.9:5555", "10.0.0.9:5555"])


if __name__ == "__main__":
    unittest.main()
