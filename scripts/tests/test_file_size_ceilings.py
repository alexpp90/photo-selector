"""Size ceilings: the hot files may not grow (improvement plan step 0.5, decision D-3).

Each product has one file that is both its largest and its most-changed, and each grew by
180 to 270 lines a week. The ceilings below are explicit numbers, taken as `wc -l` of the file on
origin/main after the 2026-11-11 backlog sweep's product PRs (2026-11-12). A product PR that adds a line to one of these files fails this
test; a split PR lowers the number in the same commit (the ceiling only ratchets down). New logic
belongs in a new file.

A deliberate, reviewed exception raises the number in the PR that needs it, with the reason in
the PR description; it is not a reason to edit the test around a red result.
"""

from __future__ import annotations

import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
P = "products"

CEILINGS = {
    f"{P}/android/phototok/src/com/phototok/viewmodel/PhoneModeViewModel.kt": 891,
    f"{P}/android/android-desktop/src/com/photoselectortoolbox/viewmodel/SelectorViewModel.kt": 2385,
    f"{P}/android/android-desktop/src/com/photoselectortoolbox/ui/inspect/InspectScreen.kt": 2008,
    f"{P}/macos-desktop/src/PhotoSelectorKit/ViewModels/CullingWorkspaceViewModel.swift": 1429,
    f"{P}/linux-desktop/src/photo_selector_linux/core/file_ops.py": 1271,
}


def line_count(path: Path) -> int:
    """Newline count, as `wc -l` reports it."""
    return path.read_bytes().count(b"\n")


class FileSizeCeilingTest(unittest.TestCase):
    def test_no_hot_file_grows_past_its_ceiling(self):
        over = []
        for rel, ceiling in sorted(CEILINGS.items()):
            path = REPO / rel
            self.assertTrue(path.is_file(), f"{rel} moved or was split: update CEILINGS (lower it, or drop the entry)")
            n = line_count(path)
            if n > ceiling:
                over.append(f"{rel}: {n} lines, ceiling {ceiling} (+{n - ceiling}); put new logic in a new file")
        self.assertEqual(over, [], "hot files grew:\n" + "\n".join(over))

    def test_a_ceiling_is_not_left_above_the_real_size(self):
        """A split lowers the ceiling in the same commit, so the headroom cannot be reused."""
        slack = []
        for rel, ceiling in sorted(CEILINGS.items()):
            n = line_count(REPO / rel)
            if n < ceiling - 50:
                slack.append(f"{rel}: {n} lines, ceiling {ceiling}: lower the ceiling to {n}")
        self.assertEqual(slack, [], "\n".join(slack))


if __name__ == "__main__":
    unittest.main()
