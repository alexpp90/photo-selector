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


def install() -> Path:
    global _SANDBOX
    if _SANDBOX is None:
        _SANDBOX = Path(tempfile.mkdtemp(prefix="pstlab-test-", dir="/tmp"))
        os.environ["PST_LAB_TMP"] = str(_SANDBOX)
        atexit.register(shutil.rmtree, _SANDBOX, ignore_errors=True)
    return _SANDBOX


def sandbox() -> Path | None:
    return _SANDBOX
