"""Journey bindings (LAB-5): fs expectations name the photo the app showed, not a key count."""

from __future__ import annotations

import fnmatch
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import yaml  # noqa: E402

from lab.android.tree_android import parse_uiautomator_xml  # noqa: E402
from lab.bindings import bind_from_tree, resolve_expect  # noqa: E402
from lab.fs_oracle import evaluate_fs_oracle  # noqa: E402
from lab.model import Tree  # noqa: E402

DATA = Path(__file__).resolve().parent / "data"
JOURNEYS = Path(__file__).resolve().parent.parent / "journeys"


def moved_tree(name: str) -> Tree:
    """J-AD-02 `moved` on the Pixel Tablet, 2026-10-17: the frame's label is on a child node."""
    node = {"role": "container", "text": "", "interactive": False, "visible": True}
    return Tree.from_dict({
        "platform": "android", "window": {"w": 1480, "h": 924, "unit": "dp", "scale": 2.0},
        "nodes": [
            {**node, "id": "selector.frame.current", "parent": "image_region", "name": "", "bounds": [460, 44, 648, 432]},
            {**node, "id": "column_current", "parent": "selector.frame.current", "name": "", "bounds": [460, 44, 648, 432], "interactive": True},
            {**node, "id": "#51", "parent": "column_current", "name": name, "bounds": [460, 44, 648, 432]},
            {**node, "id": "position_counter", "parent": "control_block", "name": "Image 2 of 6", "text": "2 / 6", "bounds": [1126, 439, 336, 26]},
        ],
    })


# The real outcome: the first DPAD_RIGHT was swallowed, so M moved DSC_0002, correctly.
AFTER_KEEP = {"files": [{"path": "DSC_0003.JPG", "size": 1}, {"path": "Selection/JPEG/DSC_0002.JPG", "size": 1}], "trash": []}
EXPECT = {"present": ["Selection/*{kept}"], "absent": ["{kept}"]}


class TestBindings(unittest.TestCase):
    def test_the_shown_file_name_is_bound_from_a_descendant(self) -> None:
        self.assertEqual(bind_from_tree(moved_tree("DSC_0002.JPG"), {"kept": "column_current"}), {"kept": "DSC_0002.JPG"})

    def test_a_correct_move_of_the_shown_photo_passes(self) -> None:
        # Before: fs_expect named DSC_0003 and reported a blocker plus a major for this Move.
        expect, missing = resolve_expect(EXPECT, {"kept": "DSC_0002.JPG"})
        self.assertEqual(missing, [])
        self.assertEqual(evaluate_fs_oracle(AFTER_KEEP, expect), [])

    def test_moving_a_different_photo_than_the_one_shown_still_fails(self) -> None:
        expect, _ = resolve_expect(EXPECT, {"kept": "DSC_0003.JPG"})
        self.assertEqual({f.rule for f in evaluate_fs_oracle(AFTER_KEEP, expect)}, {"fs-present", "fs-absent"})

    def test_an_unbound_name_is_reported_and_its_patterns_dropped(self) -> None:
        expect, missing = resolve_expect(EXPECT, {"kept": None})
        self.assertEqual((missing, expect), (["kept"], {"present": [], "absent": []}))

    def test_bound_names_match_literally(self) -> None:
        expect, _ = resolve_expect({"absent": ["{kept}"]}, {"kept": "IMG[1].JPG"})
        self.assertTrue(fnmatch.fnmatchcase("IMG[1].JPG", expect["absent"][0]))
        self.assertFalse(fnmatch.fnmatchcase("IMG1.JPG", expect["absent"][0]))



def fs(*paths: str) -> dict:
    return {"files": [{"path": p, "size": 1} for p in paths], "trash": []}


# J-TOK-03 on the Moto G77, 2026-10-19 (`real-2026-10-19-tok`): the feed resumed at DSC_0011, so
# swipe right copied DSC_0011, swipe left deleted DSC_0010, Revert restored it and the second
# delete removed it again. The journey said DSC_0011 and reported a blocker at `selection`.
SMOKE = [f"DSC_{i:04d}.JPG" for i in range(1, 13)]
TOK_KEPT = fs(*SMOKE, "Selection/.photoselector-selection", "Selection/DSC_0011.JPG")
TOK_SELECTION = fs(*[p for p in SMOKE if p != "DSC_0010.JPG"], "Selection/.photoselector-selection", "Selection/DSC_0011.JPG")


class TestJTok03Bindings(unittest.TestCase):
    """LAB-22: J-TOK-03 names the photos the feed showed, not the ones a fresh start would show."""

    def setUp(self) -> None:
        self.journey = yaml.safe_load((JOURNEYS / "J-TOK-03.yaml").read_text(encoding="utf-8"))

    def resolved(self, checkpoint: str, values: dict) -> dict:
        expect, missing = resolve_expect(self.journey["fs_expect"][checkpoint], values)
        self.assertEqual(missing, [])
        return expect

    def test_the_photo_after_the_kept_one_is_bound_from_the_feed(self) -> None:
        tree = parse_uiautomator_xml(DATA / "phototok_feed_kept.xml", density_dpi=390)
        self.assertEqual(bind_from_tree(tree, self.journey["bind"]["kept"]), {"deleted": "DSC_0010.JPG"})

    def test_the_resumed_run_passes_every_checkpoint(self) -> None:
        values = {"kept": "DSC_0011.JPG", "deleted": "DSC_0010.JPG"}
        self.assertEqual(evaluate_fs_oracle(TOK_KEPT, self.resolved("kept", values)), [])
        self.assertEqual(evaluate_fs_oracle(fs(*SMOKE, "Selection/.photoselector-selection",
                                               "Selection/DSC_0011.JPG"), self.resolved("reverted", values)), [])
        self.assertEqual(evaluate_fs_oracle(TOK_SELECTION, self.resolved("selection", values)), [])

    def test_a_delete_that_did_not_happen_is_still_a_finding(self) -> None:
        values = {"kept": "DSC_0011.JPG", "deleted": "DSC_0010.JPG"}
        findings = evaluate_fs_oracle(TOK_KEPT, self.resolved("selection", values))
        self.assertEqual([f.rule for f in findings], ["fs-absent"])

    def test_no_phototok_journey_names_a_fixture_photo(self) -> None:
        # A fixed name assumes where the shared, resumed folder starts (LAB-22).
        for path in sorted(JOURNEYS.glob("J-TOK-*.yaml")):
            text = yaml.safe_dump(yaml.safe_load(path.read_text(encoding="utf-8")).get("fs_expect") or {})
            with self.subTest(journey=path.name):
                self.assertNotRegex(text, r"DSC_\d{4}")


if __name__ == "__main__":
    unittest.main()
