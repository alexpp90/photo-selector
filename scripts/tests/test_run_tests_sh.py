"""Behavioural tests of scripts/run_tests.sh, the local CI mirror.

The script is copied into a throw-away repository and run with a fake PATH, so these tests
need no Poetry, no virtualenv and no GUI: they prove which interpreter the Python gates use.

2026-11-03 (SET-CI-1): `poetry run <tool>` in a worktree whose venv was empty created that venv and
ran whichever same-named tool was first on PATH, so a user-global pytest without pytest-cov
became the gate ("unrecognized arguments: --cov") and both flake8 gates skipped silently.
"""

from __future__ import annotations

import os
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "run_tests.sh"

POETRY_STUB = """#!/bin/sh
# Records every call. `env info -p --directory D` prints the venv the test staged for D.
echo "$*" >> "$STUB_LOG"
case "$*" in
  *"env info -p"*)
    dir=""
    while [ $# -gt 0 ]; do
      if [ "$1" = "--directory" ]; then dir="$2"; fi
      shift
    done
    case "$dir" in
      "$STUB_MAIN_ROOT"/*) [ -n "$STUB_MAIN_VENV" ] && echo "$STUB_MAIN_VENV" ;;
      *) [ -n "$STUB_VENV" ] && echo "$STUB_VENV" ;;
    esac
    exit 0
    ;;
esac
echo "poetry stub: unexpected call: $*" >&2
exit 99
"""

# A tool found on PATH instead of in the project's environment: it must never run.
FOREIGN_TOOL = """#!/bin/sh
echo "FOREIGN $(basename "$0") $*" >> "$STUB_MARK"
exit 0
"""

VENV_TOOL = """#!/bin/sh
echo "VENV $(basename "$0") $*" >> "$STUB_MARK"
exit 0
"""

# The probe is `python -c <code> <modules>`: pass or fail on demand, run everything else for real.
VENV_PYTHON = """#!/bin/sh
if [ "$1" = "-c" ]; then
  if [ -n "$STUB_PROBE_MISSING" ]; then echo "$STUB_PROBE_MISSING"; exit 1; fi
  exit 0
fi
echo "VENV python $*" >> "$STUB_MARK"
exit 0
"""

# xvfb-run without an X server: drop `-a -s <geometry>` and run the command.
XVFB_RUN = """#!/bin/sh
while [ $# -gt 0 ]; do
  case "$1" in
    -a) shift ;;
    -s) shift 2 ;;
    *) break ;;
  esac
done
exec "$@"
"""


def write_exe(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


GIT_ENV = {
    # Auto-maintenance would keep writing into .git/ and race the cleanup (2026-10-22).
    "GIT_CONFIG_COUNT": "3",
    "GIT_CONFIG_KEY_0": "gc.auto",
    "GIT_CONFIG_VALUE_0": "0",
    "GIT_CONFIG_KEY_1": "maintenance.auto",
    "GIT_CONFIG_VALUE_1": "false",
    "GIT_CONFIG_KEY_2": "commit.gpgsign",
    "GIT_CONFIG_VALUE_2": "false",
    "GIT_AUTHOR_NAME": "t",
    "GIT_AUTHOR_EMAIL": "t@example.invalid",
    "GIT_COMMITTER_NAME": "t",
    "GIT_COMMITTER_EMAIL": "t@example.invalid",
}


class _MirrorHarness(unittest.TestCase):
    """The throw-away repository, fake PATH and stubs; holds no tests."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="pst-run-tests-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.fakebin = self.tmp / "fakebin"
        self.log = self.tmp / "poetry.log"
        self.mark = self.tmp / "marker.log"
        write_exe(self.fakebin / "poetry", POETRY_STUB)
        for tool in ("pytest", "flake8"):
            write_exe(self.fakebin / tool, FOREIGN_TOOL)
        write_exe(self.fakebin / "xvfb-run", XVFB_RUN)

    def make_root(self, name: str = "repo") -> Path:
        root = self.tmp / name
        (root / "scripts").mkdir(parents=True)
        shutil.copy(SCRIPT, root / "scripts" / "run_tests.sh")
        for rel in ("products/desktop", "products/desktop/src", "products/desktop/tests"):
            (root / rel).mkdir(parents=True, exist_ok=True)
        (root / "products" / "desktop" / "poetry.lock").write_text("", encoding="utf-8")
        return root

    def make_venv(self, name: str, *, with_tools: bool) -> Path:
        venv = self.tmp / name
        write_exe(venv / "bin" / "python", VENV_PYTHON)
        if with_tools:
            for tool in ("pytest", "flake8"):
                write_exe(venv / "bin" / tool, VENV_TOOL)
        return venv

    def run_script(self, root: Path, *args: str, venv: Path | None = None, main_root: Path | None = None,
                   main_venv: Path | None = None, extra_env: dict[str, str] | None = None) -> subprocess.CompletedProcess:
        env = dict(os.environ)
        env.update(GIT_ENV)
        env.update({
            "PATH": f"{self.fakebin}{os.pathsep}{os.environ['PATH']}",
            "STUB_LOG": str(self.log),
            "STUB_MARK": str(self.mark),
            "STUB_VENV": str(venv) if venv else "",
            "STUB_MAIN_ROOT": str(main_root) if main_root else "/nonexistent",
            "STUB_MAIN_VENV": str(main_venv) if main_venv else "",
            "PST_NO_RECORD": "1",
            "PST_NO_HOST_REEXEC": "1",
            "NO_COLOR": "1",
        })
        env.pop("PST_VENV_BIN", None)
        env.update(extra_env or {})
        return subprocess.run(
            ["bash", str(root / "scripts" / "run_tests.sh"), *args],
            cwd=root, env=env, capture_output=True, text=True, timeout=120,
        )

    def marker(self) -> str:
        return self.mark.read_text(encoding="utf-8") if self.mark.exists() else ""

    def poetry_calls(self) -> list[str]:
        return self.log.read_text(encoding="utf-8").splitlines() if self.log.exists() else []


class RunTestsPythonEnvironmentTest(_MirrorHarness):
    # ── the failure is loud, names the remedy, and never touches a foreign tool ─────────────

    def test_an_empty_venv_fails_with_the_install_command_and_runs_no_foreign_pytest(self):
        root = self.make_root()
        empty = self.make_venv("venv-empty", with_tools=False)
        result = self.run_script(root, "--python", venv=empty, extra_env={"STUB_PROBE_MISSING": "pytest_cov matplotlib"})
        out = result.stdout + result.stderr
        self.assertEqual(result.returncode, 1, out)
        self.assertIn("poetry --directory products/desktop install --no-root", out)
        self.assertIn("pytest_cov matplotlib", out)
        self.assertEqual(self.marker(), "", "a tool outside the project's venv ran as a gate:\n" + self.marker())

    def test_no_venv_at_all_fails_with_the_install_command(self):
        root = self.make_root()
        result = self.run_script(root, "--python")
        out = result.stdout + result.stderr
        self.assertEqual(result.returncode, 1, out)
        self.assertIn("no virtualenv found", out)
        self.assertIn("poetry --directory products/desktop install --no-root", out)
        self.assertEqual(self.marker(), "", self.marker())

    def test_the_mirror_never_runs_poetry_run_or_creates_a_venv(self):
        # `poetry run` creates the venv it cannot find (virtualenvs.create = true): one empty
        # venv per worktree. Only the read-only `env info -p` may be called.
        root = self.make_root()
        self.run_script(root, "--python")
        calls = self.poetry_calls()
        self.assertTrue(calls, "the stub poetry was never consulted")
        for call in calls:
            self.assertIn("env info -p", call)

    def test_a_missing_probe_result_is_not_mistaken_for_a_pass(self):
        # A python that cannot even run the probe (broken venv symlink) is a failure too.
        root = self.make_root()
        broken = self.tmp / "venv-broken"
        write_exe(broken / "bin" / "python", "#!/bin/sh\nexit 127\n")
        result = self.run_script(root, "--python", venv=broken)
        out = result.stdout + result.stderr
        self.assertEqual(result.returncode, 1, out)
        self.assertIn("not a working interpreter", out)
        self.assertEqual(self.marker(), "")

    # ── the provisioned venv is the one that runs the gates ────────────────────────────────

    def test_a_provisioned_venv_runs_every_gate_with_its_own_tools(self):
        root = self.make_root()
        venv = self.make_venv("venv-ok", with_tools=True)
        result = self.run_script(root, "--python", venv=venv)
        out = result.stdout + result.stderr
        self.assertIn("Python environment provisioned (products/desktop)", out)
        marker = self.marker()
        self.assertNotIn("FOREIGN", marker, marker)
        self.assertIn("VENV flake8 src/ tests/", marker)
        self.assertIn("VENV pytest --tb=short -q -o faulthandler_timeout=120 --cov=photo_selector_toolbox", marker)
        self.assertIn("--cov-fail-under=60", marker)

    def test_pst_venv_bin_points_the_gates_at_an_environment_without_poetry(self):
        root = self.make_root()
        venv = self.make_venv("venv-env", with_tools=True)
        result = self.run_script(root, "--python", extra_env={"PST_VENV_BIN": str(venv / "bin")})
        out = result.stdout + result.stderr
        self.assertIn("Python environment provisioned (products/desktop)", out)
        self.assertNotIn("FOREIGN", self.marker())
        self.assertIn("VENV pytest", self.marker())

    def test_a_worktree_with_an_empty_venv_uses_the_main_checkouts_provisioned_one(self):
        main = self.make_root("main")
        wt_parent = self.tmp / "wt"
        subprocess.run(["git", "init", "-q", "-b", "main", str(main)], check=True, env={**os.environ, **GIT_ENV})
        subprocess.run(["git", "-C", str(main), "add", "-A"], check=True, env={**os.environ, **GIT_ENV})
        subprocess.run(["git", "-C", str(main), "commit", "-q", "-m", "init"], check=True, env={**os.environ, **GIT_ENV})
        subprocess.run(["git", "-C", str(main), "worktree", "add", "-q", "-b", "side", str(wt_parent)],
                       check=True, env={**os.environ, **GIT_ENV})
        empty = self.make_venv("venv-wt-empty", with_tools=False)
        good = self.make_venv("venv-main", with_tools=True)
        # The empty worktree venv fails the probe; the main checkout's passes it.
        write_exe(empty / "bin" / "python", "#!/bin/sh\nif [ \"$1\" = \"-c\" ]; then echo pytest_cov; exit 1; fi\nexit 0\n")
        result = self.run_script(wt_parent, "--python", venv=empty, main_root=main, main_venv=good)
        out = result.stdout + result.stderr
        self.assertIn("Python environment provisioned (products/desktop)", out)
        self.assertIn(str(good / "bin"), out)
        self.assertNotIn("FOREIGN", self.marker())


class RunTestsDiskSpaceTest(_MirrorHarness):
    """Under PST_MIN_SPACE_GB the disk-filling sections do not start, and a failed gate says the
    failure may be disk. Gate output is never piped, so every gate keeps its terminal."""

    def make_android_root(self) -> Path:
        root = self.make_root()
        write_exe(root / "products" / "android" / "gradlew", '#!/bin/sh\necho "GRADLEW $*" >> "$STUB_MARK"\nexit 0\n')
        return root

    def failing_venv(self) -> Path:
        venv = self.make_venv("venv-failing", with_tools=True)
        write_exe(venv / "bin" / "pytest", "#!/bin/sh\nexit 1\n")
        return venv

    def test_low_disk_skips_the_android_gates_with_one_named_failure(self):
        root = self.make_android_root()
        result = self.run_script(root, "--android", extra_env={"PST_MIN_SPACE_GB": "100000"})
        out = result.stdout + result.stderr
        self.assertEqual(result.returncode, 1, out)
        self.assertIn("free disk space for the Android gates", out)
        self.assertIn("< 100000 GB (PST_MIN_SPACE_GB)", out)
        self.assertNotIn("GRADLEW", self.marker(), "Gradle ran below the disk floor")

    def test_enough_disk_runs_the_android_gates(self):
        root = self.make_android_root()
        result = self.run_script(root, "--android", extra_env={"PST_MIN_SPACE_GB": "0"})
        out = result.stdout + result.stderr
        self.assertNotIn("free disk space for the Android gates", out)
        self.assertIn("GRADLEW", self.marker(), out)

    def test_a_failed_gate_below_the_floor_names_disk_as_a_possible_cause(self):
        result = self.run_script(self.make_root(), "--python", venv=self.failing_venv(),
                                 extra_env={"PST_MIN_SPACE_GB": "100000"})
        self.assertIn("this failure may be disk (No space left on device), not code", result.stdout + result.stderr)

    def test_a_failed_gate_with_enough_disk_does_not_blame_the_disk(self):
        result = self.run_script(self.make_root(), "--python", venv=self.failing_venv(),
                                 extra_env={"PST_MIN_SPACE_GB": "0"})
        out = result.stdout + result.stderr
        self.assertIn("✘", out)
        self.assertNotIn("may be disk", out)


if __name__ == "__main__":
    unittest.main()
