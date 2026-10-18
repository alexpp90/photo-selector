"""Linux runner: per-checkpoint file-system snapshots and the await step (LAB-1, LAB-2).

The fake session below replays what the real 2026-10-17 run showed on the ThinkPad Xvfb runner:
the Move lands within milliseconds, a queued Trash lands seconds later (LIN-4), and the hub's
file-system steps run in between the app's checkpoints, in script order.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lab.linux import hub_step  # noqa: E402
from lab.linux.runner import build_journey_commands, run_linux_journey  # noqa: E402

# J-LIN-01 as it ran on 2026-10-17 (fixture burst-jpeg), without the fixed waits.
J_LIN_01 = {
    "id": "J-LIN-01",
    "product": "linux",
    "fixture": "burst-jpeg",
    "configs": ["fhd"],
    "steps": [
        {"checkpoint": "opened"},
        {"keys": ["3"]},
        {"checkpoint": "three-up"},
        {"keys": ["Right", "Right"]},
        {"checkpoint": "moved"},
        {"keys": ["m"]},
        {"checkpoint": "after-keep"},
        {"keys": ["Delete"]},
        {"checkpoint": "after-trash"},
        {"keys": ["ctrl+z"]},
        {"checkpoint": "after-undo"},
    ],
    "fs_expect": {
        "after-keep": {"present": ["Selection/DSC_0003.JPG"], "absent": ["DSC_0003.JPG"]},
        "after-trash": {
            "present": ["Selection/DSC_0003.JPG"],
            "absent": ["DSC_0003.JPG", "DSC_0004.JPG"],
            "trashed": ["*DSC_0004.JPG"],
        },
        "after-undo": {"present": ["Selection/DSC_0003.JPG", "DSC_0004.JPG"], "absent": ["DSC_0003.JPG"]},
    },
}


class FakeSession:
    """Stands in for `session.sh`: runs the step script, with the app's file operations as keys.

    `m` moves the photo at index 2 (DSC_0003) into Selection, `Delete` trashes the next one
    (DSC_0004), `ctrl+z` restores it. `await`/`fs` lines go to `hub_step.main` exactly as the
    shell script invokes it.
    """

    def __init__(self, drop_fs_steps: bool = False) -> None:
        self.drop_fs_steps = drop_fs_steps
        self.lines: list[str] = []

    def __call__(self, cmd: list[str], env: dict[str, str]) -> subprocess.CompletedProcess[str]:
        fixture = Path(cmd[cmd.index("--fixture") + 1])
        lab_dir = Path(cmd[cmd.index("--lab-dir") + 1])
        trash = lab_dir / "xdg-data" / "Trash" / "files"
        script = Path(cmd[cmd.index("--script") + 1])
        with mock.patch.dict(os.environ, {"PST_LAB_HUB_CTX": env["PST_LAB_HUB_CTX"]}):
            for line in script.read_text(encoding="utf-8").splitlines():
                if not line or line.startswith("#"):
                    continue
                self.lines.append(line)
                verb, _, arg = line.partition(" ")
                if verb in ("await", "fs") and not self.drop_fs_steps:
                    hub_step.main([verb, arg])
                elif verb == "key" and arg == "m":
                    (fixture / "Selection").mkdir(exist_ok=True)
                    (fixture / "DSC_0003.JPG").rename(fixture / "Selection" / "DSC_0003.JPG")
                elif verb == "key" and arg == "Delete":
                    trash.mkdir(parents=True, exist_ok=True)
                    (fixture / "DSC_0004.JPG").rename(trash / "DSC_0004.JPG")
                elif verb == "key" and arg == "ctrl+z":
                    (trash / "DSC_0004.JPG").rename(fixture / "DSC_0004.JPG")
        return subprocess.CompletedProcess(cmd, 0, stdout="", stderr="")


def _paths(fs_json: Path) -> tuple[list[str], list[str]]:
    data = json.loads(fs_json.read_text(encoding="utf-8"))
    return [e["path"] for e in data["files"]], [e["path"] for e in data["trash"]]


class TestLinuxCheckpoints(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-linux-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.run_dir = self.tmp / "run"
        # The fake lands every file operation at once; don't sit out the real settle period.
        self.addCleanup(setattr, hub_step, "DEFAULT_SETTLE_MS", hub_step.DEFAULT_SETTLE_MS)
        hub_step.DEFAULT_SETTLE_MS = 0

    def _run(self, session: FakeSession) -> tuple[str, list, Path]:
        status, findings = run_linux_journey(
            J_LIN_01, "fhd", {"size": "1920x1080", "scale": 1}, self.run_dir,
            fixture_base_dir=self.tmp / "fixtures", exec_fn=session,
        )
        return status, findings, self.run_dir / "linux" / "fhd" / "J-LIN-01"

    def test_every_checkpoint_is_bracketed_by_await_and_its_own_snapshot(self) -> None:
        cmds = build_journey_commands(J_LIN_01, self.tmp)
        i = cmds.index("checkpoint after-keep")
        self.assertEqual(cmds[i - 1], "await 04_after-keep")
        self.assertEqual(cmds[i + 1], "fs 04_after-keep")
        self.assertEqual(cmds[0:3], ["await 01_opened", "checkpoint opened", "fs 01_opened"])

    def test_each_checkpoint_carries_the_file_system_of_its_own_moment(self) -> None:
        # 2026-10-17: 01_opened.fs.json already showed Selection/DSC_0003.JPG, because one
        # end-of-run snapshot was copied into every checkpoint; after-trash could never pass.
        status, findings, j_dir = self._run(FakeSession())
        opened, _ = _paths(j_dir / "01_opened.fs.json")
        self.assertIn("DSC_0003.JPG", opened)
        self.assertNotIn("Selection/DSC_0003.JPG", opened)
        files, trash = _paths(j_dir / "05_after-trash.fs.json")
        self.assertNotIn("DSC_0004.JPG", files)
        self.assertEqual(trash, ["DSC_0004.JPG"])
        files, trash = _paths(j_dir / "06_after-undo.fs.json")
        self.assertIn("DSC_0004.JPG", files)
        self.assertEqual(trash, [])
        self.assertEqual([f for f in findings if f.source == "fs-oracle"], [])
        self.assertEqual(status, "pass")

    def test_a_missing_snapshot_is_reported_not_filled_from_the_end_state(self) -> None:
        _, findings, j_dir = self._run(FakeSession(drop_fs_steps=True))
        self.assertFalse((j_dir / "01_opened.fs.json").exists())
        rules = sorted({f.rule for f in findings if f.source == "fs-oracle"})
        self.assertEqual(rules, ["fs-snapshot-missing"])


class FakeClock:
    def __init__(self) -> None:
        self.t = 0.0

    def __call__(self) -> float:
        return self.t

    def sleep(self, s: float) -> None:
        self.t += s


class TestAwait(unittest.TestCase):
    CTX = {"fs_expect": J_LIN_01["fs_expect"], "timeout_ms": 10_000, "settle_ms": 1_000}
    BEFORE = {"files": [{"path": "DSC_0003.JPG", "size": 1}, {"path": "DSC_0004.JPG", "size": 1}], "trash": []}
    KEPT = {"files": [{"path": "DSC_0004.JPG", "size": 1}, {"path": "Selection/DSC_0003.JPG", "size": 1}], "trash": []}

    def _await(self, timeline: list[tuple[float, dict]], checkpoint: str) -> dict:
        """Replay `timeline` ((from-second, snapshot) pairs) through `await_expectation`."""
        clock = FakeClock()

        def snap(_ctx: dict) -> dict:
            return [s for t, s in timeline if t <= clock.t][-1]

        return hub_step.await_expectation(self.CTX, checkpoint, clock=clock, sleep=clock.sleep, snap=snap)

    def test_waits_for_a_late_file_operation_and_reports_when_it_landed(self) -> None:
        # LIN-4 as measured: the Move's result reached the folder seconds after the key.
        out = self._await([(0.0, self.BEFORE), (3.0, self.KEPT)], "after-keep")
        self.assertTrue(out["satisfied"])
        self.assertAlmostEqual(out["landed_ms"], 3000, delta=150)
        self.assertGreaterEqual(out["waited_ms"], out["landed_ms"] + 1000)

    def test_an_operation_that_never_lands_spends_the_budget_and_stays_unsatisfied(self) -> None:
        out = self._await([(0.0, self.BEFORE)], "after-keep")
        self.assertFalse(out["satisfied"])
        self.assertGreaterEqual(out["waited_ms"], 10_000)

    def test_an_intermediate_state_that_meets_the_expectation_is_not_captured(self) -> None:
        # J-LIN-03: "DSC_0002.JPG present" is already true before the queued Delete runs; the
        # wait must outlast Delete and Undo, which change the folder, before it settles.
        ctx_expect = {"gvfs-after-cull": {"present": ["DSC_0002.JPG"]}}
        a = {"files": [{"path": "DSC_0002.JPG", "size": 1}], "trash": []}
        trashed = {"files": [], "trash": [{"path": "DSC_0002.JPG", "size": 1}]}
        restored = {"files": [{"path": "DSC_0002.JPG", "size": 1}], "trash": [], "n": 2}
        clock = FakeClock()
        timeline = [(0.0, a), (0.5, trashed), (1.2, restored)]

        def snap(_ctx: dict) -> dict:
            return [s for t, s in timeline if t <= clock.t][-1]

        out = hub_step.await_expectation(
            {"fs_expect": ctx_expect, "settle_ms": 1_000}, "gvfs-after-cull",
            clock=clock, sleep=clock.sleep, snap=snap,
        )
        self.assertTrue(out["satisfied"])
        self.assertGreaterEqual(clock.t, 2.2)  # settled only after the restore at 1.2 s

    def test_a_checkpoint_without_expectation_does_not_wait(self) -> None:
        out = self._await([(0.0, self.BEFORE)], "opened")
        self.assertEqual((out["satisfied"], out["waited_ms"]), (True, 0))


if __name__ == "__main__":
    unittest.main()
