"""Unit tests for C-03 layout lint rules R1-R8."""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lab.android.tree_android import parse_uiautomator_xml  # noqa: E402
from lab.layout_lint import lint, load_rules  # noqa: E402
from lab.model import Tree  # noqa: E402

DATA_DIR = Path(__file__).resolve().parent / "data"


class TestLayoutLint(unittest.TestCase):
    def test_tree_ok_produces_zero_findings(self) -> None:
        tree = Tree.from_dict(json.loads((DATA_DIR / "tree_ok.json").read_text(encoding="utf-8")))
        rules = load_rules("android-desktop")
        expect = {
            "required": ["image_region", "selector_sidebar", "position_counter"],
            "frames": ["image_region"],
        }
        findings = lint(tree, rules, expect, config="tab-landscape")
        self.assertEqual(findings, [])

    def test_tree_overlap_triggers_r1_or_r2(self) -> None:
        tree = Tree.from_dict(json.loads((DATA_DIR / "tree_overlap.json").read_text(encoding="utf-8")))
        rules = load_rules("android-desktop")
        findings = lint(tree, rules, config="tab-landscape")
        rule_names = {f.rule for f in findings}
        self.assertTrue(bool(rule_names & {"overlap-interactive", "occludes-frame"}))

    def test_tree_zero_size_triggers_r3(self) -> None:
        tree = Tree.from_dict(json.loads((DATA_DIR / "tree_zero_size.json").read_text(encoding="utf-8")))
        rules = load_rules("android-desktop")
        expect = {"required": ["coach_overlay_dismiss"]}
        findings = lint(tree, rules, expect, config="tab-landscape")
        rule_names = {f.rule for f in findings}
        self.assertIn("missing-or-zero-size", rule_names)

    def test_r4_r5_r6_r7_r8(self) -> None:
        raw_tree = {
            "platform": "android",
            "window": {"w": 1480, "h": 924, "scale": 2.0, "unit": "dp"},
            "nodes": [
                {
                    "id": "off_btn",
                    "parent": None,
                    "role": "button",
                    "name": "Off",
                    "bounds": [1450, 10, 60, 50],
                    "interactive": True,
                    "visible": True,
                    "overlay": False,
                    "text": "",
                    "clipped": False,
                },
                {
                    "id": "small_unnamed_btn",
                    "parent": None,
                    "role": "button",
                    "name": "",
                    "bounds": [10, 10, 30, 30],
                    "interactive": True,
                    "visible": True,
                    "overlay": False,
                    "text": "",
                    "clipped": False,
                },
                {
                    "id": "clip_lbl",
                    "parent": None,
                    "role": "text",
                    "name": "",
                    "bounds": [10, 150, 80, 20],
                    "interactive": False,
                    "visible": True,
                    "overlay": False,
                    "text": "Truncated...",
                    "clipped": True,
                },
                {
                    "id": "image_region",
                    "parent": None,
                    "role": "image",
                    "name": "Image region",
                    "bounds": [400, 100, 300, 200],
                    "interactive": False,
                    "visible": True,
                    "overlay": False,
                    "text": "",
                    "clipped": False,
                },
            ],
        }
        tree = Tree.from_dict(raw_tree)
        rules = load_rules("android-desktop")
        findings = lint(tree, rules, {"frames": ["image_region"]}, config="tab-landscape")
        rule_names = {f.rule for f in findings}
        self.assertIn("off-screen", rule_names)
        self.assertIn("touch-target", rule_names)
        self.assertIn("unnamed-control", rule_names)
        self.assertIn("clipped-text", rule_names)
        self.assertIn("frame-size", rule_names)



def _real(name: str, product: str, dpi: int):
    """Lint a trimmed real dump (tests/data/<name>.xml) with the product's shipped rules."""
    rules = load_rules(product)
    tree = parse_uiautomator_xml(DATA_DIR / f"{name}.xml", density_dpi=dpi, overlay_ids=set(rules["overlay_ids"]))
    return tree, rules


class TestLintOnRealDumps(unittest.TestCase):
    """Fixtures are trimmed from the 2026-10-17 dedicated-device runs (LAB-8, LAB-9, LAB-10)."""

    def test_touch_expanded_star_bounds_do_not_overlap_the_chips_below(self) -> None:
        # Raw bounds: star_1 reaches y 802 px, filter_All starts at 784 px. On screen the 22 dp
        # stars end with their bar at 786 px and the chips start 8 dp lower.
        tree, rules = _real("android_star_bar", "android-desktop", 320)
        rules_found = [f.message for f in lint(tree, rules, config="tab-landscape") if f.rule == "overlap-interactive"]
        self.assertEqual(rules_found, [])

    def test_a_control_labelled_by_its_child_is_named(self) -> None:
        tree, rules = _real("android_star_bar", "android-desktop", 320)
        self.assertNotIn("unnamed-control", {f.rule for f in lint(tree, rules, config="tab-landscape")})
        tree, rules = _real("phototok_top_bar", "phototok", 440)
        unnamed = [f for f in lint(tree, rules, config="phone-portrait") if f.rule == "unnamed-control"]
        self.assertEqual(unnamed, [])  # Help has no test tag; its label child still names it

    def test_an_unlabelled_control_is_still_reported(self) -> None:
        tree, rules = _real("phototok_top_bar", "phototok", 440)
        for n in tree.nodes:
            n.name = ""
        self.assertIn("unnamed-control", {f.rule for f in lint(tree, rules, config="phone-portrait")})

    def test_phototok_first_run_hint_is_an_overlay(self) -> None:
        # The rules named `first_run_hint_card`; the app's tag is `first_run_hint`, so the card's
        # text "occluded" the photo on every phone (TOK-7).
        tree, rules = _real("phototok_top_bar", "phototok", 440)
        found = lint(tree, rules, {"frames": ["viewer.photo"]}, config="phone-portrait")
        self.assertNotIn("occludes-frame", {f.rule for f in found})

    def test_first_run_pill_over_the_maximise_badge_is_reported_and_the_badge_on_its_frame_is_not(self) -> None:
        tree, rules = _real("android_first_run_pill", "android-desktop", 320)
        found = lint(tree, rules, {"frames": ["image_region"]}, config="dex-960x600")
        majors = [f for f in found if f.severity == "major"]
        self.assertEqual([f.rule for f in majors], ["guidance-covers-control"])
        self.assertIn("first_run_hint covers maximise_badge", majors[0].message)

    def test_touch_target_tolerates_density_rounding(self) -> None:
        # phone-large-text (density factor 1.15) reports a 48 dp button as 47.86 dp.
        tree = Tree.from_dict({
            "platform": "android", "window": {"w": 400, "h": 800, "scale": 2.0, "unit": "dp"},
            "nodes": [{"id": "viewer_sources_button", "parent": None, "role": "button", "name": "Sources",
                       "bounds": [10, 700, 72, 47.86], "interactive": True, "visible": True}],
        })
        self.assertEqual(lint(tree, load_rules("phototok"), config="phone-large-text"), [])

    def test_required_accepts_alternatives(self) -> None:
        tree = Tree.from_dict({
            "platform": "android", "window": {"w": 400, "h": 800, "scale": 2.0, "unit": "dp"},
            "nodes": [{"id": "delete_button_compact", "parent": None, "role": "button", "name": "Delete",
                       "bounds": [10, 700, 72, 48], "interactive": True, "visible": True}],
        })
        rules = load_rules("android-desktop")
        either = {"required": [["delete_button_expanded", "delete_button_compact"]]}
        self.assertEqual(lint(tree, rules, either, config="tab-portrait"), [])
        neither = {"required": [["delete_button_expanded", "reject_button"]]}
        self.assertEqual([f.rule for f in lint(tree, rules, neither, config="tab-portrait")], ["missing-or-zero-size"])


if __name__ == "__main__":
    unittest.main()
