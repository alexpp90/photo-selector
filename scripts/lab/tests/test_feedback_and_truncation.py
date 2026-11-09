"""Feedback oracle (R11) and text-truncation lint (R10), on captured trees.

Captures: Frameduel, Pixel Tablet, 2026-11-05 (`frameduel_*.tree.json`): one with the snackbar
"Moved to Selection" up, one without. Jackpix and Lensjet have no capture of a feedback surface
yet: their positive trees are a captured tree plus the surface nodes the product source defines
(`PhoneModeScreen.kt: FeedSnackbarHost`, `CullingHUDView.swift`), marked SYNTHETIC below.
"""

from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from _explore_fakes import default_state, make_state, node as fake_node  # noqa: E402

from lab.explore.actions import Action  # noqa: E402
from lab.explore.expectations import Expectation, ExpectationRecorder  # noqa: E402
from lab.explore.verifier import verify_step  # noqa: E402
from lab.feedback import MISSING, OK, UNCHECKED, WRONG, check_feedback  # noqa: E402
from lab.layout_lint import lint, load_rules, text_truncation  # noqa: E402
from lab.model import Tree  # noqa: E402

DATA = Path(__file__).resolve().parent / "data"


def real(name: str) -> dict:
    return json.loads((DATA / name).read_text(encoding="utf-8"))


def tree_of(data: dict) -> Tree:
    return Tree.from_dict(data)


def with_nodes(data: dict, *extra: dict) -> dict:
    out = copy.deepcopy(data)
    out["nodes"].extend(extra)
    return out


def raw(nid: str, bounds: list[float], *, parent: str | None = None, text: str = "", name: str = "", interactive: bool = False) -> dict:
    return {"id": nid, "parent": parent, "role": "text", "name": name, "bounds": bounds, "interactive": interactive,
            "visible": True, "overlay": False, "text": text, "clipped": None}


def rules_of(tree: Tree, product: str, expect: object, **kw) -> list[str]:
    findings = lint(tree, load_rules(product), {"expect_feedback": expect}, product=product, **kw)
    return [f.rule for f in findings if f.rule in ("missing-feedback", "wrong-feedback", "feedback-unchecked")]


WITH_SNACKBAR = "frameduel_first_run_hint_under_snackbar.tree.json"  # "Moved to Selection" + UNDO
WITHOUT = "frameduel_filmstrip_opened.tree.json"


class FrameduelFeedbackTest(unittest.TestCase):
    """The captured snackbar answers a move; no other capture shows one."""

    def test_the_real_snackbar_names_the_move(self) -> None:
        self.assertEqual(rules_of(tree_of(real(WITH_SNACKBAR)), "android-desktop", "move"), [])
        self.assertEqual(rules_of(tree_of(real(WITH_SNACKBAR)), "android-desktop", True), [])

    def test_the_real_snackbar_is_the_wrong_answer_to_a_copy_or_a_delete(self) -> None:
        for action in ("copy", "delete"):
            self.assertEqual(rules_of(tree_of(real(WITH_SNACKBAR)), "android-desktop", action), ["wrong-feedback"], action)

    def test_a_screen_with_no_snackbar_is_missing_feedback(self) -> None:
        for expect in (True, "move", ["copy", "move"]):
            self.assertEqual(rules_of(tree_of(real(WITHOUT)), "android-desktop", expect), ["missing-feedback"], expect)

    def test_the_undo_button_alone_is_not_feedback(self) -> None:
        data = real(WITH_SNACKBAR)
        data["nodes"] = [n for n in data["nodes"] if n["text"] != "Moved to Selection"]
        res = check_feedback(tree_of(data), load_rules("android-desktop"), True)
        self.assertEqual(res.status, MISSING)  # snackbar_undo ('UNDO') is under the surface but is not the message
        self.assertIn("UNDO", " ".join(n["text"] for n in data["nodes"]))

    def test_a_list_of_actions_is_satisfied_by_any_of_them(self) -> None:
        self.assertEqual(rules_of(tree_of(real(WITH_SNACKBAR)), "android-desktop", ["copy", "move"]), [])

    def test_no_claim_means_no_finding(self) -> None:
        self.assertEqual(rules_of(tree_of(real(WITHOUT)), "android-desktop", None), [])
        self.assertEqual(rules_of(tree_of(real(WITHOUT)), "android-desktop", False), [])

    def test_an_action_without_words_is_unchecked_not_a_pass(self) -> None:
        res = check_feedback(tree_of(real(WITH_SNACKBAR)), load_rules("android-desktop"), "undo")
        self.assertEqual(res.status, UNCHECKED)


class JackpixFeedbackTest(unittest.TestCase):
    """SYNTHETIC: the real feed tree plus the host and snackbar text the Compose source defines."""

    FEED = "jackpix_feed_before_settings.tree.json"

    def snackbar(self, text: str, action: str | None = None) -> Tree:
        data = real(self.FEED)
        w, h = data["window"]["w"], data["window"]["h"]
        extra = [raw("feed_snackbar_host", [16, h - 160, w - 32, 56]), raw("#900", [32, h - 150, 200, 26], parent="feed_snackbar_host", text=text)]
        if action:
            extra.append(raw("#901", [w - 100, h - 150, 60, 26], parent="feed_snackbar_host", text=action, interactive=True))
        return tree_of(with_nodes(data, *extra))

    def test_the_real_feed_without_a_snackbar_is_missing_feedback(self) -> None:
        self.assertEqual(rules_of(tree_of(real(self.FEED)), "phototok", "delete"), ["missing-feedback"])

    def test_delete_feedback_in_either_language(self) -> None:
        for text, undo in (("Photo deleted", "Undo"), ("Foto gelöscht", "Rückgängig"), ("3 deleted", None)):
            self.assertEqual(rules_of(self.snackbar(text, undo), "phototok", "delete"), [], text)

    def test_a_copy_snackbar_does_not_answer_a_delete(self) -> None:
        self.assertEqual(rules_of(self.snackbar("Copied to Selection"), "phototok", "delete"), ["wrong-feedback"])
        self.assertEqual(rules_of(self.snackbar("Kopiert nach Selection"), "phototok", "copy"), [])

    def test_an_empty_host_is_missing_feedback(self) -> None:
        data = with_nodes(real(self.FEED), raw("feed_snackbar_host", [16, 1500, 400, 56]))
        self.assertEqual(rules_of(tree_of(data), "phototok", True), ["missing-feedback"])


class MacosAndLinuxFeedbackTest(unittest.TestCase):
    def test_lensjet_toast_is_seen_but_its_words_are_not(self) -> None:
        # SYNTHETIC: the lab's macOS tree has frames only (`UITestFrameRegistry`).
        window = {"platform": "macos", "window": {"w": 1440, "h": 900, "unit": "pt"}, "nodes": [raw("slot_current", [100, 100, 800, 600])]}
        self.assertEqual(rules_of(tree_of(window), "macos", "move"), ["missing-feedback"])
        shown = with_nodes(window, raw("action_toast", [500, 640, 240, 40]))
        self.assertEqual(rules_of(tree_of(shown), "macos", "move"), [])

    def test_blitzlens_has_no_surface_so_the_claim_is_unchecked(self) -> None:
        window = {"platform": "linux", "window": {"w": 1440, "h": 900, "unit": "px"}, "nodes": [raw("slot_current", [100, 100, 800, 600])]}
        findings = lint(tree_of(window), load_rules("linux"), {"expect_feedback": "delete"}, product="linux")
        self.assertEqual([(f.rule, f.severity) for f in findings], [("feedback-unchecked", "minor")])


class JourneyDeclarationTest(unittest.TestCase):
    def test_the_journey_manifest_key_reaches_the_lint(self) -> None:
        """`layout_expect.<checkpoint>.expect_feedback` is what the runners pass to `lint` as `expect`."""
        manifest = {"layout_expect": {"deleted": {"required": [], "expect_feedback": "delete"}}}
        expect = manifest["layout_expect"]["deleted"]
        findings = lint(tree_of(real(WITHOUT)), load_rules("android-desktop"), expect, product="android-desktop")
        self.assertEqual([f.rule for f in findings if f.rule == "missing-feedback"], ["missing-feedback"])


def journeys() -> dict[str, dict]:
    import yaml
    from lab.paths import repo_root

    return {p.stem: yaml.safe_load(p.read_text(encoding="utf-8")) for p in sorted((repo_root() / "scripts" / "lab" / "journeys").glob("*.yaml"))}


class JourneyClassChecksTest(unittest.TestCase):
    """Every journey that claims feedback, or that a Back press must not close the app on, is well formed."""

    PRODUCT_RULES = {"phototok": "phototok", "android-desktop": "android-desktop", "macos": "macos", "linux": "linux"}

    def test_every_expect_feedback_names_an_action_the_product_has_words_for(self) -> None:
        claims = 0
        for jid, j in journeys().items():
            rules = load_rules(self.PRODUCT_RULES[j["product"]])
            for cp, expect in (j.get("layout_expect") or {}).items():
                if "expect_feedback" not in expect:
                    continue
                claims += 1
                self.assertIn(cp, [s.get("checkpoint") for s in j["steps"]], f"{jid}: {cp} is not a checkpoint")
                want = expect["expect_feedback"]
                for action in ([] if want is True else [want] if isinstance(want, str) else want):
                    self.assertTrue((rules.get("feedback") or {}).get("actions", {}).get(action), f"{jid}/{cp}: no words for {action!r}")
                self.assertTrue((rules.get("feedback") or {}).get("surfaces"), f"{jid}: {j['product']} has no feedback surface")
        self.assertGreaterEqual(claims, 2)

    def test_a_feedback_checkpoint_follows_an_action_step(self) -> None:
        """The checkpoint after the action, with at most a wait between: feedback does not outlive a few seconds."""
        for jid, j in journeys().items():
            steps = j["steps"]
            for i, step in enumerate(steps):
                if "expect_feedback" not in ((j.get("layout_expect") or {}).get(step.get("checkpoint")) or {}):
                    continue
                previous = [s for s in steps[:i] if "wait_ms" not in s]
                self.assertTrue(previous and any(k in previous[-1] for k in ("maestro", "keys")), f"{jid}: {step['checkpoint']}")
                self.assertLessEqual(sum(s.get("wait_ms", 0) for s in steps[:i][-2:]), 1500, jid)

    def test_forbidden_ids_are_lists_of_strings_and_back_journeys_check_the_guide_is_gone(self) -> None:
        for jid, j in journeys().items():
            for cp, expect in (j.get("layout_expect") or {}).items():
                if "forbidden" in expect:
                    self.assertTrue(all(isinstance(x, str) for x in expect["forbidden"]), f"{jid}/{cp}")
        for jid, guide in (("J-TOK-08", "controls_guide"), ("J-AD-07", "selector_coach_overlay")):
            steps = journeys()[jid]["steps"]
            self.assertIn({"keys": ["BACK"]}, steps)
            after = journeys()[jid]["layout_expect"]["guide-closed"]
            self.assertEqual(after["forbidden"], [guide])
            self.assertIn(guide, journeys()[jid]["layout_expect"]["guide-open"]["required"])

    def test_the_new_journeys_use_only_flows_and_configs_that_exist(self) -> None:
        import yaml
        from lab.paths import repo_root

        configs = {}
        for f in ("android.yaml",):
            configs.update(yaml.safe_load((repo_root() / "scripts" / "lab" / "configs" / f).read_text(encoding="utf-8")))
        for jid in ("J-TOK-07", "J-TOK-08", "J-AD-07", "J-AD-08"):
            j = journeys()[jid]
            for cfg in j["configs"]:
                self.assertIn(cfg, configs, jid)
            for step in j["steps"]:
                if "maestro" in step:
                    self.assertTrue((repo_root() / step["maestro"]).is_file(), step["maestro"])

    def test_forbidden_finds_the_guide_that_is_still_open(self) -> None:
        still_open = with_nodes(real(WITHOUT), raw("selector_coach_overlay", [100, 100, 600, 400]))
        expect = {"required": ["image_region"], "forbidden": ["selector_coach_overlay"]}
        found = [f.rule for f in lint(tree_of(still_open), load_rules("android-desktop"), expect, product="android-desktop")]
        self.assertEqual(found.count("forbidden-node-present"), 1)
        gone = lint(tree_of(real(WITHOUT)), load_rules("android-desktop"), expect, product="android-desktop")
        self.assertNotIn("forbidden-node-present", [f.rule for f in gone])


class ExploreFeedbackTest(unittest.TestCase):
    """The explorer records a culling action's feedback and the verifier judges it."""

    def verify(self, product: str, tree_data: dict, kind: str):
        recorder = ExpectationRecorder(product)
        action = Action("tap", target_id="btn", label=kind)
        exp = recorder.record_cull(default_state(), action, "a.jpg", kind)
        after = default_state()
        after.tree = tree_of(tree_data)
        with tempfile.TemporaryDirectory() as tmp:
            return exp, verify_step(
                step_index=1, action=action, expectation=exp, before_state=default_state(), after_state=after,
                app_log=[], product=product, config="default@tab", run_dir=Path(tmp), evidence_dir=Path(tmp) / "e",
                packet_dir=Path(tmp) / "p",
            )

    def test_cull_actions_record_the_feedback_they_expect(self) -> None:
        recorder = ExpectationRecorder("android-desktop")
        got = {k: recorder.record_cull(default_state(), Action("key", params={"key": "x"}), "a.jpg", k).expected_feedback
               for k in ("move", "copy", "delete", "undo")}
        self.assertEqual(got, {"move": "move", "copy": "copy", "delete": "delete", "undo": None})
        self.assertEqual(Expectation.from_dict(Expectation(Action("tap"), "d", expected_feedback="copy").to_dict()).expected_feedback, "copy")

    def test_a_move_with_its_snackbar_raises_no_feedback_finding(self) -> None:
        _, res = self.verify("android-desktop", real(WITH_SNACKBAR), "move")
        self.assertEqual([f.rule for f in res.findings if "feedback" in f.rule], [])

    def test_a_move_without_feedback_is_a_minor_finding(self) -> None:
        _, res = self.verify("android-desktop", real(WITHOUT), "move")
        found = [f for f in res.findings if f.rule == "missing-feedback"]
        self.assertEqual([(f.severity, f.source) for f in found], [("minor", "flow")])

    def test_a_product_with_no_surface_reports_the_claim_as_unchecked(self) -> None:
        window = {"platform": "linux", "window": {"w": 1080, "h": 1920, "unit": "px"}, "nodes": [raw("root", [0, 0, 1080, 1920])]}
        _, res = self.verify("linux", window, "delete")
        self.assertEqual([f.rule for f in res.findings if "feedback" in f.rule], [])
        self.assertTrue(any("feedback after delete" in u for u in res.unchecked), res.unchecked)


class TruncationLintTest(unittest.TestCase):
    def test_real_captures_have_no_truncated_text(self) -> None:
        for name, product in ((WITHOUT, "android-desktop"), (WITH_SNACKBAR, "android-desktop"),
                              ("jackpix_feed_before_settings.tree.json", "phototok"),
                              ("jackpix_settings_sheet.tree.json", "phototok"), ("frameduel_maximised.tree.json", "android-desktop")):
            findings = lint(tree_of(real(name)), load_rules(product), product=product)
            self.assertEqual([f.message for f in findings if f.rule == "truncated-text"], [], name)

    def test_a_real_file_name_cut_with_an_ellipsis_is_found(self) -> None:
        data = real(WITHOUT)
        for n in data["nodes"]:
            if n["text"] == "DSC_0006.JPG":
                n["text"] = "DSC_00…"
        found = [f for f in lint(tree_of(data), load_rules("android-desktop"), product="android-desktop") if f.rule == "truncated-text"]
        self.assertEqual([(f.severity, f.message) for f in found], [("minor", "text 'DSC_00…' in #55 ends in an ellipsis")])

    def test_three_dots_and_a_label_in_the_content_description_count(self) -> None:
        data = with_nodes(real(WITHOUT), raw("lbl", [500, 800, 90, 26], text="Verschieben nach Sele...", name="Verschieben nach Selection"))
        found = [f.message for f in lint(tree_of(data), load_rules("android-desktop"), product="android-desktop") if f.rule == "truncated-text"]
        self.assertEqual(found, ["text 'Verschieben nach Sele...' in lbl ends in an ellipsis"])

    def test_a_word_broken_across_lines_is_found(self) -> None:
        self.assertEqual(text_truncation("Ausgewählte Fotos ver-\nschieben"), "is broken inside a word across lines")
        self.assertIsNone(text_truncation("Well-\nKnown"))  # a compound's hyphen before a capital
        self.assertIsNone(text_truncation("Two\nlines of text"))
        self.assertIsNone(text_truncation("Loading"))
        self.assertIsNone(text_truncation("…"))  # nothing before it: not a cut-off word

    def test_clipped_text_stays_its_own_rule(self) -> None:
        data = with_nodes(real(WITHOUT), raw("c", [500, 800, 90, 26], text="Fits"))
        data["nodes"][-1]["clipped"] = True
        rules = [f.rule for f in lint(tree_of(data), load_rules("android-desktop"), product="android-desktop")]
        self.assertIn("clipped-text", rules)
        self.assertNotIn("truncated-text", rules)

    def test_a_product_may_allow_a_text_or_an_id(self) -> None:
        data = with_nodes(real(WITHOUT), raw("busy", [500, 800, 90, 26], text="Scanning…"), raw("hint", [500, 840, 90, 26], text="Not all of it…"))
        for allow in ({"allow_text": [r"^Scanning…$"], "allow_ids": ["hint"]},):
            rules = dict(load_rules("android-desktop"), text_truncation=allow)
            self.assertEqual([f for f in lint(tree_of(data), rules, product="android-desktop") if f.rule == "truncated-text"], [])
        strict = dict(load_rules("android-desktop"), text_truncation={"allow_text": [r"^Scanning…$"]})
        found = [f.message for f in lint(tree_of(data), strict, product="android-desktop") if f.rule == "truncated-text"]
        self.assertEqual(found, ["text 'Not all of it…' in hint ends in an ellipsis"])


if __name__ == "__main__":
    unittest.main()
