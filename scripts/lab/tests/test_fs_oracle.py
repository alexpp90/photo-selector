"""Unit tests for C-04 filesystem oracle."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lab.fs_oracle import evaluate_fs_oracle  # noqa: E402


class TestFsOracle(unittest.TestCase):
    def setUp(self) -> None:
        self.initial = {
            "root": "PSTLab/burst-sharpness",
            "files": [
                {"path": "DSC_0001.JPG", "size": 100},
                {"path": "DSC_0002.JPG", "size": 100},
                {"path": "DSC_0003.JPG", "size": 100},
            ],
            "trash": [],
        }

    def test_unchanged_passes_and_fails(self) -> None:
        self.assertEqual(evaluate_fs_oracle(self.initial, "unchanged", initial_fs=self.initial), [])

        modified = {
            "root": "PSTLab/burst-sharpness",
            "files": [
                {"path": "DSC_0001.JPG", "size": 100},
                {"path": "DSC_0002.JPG", "size": 100},
            ],
            "trash": [],
        }
        findings = evaluate_fs_oracle(modified, {"unchanged": True}, initial_fs=self.initial)
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0].rule, "fs-unchanged")

    def test_removals_allowed_tolerates_only_removals_of_that_many_photos(self) -> None:
        def files(*names):
            return {"files": [{"path": n, "size": 100} for n in names], "trash": []}

        before = files("DSC_0001.JPG", "RAW/DSC_0001.ARW", "DSC_0002.JPG", "DSC_0003.JPG")
        one = {"unchanged": True, "removals_allowed": 1}
        # One photo's JPEG and RAW go: one pending delete landed.
        self.assertEqual(evaluate_fs_oracle(files("DSC_0002.JPG", "DSC_0003.JPG"), one, initial_fs=before), [])
        # Two photos go with one delete pending: a blocker.
        two_gone = evaluate_fs_oracle(files("DSC_0003.JPG"), one, initial_fs=before)
        self.assertEqual([f.rule for f in two_gone], ["fs-unchanged"])
        # A file appearing is never a pending delete.
        added = files("DSC_0002.JPG", "DSC_0003.JPG", "DSC_0004.JPG")
        self.assertEqual(len(evaluate_fs_oracle(added, one, initial_fs=before)), 1)
        # A size change is a removal plus an addition: still a blocker.
        resized = {"files": before["files"][:3] + [{"path": "DSC_0003.JPG", "size": 7}], "trash": []}
        self.assertEqual(len(evaluate_fs_oracle(resized, one, initial_fs=before)), 1)

    def test_present_absent_and_trashed(self) -> None:
        after_move = {
            "root": "PSTLab/burst-sharpness",
            "files": [
                {"path": "DSC_0001.JPG", "size": 100},
                {"path": "Selection/DSC_0003.JPG", "size": 100},
            ],
            "trash": [{"path": "DSC_0002.JPG", "size": 100}],
        }
        expect = {
            "present": ["Selection/DSC_0003.JPG"],
            "absent": ["DSC_0003.JPG", "DSC_0002.JPG"],
            "trashed": ["*DSC_0002.JPG"],
        }
        self.assertEqual(evaluate_fs_oracle(after_move, expect, initial_fs=self.initial), [])

        bad_after = {
            "root": "PSTLab/burst-sharpness",
            "files": [{"path": "DSC_0003.JPG", "size": 100}],
            "trash": [],
        }
        findings = evaluate_fs_oracle(bad_after, expect, initial_fs=self.initial)
        rules = {f.rule for f in findings}
        self.assertIn("fs-present", rules)
        self.assertIn("fs-absent", rules)
        self.assertIn("fs-trashed", rules)


if __name__ == "__main__":
    unittest.main()
