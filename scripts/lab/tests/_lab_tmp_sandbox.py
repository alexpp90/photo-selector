"""Point the lab's scratch root at a directory this test process owns, removed when it exits.

`lab.paths.lab_tmp_root()` is `/tmp/pstlab`, which also holds the hub and device lock files of a
real run. Tests that create fixtures, mock runs or sessions under it left 196 folders there by
2026-11-10. `install()` sets `PST_LAB_TMP` to a fresh `/tmp/pstlab-test-<random>` (the lab's
session guards want the `/tmp/pstlab` prefix) and registers its removal. It is idempotent, and
imported first by `conftest.py` (pytest) and `test_00_lab_tmp_sandbox.py` (`unittest discover`,
which is what `run_tests.sh` runs).
"""

from __future__ import annotations

import atexit
import os
import shutil
import tempfile
from pathlib import Path

_SANDBOX: Path | None = None
JUDGE_CLIS = ("claude", "gemini")


def install() -> Path:
    global _SANDBOX
    if _SANDBOX is None:
        _SANDBOX = Path(tempfile.mkdtemp(prefix="pstlab-test-", dir="/tmp"))
        os.environ["PST_LAB_TMP"] = str(_SANDBOX)
        # Every `tempfile.mkdtemp()` / `TemporaryDirectory()` without `dir=` lands in the sandbox too
        # (2026-11-11: `pstlab-rawtxt-*`, `pstlab-packet-*` ... were left in /tmp).
        scratch = _SANDBOX / "scratch"
        scratch.mkdir()
        tempfile.tempdir = str(scratch)
        # No test may start a real judge CLI: `claude` / `gemini` on PATH are tripwires that
        # record the call and fail (a test that wants a judge passes a fake provider).
        bin_dir = _SANDBOX / "judge-bin"
        bin_dir.mkdir()
        for cli in JUDGE_CLIS:
            stub = bin_dir / cli
            stub.write_text(f'#!/bin/sh\necho "{cli}" >> "{bin_dir}/CALLED"\necho "a test reached the real {cli} CLI" >&2\nexit 97\n')
            stub.chmod(0o755)
        os.environ["PATH"] = f"{bin_dir}{os.pathsep}{os.environ.get('PATH', '')}"
        atexit.register(shutil.rmtree, _SANDBOX, ignore_errors=True)
    return _SANDBOX


def sandbox() -> Path | None:
    return _SANDBOX


def judge_calls() -> list[str]:
    """The judge CLIs a test started (always empty in a clean run)."""
    marker = _SANDBOX / "judge-bin" / "CALLED" if _SANDBOX else None
    return marker.read_text().split() if marker and marker.is_file() else []
