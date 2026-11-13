"""Harness traps of 2026-11-12: a mocked Gio must not loop, a lab screenshot failure leaves a log line,
and a private Xvfb stands in for a missing display (``tests/conftest.py``)."""

import logging
import time
from pathlib import Path
from unittest.mock import MagicMock

from photo_selector_linux.core import file_ops
from photo_selector_linux.ui import lab_hooks


def test_a_mocked_gio_trash_listing_returns_instead_of_looping(monkeypatch):
    monkeypatch.setattr(file_ops, "Gio", MagicMock())
    manager = file_ops.FileCullingManager()
    started = time.monotonic()
    try:
        names = manager._gio_trash_names(Path("/tmp/never-trashed.jpg"))
    except TypeError:  # os.fsdecode of a mock: the point is that the call ended
        names = []
    assert isinstance(names, list)
    assert time.monotonic() - started < 30


def test_a_lab_screenshot_that_fails_says_so_in_the_log(monkeypatch, caplog, tmp_path):
    class BrokenWindow:
        def get_width(self):
            raise RuntimeError("no surface")

    monkeypatch.setattr(lab_hooks, "HAS_GI", True)
    recorder = lab_hooks.LabCheckpointRecorder.__new__(lab_hooks.LabCheckpointRecorder)
    with caplog.at_level(logging.WARNING, logger=lab_hooks.logger.name):
        recorder._write_screenshot(BrokenWindow(), tmp_path / "x.png")
    assert any("Lab screenshot x.png failed" in r.getMessage() for r in caplog.records)
    assert not (tmp_path / "x.png").exists()


def test_a_missing_display_is_replaced_by_a_private_xvfb_or_says_why():
    import conftest

    assert callable(conftest.start_private_xvfb)
    assert conftest.start_private_xvfb(which=lambda _name: None) is None  # no Xvfb: no display, stubs run


def test_a_display_nobody_serves_falls_back_to_the_stubs_instead_of_crashing():
    """BL-B-10 (decision #35): conftest asked only whether DISPLAY is set; a dead one segfaulted."""
    import os
    import subprocess
    import sys

    code = (
        "import sys; sys.path.insert(0, 'tests'); import conftest; "
        "from unittest.mock import MagicMock; "
        "print(isinstance(sys.modules['gi.repository'], MagicMock))"
    )
    env = {**os.environ, "DISPLAY": ":9876", "GDK_BACKEND": "x11", "PST_NO_XVFB": "1"}
    env.pop("WAYLAND_DISPLAY", None)
    result = subprocess.run(
        [sys.executable, "-c", code], capture_output=True, text=True, env=env, timeout=60,
        cwd=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    )
    assert result.returncode == 0, result.stderr[-400:]
    assert result.stdout.strip().splitlines()[-1] == "True"
