"""Exploratory drivers: Android over a scripted adb, Linux commands, the macOS refusal, the mock.

Nothing here talks to a device or starts a display: adb is a scripted `FakeDevice`, xdotool is a
recording function. The real Linux session is in `test_explore_e2e_smoke.py`.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import types
import unittest
from pathlib import Path
from typing import Any, Callable
from unittest.mock import patch

LAB_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(LAB_DIR))

from lab.android.adb import AdbClient  # noqa: E402
from lab.android.configs import ConfigNotRunError  # noqa: E402
from lab.explore.actions import Action  # noqa: E402
from lab.explore.discovery import SYSTEM_PICKER_MODE  # noqa: E402
from lab.explore.expectations import read_counter  # noqa: E402
from lab.explore.driver import (  # noqa: E402
    AndroidDeviceDriver,
    AppCrashedError,
    AppLeftError,
    DriverError,
    DriverExecutionError,
    DriverSettlingError,
    DriverTimeoutError,
    LinuxDeviceDriver,
    MacOSDeviceDriver,
    MockDeviceDriver,
)
from lab.locks import DeviceBusy, held_lock, hub_lock_path  # noqa: E402
from lab.model import Tree  # noqa: E402
from lab.paths import lab_tmp_root  # noqa: E402

APP = "com.phototok.lab"
PNG = b"\x89PNG\r\n\x1a\n" + b"0" * 16


def node(res_id: str, bounds: str, *, package: str = APP, clickable: bool = False, focused: bool = False,
         klass: str = "android.view.View", children: str = "") -> str:
    return (
        f'<node index="0" text="" resource-id="{res_id}" class="{klass}" package="{package}" content-desc="" '
        f'checkable="false" checked="false" clickable="{str(clickable).lower()}" enabled="true" focusable="true" '
        f'focused="{str(focused).lower()}" scrollable="false" long-clickable="false" password="false" '
        f'selected="false" bounds="{bounds}">{children}</node>'
    )


def hierarchy(*, package: str = APP, origin: tuple[int, int] = (0, 0), size: tuple[int, int] = (1080, 1920),
              nav_top: int | None = None, focused_input: bool = False) -> str:
    ox, oy = origin
    button = node("com.phototok.lab:id/btn_keep", f"[{ox + 300},{oy + 400}][{ox + 700},{oy + 600}]", package=package,
                  clickable=True, klass="android.widget.Button")
    field = node("com.phototok.lab:id/note", f"[{ox + 100},{oy + 700}][{ox + 900},{oy + 800}]", package=package,
                 clickable=True, focused=focused_input, klass="android.widget.EditText")
    nav = node("android:id/navigationBarBackground", f"[0,{nav_top}][1080,1920]", package="com.android.systemui") if nav_top else ""
    root = node("", f"[{ox},{oy}][{ox + size[0]},{oy + size[1]}]", package=package, children=button + field)
    return f'<?xml version="1.0"?><hierarchy rotation="0">{root}{nav}</hierarchy>'


class FakeDevice:
    """The `exec_fn` of an `AdbClient`: answers the commands the driver issues, records all of them."""

    def __init__(self, *, xml: str | None = None, pid: str | None = "4242", density: int = 320) -> None:
        self.xml = xml or hierarchy()
        self.pid = pid
        self.density = density
        self.calls: list[tuple[str, ...]] = []
        self.fail: Callable[[tuple[str, ...]], BaseException | None] = lambda args: None
        self.log = ""
        self.dump_answers: list[str] = []
        self.files = "1000 ./IMG_0001.JPG\n"

    def __call__(self, cmd: list[str], timeout: int) -> subprocess.CompletedProcess[bytes]:
        assert cmd[:2] == ["adb", "-s"], cmd
        args = tuple(cmd[3:])
        self.calls.append(args)
        err = self.fail(args)
        if err is not None:
            raise err
        out = b""
        if args == ("get-state",):
            out = b"device\n"
        elif args == ("shell", "wm", "density"):
            out = f"Physical density: {self.density}\n".encode()
        elif args[:2] == ("shell", "pidof"):
            if self.pid is None:
                raise subprocess.CalledProcessError(1, cmd, b"")
            out = f"{self.pid}\n".encode()
        elif args[:3] == ("exec-out", "uiautomator", "dump"):
            out = (self.dump_answers.pop(0) if self.dump_answers else self.xml).encode()
        elif args[:2] == ("exec-out", "screencap"):
            out = PNG
        elif args[:3] == ("shell", "cmd", "package"):
            out = b"priority=0\n" + f"{APP}/.MainActivity\n".encode()
        elif args[:1] == ("logcat",):
            out = self.log.encode()
        elif args[:2] == ("shell", "cd") or (len(args) == 2 and args[0] == "shell" and "find ." in args[1]):
            out = self.files.encode()
        elif args[:3] == ("shell", "dumpsys", "window") and len(args) == 3:
            out = b"mDreamingLockscreen=false\n"
        elif args[:4] == ("shell", "dumpsys", "window", "displays"):
            out = b"init=1080x1920 320dpi cur=1080x1920\n"
        return subprocess.CompletedProcess(cmd, 0, out, b"")

    def commands(self, *prefix: str) -> list[tuple[str, ...]]:
        return [c for c in self.calls if c[: len(prefix)] == prefix]


class AndroidCase(unittest.TestCase):
    """Scratch dirs under the lab root, removed after the test; locks in a directory of their own."""

    def make_driver(self, device: FakeDevice | None = None, *, serial: str = "TESTSERIAL1", **kwargs: Any) -> AndroidDeviceDriver:
        self.device = device or FakeDevice()
        root = Path(tempfile.mkdtemp(prefix="pstlab-test-driver-", dir=_ensure_root()))
        self.addCleanup(shutil.rmtree, root, True)
        self.lock_dir = root / "locks"
        self.maestro_calls: list[tuple[Any, Path, Path]] = []

        def maestro(serial_: Any, flow: Path, junit: Path) -> int:
            self.maestro_calls.append((serial_, flow, junit))
            return 0

        defaults: dict[str, Any] = dict(
            serial=serial, app_id=APP, adb=AdbClient(serial=serial, exec_fn=self.device), out_dir=root / "out",
            lock_dir=self.lock_dir, maestro_fn=maestro, shade_settle_s=0.0, hub_wait_s=0.2,
        )
        defaults.update(kwargs)
        driver = AndroidDeviceDriver(**defaults)
        self.addCleanup(driver.teardown)
        return driver

    def ready(self, device: FakeDevice | None = None, **kwargs: Any) -> AndroidDeviceDriver:
        driver = self.make_driver(device, **kwargs)
        driver.setup()
        driver.capture_state()
        self.device.calls.clear()
        return driver


def _ensure_root() -> Path:
    root = lab_tmp_root()
    root.mkdir(parents=True, exist_ok=True)
    return root


class TestAndroidSetupAndPrepare(AndroidCase):
    def test_setup_only_reads_the_device(self) -> None:
        driver = self.make_driver()
        driver.setup()
        self.assertEqual(self.device.calls, [("get-state",), ("shell", "wm", "density")])
        self.assertEqual(self.maestro_calls, [])

    def test_setup_refuses_a_device_that_is_not_ready(self) -> None:
        device = FakeDevice()
        device.fail = lambda args: subprocess.CalledProcessError(1, ["adb"], b"", b"device 'X' not found") if args == ("get-state",) else None
        driver = self.make_driver(device)
        with self.assertRaises(DriverError) as ctx:
            driver.setup()
        self.assertIn("not reachable", str(ctx.exception))
        # the failed setup left no lock behind
        with held_lock(hub_lock_path(self.lock_dir), "probe"):
            pass

    def test_hub_lock_is_shared_by_drivers_of_one_run_and_released_by_the_last(self) -> None:
        first = self.make_driver(serial="TESTSERIAL1")
        first.setup()
        second = AndroidDeviceDriver(
            serial="TESTSERIAL2", app_id=APP, adb=AdbClient(serial="TESTSERIAL2", exec_fn=FakeDevice()),
            out_dir=first.out_dir.parent / "out2", lock_dir=self.lock_dir, hub_wait_s=0.2,
        )
        self.addCleanup(second.teardown)
        second.setup()  # a second device of the same run does not wait for the hub
        with self.assertRaises(DeviceBusy):
            with held_lock(hub_lock_path(self.lock_dir), "another run"):
                pass
        first.teardown()
        with self.assertRaises(DeviceBusy):  # still held for the second device
            with held_lock(hub_lock_path(self.lock_dir), "another run"):
                pass
        second.teardown()
        with held_lock(hub_lock_path(self.lock_dir), "another run"):
            pass

    def test_one_device_is_driven_by_one_driver(self) -> None:
        first = self.make_driver(serial="TESTSERIAL1")
        first.setup()
        twin = AndroidDeviceDriver(
            serial="TESTSERIAL1", app_id=APP, adb=AdbClient(serial="TESTSERIAL1", exec_fn=FakeDevice()),
            out_dir=first.out_dir.parent / "out2", lock_dir=self.lock_dir, hub_wait_s=0.2,
        )
        with self.assertRaises(DeviceBusy):
            twin.setup()

    @patch("lab.explore.driver.generate_fixtures")
    def test_prepare_readies_stages_and_launches_in_that_order(self, gen: Any) -> None:
        gen.side_effect = lambda name, out, **kw: (Path(out).mkdir(parents=True, exist_ok=True), (Path(out) / "a.JPG").write_bytes(b"x"))
        driver = self.make_driver()
        driver.setup()
        self.device.calls.clear()
        driver.prepare()
        calls = self.device.calls

        def first(*prefix: str) -> int:
            return next(i for i, c in enumerate(calls) if c[: len(prefix)] == prefix)

        self.assertLess(first("shell", "svc", "power", "stayon"), first("logcat", "-c"))
        self.assertLess(first("logcat", "-c"), first("shell", "rm", "-rf"))
        self.assertLess(first("shell", "rm", "-rf"), first("push"))
        self.assertLess(first("push"), first("shell", "am", "start"))
        self.assertEqual(len(self.maestro_calls), 1)
        self.assertEqual(self.maestro_calls[0][1].name, "_ensure_folder.yaml")
        self.assertEqual(self.maestro_calls[0][1].parent.parent.parent.name, "phototok")
        self.assertIn(("shell", "rm", "-rf", "/sdcard/PSTLab/work"), calls)
        self.assertTrue(driver.initial_fs["files"])
        self.assertIn(("shell", "am", "force-stop", APP), calls)
        self.assertFalse((driver.out_dir / "_fixture_stage").exists())

    @patch("lab.explore.driver.generate_fixtures")
    def test_prepare_fails_when_the_folder_cannot_be_opened(self, gen: Any) -> None:
        gen.side_effect = lambda name, out, **kw: Path(out).mkdir(parents=True, exist_ok=True)
        driver = self.make_driver(maestro_fn=lambda serial, flow, junit: 1)
        driver.setup()
        with self.assertRaises(DriverError) as ctx:
            driver.prepare()
        self.assertIn("_ensure_folder.yaml failed", str(ctx.exception))

    @patch("lab.explore.driver.generate_fixtures")
    def test_prepare_fails_when_the_app_is_not_in_front_afterwards(self, gen: Any) -> None:
        gen.side_effect = lambda name, out, **kw: Path(out).mkdir(parents=True, exist_ok=True)
        driver = self.make_driver(FakeDevice(xml=hierarchy(package="com.android.launcher3")))
        driver.setup()
        with self.assertRaises(AppLeftError):
            driver.prepare()

    def test_restart_force_stops_and_opens_the_folder_again(self) -> None:
        driver = self.ready()
        driver.restart_app()
        calls = self.device.calls
        stop = calls.index(("shell", "am", "force-stop", APP))
        home = ("shell", "input", "keyboard", "keyevent", "KEYCODE_HOME")
        self.assertIn(home, calls[:stop])  # a share sheet in its own task leaves the foreground first
        self.assertEqual(len(self.maestro_calls), 1)

    def test_a_working_folder_outside_the_lab_root_is_refused(self) -> None:
        with self.assertRaises(ValueError):
            AndroidDeviceDriver(serial="X1", app_id=APP, working_dir="/sdcard/DCIM/Camera")


class TestAndroidCapture(AndroidCase):
    def test_capture_reads_tree_screenshot_and_files(self) -> None:
        driver = self.ready()
        state = driver.capture_state()
        self.assertEqual([n.id for n in state.tree.nodes][1:], ["btn_keep", "note"])
        self.assertTrue(state.screenshot_bytes.startswith(b"\x89PNG"))
        self.assertEqual(state.fs_snapshot["root"], "PSTLab/work")
        self.assertEqual(state.fs_snapshot["files"], [{"path": "IMG_0001.JPG", "size": 1000}])
        self.assertIn(("shell", "cmd", "statusbar", "collapse"), self.device.calls)

    def test_another_app_in_front_raises_app_left_naming_it(self) -> None:
        driver = self.make_driver(FakeDevice(xml=hierarchy(package="com.android.launcher3")))
        driver.setup()
        with self.assertRaises(AppLeftError) as ctx:
            driver.capture_state()
        self.assertIn("com.android.launcher3", str(ctx.exception))
        self.assertGreaterEqual(len(self.device.commands("shell", "cmd", "statusbar", "collapse")), 2)  # tried the shade

    def test_the_folder_picker_in_front_is_still_the_app(self) -> None:
        driver = self.ready(FakeDevice(xml=hierarchy(package="com.google.android.documentsui")))
        state = driver.capture_state()
        self.assertTrue(state.tree.nodes)
        self.assertEqual(state.screen_mode, SYSTEM_PICKER_MODE)

    def test_the_app_in_front_is_not_the_picker(self) -> None:
        self.assertNotEqual(self.ready().capture_state().screen_mode, SYSTEM_PICKER_MODE)

    def test_an_offline_device_is_not_a_crashed_app(self) -> None:
        driver = self.ready()

        def offline(args):
            if args[:2] == ("shell", "pidof"):
                return subprocess.CalledProcessError(1, ["adb"], b"", b"error: device offline")
            return None

        self.device.fail = offline
        with self.assertRaises(DriverError) as ctx:
            driver.capture_state()
        self.assertNotIsInstance(ctx.exception, AppCrashedError)
        self.assertIn("device offline", str(ctx.exception))

    def test_a_vanished_process_raises_app_crashed_with_the_crash_log(self) -> None:
        device = FakeDevice(pid=None)
        device.log = "FATAL EXCEPTION: main"
        driver = self.make_driver(device)
        driver.setup()
        with self.assertRaises(AppCrashedError) as ctx:
            driver.capture_state()
        self.assertIn("FATAL EXCEPTION", str(ctx.exception))

    def test_a_dump_that_never_settles_is_an_error_not_an_empty_tree(self) -> None:
        device = FakeDevice()
        device.dump_answers = ["ERROR: could not get idle state."] * 5
        driver = self.make_driver(device)
        driver.setup()
        with patch("lab.explore.driver.time.sleep"):
            with self.assertRaises(DriverSettlingError):
                driver.capture_state()

    def test_a_truncated_dump_is_retried(self) -> None:
        device = FakeDevice()
        device.dump_answers = ['<hierarchy rotation="0"><node bounds="[0,0][10', hierarchy()]
        driver = self.make_driver(device)
        driver.setup()
        with patch("lab.explore.driver.time.sleep"):
            self.assertTrue(driver.capture_state().tree.nodes)

    def test_a_screencap_that_is_not_a_png_is_an_error(self) -> None:
        driver = self.ready()
        with patch.object(driver.adb, "run_bytes", side_effect=lambda *a, **k: b"<html>" if "screencap" in a else hierarchy().encode()):
            with self.assertRaises(DriverError):
                driver.capture_state()

    def test_a_focused_input_is_marked_on_the_tree(self) -> None:
        driver = self.ready(FakeDevice(xml=hierarchy(focused_input=True)))
        focused = [n.id for n in driver.capture_state().tree.nodes if getattr(n, "focused", False)]
        self.assertEqual(focused, ["note"])

    def test_app_log_is_the_app_process_since_the_last_read(self) -> None:
        device = FakeDevice()
        driver = self.ready(device)
        device.log = "1760000000.100  4242  4242 E App: boom\n1760000001.200  4242  4242 I App: next\n"
        lines = driver.read_app_log()
        self.assertEqual(len(lines), 2)
        first_read = device.commands("logcat")[-1]
        self.assertIn("--pid=4242", first_read)
        self.assertNotIn("-T", first_read)
        device.log = ""
        driver.read_app_log()
        second_read = device.commands("logcat")[-1]
        self.assertEqual(second_read[second_read.index("-T") + 1], "1760000001.201")

    def test_app_log_of_a_dead_process_is_still_readable(self) -> None:
        device = FakeDevice()
        driver = self.ready(device)
        device.pid = None
        device.log = "1760000000.100  4242  4242 E AndroidRuntime: FATAL EXCEPTION\n"
        self.assertEqual(len(driver.read_app_log()), 1)
        self.assertIn("--pid=4242", device.commands("logcat")[-1])


class TestAndroidActions(AndroidCase):
    def test_tap_adds_the_window_origin_and_converts_dp(self) -> None:
        # a freeform window at (100, 50) px, 2 px per dp
        driver = self.ready(FakeDevice(xml=hierarchy(origin=(100, 50), size=(800, 1000))))
        driver.execute_action(Action("tap", "btn_keep", params={"x": 200.0, "y": 150.0}))
        self.assertEqual(self.device.commands("shell", "input", "tap"), [("shell", "input", "tap", "500", "350")])

    def test_tap_without_coordinates_uses_the_captured_node(self) -> None:
        driver = self.ready(FakeDevice(xml=hierarchy(origin=(100, 50), size=(800, 1000))))
        driver.execute_action(Action("tap", "btn_keep"))
        # button px [400,450][800,650] on the screen (origin + [300,400][700,600]) -> centre (600, 550)
        self.assertEqual(self.device.commands("shell", "input", "tap"), [("shell", "input", "tap", "600", "550")])

    def test_tap_on_an_unknown_target_without_coordinates_raises(self) -> None:
        driver = self.ready()
        with self.assertRaises(DriverExecutionError):
            driver.execute_action(Action("tap", "nowhere"))

    def test_taps_and_swipes_stay_above_the_navigation_bar(self) -> None:
        driver = self.ready(FakeDevice(xml=hierarchy(nav_top=1800)))
        driver.execute_action(Action("tap", params={"x": 100.0, "y": 940.0}))  # 1880 px: on the bar
        driver.execute_action(Action("swipe", params={"x1": 100.0, "y1": 500.0, "x2": 100.0, "y2": 950.0}))
        tap = self.device.commands("shell", "input", "tap")[0]
        swipe = self.device.commands("shell", "input", "swipe")[0]
        self.assertEqual(int(tap[-1]), 1798)
        self.assertEqual(int(swipe[-2]), 1798)

    def test_without_a_navigation_bar_node_92_percent_of_the_window_is_the_limit(self) -> None:
        driver = self.ready()
        driver.execute_action(Action("tap", params={"x": 100.0, "y": 960.0}))
        self.assertEqual(int(self.device.commands("shell", "input", "tap")[0][-1]), int(1920 * 0.92) - 2)

    def test_directional_swipe_crosses_the_window_centre(self) -> None:
        driver = self.ready()
        driver.execute_action(Action("swipe", params={"direction": "up", "duration_ms": 250}))
        _, _, _, x1, y1, x2, y2, dur = self.device.commands("shell", "input", "swipe")[0]
        self.assertEqual((int(x1), int(x2), dur), (540, 540, "250"))
        self.assertGreater(int(y1), int(y2))

    def test_incomplete_swipe_raises(self) -> None:
        driver = self.ready()
        with self.assertRaises(DriverExecutionError):
            driver.execute_action(Action("swipe", params={"x1": 1, "y1": 2}))

    def test_nan_coordinates_raise(self) -> None:
        driver = self.ready()
        with self.assertRaises(DriverExecutionError):
            driver.execute_action(Action("tap", params={"x": float("nan"), "y": 1.0}))

    def test_double_tap_taps_twice(self) -> None:
        driver = self.ready()
        with patch("lab.explore.driver.time.sleep"):
            driver.execute_action(Action("tap", params={"x": 100.0, "y": 200.0, "double_tap": True, "count": 2}))
        self.assertEqual(len(self.device.commands("shell", "input", "tap")), 2)

    def test_key_codes(self) -> None:
        driver = self.ready()
        driver.execute_action(Action("key", params={"key": "M"}))
        driver.execute_action(Action("key", params={"key": "Right"}))
        driver.execute_action(Action("key", params={"key": "Return"}))
        keys = [c[-1] for c in self.device.commands("shell", "input", "keyboard", "keyevent")]
        self.assertEqual(keys, ["KEYCODE_M", "KEYCODE_DPAD_RIGHT", "KEYCODE_ENTER"])

    def test_delete_is_forward_delete_not_backspace(self) -> None:
        driver = self.ready()
        driver.execute_action(Action("key", params={"key": "Delete"}))
        self.assertEqual(self.device.commands("shell", "input", "keyboard", "keyevent")[0][-1], "KEYCODE_FORWARD_DEL")

    def test_shortcut_is_a_key_combination_with_its_modifier(self) -> None:
        driver = self.ready()
        driver.execute_action(Action("key", params={"key": "z", "modifiers": ["ctrl"], "shortcut": "Ctrl+Z"}))
        driver.execute_action(Action("key", params={"key": "Ctrl+O"}))
        combos = [c[4:] for c in self.device.commands("shell", "input", "keyboard", "keycombination")]
        self.assertEqual(combos, [("KEYCODE_CTRL_LEFT", "KEYCODE_Z"), ("KEYCODE_CTRL_LEFT", "KEYCODE_O")])

    def test_question_mark_is_shift_slash_and_comma_has_a_code(self) -> None:
        driver = self.ready()
        driver.execute_action(Action("key", params={"key": "?"}))
        driver.execute_action(Action("key", params={"key": ",", "modifiers": ["ctrl"]}))
        combos = [c[4:] for c in self.device.commands("shell", "input", "keyboard", "keycombination")]
        self.assertEqual(combos, [("KEYCODE_SHIFT_LEFT", "KEYCODE_SLASH"), ("KEYCODE_CTRL_LEFT", "KEYCODE_COMMA")])

    def test_unknown_key_and_unknown_modifier_raise_instead_of_being_dropped(self) -> None:
        driver = self.ready()
        with self.assertRaises(DriverExecutionError):
            driver.execute_action(Action("key", params={"key": "Hyperspace"}))
        with self.assertRaises(DriverExecutionError):
            driver.execute_action(Action("key", params={"key": "z", "modifiers": ["bucky"]}))
        with self.assertRaises(DriverExecutionError):
            driver.execute_action(Action("key", params={}))
        self.assertEqual(self.device.commands("shell", "input"), [])

    def test_unsupported_action_type_raises(self) -> None:
        driver = self.ready()
        with self.assertRaises(DriverExecutionError) as ctx:
            driver.execute_action(Action("pinch"))
        self.assertIn("pinch", str(ctx.exception))

    def test_a_command_that_exits_non_zero_raises(self) -> None:
        device = FakeDevice()
        driver = self.ready(device)
        device.fail = lambda args: subprocess.CalledProcessError(255, ["adb"], b"", b"inject failed") if "tap" in args else None
        with self.assertRaises(DriverExecutionError) as ctx:
            driver.execute_action(Action("tap", params={"x": 1.0, "y": 1.0}))
        self.assertIn("255", str(ctx.exception))

    def test_a_command_that_times_out_raises_a_timeout(self) -> None:
        device = FakeDevice()
        driver = self.ready(device)
        device.fail = lambda args: subprocess.TimeoutExpired(["adb"], 30) if "tap" in args else None
        with self.assertRaises(DriverTimeoutError):
            driver.execute_action(Action("tap", params={"x": 1.0, "y": 1.0}))

    def test_back_and_background(self) -> None:
        driver = self.ready()
        driver.execute_action(Action("back"))
        with patch("lab.explore.driver.time.sleep"):
            driver.execute_action(Action("background", params={"duration_s": 1.0}))
        self.assertIn(("shell", "input", "keyboard", "keyevent", "KEYCODE_BACK"), self.device.calls)
        self.assertIn(("shell", "input", "keyevent", "KEYCODE_HOME"), self.device.calls)
        self.assertIn(("shell", "am", "start", "-W", "-n", f"{APP}/.MainActivity"), self.device.calls)

    def test_rotate_toggles_to_the_other_orientation_with_the_verified_helper(self) -> None:
        driver = self.ready()
        with patch("lab.explore.driver.set_verified_rotation") as rotate, patch("lab.explore.driver.time.sleep"):
            driver.execute_action(Action("rotate", params={"toggle": True}))  # the fake display is portrait
        rotate.assert_called_once_with(driver.adb, "landscape")

    def test_a_rotation_the_display_refuses_is_a_failed_step(self) -> None:
        driver = self.ready()
        with patch("lab.explore.driver.set_verified_rotation", side_effect=ConfigNotRunError("locked at 1080x1920")):
            with self.assertRaises(DriverExecutionError):
                driver.execute_action(Action("rotate", params={"toggle": True}))

    def test_rotate_without_a_target_orientation_raises(self) -> None:
        driver = self.ready()
        with self.assertRaises(DriverExecutionError):
            driver.execute_action(Action("rotate", params={}))

    def test_resize_needs_a_freeform_window_and_a_size(self) -> None:
        driver = self.ready()
        with self.assertRaises(DriverExecutionError):
            driver.execute_action(Action("resize", params={}))
        with patch("lab.explore.driver.resize_app_window", side_effect=ConfigNotRunError("app is fullscreen")):
            with self.assertRaises(DriverExecutionError):
                driver.execute_action(Action("resize", params={"width": 800, "height": 600}))
        with patch("lab.explore.driver.resize_app_window") as resize:
            driver.execute_action(Action("resize", params={"width": 800, "height": 600}))
        resize.assert_called_once_with(driver.adb, APP, "800x600")


class RecordingXdotool:
    """The `exec_fn` of a `LinuxDeviceDriver`: records commands, answers window queries.

    `windows` maps window id to size; `main` is the id named "Blitzlens"; a key press listed in
    `closes_on` (e.g. "Escape") removes the newest window that is not the main one.
    """

    def __init__(self) -> None:
        self.commands: list[list[str]] = []
        self.reply = subprocess.CompletedProcess([], 0, "", "")
        self.windows = {5: (1920, 1080)}
        self.main = 5
        self.closes_on: set[str] = set()

    def __call__(self, cmd: list[str], env: dict[str, str]) -> subprocess.CompletedProcess[str]:
        self.commands.append(cmd)
        if cmd[:2] == ["xdotool", "search"]:
            ids = sorted(self.windows) if cmd[-1] == "." else [w for w in self.windows if w == self.main]
            return subprocess.CompletedProcess(cmd, 0 if ids else 1, "".join(f"{w}\n" for w in ids), "")
        if cmd[:2] == ["xdotool", "getwindowgeometry"]:
            w, h = self.windows[int(cmd[-1])]
            return subprocess.CompletedProcess(cmd, 0, f"WINDOW={cmd[-1]}\nWIDTH={w}\nHEIGHT={h}\n", "")
        if cmd[:2] == ["xdotool", "key"] and cmd[-1] in self.closes_on:
            dialogs = [w for w in self.windows if w != self.main]
            if dialogs:
                del self.windows[max(dialogs)]
        return self.reply

    @property
    def last(self) -> list[str]:
        return self.commands[-1]

    def sent(self, verb: str) -> list[list[str]]:
        return [c for c in self.commands if c[:2] == ["xdotool", verb]]


class TestLinuxCommands(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-linux-", dir=_ensure_root()))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.xdo = RecordingXdotool()
        self.driver = LinuxDeviceDriver(out_dir=self.tmp / "out", exec_fn=self.xdo, fixture_dir=self.tmp / "work")

    def key(self, key: str, *mods: str) -> str:
        self.driver.execute_action(Action("key", params={"key": key, "modifiers": list(mods)}))
        sent = self.xdo.sent("key")[-1]
        self.assertEqual(sent[:3], ["xdotool", "key", "--clearmodifiers"])
        return sent[3]

    def test_keys_become_x_keysyms(self) -> None:
        self.assertEqual(self.key("M"), "m")
        self.assertEqual(self.key("Space"), "space")
        self.assertEqual(self.key("Delete"), "Delete")
        self.assertEqual(self.key("Return"), "Return")
        self.assertEqual(self.key("Escape"), "Escape")
        self.assertEqual(self.key("Right"), "Right")
        self.assertEqual(self.key("?"), "question")
        self.assertEqual(self.key(","), "comma")

    def test_modifiers_are_kept(self) -> None:
        self.assertEqual(self.key("z", "ctrl"), "ctrl+z")
        self.assertEqual(self.key(",", "ctrl"), "ctrl+comma")
        self.assertEqual(self.key("o", "ctrl", "shift"), "ctrl+shift+o")
        self.driver.execute_action(Action("key", params={"key": "Ctrl+D"}))
        self.assertEqual(self.xdo.sent("key")[-1][3], "ctrl+d")

    def test_unknown_modifier_raises_and_nothing_is_sent(self) -> None:
        with self.assertRaises(DriverExecutionError):
            self.driver.execute_action(Action("key", params={"key": "z", "modifiers": ["bucky"]}))
        self.assertEqual(self.xdo.commands, [])

    def test_xdotool_warning_about_an_unknown_key_is_an_error(self) -> None:
        self.xdo.reply = subprocess.CompletedProcess([], 0, "", "No such key name 'Hyperspace'")
        with self.assertRaises(DriverExecutionError):
            self.driver.execute_action(Action("key", params={"key": "Hyperspace"}))

    def test_xdotool_failure_raises(self) -> None:
        self.xdo.reply = subprocess.CompletedProcess([], 1, "", "Cannot open display")
        with self.assertRaises(DriverExecutionError):
            self.driver.execute_action(Action("key", params={"key": "m"}))

    def test_keys_go_to_the_window_the_last_tree_described(self) -> None:
        self.xdo.windows = {5: (1920, 1080), 9: (859, 338), 12: (209, 30)}  # main, dialog, tooltip
        self.driver.last_state = types.SimpleNamespace(tree=Tree("linux", {"w": 849, "h": 328, "unit": "px"}))
        self.driver.execute_action(Action("key", params={"key": "Right"}))
        focus = next(c for c in self.xdo.commands if c[:2] == ["xdotool", "windowfocus"])
        self.assertEqual(focus, ["xdotool", "windowfocus", "9"])

    def test_click_moves_the_pointer_then_clicks(self) -> None:
        self.driver.execute_action(Action("click", "btn", params={"x": 500.4, "y": 399.6}))
        self.assertEqual(self.xdo.last, ["xdotool", "mousemove", "500", "400", "click", "1"])

    def test_double_click(self) -> None:
        self.driver.execute_action(Action("click", "slot", params={"x": 10, "y": 20, "double_click": True, "count": 2}))
        self.assertEqual(self.xdo.last, ["xdotool", "mousemove", "10", "20", "click", "--repeat", "2", "--delay", "80", "1"])

    def test_click_on_a_captured_node_without_coordinates(self) -> None:
        tree = Tree.from_dict({"platform": "linux", "window": {"w": 100, "h": 100}, "nodes": [
            {"id": "btn", "parent": None, "role": "button", "name": "", "bounds": [10, 20, 30, 40], "interactive": True, "visible": True}]})
        self.driver.last_state = types.SimpleNamespace(tree=tree)
        self.driver.execute_action(Action("click", "btn"))
        self.assertEqual(self.xdo.last[:4], ["xdotool", "mousemove", "25", "40"])
        with self.assertRaises(DriverExecutionError):
            self.driver.execute_action(Action("click", "gone"))

    def test_back_on_the_main_window_is_just_escape(self) -> None:
        with patch.object(LinuxDeviceDriver, "_send_wm_delete") as delete:
            self.driver.execute_action(Action("back"))
        self.assertEqual(self.xdo.sent("key"), [["xdotool", "key", "--clearmodifiers", "Escape"]])
        delete.assert_not_called()

    def test_back_leaves_a_dialog_that_closes_on_escape_alone(self) -> None:
        self.xdo.windows = {5: (1920, 1080), 9: (500, 480)}
        self.xdo.closes_on = {"Escape"}
        self.driver.last_state = types.SimpleNamespace(tree=Tree("linux", {"w": 490, "h": 470, "unit": "px"}))
        with patch.object(LinuxDeviceDriver, "_send_wm_delete") as delete, patch("lab.explore.driver.time.sleep"):
            self.driver.execute_action(Action("back"))
        self.assertIn(["xdotool", "windowfocus", "9"], self.xdo.commands)
        delete.assert_not_called()

    def test_back_closes_a_dialog_that_ignores_escape_like_a_window_manager(self) -> None:
        self.xdo.windows = {5: (1920, 1080), 9: (500, 480)}  # the shortcuts window: a plain Adw.Window
        self.driver.last_state = types.SimpleNamespace(tree=Tree("linux", {"w": 490, "h": 470, "unit": "px"}))
        with patch.object(LinuxDeviceDriver, "_send_wm_delete") as delete, patch("lab.explore.driver.time.sleep"):
            self.driver.execute_action(Action("back"))
            self.driver.execute_action(Action("key", params={"key": "Escape"}))  # the same thing
        self.assertEqual([c.args for c in delete.call_args_list], [(9,), (9,)])

    def test_resize_sizes_the_window(self) -> None:
        self.driver.execute_action(Action("resize", params={"width": 1000, "height": 700}))
        self.assertEqual(self.xdo.last[-3:], ["--sync", "1000", "700"])

    def test_actions_a_desktop_window_cannot_do_raise(self) -> None:
        for action in (Action("swipe", params={"direction": "up"}), Action("background"), Action("rotate"), Action("pinch")):
            with self.subTest(action=action.action_type):
                with self.assertRaises(DriverExecutionError):
                    self.driver.execute_action(action)
        self.assertEqual(self.xdo.commands, [])

    def test_app_log_is_what_the_app_wrote_since_the_last_read(self) -> None:
        log = self.driver.artifacts_dir / "app.log"
        log.parent.mkdir(parents=True)
        log.write_text("one\ntwo\n", encoding="utf-8")
        self.assertEqual(self.driver.read_app_log(), ["one", "two"])
        with log.open("a", encoding="utf-8") as fh:
            fh.write("three\n")
        self.assertEqual(self.driver.read_app_log(), ["three"])
        self.assertEqual(self.driver.read_app_log(), [])

    def test_an_exited_app_is_a_crash_with_its_log(self) -> None:
        (self.driver.artifacts_dir).mkdir(parents=True)
        (self.driver.artifacts_dir / "app.log").write_text("Segmentation fault", encoding="utf-8")
        self.driver._app_proc = types.SimpleNamespace(poll=lambda: -11)  # type: ignore[assignment]
        with self.assertRaises(AppCrashedError) as ctx:
            self.driver.capture_state()
        self.assertIn("Segmentation fault", str(ctx.exception))

    def test_capture_reads_the_checkpoint_the_app_wrote(self) -> None:
        art = self.driver.artifacts_dir
        art.mkdir(parents=True)
        (self.tmp / "work").mkdir()
        (self.tmp / "work" / "IMG_1.JPG").write_bytes(b"x")
        self.driver._app_proc = types.SimpleNamespace(poll=lambda: None)  # type: ignore[assignment]
        tree = {"platform": "linux", "window": {"w": 1910, "h": 1070, "unit": "px", "scale": 1.0}, "nodes": [
            {"id": "btn_move", "parent": None, "role": "button", "name": "", "bounds": [0, 0, 80, 40], "interactive": True, "visible": True}]}
        written: list[list[str]] = []

        def exec_fn(cmd: list[str], env: dict[str, str]) -> subprocess.CompletedProcess[str]:
            written.append(cmd)
            if cmd[0] == "gapplication":
                name = cmd[-1].strip("'")
                (art / f"01_{name}.png").write_bytes(b"\x89PNGdata")
                (art / f"01_{name}.tree.json").write_text(json.dumps(tree), encoding="utf-8")
                state = {"current_index": 4, "total": 9, "tally": {"selected": 1, "trashed": 0, "remaining": 9}, "mode": 3}
                (art / f"01_{name}.state.json").write_text(json.dumps(state), encoding="utf-8")
                (art / f"01_{name}.timing.json").write_text("{}", encoding="utf-8")
            return subprocess.CompletedProcess(cmd, 0, "", "")

        self.driver.exec_fn = exec_fn
        state = self.driver.capture_state()
        self.assertEqual(written[0][:4], ["gapplication", "action", "app.alpatix.Blitzlens", "lab-checkpoint"])
        self.assertEqual([n.id for n in state.tree.nodes], ["btn_move"])
        self.assertEqual(state.screenshot_bytes, b"\x89PNGdata")
        self.assertEqual(state.fs_snapshot["files"], [{"path": "IMG_1.JPG", "size": 1}])
        self.assertEqual(state.screen_mode, "viewer")
        # The tree exports no counter; the app's own position is what navigation is checked by.
        self.assertEqual(state.position, (4, 9))
        self.assertEqual(read_counter(state), (4, 9))

    def test_a_checkpoint_without_the_apps_position_is_an_error(self) -> None:
        art = self.driver.artifacts_dir
        art.mkdir(parents=True)
        (self.tmp / "work").mkdir()
        self.driver._app_proc = types.SimpleNamespace(poll=lambda: None)  # type: ignore[assignment]
        tree = {"platform": "linux", "window": {"w": 100, "h": 100}, "nodes": []}

        def exec_fn(cmd: list[str], env: dict[str, str]) -> subprocess.CompletedProcess[str]:
            if cmd[0] == "gapplication":
                name = cmd[-1].strip("'")
                (art / f"01_{name}.png").write_bytes(b"\x89PNGdata")
                (art / f"01_{name}.tree.json").write_text(json.dumps(tree), encoding="utf-8")
                (art / f"01_{name}.timing.json").write_text("{}", encoding="utf-8")
            return subprocess.CompletedProcess(cmd, 0, "", "")

        self.driver.exec_fn = exec_fn
        with self.assertRaises(DriverError) as ctx:
            self.driver.capture_state()
        self.assertIn("position", str(ctx.exception))

    def test_a_checkpoint_without_a_screenshot_is_an_error(self) -> None:
        art = self.driver.artifacts_dir
        art.mkdir(parents=True)
        (self.tmp / "work").mkdir()
        self.driver._app_proc = types.SimpleNamespace(poll=lambda: None)  # type: ignore[assignment]

        def exec_fn(cmd: list[str], env: dict[str, str]) -> subprocess.CompletedProcess[str]:
            if cmd[0] == "gapplication":
                name = cmd[-1].strip("'")
                (art / f"01_{name}.tree.json").write_text("{}", encoding="utf-8")
                (art / f"01_{name}.timing.json").write_text("{}", encoding="utf-8")
            return subprocess.CompletedProcess(cmd, 0, "", "")

        self.driver.exec_fn = exec_fn
        with self.assertRaises(DriverError) as ctx:
            self.driver.capture_state()
        self.assertIn("screenshot", str(ctx.exception))

    def test_a_checkpoint_that_never_arrives_times_out(self) -> None:
        self.driver.artifacts_dir.mkdir(parents=True)
        self.driver.checkpoint_timeout_s = 0.1
        self.driver._app_proc = types.SimpleNamespace(poll=lambda: None)  # type: ignore[assignment]
        with self.assertRaises(DriverTimeoutError):
            self.driver.capture_state()

    def test_a_failed_checkpoint_command_is_an_error(self) -> None:
        self.driver.artifacts_dir.mkdir(parents=True)
        self.driver._app_proc = types.SimpleNamespace(poll=lambda: None)  # type: ignore[assignment]
        self.xdo.reply = subprocess.CompletedProcess([], 1, "", "no such action")
        with self.assertRaises(DriverExecutionError):
            self.driver.capture_state()

    def test_a_fixture_outside_the_lab_root_is_refused(self) -> None:
        with self.assertRaises(ValueError):
            LinuxDeviceDriver(fixture_dir="/etc", out_dir=self.tmp / "out")

    def test_nothing_is_created_until_the_session_starts(self) -> None:
        driver = LinuxDeviceDriver()
        self.assertFalse(driver.lab_dir.exists())
        self.assertFalse(driver.out_dir.exists())
        driver.teardown()


class TestMacOSRefusal(unittest.TestCase):
    def test_every_step_refuses_with_the_reason(self) -> None:
        driver = MacOSDeviceDriver(out_dir=Path("."))
        for call in (driver.setup, driver.prepare, driver.capture_state, driver.restart_app, driver.read_app_log,
                     lambda: driver.execute_action(Action("key", params={"key": "m"}))):
            with self.assertRaises(DriverError) as ctx:
                call()
            self.assertEqual(str(ctx.exception), "Lensjet exploration needs a live-app driver; not implemented yet")
        driver.teardown()  # nothing was started, nothing to refuse


class TestMockDriver(unittest.TestCase):
    def test_lifecycle_and_transitions(self) -> None:
        driver = MockDeviceDriver(product="phototok", total_photos=3)
        with driver:
            state0 = driver.capture_state()
            self.assertEqual(state0.screen_mode, "landing")
            self.assertEqual(len(state0.fs_snapshot["files"]), 3)

            driver.execute_action(Action("tap", "card_folder"))
            self.assertEqual(driver.capture_state().screen_mode, "viewer")

            driver.execute_action(Action("swipe", params={"direction": "up"}))
            self.assertEqual(driver.current_index, 1)

            driver.execute_action(Action("key", params={"key": "M"}))
            selection = [f["path"] for f in driver.capture_state().fs_snapshot["files"] if "Selection/" in f["path"]]
            self.assertIn("Selection/photo_02.jpg", selection)

            driver.execute_action(Action("key", params={"key": "Z", "modifiers": ["ctrl"]}))
            selection = [f["path"] for f in driver.capture_state().fs_snapshot["files"] if "Selection/" in f["path"]]
            self.assertNotIn("Selection/photo_02.jpg", selection)

    def test_fault_injection(self) -> None:
        with self.assertRaises(DriverExecutionError):
            MockDeviceDriver(fail_on_action="tap").execute_action(Action("tap", "btn_fail"))
        with self.assertRaises(DriverTimeoutError):
            MockDeviceDriver(timeout_on_capture=True).capture_state()
        with self.assertRaises(DriverSettlingError):
            MockDeviceDriver(settle_failure_count=1).capture_state()

    def test_scripted_capture_errors_are_raised_once_each_in_order(self) -> None:
        driver = MockDeviceDriver(capture_errors=[AppLeftError("com.android.launcher3 is in front"), AppCrashedError("gone")])
        with self.assertRaises(AppLeftError):
            driver.capture_state()
        with self.assertRaises(AppCrashedError):
            driver.capture_state()
        self.assertTrue(driver.capture_state().tree.nodes)

    def test_unsupported_action_raises_and_restart_goes_home(self) -> None:
        driver = MockDeviceDriver(product="phototok")
        with self.assertRaises(DriverExecutionError):
            driver.execute_action(Action("pinch"))
        driver.execute_action(Action("tap", "card_folder"))
        self.assertEqual(driver.screen_mode, "viewer")
        driver.restart_app()
        self.assertEqual((driver.screen_mode, driver.restarts), ("landing", 1))

    def test_app_log_is_read_once(self) -> None:
        driver = MockDeviceDriver(log_lines=["E boom"])
        self.assertEqual(driver.read_app_log(), ["E boom"])
        self.assertEqual(driver.read_app_log(), [])

    def test_caller_supplied_directories_survive_teardown(self) -> None:
        root = Path(tempfile.mkdtemp(prefix="pstlab-test-mock-", dir=_ensure_root()))
        self.addCleanup(shutil.rmtree, root, True)
        (root / "work").mkdir()
        (root / "work" / "custom.txt").write_text("keep", encoding="utf-8")
        driver = MockDeviceDriver(working_dir=root / "work", out_dir=root / "out")
        driver.setup()
        driver.teardown()
        self.assertTrue((root / "work" / "custom.txt").exists())
        self.assertTrue((root / "out").exists())

    def test_owned_directories_are_created_by_setup_and_removed_by_teardown(self) -> None:
        driver = MockDeviceDriver()
        self.assertFalse(driver.working_dir.exists())
        self.assertFalse(driver.out_dir.exists())
        driver.setup()
        self.assertTrue(driver.working_dir.exists() and driver.out_dir.exists())
        driver.teardown()
        self.assertFalse(driver.working_dir.exists() or driver.out_dir.exists())


if __name__ == "__main__":
    unittest.main()
