"""The Mac LaunchAgent script and its hub trigger (LAB-20, LAB-21, 2026-10-19 lab report)."""

from __future__ import annotations

import datetime as dt
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lab.macos.remote import remote_run_id, trigger_remote_mac_run  # noqa: E402

AGENT_RUN = Path(__file__).resolve().parent.parent / "macos" / "agent_run.sh"

# Stand-ins for the build and the lab inside the clone: they record how they were called.
BUILD_STUB = """#!/usr/bin/env bash
echo "$*" >> "$HOME/build-calls"
mkdir -p .build/hooks/app/PhotoSelector.app
"""
LAB_STUB = """import sys, pathlib
pathlib.Path(sys.argv[sys.argv.index("--out") + 1], "lab-ran").write_text(" ".join(sys.argv[1:]))
"""


@unittest.skipUnless(shutil.which("git") and shutil.which("bash"), "needs git and bash")
class TestAgentRunAfterScriptChange(unittest.TestCase):
    """LAB-20: `git checkout` replaced agent_run.sh while bash was still reading the old file."""

    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pst-agent-run-"))
        self.home = self.tmp / "home"
        self.clone = self.home / "pst" / "photo-selector"
        (self.clone / "scripts" / "lab" / "macos").mkdir(parents=True)
        (self.clone / "products" / "macos-desktop" / "scripts").mkdir(parents=True)
        build = self.clone / "products" / "macos-desktop" / "scripts" / "build_app.sh"
        build.write_text(BUILD_STUB)
        build.chmod(0o755)
        (self.clone / "scripts" / "lab" / "lab.py").write_text(LAB_STUB)
        self.env = {
            **os.environ,
            "HOME": str(self.home),
            "PST_LAB_TMP": str(self.tmp / "scratch"),  # never the hub's shared /tmp/pstlab
            "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_CONFIG_NOSYSTEM": "1",
        }
        self.env.pop("PST_LAB_CHECKED_OUT", None)
        self._git("init", "-q")

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _git(self, *args: str) -> str:
        res = subprocess.run(
            ["git", "-c", "user.name=lab", "-c", "user.email=lab@example.invalid", *args],
            cwd=self.clone, env=self.env, capture_output=True, text=True, check=True,
        )
        return res.stdout.strip()

    def _commit(self, script: str, message: str) -> str:
        (self.clone / "scripts" / "lab" / "macos" / "agent_run.sh").write_text(script)
        self._git("add", "-A")
        self._git("commit", "-q", "-m", message)
        return self._git("rev-parse", "HEAD")

    def _run_agent(self, commit: str) -> str:
        trigger = self.home / "pst" / "trigger"
        trigger.mkdir(parents=True, exist_ok=True)
        (trigger / "run.json").write_text(json.dumps({"run_id": "r1", "commit": commit}))
        # As com.pstlab.runner.plist does: bash runs the script file inside the clone.
        subprocess.run(
            ["bash", str(self.clone / "scripts" / "lab" / "macos" / "agent_run.sh")],
            env=self.env, capture_output=True, text=True, check=True, timeout=60,
        )
        self.assertTrue((self.home / "pst" / "out" / "r1" / "done").is_file())
        self.assertTrue((self.home / "pst" / "out" / "r1" / "lab-ran").is_file())
        return (self.home / "build-calls").read_text()

    def test_the_first_run_after_a_script_change_runs_the_new_script(self) -> None:
        current = AGENT_RUN.read_text()
        # 2026-10-19: the clone held a script that built without --test-hooks; the trigger named
        # the commit that added it. Every journey then reported "no UI test hooks".
        old = current.replace("release --test-hooks", "release")
        self.assertNotEqual(old, current)
        self._commit(old, "old script")
        new_sha = self._commit(current, "script builds with --test-hooks")
        self._git("checkout", "-q", "HEAD~1")

        self.assertEqual(self._run_agent(new_sha).splitlines(), ["release --test-hooks"])
        self.assertEqual(self._git("rev-parse", "HEAD"), new_sha)

    def test_without_the_re_exec_the_old_script_goes_on(self) -> None:
        # The defect itself, kept as a control: drop the re-exec and the stale build comes back.
        current = AGENT_RUN.read_text()
        start = current.index('if [[ "${PST_LAB_CHECKED_OUT:-}"')
        end = current.index("fi\n", start) + 3
        pre_fix = current[:start] + 'git fetch --all --prune\ngit checkout "$COMMIT"\n' + current[end:]
        self._commit(pre_fix.replace("release --test-hooks", "release"), "old script")
        new_sha = self._commit(pre_fix, "script builds with --test-hooks")
        self._git("checkout", "-q", "HEAD~1")

        self.assertEqual(self._run_agent(new_sha).splitlines(), ["release"])


class FakeMac:
    """The pst-lab account as the hub sees it over ssh: a set of files under ~/pst/out."""

    def __init__(self, existing: set[str]) -> None:
        self.files = set(existing)
        self.trigger: dict | None = None

    def __call__(self, cmd: list[str]) -> subprocess.CompletedProcess[str]:
        remote = cmd[-1]
        out = ""
        if "launchctl print" in remote:
            out = "ok\n"
        elif "> ~/pst/trigger/run.json" in remote:
            self.trigger = json.loads(remote.split("'")[3])  # printf '%s\\n' '<payload>'
        elif remote.startswith("test -e ~/pst/out/"):
            name = remote.split()[2].removeprefix("~/pst/out/")
            out = "exists\n" if any(f.startswith(f"{name}/") for f in self.files) else ""
        elif remote.startswith("test -f ~/pst/out/"):
            path = remote.split()[2].removeprefix("~/pst/out/")
            out = "done\n" if path in self.files else ""
        return subprocess.CompletedProcess(cmd, 0, out, "")


class TestRemoteRunId(unittest.TestCase):
    """LAB-21: the hub re-used the `--out` name and read the earlier run's `done` at once."""

    NOW = dt.datetime(2026, 10, 19, 17, 23, 34, tzinfo=dt.timezone.utc)

    def test_a_reused_out_name_does_not_return_the_earlier_results(self) -> None:
        # 2026-10-19: `real-2026-10-19-mac2` had finished; a second run with the same --out got
        # "done" from the first poll and synced the old results.
        mac = FakeMac({"real-2026-10-19-mac2/done", "real-2026-10-19-mac2/macos/w1024/J-MAC-01/result.json"})
        local = Path(tempfile.mkdtemp(prefix="pst-mac-run-"))
        self.addCleanup(shutil.rmtree, local, True)
        ok, reason = trigger_remote_mac_run(
            host="mac", run_id="real-2026-10-19-mac2", commit="40793e0", journeys=["J-MAC-01"],
            configs=["w1024"], local_run_dir=local, timeout_s=0.05, poll_interval_s=0.01,
            run_cmd=mac, now=self.NOW,
        )
        self.assertFalse(ok)
        self.assertIn("Timed out", str(reason))
        self.assertEqual(mac.trigger["run_id"], "real-2026-10-19-mac2-20261019T172334")

    def test_an_existing_remote_out_dir_is_refused(self) -> None:
        mac = FakeMac({"r-20261019T172334/done"})
        ok, reason = trigger_remote_mac_run(
            host="mac", run_id="r", commit="40793e0", journeys=[], configs=[],
            local_run_dir=Path("/nonexistent"), timeout_s=0.05, poll_interval_s=0.01,
            run_cmd=mac, now=self.NOW,
        )
        self.assertFalse(ok)
        self.assertIn("already exists", str(reason))
        self.assertIsNone(mac.trigger)

    def test_run_ids_differ_per_trigger(self) -> None:
        later = self.NOW + dt.timedelta(seconds=1)
        self.assertNotEqual(remote_run_id("r", self.NOW), remote_run_id("r", later))


if __name__ == "__main__":
    unittest.main()
