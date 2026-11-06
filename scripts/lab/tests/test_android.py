"""Unit tests for C-12, C-13, C-14 Android adb wrapper, tree dump, configs, and runner."""

from __future__ import annotations

import io
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import lab.android.configs as android_configs  # noqa: E402
from lab.android.adb import AdbClient  # noqa: E402
from lab.android.configs import ConfigNotRunError, apply_config, physical_alphabetic_keyboards, record_device_state, reset_config  # noqa: E402
from lab.android.runner import is_infra_failure, run_android_journey  # noqa: E402
from lab.android.tree_android import parse_uiautomator_xml  # noqa: E402
from lab.schema import validate  # noqa: E402
from PIL import Image  # noqa: E402

DATA_DIR = Path(__file__).resolve().parent / "data"


class FakeAdbExec:
    def __init__(self, landscape_on_rot: int = 0, input_dump: str = "") -> None:
        self.input_dump = input_dump
        self.commands: list[list[str]] = []
        self.cur_rotation = 1
        self.landscape_on_rot = landscape_on_rot

    def __call__(self, cmd: list[str], timeout: int = 30) -> subprocess.CompletedProcess[bytes]:
        self.commands.append(cmd)
        joined = " ".join(cmd)
        if "user_rotation" in joined and "put" in joined:
            self.cur_rotation = int(cmd[-1])
        if "wm density" in joined and "reset" not in joined and cmd[-1] == "density":
            return subprocess.CompletedProcess(cmd, 0, stdout=b"Physical density: 320\n", stderr=b"")
        if cmd[-3:] == ["shell", "dumpsys", "input"]:
            return subprocess.CompletedProcess(cmd, 0, stdout=self.input_dump.encode(), stderr=b"")
        if "dumpsys window" in joined:
            if self.cur_rotation == self.landscape_on_rot:
                return subprocess.CompletedProcess(cmd, 0, stdout=b"sw924dp w1480dp h924dp\n", stderr=b"")
            return subprocess.CompletedProcess(cmd, 0, stdout=b"sw924dp w924dp h1480dp\n", stderr=b"")
        if "pull /sdcard/PSTLab/pst_dump.xml" in joined:
            dest = Path(cmd[-1])
            shutil.copyfile(DATA_DIR / "android_dump.xml", dest)
            return subprocess.CompletedProcess(cmd, 0, stdout=b"", stderr=b"")
        if "exec-out screencap -p" in joined:
            buf = io.BytesIO()
            Image.new("RGB", (100, 60), (20, 40, 60)).save(buf, format="PNG")
            return subprocess.CompletedProcess(cmd, 0, stdout=buf.getvalue(), stderr=b"")
        if "dumpsys activity activities" in joined:
            task = "  * Task{abc #12 type=standard A=10074:com.photoselectortoolbox.lab U=0 visible=true mode=fullscreen sz=1}\n"
            return subprocess.CompletedProcess(cmd, 0, stdout=task.encode(), stderr=b"")
        if "resolve-activity" in joined:
            pkg = cmd[-1]
            return subprocess.CompletedProcess(cmd, 0, stdout=f"priority=0\n{pkg}/.MainActivity\n".encode(), stderr=b"")
        if "find . -type f" in joined:
            return subprocess.CompletedProcess(cmd, 0, stdout=b"1024 ./DSC_0001.JPG\n", stderr=b"")
        return subprocess.CompletedProcess(cmd, 0, stdout=b"0\n", stderr=b"")


class TestAndroidLayer(unittest.TestCase):
    def test_infra_failures_are_told_apart_from_broken_flows(self) -> None:
        self.assertTrue(is_infra_failure("maestro.android.DeviceServerDiedException: Device server died"))
        self.assertTrue(is_infra_failure("Device 192.168.0.117:46567 was requested, but it is not connected."))
        self.assertFalse(is_infra_failure("[Failed] 02_open_info (20s) (Element not found: Id matching regex: x)"))
        # Real Maestro output, Xperia XA2, 2026-11-05 (lab run 2026-11-05-tok-full).
        self.assertTrue(is_infra_failure(
            "maestro.MaestroDriverStartupException$AndroidDriverTimeoutException: Maestro Android driver did not "
            "start up in time on emulator [ CQ30012CMV ] (driver port 41943)"
        ))

    def test_freeform_window_offset_is_removed(self) -> None:
        xml = (
            '<hierarchy><node bounds="[584,240][2376,1584]" resource-id="" class="android.widget.FrameLayout">'
            '<node bounds="[2200,300][2376,400]" resource-id="btn" class="android.widget.Button" clickable="true" text="x"/>'
            "</node></hierarchy>"
        )
        tree = parse_uiautomator_xml(xml, density_dpi=280)
        self.assertEqual((tree.window["w"], tree.window["h"]), (1024.0, 768.0))
        btn = {n.id: n for n in tree.nodes}["btn"]
        self.assertAlmostEqual(btn.bounds[0], (2200 - 584) / 1.75, places=1)  # inside the window
        self.assertAlmostEqual(btn.bounds[1], (300 - 240) / 1.75, places=1)

    def test_parse_uiautomator_xml_converts_px_to_dp(self) -> None:
        tree = parse_uiautomator_xml(DATA_DIR / "android_dump.xml", density_dpi=320)
        self.assertEqual(validate(tree.to_dict(), "tree"), [])
        by_id = {n.id: n for n in tree.nodes}
        self.assertIn("selector.frame.current", by_id)
        self.assertEqual(by_id["selector.frame.current"].bounds[0], 440.0)
        self.assertEqual(by_id["selector.frame.current"].bounds[2], 600.0)
        self.assertEqual(by_id["sidebar_open_folder"].role, "button")

    def test_adb_guards_paths_and_verifies_rotation(self) -> None:
        # The fake applies rotations instantly; don't wait out the real-device settle polls.
        self.addCleanup(setattr, android_configs, "ROTATION_POLL_S", android_configs.ROTATION_POLL_S)
        android_configs.ROTATION_POLL_S = 0.0
        recorder = FakeAdbExec(landscape_on_rot=0)
        adb = AdbClient(serial="TEST1234", exec_fn=recorder)

        with self.assertRaises(ValueError):
            adb.rm_rf("/sdcard/DCIM/Camera")

        saved = record_device_state(adb)
        apply_config(adb, "tab-landscape", is_avd_kbd=False)
        self.assertEqual(recorder.cur_rotation, 0)
        reset_config(adb, saved)
        self.assertTrue(any("reset" in " ".join(c) for c in recorder.commands))

    def test_keyboard_config_raises_not_run_on_physical_device(self) -> None:
        recorder = FakeAdbExec()
        adb = AdbClient(serial="TEST1234", exec_fn=recorder)
        with self.assertRaises(ConfigNotRunError):
            apply_config(adb, "tab-landscape-kbd", is_avd_kbd=False)

    # The Pixel Tablet's real `dumpsys input` (API 37, docked, no keyboard; trimmed) is in
    # tests/data. Its framework `Device -1: Virtual` reports `KeyboardType: 2` and
    # `IsVirtualDevice: false`, so neither field tells it from hardware: the id -1 (and its
    # `Path: <virtual>`) does. The positive cases below are NOT captures: they follow the same real
    # layout with a keyboard device added, in AOSP's wording for it.
    PHONE_INPUT = (
        "INPUT MANAGER (dumpsys input)\n\nInput Reader State (Nums of device: 3):\n"
        "  Device -1: Virtual\n    Classes: KEYBOARD | ALPHAKEY | VIRTUAL\n    Path: <virtual>\n"
        "    Keyboard Input Mapper:\n      KeyboardType: 2\n"
        "  Device 2: gpio-keys\n    IsExternal: false\n    Keyboard Input Mapper:\n      KeyboardType: 1\n"
        "  Device 3: Touchscreen\n    Touch Input Mapper:\n      Parameters:\n"
    )
    DOCKED_INPUT = PHONE_INPUT + (
        "  Device 7: Google Pixel Tablet Keyboard\n    IsExternal: true\n    Keyboard Input Mapper:\n"
        "      Parameters:\n      KeyboardType: 2\n"
    )
    AVD_INPUT = PHONE_INPUT + "  Device 4: qwerty2\n    IsExternal: false\n    Keyboard Input Mapper:\n      Keyboard Type: alphabetic\n"

    def test_the_real_pixel_tablet_without_a_keyboard_reports_none(self) -> None:
        real = (DATA_DIR / "pixeltablet_dumpsys_input.txt").read_text(encoding="utf-8")
        self.assertIn("Device -1: Virtual", real)
        self.assertRegex(real, r"Device -1: Virtual(?s:.*?)KeyboardType: 2")  # the trap: alphabetic and not IsVirtualDevice
        self.assertEqual(physical_alphabetic_keyboards(real), [])
        bare = FakeAdbExec(input_dump=real)
        with self.assertRaises(ConfigNotRunError):
            apply_config(AdbClient(serial="TEST1234", exec_fn=bare), "tab-landscape-kbd")

    def test_a_keyboard_added_to_the_real_tablet_dump_is_found(self) -> None:
        real = (DATA_DIR / "pixeltablet_dumpsys_input.txt").read_text(encoding="utf-8")
        docked = real.replace("  Device 6: uinput-folio\n", "  Device 7: Pixel Tablet Keyboard\n    IsExternal: true\n    IsVirtualDevice: false\n    Sources: KEYBOARD\n    KeyboardType: 2\n  Device 6: uinput-folio\n", 1)
        self.assertNotEqual(docked, real)
        self.assertEqual(physical_alphabetic_keyboards(docked), ["Pixel Tablet Keyboard"])

    def test_only_a_physical_alphabetic_keyboard_counts(self) -> None:
        self.assertEqual(physical_alphabetic_keyboards(self.PHONE_INPUT), [])  # the virtual keyboard and the keys
        self.assertEqual(physical_alphabetic_keyboards(self.DOCKED_INPUT), ["Google Pixel Tablet Keyboard"])
        self.assertEqual(physical_alphabetic_keyboards(self.AVD_INPUT), ["qwerty2"])
        self.assertEqual(physical_alphabetic_keyboards(""), [])

    def test_keyboard_config_runs_where_the_device_reports_a_keyboard_and_is_not_run_otherwise(self) -> None:
        self.addCleanup(setattr, android_configs, "ROTATION_POLL_S", android_configs.ROTATION_POLL_S)
        android_configs.ROTATION_POLL_S = 0.0
        docked = FakeAdbExec(landscape_on_rot=0, input_dump=self.DOCKED_INPUT)
        apply_config(AdbClient(serial="TEST1234", exec_fn=docked), "tab-landscape-kbd")  # nothing passed: asked of the device
        self.assertIn(["adb", "-s", "TEST1234", "shell", "dumpsys", "input"], docked.commands)
        self.assertEqual(docked.cur_rotation, 0)
        bare = FakeAdbExec(input_dump=self.PHONE_INPUT)
        with self.assertRaises(ConfigNotRunError) as ctx:
            apply_config(AdbClient(serial="TEST1234", exec_fn=bare), "tab-landscape-kbd")
        self.assertIn("no physical keyboard", str(ctx.exception))
        self.assertFalse([c for c in bare.commands if "wm" in c])  # refused before it changed a setting

    def test_android_runner_executes_segments_and_captures_checkpoints(self) -> None:
        run_dir = Path(tempfile.mkdtemp(prefix="pstlab-test-android-runner-"))  # per process
        try:
            recorder = FakeAdbExec(landscape_on_rot=1)
            adb = AdbClient(serial="TEST1234", exec_fn=recorder)
            manifest = {
                "id": "SMOKE-android-desktop",
                "product": "android-desktop",
                "title": "Smoke launch",
                "fixture": "smoke",
                "configs": ["tab-landscape"],
                "fresh_install": True,
                "steps": [
                    {"maestro": "products/android/android-desktop/tests/e2e/SMOKE/01_launch.yaml"},
                    {"checkpoint": "launched"},
                ],
                "layout_expect": {"launched": {"required": ["selector_sidebar"]}},
            }
            status, _ = run_android_journey(
                manifest=manifest,
                config_name="tab-landscape",
                run_dir=run_dir,
                adb=adb,
                maestro_fn=lambda serial, flow, junit: 0,
            )
            self.assertIn(status, ("pass", "fail"))
            png_file = run_dir / "android-desktop" / "tab-landscape" / "SMOKE-android-desktop" / "01_launched.png"
            self.assertTrue(png_file.is_file())
            # Launch via the resolved launcher activity, never `monkey` (aborts on ChromeOS ARC).
            launches = [c for c in recorder.commands if "am" in c and "start" in c]
            self.assertTrue(any("com.photoselectortoolbox.lab/.MainActivity" in c for c in launches))
            self.assertFalse(any("monkey" in c for c in recorder.commands))
        finally:
            if run_dir.exists():
                shutil.rmtree(run_dir)

    def test_journey_is_not_run_on_a_device_without_a_required_dir(self) -> None:
        run_dir = Path(tempfile.mkdtemp(prefix="pstlab-test-android-requires-"))  # per process
        try:
            recorder = FakeAdbExec()
            adb = AdbClient(serial="TEST1234", exec_fn=recorder)
            manifest = {
                "id": "J-TOK-06",
                "product": "phototok",
                "title": "Phone Camera opens the picker inside the camera roll",
                "fixture": "smoke",
                "configs": ["phone-portrait"],
                "fresh_install": True,
                "requires_dirs": ["/sdcard/DCIM/Camera"],
                "steps": [{"checkpoint": "landing"}],
            }
            self.assertEqual(validate(manifest, "journey"), [])
            status, findings = run_android_journey(
                manifest=manifest,
                config_name="phone-portrait",
                run_dir=run_dir,
                adb=adb,
                maestro_fn=lambda serial, flow, junit: 0,
            )
            self.assertEqual((status, findings), ("not-run", []))
            result = (run_dir / "phototok" / "phone-portrait" / "J-TOK-06" / "result.json").read_text()
            self.assertIn("/sdcard/DCIM/Camera", result)
            # Nothing was installed, cleared or launched.
            self.assertFalse(any("pm" in c and "clear" in c for c in recorder.commands))
        finally:
            if run_dir.exists():
                shutil.rmtree(run_dir)


if __name__ == "__main__":
    unittest.main()
