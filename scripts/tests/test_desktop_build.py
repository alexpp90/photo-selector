"""products/desktop/scripts/build.py: the legacy Desktop PyInstaller build driver.

Stdlib only (run_tests.sh runs this directory with bare python3); build.py imports
generate_notices from its own directory, so that directory goes on sys.path first.
"""

from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import unittest

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


if __name__ == "__main__":
    unittest.main()
