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

    def test_plain_temp_folders_land_in_the_sandbox(self) -> None:
        import tempfile

        with tempfile.TemporaryDirectory(prefix="pstlab-probe-") as tmp:
            self.assertTrue(str(Path(tmp).resolve()).startswith(str(SANDBOX.resolve())))

    def test_the_judge_clis_are_tripwires_under_test(self) -> None:
        import shutil
        import subprocess

        for cli in _lab_tmp_sandbox.JUDGE_CLIS:
            self.assertTrue((shutil.which(cli) or "").startswith(str(SANDBOX)), cli)
        run = subprocess.run(["claude", "-p", "x"], capture_output=True, text=True, check=False)
        self.assertEqual(run.returncode, 97)
        self.assertEqual(_lab_tmp_sandbox.judge_calls(), ["claude"])
        (SANDBOX / "judge-bin" / "CALLED").unlink()  # this probe is the only call allowed

    def test_install_is_idempotent(self) -> None:
        self.assertEqual(_lab_tmp_sandbox.install(), SANDBOX)


if __name__ == "__main__":
    unittest.main()
