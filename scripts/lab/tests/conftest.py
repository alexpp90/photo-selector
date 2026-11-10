"""pytest entry: give the run its own lab scratch root (see `_lab_tmp_sandbox`)."""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import _lab_tmp_sandbox  # noqa: E402

_lab_tmp_sandbox.install()
