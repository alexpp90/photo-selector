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

    def test_rewrites_allowed_tolerates_one_photo_written_in_place_or_a_sidecar(self) -> None:
        # Frameduel writes a JPEG's rating into its XMP (2026-11-06, Pixel Tablet: "Tap 1 star").
        def files(*entries):
            return {"files": [{"path": p, "size": s} for p, s in entries], "trash": []}

        before = files(("DSC_0009.JPG", 100), ("DSC_0010.JPG", 100), ("DSC_0011.ARW", 300))
        rate = {"unchanged": True, "rewrites_allowed": 1}
        rated = files(("DSC_0009.JPG", 140), ("DSC_0010.JPG", 100), ("DSC_0011.ARW", 300))
        self.assertEqual(evaluate_fs_oracle(rated, rate, initial_fs=before), [])
        sidecar = files(("DSC_0009.JPG", 100), ("DSC_0010.JPG", 100), ("DSC_0011.ARW", 300), ("DSC_0011.ARW.xmp", 9))
        self.assertEqual(evaluate_fs_oracle(sidecar, rate, initial_fs=before), [])
        two = files(("DSC_0009.JPG", 140), ("DSC_0010.JPG", 120), ("DSC_0011.ARW", 300))
        self.assertEqual(len(evaluate_fs_oracle(two, rate, initial_fs=before)), 1)
        gone = files(("DSC_0010.JPG", 100), ("DSC_0011.ARW", 300))
        self.assertEqual(len(evaluate_fs_oracle(gone, rate, initial_fs=before)), 1)  # a rating deletes nothing
        other = files(("DSC_0009.JPG", 100), ("DSC_0010.JPG", 100), ("DSC_0011.ARW", 300), ("notes.txt", 5))
        self.assertEqual(len(evaluate_fs_oracle(other, rate, initial_fs=before)), 1)
        self.assertEqual(len(evaluate_fs_oracle(rated, {"unchanged": True}, initial_fs=before)), 1)

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
