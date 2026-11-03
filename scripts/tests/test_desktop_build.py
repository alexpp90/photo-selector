"""products/desktop/scripts/build.py: the legacy Desktop PyInstaller build driver.

Stdlib only (run_tests.sh runs this directory with bare python3); build.py imports
generate_notices from its own directory, so that directory goes on sys.path first.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
from pathlib import Path
import subprocess
import sys
import unittest
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[2] / "products" / "desktop" / "scripts"
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("desktop_build", SCRIPTS / "build.py")
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


class IconsTest(unittest.TestCase):
    def test_no_dead_icon_generator(self):
        """generate_icons.py wrote Android mipmaps to a path that no longer exists and the build
        ignored its failure (LD-Q-2)."""
        self.assertFalse((SCRIPTS / "generate_icons.py").exists())
        self.assertFalse(hasattr(build, "generate_icons_if_possible"))

    def test_icons_the_build_bundles_are_committed(self):
        for name in ("logo.png", "logo.ico", "logo.icns"):
            self.assertTrue((build.ASSETS_DIR / name).is_file(), name)


class CommandLineTest(unittest.TestCase):
    """`build.py --help` once started a real build: download ExifTool, then PyInstaller (LD-R-9)."""

    def test_help_prints_usage_and_builds_nothing(self):
        result = subprocess.run([sys.executable, str(SCRIPTS / "build.py"), "--help"],
                                capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("usage:", result.stdout)
        self.assertIn("--dry-run", result.stdout)
        self.assertNotIn("Downloading", result.stdout)
        self.assertNotIn("Running:", result.stdout)

    def test_unknown_option_is_refused_before_any_work(self):
        with mock.patch.object(build, "setup_exiftool") as setup, \
                mock.patch.object(build.subprocess, "run") as run, \
                contextlib.redirect_stderr(io.StringIO()), \
                self.assertRaises(SystemExit) as raised:
            build.main(["--bogus"])
        self.assertEqual(raised.exception.code, 2)
        setup.assert_not_called()
        run.assert_not_called()

    def test_dry_run_prints_the_commands_and_touches_nothing(self):
        out = io.StringIO()
        with mock.patch.object(build, "setup_exiftool") as setup, \
                mock.patch.object(build.subprocess, "run") as run, \
                mock.patch.object(build, "generate_notices") as notices, \
                contextlib.redirect_stdout(out):
            build.main(["--dry-run"])
        setup.assert_not_called()
        run.assert_not_called()
        notices.assert_not_called()
        text = out.getvalue()
        self.assertEqual(text.count("Running: poetry run pyinstaller"), 2)
        self.assertIn("--splash", text)  # the GUI command carries the splash screen

    def test_a_plain_run_still_builds_both_targets(self):
        """CI calls `python scripts/build.py` with no arguments."""
        with mock.patch.object(build, "setup_exiftool") as setup, \
                mock.patch.object(build.subprocess, "run") as run, \
                mock.patch.object(build, "generate_notices"), \
                contextlib.redirect_stdout(io.StringIO()):
            build.main([])
        setup.assert_called_once()
        pyinstaller = [c for c in run.call_args_list if "pyinstaller" in c.args[0]]
        self.assertEqual(len(pyinstaller), 2)


if __name__ == "__main__":
    unittest.main()
