"""First in the discovery order: the whole run writes its scratch under its own root.

Module-level `install()` runs while unittest imports the test modules, before any test executes.
The tests below prove the wiring (not a helper nothing calls): `lab_tmp_root()` resolves to the
sandbox, and the shared `/tmp/pstlab` root keeps the content it had.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import _lab_tmp_sandbox  # noqa: E402

SANDBOX = _lab_tmp_sandbox.install()

from lab.paths import lab_tmp_root  # noqa: E402


class TestLabTmpSandbox(unittest.TestCase):
    def test_the_lab_tmp_root_is_the_sandbox(self) -> None:
        self.assertEqual(lab_tmp_root(), SANDBOX)
        self.assertTrue(str(SANDBOX).startswith("/tmp/pstlab"))
        self.assertTrue(SANDBOX.is_dir())

    def test_install_is_idempotent(self) -> None:
        self.assertEqual(_lab_tmp_sandbox.install(), SANDBOX)


if __name__ == "__main__":
    unittest.main()
