#!/usr/bin/env python3
"""Entry point for the device lab CLI."""

from __future__ import annotations

import importlib.util
import os
import shutil
import subprocess
import sys
from pathlib import Path

# Commands that put generated photos on real devices. They need the real Pillow: the built-in
# fallback writes PNG data inside JPEG markers, which no camera app, picker or decoder reads.
_NEEDS_REAL_DEPS = {"run", "fixtures", "checkpoint"}
_PROBE = "import PIL.Image, yaml"


def _has_real_deps(python: str) -> bool:
    try:
        return subprocess.run([python, "-c", _PROBE], capture_output=True, check=False).returncode == 0
    except OSError:
        return False


def _reexec_with_real_deps() -> None:
    """Re-run under an interpreter that has Pillow and PyYAML when this one does not.

    The first `python3` on PATH may be a Homebrew or venv interpreter without them, while
    Debian's `/usr/bin/python3` has `python3-pil` and `python3-yaml`. `PST_LAB_PYTHON` wins.
    """
    if os.environ.get("PST_LAB_REEXEC") or not set(sys.argv[1:2]) & _NEEDS_REAL_DEPS:
        return
    if importlib.util.find_spec("PIL") and importlib.util.find_spec("yaml"):
        return
    candidates = [os.environ.get("PST_LAB_PYTHON", ""), "/usr/bin/python3", shutil.which("python3") or ""]
    for python in dict.fromkeys(c for c in candidates if c and Path(c).is_file()):
        if Path(python).resolve() != Path(sys.executable).resolve() and _has_real_deps(python):
            os.environ["PST_LAB_REEXEC"] = "1"
            os.execv(python, [python, str(Path(__file__).resolve()), *sys.argv[1:]])
    sys.exit(
        f"error: '{sys.argv[1]}' needs Pillow and PyYAML; {sys.executable} has neither and no other "
        "python3 does (Debian: sudo apt install python3-pil python3-yaml)"
    )


_reexec_with_real_deps()
sys.path.insert(0, str(Path(__file__).resolve().parent))

from lab.cli import main  # noqa: E402


if __name__ == "__main__":
    raise SystemExit(main())
