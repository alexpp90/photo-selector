"""The Lensjet explore driver, against a fake app that speaks the `--ui-live` control-folder protocol."""

from __future__ import annotations

import json
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from typing import Any
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from _explore_fakes import png_bytes  # noqa: E402

from lab.explore.actions import Action  # noqa: E402
from lab.explore.drivers import AppCrashedError, DriverError, DriverExecutionError, MacOSDeviceDriver  # noqa: E402
from lab.paths import lab_tmp_root  # noqa: E402

TREE = (Path(__file__).resolve().parent / "data" / "tree_ok.json").read_text(encoding="utf-8")


class FakeApp:
    """Stands in for the app process: answers each `cmd-N.json` in the control folder."""

    def __init__(self, cmd: list[str], log: Path, *, live_flag: bool = True, die_on: str | None = None) -> None:
        self.cmd, self.returncode, self.seen = cmd, None, []
        self.die_on = die_on
        opts = dict(zip(cmd[1::2], cmd[2::2]))
        self.control, self.artifacts = Path(opts.get("--ui-live", "")), Path(opts["--artifacts"])
        self.checkpoints = 0
        if not live_flag:
            self.returncode = 64  # an unknown flag: the real build prints its usage and exits
            log.write_text("usage: PhotoSelectorApp --open <folder> [--ui-scenario ...]\n")
            return
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.serve, daemon=True)
        (self.control / "ready.json").write_text("{}")
        self.thread.start()

    def poll(self) -> int | None:
        return self.returncode

    def terminate(self) -> None:
        self.kill()

    def kill(self) -> None:
        if self.returncode is None:
            self.returncode = -15
            self.stop.set()

    def wait(self, timeout: float = 0) -> int:
        return self.returncode or 0

    def serve(self) -> None:
        while not self.stop.is_set():
            for cmd in sorted(self.control.glob("cmd-*.json")):
                seq = json.loads(cmd.read_text())["seq"]
                done = self.control / f"done-{seq}.json"
                if done.exists():
                    continue
                step = json.loads(cmd.read_text())["step"]
                self.seen.append(step)
                if self.die_on and self.die_on in step:
                    self.returncode = -11
                    return
                if "checkpoint" in step:
                    self.checkpoints += 1
                    stem = f"{self.checkpoints:02d}_{step['checkpoint']}"
                    self.artifacts.mkdir(parents=True, exist_ok=True)
                    (self.artifacts / f"{stem}.png").write_bytes(png_bytes())
                    (self.artifacts / f"{stem}.tree.json").write_text(TREE)
                    (self.artifacts / f"{stem}.state.json").write_text(json.dumps({"current_index": 2, "total": 9}))
                refused = "bogus" in json.dumps(step)
                answer = json.dumps({"ok": False, "error": "unknown control"} if refused else {"ok": True})
                # Like the app's own writer: the file exists empty before it has its content, so a
                # reader that polls for the file meets an empty answer (a flake under load, 2026-11-11).
                done.write_text("")
                time.sleep(0.03)
                done.write_text(answer)
            time.sleep(0.01)


class MacDriverCase(unittest.TestCase):
    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.apps: list[FakeApp] = []
        binary = self.root / "PhotoSelectorApp"
        binary.write_text("")
        self.binary = binary

    def driver(self, **fake: Any) -> MacOSDeviceDriver:
        def spawn(cmd: list[str], log: Path) -> FakeApp:
            app = FakeApp(cmd, log, **fake)
            self.apps.append(app)
            return app

        out = self.root / "out"
        d = MacOSDeviceDriver(out_dir=out, app_binary=self.binary, spawn=spawn, poll_s=0.005,
                              start_timeout_s=15, command_timeout_s=15, fixture_dir=lab_tmp_root() / "mac-test-work")
        self.addCleanup(d.teardown)
        patcher = patch("lab.explore.drivers.macos.generate_fixtures",
                        side_effect=lambda name, out, **kw: (Path(out).mkdir(parents=True, exist_ok=True), (Path(out) / "a.JPG").write_bytes(b"x")))
        patcher.start()
        self.addCleanup(patcher.stop)
        d.setup()
        d.prepare()
        return d


class TestLiveDriver(MacDriverCase):
    def test_the_app_is_launched_once_on_the_staged_fixture_with_the_live_flag(self) -> None:
        d = self.driver()
        self.assertEqual(len(self.apps), 1)
        cmd = self.apps[0].cmd
        self.assertEqual(cmd[1], "--open")
        self.assertIn("--ui-live", cmd)
        self.assertTrue(str(d.working_dir).startswith(str(lab_tmp_root())))

    def test_every_capture_has_nodes_a_position_and_a_checkpoint_name_of_its_own(self) -> None:
        d = self.driver()
        states = [d.capture_state() for _ in range(3)]
        self.assertTrue(all(s.tree.nodes and s.screenshot_bytes for s in states))
        self.assertEqual(states[0].position, (2, 9))
        names = [s["checkpoint"] for s in self.apps[0].seen]
        self.assertEqual(names, ["s1", "s2", "s3"])  # `duplicateCheckpoint` never fires

    def test_keys_keep_their_modifiers_and_other_steps_are_not_replayed(self) -> None:
        d = self.driver()
        d.execute_action(Action("key", params={"key": "Z", "modifiers": ["cmd"]}))
        d.execute_action(Action("key", params={"key": "Cmd+O"}))
        d.execute_action(Action("key", params={"key": "M"}))
        d.execute_action(Action("click", target_id="toolbar_zoom"))
        d.execute_action(Action("back"))
        self.assertEqual(self.apps[0].seen, [
            {"key": "z", "modifiers": ["command"]}, {"key": "o", "modifiers": ["command"]}, {"key": "m"},
            {"click": "toolbar_zoom"}, {"key": "escape"},
        ])

    def test_an_action_the_app_refuses_raises(self) -> None:
        d = self.driver()
        with self.assertRaisesRegex(DriverExecutionError, "unknown control"):
            d.execute_action(Action("click", target_id="bogus"))
        with self.assertRaises(DriverExecutionError):
            d.execute_action(Action("click"))  # no frame named
        with self.assertRaises(DriverExecutionError):
            d.execute_action(Action("swipe", params={"direction": "up"}))

    def test_a_build_without_the_live_flag_says_so(self) -> None:
        with self.assertRaisesRegex(DriverError, "--ui-live"):
            self.driver(live_flag=False)

    def test_the_app_dying_during_a_command_is_a_crash_not_a_timeout(self) -> None:
        d = self.driver(die_on="click")
        with self.assertRaises(AppCrashedError):
            d.execute_action(Action("click", target_id="toolbar_zoom"))

    def test_restart_launches_again_and_numbers_commands_afresh(self) -> None:
        d = self.driver()
        d.capture_state()
        d.restart_app()
        self.assertEqual(len(self.apps), 2)
        d.capture_state()
        self.assertEqual(self.apps[1].seen, [{"checkpoint": "s2"}])  # capture names never repeat across restarts

    def test_the_app_log_is_read_incrementally(self) -> None:
        d = self.driver()
        d.log_path.write_text("one\ntwo\n")
        self.assertEqual(d.read_app_log(), ["one", "two"])
        with open(d.log_path, "a") as fh:
            fh.write("three\n")
        self.assertEqual(d.read_app_log(), ["three"])

    def test_a_missing_build_is_refused_in_setup(self) -> None:
        d = MacOSDeviceDriver(app_binary=self.root / "nope", out_dir=self.root / "o")
        with self.assertRaisesRegex(DriverError, "no Lensjet test-hook build"):
            d.setup()


if __name__ == "__main__":
    unittest.main()
