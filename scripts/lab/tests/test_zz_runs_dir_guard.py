"""No lab test may write into the real runs directory (`private/lab-runs`).

On 2026-10-24 about 218 folders of mock-device and sub-second explore runs landed in the
maintainer's real run history, among them a 900 s mock run with 259k findings: tests and mock
drivers that called `new_run_dir()` without an out directory. The explore orchestrator's mock
driver has used a temp directory since 2026-10-26; this guard keeps it that way for every test.

The file name sorts last, so `unittest discover` runs it after every other test; its module-level
snapshot is taken during discovery, before any test has run.
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lab.paths import default_runs_dir, new_run_dir, runs_dir  # noqa: E402


def snapshot(directory: Path) -> set[str] | None:
    """Names inside `directory`, or None when it does not exist."""
    return {p.name for p in directory.iterdir()} if directory.is_dir() else None


def changes(before: set[str] | None, directory: Path) -> list[str]:
    """What appeared in `directory` since `before` was taken."""
    after = snapshot(directory)
    if after is None:
        return []
    if before is None:
        return sorted(after) + ["<directory created>"]
    return sorted(after - before)


REAL_RUNS_DIR = default_runs_dir()
BEFORE = snapshot(REAL_RUNS_DIR)


class TestRealRunsDirIsUntouched(unittest.TestCase):
    def test_no_test_wrote_into_the_real_runs_directory(self) -> None:
        self.assertEqual(
            changes(BEFORE, REAL_RUNS_DIR), [],
            f"a test created runs under {REAL_RUNS_DIR}: give it its own temp dir (out_dir=...)",
        )

    def test_the_check_sees_a_new_run(self) -> None:
        # Red-capable: the same comparison on a fake repository whose runs directory a "test" fills.
        with tempfile.TemporaryDirectory() as tmp:
            fake_root = Path(tmp)
            (fake_root / "private").mkdir()
            runs = default_runs_dir(fake_root)
            self.assertEqual(runs, fake_root / "private" / "lab-runs")
            self.assertIsNone(snapshot(runs))  # looking does not create it
            runs_dir(fake_root)
            before = snapshot(runs)
            self.assertEqual(changes(before, runs), [])
            run = new_run_dir("abc1234", root=fake_root, label="explore-phototok")
            self.assertEqual(changes(before, runs), [run.name])

    def test_a_runs_directory_that_appears_is_reported(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            runs = Path(tmp) / "lab-runs"
            runs.mkdir()
            self.assertEqual(changes(None, runs), ["<directory created>"])


if __name__ == "__main__":
    unittest.main()
