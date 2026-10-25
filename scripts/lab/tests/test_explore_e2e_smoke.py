"""Blitzlens driven for real in a headless session, and discovery fed hostile trees.

`TestLinuxRealSession` starts the app in its own Xvfb display and private D-Bus (Xvfb, xdotool,
gapplication, dbus-daemon and PyGObject with GTK 4 + Libadwaita are needed; the test is skipped
with the missing piece named) and checks what a run is worth: the captured trees have nodes, an
executed click changes what the app shows, a killed app is reported, nothing is left running.
"""

from __future__ import annotations

import json
import math
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import List, Optional

LAB_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(LAB_DIR))

from lab.explore.actions import Action  # noqa: E402
from lab.explore.discovery import inspect_screen  # noqa: E402
from lab.explore.driver import (  # noqa: E402
    AndroidDeviceDriver,
    AppCrashedError,
    DriverExecutionError,
    LinuxDeviceDriver,
    _parse_coord,
)
from lab.explore.selector import ActionSelector  # noqa: E402
from lab.linux.runner import lab_python  # noqa: E402
from lab.model import Node, Tree  # noqa: E402
from lab.paths import lab_tmp_root  # noqa: E402


def linux_session_gap() -> Optional[str]:
    """What is missing for a real Blitzlens session on this machine, or None."""
    if sys.platform != "linux":
        return f"a Linux host (this is {sys.platform})"
    for binary in ("Xvfb", "xdotool", "gapplication"):
        if shutil.which(binary) is None:
            return f"{binary} on PATH"
    if shutil.which("dbus-daemon") is None and not Path("/usr/bin/dbus-daemon").is_file():
        return "dbus-daemon"
    probe = subprocess.run(
        [lab_python(), "-c", "import gi; gi.require_version('Gtk', '4.0'); gi.require_version('Adw', '1'); "
                             "from gi.repository import Gtk, Adw"],
        capture_output=True, text=True, check=False,
    )
    if probe.returncode != 0:
        return f"PyGObject with GTK 4 and Libadwaita for {lab_python()}"
    return None


LINUX_GAP = linux_session_gap()


@unittest.skipIf(LINUX_GAP is not None, f"no headless Blitzlens session without {LINUX_GAP}")
class TestLinuxRealSession(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        lab_tmp_root().mkdir(parents=True, exist_ok=True)
        cls.out = Path(tempfile.mkdtemp(prefix="pstlab-test-session-", dir=lab_tmp_root()))
        cls.driver = LinuxDeviceDriver(out_dir=cls.out / "run", settle_ms=200)
        try:
            cls.driver.setup()
            cls.driver.prepare()
            cls.first = cls.driver.capture_state()
        except BaseException:
            cls.driver.teardown()
            shutil.rmtree(cls.out, ignore_errors=True)
            raise

    @classmethod
    def tearDownClass(cls) -> None:
        cls.driver.teardown()
        shutil.rmtree(cls.out, ignore_errors=True)

    def test_the_first_capture_has_nodes_and_a_screenshot(self) -> None:
        self.assertGreater(len(self.first.tree.nodes), 0)
        self.assertTrue(any(n.interactive for n in self.first.tree.nodes))
        self.assertTrue(self.first.screenshot_bytes.startswith(b"\x89PNG"))
        self.assertEqual(self.first.screen_mode, "viewer")
        self.assertGreater(len(self.first.fs_snapshot["files"]), 0)

    def test_the_session_is_private(self) -> None:
        env = self.driver._env
        self.assertRegex(env["DISPLAY"], r"^:\d+$")
        self.assertNotEqual(env["DISPLAY"], os.environ.get("DISPLAY"))
        self.assertNotEqual(env["DBUS_SESSION_BUS_ADDRESS"], os.environ.get("DBUS_SESSION_BUS_ADDRESS"))
        self.assertNotIn("WAYLAND_DISPLAY", env)
        self.assertEqual(env["PST_LAB"], "1")
        self.assertTrue(str(self.driver.lab_dir).startswith(str(lab_tmp_root())))
        self.assertTrue(str(self.driver.working_dir).startswith(str(lab_tmp_root())))

    def click(self, state, node_id: str) -> None:
        node = next(n for n in state.tree.nodes if n.id == node_id)
        self.driver.execute_action(Action("click", node_id, params={"x": node.x + node.w / 2, "y": node.y + node.h / 2}))
        self.driver.settle()

    def test_a_click_changes_what_the_app_shows(self) -> None:
        driver = self.driver
        self.click(driver.capture_state(), "btn_mode_1")  # a known starting layout
        before = driver.capture_state()
        self.click(before, "btn_mode_2")
        after = driver.capture_state()
        self.assertNotEqual([n.id for n in before.tree.nodes], [n.id for n in after.tree.nodes])
        self.click(after, "btn_mode_1")
        self.assertEqual([n.id for n in driver.capture_state().tree.nodes], [n.id for n in before.tree.nodes])

    def test_discovered_actions_run_and_every_capture_has_a_screenshot(self) -> None:
        driver, selector = self.driver, ActionSelector(seed=11)
        state = driver.capture_state()
        steps = 0
        for _ in range(10):
            candidates = inspect_screen(state.tree, "linux-desktop").candidate_interactions
            action = selector.select(candidates, screen_state=state)
            driver.execute_action(action)
            driver.settle()
            after = driver.capture_state()
            selector.record_outcome(action, state, after)
            self.assertTrue(after.screenshot_bytes.startswith(b"\x89PNG"), action.summary())
            state, steps = after, steps + 1
        self.assertEqual(steps, 10)

    def test_an_unsupported_action_raises_against_the_real_session(self) -> None:
        with self.assertRaises(DriverExecutionError):
            self.driver.execute_action(Action("swipe", params={"direction": "up"}))
        with self.assertRaises(DriverExecutionError):
            self.driver.execute_action(Action("key", params={"key": "z", "modifiers": ["bucky"]}))

    def test_zz_a_killed_app_is_reported_and_restart_brings_it_back(self) -> None:
        driver = self.driver
        driver._app_proc.kill()  # type: ignore[union-attr]
        driver._app_proc.wait(timeout=10)  # type: ignore[union-attr]
        with self.assertRaises(AppCrashedError):
            driver.capture_state()
        driver.restart_app()
        self.assertGreater(len(driver.capture_state().tree.nodes), 0)


@unittest.skipIf(LINUX_GAP is not None, f"no headless Blitzlens session without {LINUX_GAP}")
class TestLinuxSessionCleanup(unittest.TestCase):
    def test_teardown_stops_every_process_and_removes_the_session(self) -> None:
        lab_tmp_root().mkdir(parents=True, exist_ok=True)
        out = Path(tempfile.mkdtemp(prefix="pstlab-test-cleanup-", dir=lab_tmp_root()))
        self.addCleanup(shutil.rmtree, out, True)
        driver = LinuxDeviceDriver(out_dir=out / "run", settle_ms=100)
        driver.setup()
        driver.prepare()
        procs = list(driver._procs) + [driver._app_proc]
        self.assertEqual(len(procs), 3)  # Xvfb, the session bus, the app
        self.assertTrue(all(p is not None and p.poll() is None for p in procs))
        self.assertTrue(driver.lab_dir.is_dir())
        driver.teardown()
        self.assertTrue(all(p.poll() is not None for p in procs if p is not None))
        self.assertFalse(driver.lab_dir.exists())

    def test_a_prepare_that_fails_leaves_nothing_running(self) -> None:
        lab_tmp_root().mkdir(parents=True, exist_ok=True)
        out = Path(tempfile.mkdtemp(prefix="pstlab-test-failed-", dir=lab_tmp_root()))
        self.addCleanup(shutil.rmtree, out, True)
        driver = LinuxDeviceDriver(out_dir=out / "run", start_timeout_s=3.0)
        driver.setup()
        started: List[subprocess.Popen] = []
        real_spawn = driver._spawn_reporting

        def spawn(cmd: List[str], what: str):
            proc, line = real_spawn(cmd, what)
            started.append(proc)
            return proc, line

        def app_that_exits() -> None:
            driver._app_log = open(driver.artifacts_dir / "app.log", "ab")
            driver._app_proc = subprocess.Popen([sys.executable, "-c", "raise SystemExit(3)"], stdout=driver._app_log)
            started.append(driver._app_proc)

        driver._spawn_reporting = spawn  # type: ignore[method-assign]
        driver._launch_app = app_that_exits  # type: ignore[method-assign]
        with self.assertRaises(AppCrashedError):
            driver.prepare()
        self.assertEqual(len(started), 3)
        self.assertTrue(all(p.poll() is not None for p in started))
        self.assertFalse(driver.lab_dir.exists())


class TestHostileTrees(unittest.TestCase):
    """Trees a broken app or a bad dump can produce must not break discovery or selection."""

    def test_deeply_nested_tree(self) -> None:
        nodes: List[Node] = []
        for i in range(500):
            nodes.append(Node(
                id=f"node_{i}", parent=f"node_{i-1}" if i > 0 else None,
                role="container" if i < 499 else "button", name=f"Level {i}",
                bounds=[10.0, 10.0, 100.0, 50.0], interactive=(i == 499), visible=True,
            ))
        res = inspect_screen(Tree(platform="android", window={"w": 1080, "h": 1920}, nodes=nodes), "android-desktop")
        self.assertIn("node_499", [c.action.target_id for c in res.filter_by_category("element")])
        self.assertIsNotNone(ActionSelector(seed=42).select(res.candidate_interactions))

    def test_cyclic_parent_references(self) -> None:
        a = Node("node_a", "node_b", "button", "A", [10.0, 10.0, 100.0, 50.0], True, True)
        b = Node("node_b", "node_a", "button", "B", [10.0, 70.0, 100.0, 50.0], True, True)
        res = inspect_screen(Tree(platform="linux", window={"w": 1920, "h": 1080}, nodes=[a, b]), "linux-desktop")
        self.assertGreater(len(res.candidate_interactions), 0)
        self.assertIsNotNone(ActionSelector(seed=42).select(res.candidate_interactions))

    def test_non_finite_bounds_are_never_offered_and_never_executed(self) -> None:
        nodes = [
            Node("node_nan", None, "button", "NaN", [float("nan"), float("inf"), 100.0, 50.0], True, True),
            Node("node_inf", None, "button", "Inf", [-float("inf"), 0.0, 100.0, 50.0], True, True),
            Node("node_ok", None, "button", "Valid", [50.0, 50.0, 100.0, 50.0], True, True),
        ]
        res = inspect_screen(Tree(platform="linux", window={"w": 1920, "h": 1080}, nodes=nodes), "linux-desktop")
        offered = {c.action.target_id for c in res.filter_by_category("element")}
        self.assertEqual(offered, {"node_ok"})
        for bad in (float("nan"), float("inf"), -float("inf"), None, "x"):
            with self.assertRaises(DriverExecutionError):
                _parse_coord(bad, "x")
        self.assertEqual(_parse_coord("12.5", "x"), 12.5)
        self.assertFalse(math.isnan(_parse_coord(0, "x")))

    def test_actions_with_awkward_labels_survive_a_json_round_trip(self) -> None:
        labels = ["👍❤️🔥 Click Me!", "مرحبا بالعالم", "Text with \x00 null and \n newline \t and '\" quotes", "<script>alert(1)</script>"]
        for i, label in enumerate(labels):
            action = Action(action_type="tap", target_id=f"btn_{i}", params={"x": 1.5, "y": 2}, label=label)
            self.assertEqual(Action.from_dict(json.loads(json.dumps(action.to_dict()))), action)

    def test_an_android_working_folder_cannot_climb_out_of_the_lab_root(self) -> None:
        for bad in ("/sdcard/PSTLab/../DCIM", "/sdcard/DCIM/Camera", "/data/local/tmp"):
            with self.subTest(path=bad):
                with self.assertRaises(ValueError):
                    AndroidDeviceDriver(serial="X1", app_id="com.phototok.lab", working_dir=bad)


if __name__ == "__main__":
    unittest.main()
