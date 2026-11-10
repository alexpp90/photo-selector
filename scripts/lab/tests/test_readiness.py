"""The preflight readiness table: replayed device output, a ready and a not-ready device."""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lab import readiness as rd  # noqa: E402
from lab.android.adb import AdbClient  # noqa: E402
from lab.inventory import AndroidDevice, Inventory, MacHost  # noqa: E402
from lab.locks import device_lock  # noqa: E402

DATA = Path(__file__).resolve().parent / "data" / "readiness"


def captured(name: str) -> str:
    return (DATA / f"xperia_ready.{name}.txt").read_text(encoding="utf-8")


def replay(power: str, window: str, activity: str, stay_on: str, wm_size: str, wm_density: str):
    """An adb exec_fn that answers each read with the given output."""
    answers = {("dumpsys", "power"): power, ("dumpsys", "window"): window, ("dumpsys", "activity"): activity,
               ("settings", "get"): stay_on, ("wm", "size"): wm_size, ("wm", "density"): wm_density}

    def run(cmd: list[str], timeout: int) -> subprocess.CompletedProcess[bytes]:
        args = tuple(cmd[cmd.index("shell") + 1:][:2])
        assert args in answers, f"a command the preflight must not send: {cmd}"
        return subprocess.CompletedProcess(cmd, 0, answers[args].encode(), b"")

    return run


READY = dict(power=captured("dumpsys_power"), window=captured("dumpsys_window"), activity=captured("dumpsys_activity"),
             stay_on=captured("stay_on"), wm_size=captured("wm_size"), wm_density=captured("wm_density"))
DEV = AndroidDevice(name="xperia-xa2", serial="CQ30012CMV")


class TestReadiness(unittest.TestCase):
    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.lock_dir = Path(tmp.name)

    def row(self, **over: str) -> rd.DeviceReadiness:
        out = {**READY, **over}
        return rd.read_android(DEV, AdbClient("CQ30012CMV", exec_fn=replay(**out)), self.lock_dir)

    def test_a_captured_ready_device(self) -> None:
        r = self.row()
        self.assertEqual((r.transport, r.awake, r.keyguard, r.stay_on, r.foreground),
                         ("usb", True, False, "15", "com.phototok.lab"))
        self.assertEqual((r.size_override, r.density_override, r.lock_holder), (None, None, None))
        self.assertTrue(r.ready, r.problems)

    def test_a_sleeping_locked_device_with_overrides_is_not_ready(self) -> None:
        r = self.row(
            power=READY["power"].replace("Awake", "Asleep"),
            window=READY["window"].replace("isKeyguardShowing=false", "isKeyguardShowing=true"),
            stay_on="0\n",
            wm_size="Physical size: 1080x1920\nOverride size: 540x960\n",
            wm_density="Physical density: 420\nOverride density: 320\n",
        )
        self.assertFalse(r.ready)
        self.assertEqual(len(r.problems), 4, r.problems)
        self.assertEqual((r.size_override, r.density_override), ("540x960", "320"))
        text = rd.render([r])
        self.assertIn("NOT READY", text)
        self.assertIn("screen is off", text)
        self.assertIn("keyguard showing", text)

    def test_the_device_lock_holder_is_named_and_the_probe_releases_the_lock(self) -> None:
        with device_lock("CQ30012CMV", "usability-walk", self.lock_dir):
            r = self.row()
        self.assertIn("usability-walk", r.lock_holder or "")
        self.assertFalse(r.ready)
        self.assertIsNone(rd.lock_holder("CQ30012CMV", self.lock_dir))  # free again

    def test_only_reads_are_sent(self) -> None:
        sent: list[list[str]] = []
        inner = replay(**READY)

        def spy(cmd: list[str], timeout: int) -> subprocess.CompletedProcess[bytes]:
            sent.append(cmd)
            return inner(cmd, timeout)

        rd.read_android(DEV, AdbClient("CQ30012CMV", exec_fn=spy), self.lock_dir)
        for cmd in sent:
            tail = " ".join(cmd[cmd.index("shell") + 1:])
            self.assertTrue(tail.startswith(("dumpsys", "settings get", "wm size", "wm density")), tail)

    def test_transports(self) -> None:
        self.assertEqual([rd.transport_of(s) for s in ("CQ30012CMV", "192.168.0.221:41234", "adb-3620105H804AS8-BswZ64._adb-tls-connect._tcp", "emulator-5580", None)],
                         ["usb", "wifi", "mdns", "emulator", "none"])

    def test_collect_skips_personal_devices_and_reports_the_mac(self) -> None:
        inv = Inventory(android=[DEV, AndroidDevice(name="s25", serial="R5", personal=True),
                                 AndroidDevice(name="gone", serial="XX")],
                        mac=MacHost(host="mac.local", personal=False))
        rows = rd.collect(
            inv, resolve=lambda d: (d.name != "gone", None if d.name != "gone" else "not attached"),
            make_adb=lambda s: AdbClient(s, exec_fn=replay(**READY)),
            mac_check=lambda h, u: (False, "Mac asleep or pst-lab not logged in"), lock_dir=self.lock_dir,
        )
        self.assertEqual([r.name for r in rows], ["xperia-xa2", "gone", "mac"])
        self.assertTrue(rows[0].ready)
        self.assertEqual(rows[1].problems, ["not reachable: not attached"])
        self.assertEqual(rows[2].problems, ["no GUI session: Mac asleep or pst-lab not logged in"])


if __name__ == "__main__":
    unittest.main()
