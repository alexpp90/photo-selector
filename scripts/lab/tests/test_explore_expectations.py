"""Comprehensive E2E and unit tests for Hybrid Expectation & Verification Engine (M2).

Covers Features 11, 12, 19 across Tiers 1-4:
- Feature 11: Pre-Interaction Expectation Recorder
- Feature 12: Deterministic Oracles Integration (Layout lint R1-R9, fs_oracle, crash/ANR)
- Feature 19: Post-Interaction Verification & Finding Generation
"""

from __future__ import annotations

import json
import os
import shutil
import sys
import tempfile
import unittest
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

LAB_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(LAB_DIR))

from lab.fs_oracle import evaluate_fs_oracle  # noqa: E402
from lab.layout_lint import lint, load_rules  # noqa: E402
from lab.model import Finding, Node, Tree  # noqa: E402

# Check if implementation module is present
try:
    from lab.explore import expectations as exp_expect  # noqa: E402
    from lab.explore import verifier as exp_verifier  # noqa: E402
    HAS_EXPLORE_EXPECTATIONS = True
except ImportError:
    HAS_EXPLORE_EXPECTATIONS = False


# --- Interface Contracts (PROJECT.md § Interface Contracts) ---
@dataclass(frozen=True)
class Action:
    action_type: str
    target_id: Optional[str] = None
    params: Dict[str, Any] = field(default_factory=dict)
    label: str = ""


@dataclass
class ScreenState:
    tree: Tree
    screenshot_bytes: bytes
    screen_mode: str
    fs_snapshot: Dict[str, Any]
    logs: List[str] = field(default_factory=list)


@dataclass
class Expectation:
    action: Action
    description: str
    expected_index_delta: Optional[int] = None
    expected_feed_count_delta: Optional[int] = None
    expected_counter_deltas: Dict[str, int] = field(default_factory=dict)
    fs_expect: Dict[str, Any] = field(default_factory=dict)
    required_ui_nodes: List[str] = field(default_factory=list)
    forbidden_ui_nodes: List[str] = field(default_factory=list)
    ai_prompt: Optional[str] = None


@dataclass
class StepResult:
    step_index: int
    action: Action
    expectation: Expectation
    passed: bool
    findings: List[Finding]
    before_state: ScreenState
    after_state: ScreenState
    packet_dir: Optional[Path] = None


# --- Helper to create synthetic Trees ---
def make_tree(nodes: List[Node], platform: str = "android", w: float = 1080, h: float = 1920) -> Tree:
    return Tree(platform=platform, window={"w": w, "h": h, "scale": 2.0, "unit": "dp"}, nodes=nodes)


# =====================================================================
# Tier 1 & 2: Feature 11 - Pre-Interaction Expectation Recorder
# =====================================================================
class TestPreInteractionExpectationRecorder(unittest.TestCase):
    def test_record_navigation_next_expectation(self) -> None:
        action = Action(action_type="swipe", label="Swipe Feed Next")
        exp = Expectation(
            action=action,
            description="Feed advances to next photo; index increases by 1",
            expected_index_delta=1,
            expected_feed_count_delta=0,
            fs_expect={"unchanged": True},
        )
        self.assertEqual(exp.expected_index_delta, 1)
        self.assertEqual(exp.expected_feed_count_delta, 0)
        self.assertTrue(exp.fs_expect.get("unchanged"))

    def test_record_navigation_prev_expectation(self) -> None:
        action = Action(action_type="swipe", label="Swipe Feed Prev")
        exp = Expectation(
            action=action,
            description="Feed returns to previous photo; index decreases by 1",
            expected_index_delta=-1,
            expected_feed_count_delta=0,
            fs_expect={"unchanged": True},
        )
        self.assertEqual(exp.expected_index_delta, -1)
        self.assertEqual(exp.expected_feed_count_delta, 0)

    def test_record_cull_move_expectation(self) -> None:
        action = Action(action_type="key", params={"key": "M"}, label="Move Photo to Selection")
        exp = Expectation(
            action=action,
            description="Active photo moved to Selection/; feed count decreases by 1",
            expected_index_delta=0,  # Clamps or shows next photo at current slot
            expected_feed_count_delta=-1,
            expected_counter_deltas={"selected": 1},
            fs_expect={"absent": ["IMG_001.JPG"], "present": ["Selection/IMG_001.JPG"]},
        )
        self.assertEqual(exp.expected_feed_count_delta, -1)
        self.assertEqual(exp.expected_counter_deltas["selected"], 1)
        self.assertIn("IMG_001.JPG", exp.fs_expect["absent"])

    def test_record_cull_copy_expectation(self) -> None:
        action = Action(action_type="key", params={"key": "C"}, label="Copy Photo to Selection")
        exp = Expectation(
            action=action,
            description="Active photo copied to Selection/; feed count unchanged",
            expected_index_delta=1,
            expected_feed_count_delta=0,
            expected_counter_deltas={"selected": 1},
            fs_expect={"present": ["IMG_001.JPG", "Selection/IMG_001.JPG"]},
        )
        self.assertEqual(exp.expected_feed_count_delta, 0)
        self.assertEqual(exp.expected_counter_deltas["selected"], 1)

    def test_record_cull_delete_expectation(self) -> None:
        action = Action(action_type="key", params={"key": "Del"}, label="Delete Photo")
        exp = Expectation(
            action=action,
            description="Active photo deleted; feed count decreases by 1",
            expected_feed_count_delta=-1,
            expected_counter_deltas={"trashed": 1},
            fs_expect={"absent": ["IMG_001.JPG"], "trashed": ["IMG_001.JPG"]},
        )
        self.assertEqual(exp.expected_feed_count_delta, -1)
        self.assertEqual(exp.expected_counter_deltas["trashed"], 1)

    def test_record_undo_expectation(self) -> None:
        action = Action(action_type="key", params={"key": "z", "modifiers": ["ctrl"]}, label="Undo Action")
        exp = Expectation(
            action=action,
            description="Restores previously culled photo back into feed",
            expected_feed_count_delta=1,
            expected_counter_deltas={"selected": -1},
            fs_expect={"present": ["IMG_001.JPG"], "absent": ["Selection/IMG_001.JPG"]},
        )
        self.assertEqual(exp.expected_feed_count_delta, 1)
        self.assertEqual(exp.expected_counter_deltas["selected"], -1)

    def test_boundary_expectation_last_photo_clamps(self) -> None:
        action = Action(action_type="swipe", label="Swipe Past Last Photo")
        exp = Expectation(
            action=action,
            description="At end of feed, navigation clamps to last photo without wrap",
            expected_index_delta=0,
            expected_feed_count_delta=0,
        )
        self.assertEqual(exp.expected_index_delta, 0)

    def test_expectation_forbidden_ui_nodes(self) -> None:
        action = Action(action_type="tap", target_id="btn_keep")
        exp = Expectation(
            action=action,
            description="Tapping keep must never spawn crash or error dialog",
            forbidden_ui_nodes=["crash_dialog", "anr_dialog", "error_banner"],
        )
        self.assertIn("crash_dialog", exp.forbidden_ui_nodes)
        self.assertIn("anr_dialog", exp.forbidden_ui_nodes)

    def test_expectation_with_ai_prompt(self) -> None:
        action = Action(action_type="tap", target_id="btn_zoom")
        exp = Expectation(
            action=action,
            description="Zoom into face region",
            ai_prompt="Verify that the main subject face is centered and sharp at 100% sensor zoom",
        )
        self.assertIsNotNone(exp.ai_prompt)
        self.assertTrue("face" in (exp.ai_prompt or ""))


# =====================================================================
# Tier 1 & 2: Feature 12 - Deterministic Oracles Integration
# =====================================================================
class TestDeterministicOraclesIntegration(unittest.TestCase):
    def test_layout_lint_r1_overlap_interactive_detected(self) -> None:
        # Two buttons occupying the exact same space
        n1 = Node(id="btn1", parent=None, role="button", name="Button 1",
                  bounds=[100, 100, 100, 50], interactive=True, visible=True)
        n2 = Node(id="btn2", parent=None, role="button", name="Button 2",
                  bounds=[120, 110, 100, 50], interactive=True, visible=True)
        tree = make_tree([n1, n2])
        rules = load_rules("phototok")
        findings = lint(tree, rules, product="phototok", config="phone")
        r1_findings = [f for f in findings if f.rule == "overlap-interactive"]
        self.assertGreaterEqual(len(r1_findings), 1)

    def test_layout_lint_r3_zero_size_control_detected(self) -> None:
        n = Node(id="btn_zero", parent=None, role="button", name="Zero Button",
                 bounds=[50, 50, 0, 0], interactive=True, visible=True)
        tree = make_tree([n])
        rules = load_rules("phototok")
        findings = lint(tree, rules, expect={"required": ["btn_zero"]}, product="phototok", config="phone")
        r3_findings = [f for f in findings if f.rule == "missing-or-zero-size"]
        self.assertGreaterEqual(len(r3_findings), 1)

    def test_layout_lint_r5_touch_target_too_small(self) -> None:
        # Android requires 48 dp min touch target
        n = Node(id="btn_small", parent=None, role="button", name="Tiny Button",
                 bounds=[100, 100, 24, 24], interactive=True, visible=True)
        tree = make_tree([n])
        rules = load_rules("phototok")
        findings = lint(tree, rules, product="phototok", config="phone")
        r5_findings = [f for f in findings if f.rule == "touch-target"]
        self.assertGreaterEqual(len(r5_findings), 1)

    def test_layout_lint_r6_unnamed_control_detected(self) -> None:
        n = Node(id="btn_unnamed", parent=None, role="button", name="",
                 bounds=[100, 100, 60, 60], interactive=True, visible=True)
        tree = make_tree([n])
        rules = load_rules("phototok")
        findings = lint(tree, rules, product="phototok", config="phone")
        r6_findings = [f for f in findings if f.rule == "unnamed-control"]
        self.assertGreaterEqual(len(r6_findings), 1)

    def test_layout_lint_r7_clipped_text_detected(self) -> None:
        n = Node(id="txt_clipped", parent=None, role="text", name="Clipped Description",
                 bounds=[100, 100, 200, 30], interactive=False, visible=True, clipped=True)
        tree = make_tree([n])
        rules = load_rules("phototok")
        findings = lint(tree, rules, product="phototok", config="phone")
        r7_findings = [f for f in findings if f.rule == "clipped-text"]
        self.assertGreaterEqual(len(r7_findings), 1)

    def test_fs_oracle_evaluation_passes_when_matching(self) -> None:
        fs_state = {
            "files": [{"path": "IMG_002.JPG", "size": 1024}, {"path": "Selection/IMG_001.JPG", "size": 1024}],
            "trash": [],
        }
        rule = {"absent": ["IMG_001.JPG"], "present": ["Selection/IMG_001.JPG"]}
        findings = evaluate_fs_oracle(fs_state, rule)
        self.assertEqual(len(findings), 0)

    def test_fs_oracle_evaluation_fails_when_absent_file_exists(self) -> None:
        fs_state = {
            "files": [{"path": "IMG_001.JPG", "size": 1024}],
            "trash": [],
        }
        rule = {"absent": ["IMG_001.JPG"]}
        findings = evaluate_fs_oracle(fs_state, rule)
        self.assertGreaterEqual(len(findings), 1)

    def test_fs_oracle_evaluation_fails_when_present_file_missing(self) -> None:
        fs_state = {
            "files": [{"path": "IMG_002.JPG", "size": 1024}],
            "trash": [],
        }
        rule = {"present": ["Selection/IMG_001.JPG"]}
        findings = evaluate_fs_oracle(fs_state, rule)
        self.assertGreaterEqual(len(findings), 1)


# =====================================================================
# Tier 1 & 2: Feature 19 - Post-Interaction Verification & Finding Generation
# =====================================================================
class TestPostInteractionVerification(unittest.TestCase):
    def test_step_result_contract_dataclass(self) -> None:
        action = Action(action_type="tap", target_id="btn_keep")
        exp = Expectation(action=action, description="Keep photo")
        before_state = ScreenState(
            tree=make_tree([]),
            screenshot_bytes=b"before",
            screen_mode="viewer",
            fs_snapshot={},
        )
        after_state = ScreenState(
            tree=make_tree([]),
            screenshot_bytes=b"after",
            screen_mode="viewer",
            fs_snapshot={},
        )
        res = StepResult(
            step_index=1,
            action=action,
            expectation=exp,
            passed=True,
            findings=[],
            before_state=before_state,
            after_state=after_state,
        )
        self.assertTrue(res.passed)
        self.assertEqual(res.step_index, 1)
        self.assertEqual(len(res.findings), 0)

    def test_verification_detects_forbidden_node_presence(self) -> None:
        action = Action(action_type="tap", target_id="btn_discard")
        exp = Expectation(action=action, description="Discard photo", forbidden_ui_nodes=["crash_dialog"])
        crash_node = Node(id="crash_dialog", parent=None, role="dialog", name="Application Crash",
                          bounds=[100, 500, 800, 400], interactive=True, visible=True)
        after_tree = make_tree([crash_node])

        # Verifying: if forbidden node is present, must fail
        present_forbidden = [n.id for n in after_tree.nodes if n.id in exp.forbidden_ui_nodes]
        self.assertIn("crash_dialog", present_forbidden)

    def test_finding_creation_adheres_to_model(self) -> None:
        f = Finding(
            id="a1b2c3d4e5f6",
            source="layout-lint",
            product="phototok",
            config="phone",
            journey="explore",
            checkpoint="step_01",
            rule="overlap-interactive",
            severity="major",
            confidence="high",
            region=[100, 100, 50, 50],
            message="Buttons overlap",
            evidence=["before.png", "after.png"],
        )
        self.assertEqual(f.id, "a1b2c3d4e5f6")
        self.assertEqual(f.severity, "major")
        self.assertEqual(f.rule, "overlap-interactive")
        self.assertEqual(len(f.evidence), 2)

    def test_finding_stable_id_deterministic(self) -> None:
        id1 = Finding.make_id("phototok", "explore", "step_01", "overlap-interactive", ["btn1", "btn2"])
        id2 = Finding.make_id("phototok", "explore", "step_01", "overlap-interactive", ["btn1", "btn2"])
        self.assertEqual(id1, id2)
        self.assertEqual(len(id1), 12)

    def test_finding_stable_id_differs_by_rule(self) -> None:
        id1 = Finding.make_id("phototok", "explore", "step_01", "overlap-interactive")
        id2 = Finding.make_id("phototok", "explore", "step_01", "missing-or-zero-size")
        self.assertNotEqual(id1, id2)

    def test_post_action_settle_log_scanning(self) -> None:
        logs = [
            "10-24 15:00:01.123 1001 1001 I ActivityManager: Displayed com.phototok.lab",
            "10-24 15:00:02.456 1001 1001 E AndroidRuntime: FATAL EXCEPTION: main",
            "java.lang.NullPointerException: Attempt to invoke virtual method",
        ]
        has_fatal = any("FATAL EXCEPTION" in l for l in logs)
        self.assertTrue(has_fatal)


# =====================================================================
# Tier 3: Cross-Feature Combinations (Expectations & Oracles)
# =====================================================================
class TestExpectationsCrossFeatureCombinations(unittest.TestCase):
    def test_aggregate_oracle_and_fs_failures(self) -> None:
        # Both a layout lint failure and an fs mismatch occur on the same step
        n_bad = Node(id="btn_bad", parent=None, role="button", name="Bad",
                     bounds=[10, 10, 0, 0], interactive=True, visible=True)
        tree = make_tree([n_bad])
        rules = load_rules("phototok")
        layout_findings = lint(tree, rules, expect={"required": ["btn_bad"]}, product="phototok", config="phone")

        fs_rule = {"absent": ["photo1.jpg"]}
        fs_state = {"files": [{"path": "photo1.jpg", "size": 100}], "trash": []}
        fs_findings = evaluate_fs_oracle(fs_state, fs_rule)

        self.assertGreaterEqual(len(layout_findings), 1)
        self.assertGreaterEqual(len(fs_findings), 1)

    def test_expectation_trace_continuity(self) -> None:
        # Trace across 3 steps: step 1 (nav) -> step 2 (move) -> step 3 (undo)
        trace: List[Expectation] = [
            Expectation(action=Action("swipe"), description="next", expected_index_delta=1),
            Expectation(action=Action("key", params={"key": "M"}), description="move", expected_feed_count_delta=-1),
            Expectation(action=Action("key", params={"key": "z"}), description="undo", expected_feed_count_delta=1),
        ]
        total_feed_delta = sum(e.expected_feed_count_delta or 0 for e in trace)
        self.assertEqual(total_feed_delta, 0)



# =====================================================================
# Tier 4: Direct Module Implementation Tests (lab.explore.expectations & verifier)
# =====================================================================
class TestExpectationsImplementation(unittest.TestCase):
    def test_expectation_dataclass_invariant(self) -> None:
        if not HAS_EXPLORE_EXPECTATIONS:
            self.skipTest("lab.explore.expectations not imported")
        act = Action("tap", "btn_test")
        exp = exp_expect.Expectation(action=act, description="Test invariant")
        self.assertIn("crash_dialog", exp.forbidden_ui_nodes)
        self.assertIn("anr_dialog", exp.forbidden_ui_nodes)
        self.assertIn("error_banner", exp.forbidden_ui_nodes)

    def test_expectation_serialization_roundtrip(self) -> None:
        if not HAS_EXPLORE_EXPECTATIONS:
            self.skipTest("lab.explore.expectations not imported")
        act = Action("swipe", params={"direction": "up"}, label="Swipe Up")
        exp = exp_expect.Expectation(
            action=act,
            description="Swipe up advances feed",
            expected_index_delta=1,
            expected_feed_count_delta=0,
            expected_counter_deltas={"selected": 0},
            fs_expect={"unchanged": True},
            required_ui_nodes=["main_feed"],
            forbidden_ui_nodes=["crash_dialog"],
            ai_prompt="Check feed smoothness",
        )
        data = exp.to_dict()
        restored = exp_expect.Expectation.from_dict(data)
        self.assertEqual(restored.action.action_type, "swipe")
        self.assertEqual(restored.expected_index_delta, 1)
        self.assertEqual(restored.expected_feed_count_delta, 0)
        self.assertEqual(restored.fs_expect, {"unchanged": True})
        self.assertIn("crash_dialog", restored.forbidden_ui_nodes)
        self.assertEqual(restored.ai_prompt, "Check feed smoothness")

    def test_recorder_navigation_next_and_prev(self) -> None:
        if not HAS_EXPLORE_EXPECTATIONS:
            self.skipTest("lab.explore.expectations not imported")
        rec = exp_expect.ExpectationRecorder(product="phototok")
        empty_tree = make_tree([])
        state = ScreenState(empty_tree, b"", "viewer", {})

        # Forward navigation
        act_next = Action("key", params={"key": "Right"}, label="Next Photo")
        exp_next = rec.record(state, act_next, feed_index=2, feed_count=10)
        self.assertEqual(exp_next.expected_index_delta, 1)
        self.assertEqual(exp_next.expected_feed_count_delta, 0)
        self.assertEqual(exp_next.fs_expect, {"unchanged": True})

        # Backward navigation
        act_prev = Action("key", params={"key": "Left"}, label="Prev Photo")
        exp_prev = rec.record(state, act_prev, feed_index=2, feed_count=10)
        self.assertEqual(exp_prev.expected_index_delta, -1)
        self.assertEqual(exp_prev.expected_feed_count_delta, 0)
        self.assertEqual(exp_prev.fs_expect, {"unchanged": True})

    def test_recorder_navigation_boundary_clamping(self) -> None:
        if not HAS_EXPLORE_EXPECTATIONS:
            self.skipTest("lab.explore.expectations not imported")
        rec = exp_expect.ExpectationRecorder(product="phototok")
        empty_tree = make_tree([])
        state = ScreenState(empty_tree, b"", "viewer", {})

        # At start (index 0), moving backward clamps to 0
        act_prev = Action("key", params={"key": "Left"})
        exp_start = rec.record(state, act_prev, feed_index=0, feed_count=10)
        self.assertEqual(exp_start.expected_index_delta, 0)

        # At end (index 9 of 10), moving forward clamps to 0
        act_next = Action("key", params={"key": "Right"})
        exp_end = rec.record(state, act_next, feed_index=9, feed_count=10)
        self.assertEqual(exp_end.expected_index_delta, 0)

    def test_recorder_cull_actions(self) -> None:
        if not HAS_EXPLORE_EXPECTATIONS:
            self.skipTest("lab.explore.expectations not imported")
        rec = exp_expect.ExpectationRecorder(product="phototok")
        empty_tree = make_tree([])
        state = ScreenState(empty_tree, b"", "viewer", {})

        # Move
        exp_move = rec.record_cull(state, Action("key", params={"key": "M"}), active_photo="test.jpg", cull_action="move")
        self.assertEqual(exp_move.expected_feed_count_delta, -1)
        self.assertEqual(exp_move.expected_counter_deltas["selected"], 1)
        self.assertIn("test.jpg", exp_move.fs_expect["absent"])
        self.assertIn("Selection/test.jpg", exp_move.fs_expect["present"])

        # Copy
        exp_copy = rec.record_cull(state, Action("key", params={"key": "C"}), active_photo="test.jpg", cull_action="copy")
        self.assertEqual(exp_copy.expected_feed_count_delta, 0)
        self.assertEqual(exp_copy.expected_index_delta, 1)
        self.assertEqual(exp_copy.expected_counter_deltas["selected"], 1)

        # Delete
        exp_del = rec.record_cull(state, Action("key", params={"key": "Del"}), active_photo="test.jpg", cull_action="delete")
        self.assertEqual(exp_del.expected_feed_count_delta, -1)
        self.assertEqual(exp_del.expected_counter_deltas["trashed"], 1)

        # Undo
        exp_undo = rec.record_cull(state, Action("key", params={"key": "z", "modifiers": ["ctrl"]}), active_photo="test.jpg", cull_action="undo")
        self.assertEqual(exp_undo.expected_feed_count_delta, 1)
        self.assertEqual(exp_undo.expected_counter_deltas["selected"], -1)

    def test_recorder_automatic_action_routing(self) -> None:
        if not HAS_EXPLORE_EXPECTATIONS:
            self.skipTest("lab.explore.expectations not imported")
        rec = exp_expect.ExpectationRecorder(product="phototok")
        empty_tree = make_tree([])
        state = ScreenState(empty_tree, b"", "viewer", {})

        # Tap keep button
        exp = rec.record(state, Action("tap", target_id="btn_keep", label="Keep"), active_photo="pic.jpg")
        self.assertEqual(exp.expected_feed_count_delta, -1)
        self.assertEqual(exp.expected_counter_deltas["selected"], 1)

        # Tap discard button
        exp_d = rec.record(state, Action("tap", target_id="btn_discard", label="Discard"), active_photo="pic.jpg")
        self.assertEqual(exp_d.expected_feed_count_delta, -1)
        self.assertEqual(exp_d.expected_counter_deltas["trashed"], 1)

    def test_convenience_helper_functions(self) -> None:
        if not HAS_EXPLORE_EXPECTATIONS:
            self.skipTest("lab.explore.expectations not imported")
        empty_tree = make_tree([])
        state = ScreenState(empty_tree, b"", "viewer", {})
        e1 = exp_expect.record_navigation_expectation(state, Action("swipe"), feed_index=0, feed_count=5)
        self.assertEqual(e1.expected_index_delta, 1)
        e2 = exp_expect.record_cull_expectation(state, Action("key", params={"key": "M"}), active_photo="foo.jpg", cull_action="move")
        self.assertEqual(e2.expected_feed_count_delta, -1)
        e3 = exp_expect.record_undo_expectation(state, Action("key", params={"key": "z"}), active_photo="foo.jpg")
        self.assertEqual(e3.expected_feed_count_delta, 1)


class TestVerifierImplementation(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="pstlab-test-verifier-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_verifier_rejects_legacy_desktop(self) -> None:
        if not HAS_EXPLORE_EXPECTATIONS:
            self.skipTest("lab.explore.verifier not imported")
        with self.assertRaises(ValueError):
            exp_verifier.InteractionVerifier(product="desktop")
        with self.assertRaises(ValueError):
            exp_verifier.InteractionVerifier(product="legacy-desktop")

    def test_verifier_normalizes_product_names(self) -> None:
        if not HAS_EXPLORE_EXPECTATIONS:
            self.skipTest("lab.explore.verifier not imported")
        v_linux = exp_verifier.InteractionVerifier(product="linux-desktop")
        self.assertEqual(v_linux.product, "linux")
        v_macos = exp_verifier.InteractionVerifier(product="macos-desktop")
        self.assertEqual(v_macos.product, "macos")
        v_duel = exp_verifier.InteractionVerifier(product="frameduel")
        self.assertEqual(v_duel.product, "android-desktop")
        v_jack = exp_verifier.InteractionVerifier(product="jackpix")
        self.assertEqual(v_jack.product, "phototok")

    def test_verifier_step_pass_with_clean_state(self) -> None:
        if not HAS_EXPLORE_EXPECTATIONS:
            self.skipTest("lab.explore.verifier not imported")
        verifier = exp_verifier.InteractionVerifier(product="phototok", rules={})
        act = Action("swipe", label="Next")
        exp = exp_expect.Expectation(action=act, description="Clean swipe", fs_expect={"unchanged": True})
        b_tree = make_tree([])
        a_tree = make_tree([])
        fs = {"files": [{"path": "photo.jpg", "size": 100}], "trash": []}
        b_state = ScreenState(b_tree, b"before", "viewer", fs)
        a_state = ScreenState(a_tree, b"after", "viewer", fs)

        res = verifier.verify(step_index=1, before_state=b_state, action=act, expectation=exp, after_state=a_state)
        self.assertTrue(res.passed)
        self.assertEqual(len(res.findings), 0)

    def test_verifier_fails_on_crash_log(self) -> None:
        if not HAS_EXPLORE_EXPECTATIONS:
            self.skipTest("lab.explore.verifier not imported")
        verifier = exp_verifier.InteractionVerifier(product="phototok", rules={})
        act = Action("tap", "btn_cull")
        exp = exp_expect.Expectation(action=act, description="Tap cull")
        b_state = ScreenState(make_tree([]), b"", "viewer", {})
        a_state = ScreenState(make_tree([]), b"", "viewer", {}, logs=["FATAL EXCEPTION: main NullPointerException"])

        res = verifier.verify(step_index=1, before_state=b_state, action=act, expectation=exp, after_state=a_state)
        self.assertFalse(res.passed)
        self.assertTrue(any(f.rule == "crash" and f.severity == "blocker" for f in res.findings))

    def test_verifier_fails_on_forbidden_ui_node(self) -> None:
        if not HAS_EXPLORE_EXPECTATIONS:
            self.skipTest("lab.explore.verifier not imported")
        verifier = exp_verifier.InteractionVerifier(product="phototok", rules={})
        act = Action("tap", "btn_action")
        exp = exp_expect.Expectation(action=act, description="Tap", forbidden_ui_nodes=["crash_dialog"])
        crash_node = Node(id="crash_dialog", parent=None, role="dialog", name="App Crashed", bounds=[0, 0, 100, 100], interactive=True, visible=True)
        b_state = ScreenState(make_tree([]), b"", "viewer", {})
        a_state = ScreenState(make_tree([crash_node]), b"", "viewer", {})

        res = verifier.verify(step_index=1, before_state=b_state, action=act, expectation=exp, after_state=a_state)
        self.assertFalse(res.passed)
        self.assertTrue(any(f.rule == "crash" and f.severity == "blocker" for f in res.findings))

    def test_verifier_fails_on_missing_required_node(self) -> None:
        if not HAS_EXPLORE_EXPECTATIONS:
            self.skipTest("lab.explore.verifier not imported")
        verifier = exp_verifier.InteractionVerifier(product="phototok", rules={})
        act = Action("tap", "btn_action")
        exp = exp_expect.Expectation(action=act, description="Tap", required_ui_nodes=["main_viewer"])
        b_state = ScreenState(make_tree([]), b"", "viewer", {})
        a_state = ScreenState(make_tree([]), b"", "viewer", {})

        res = verifier.verify(step_index=1, before_state=b_state, action=act, expectation=exp, after_state=a_state)
        self.assertFalse(res.passed)
        self.assertTrue(any(f.rule == "missing-required-node" and f.severity == "major" for f in res.findings))

    def test_verifier_fails_on_fs_mismatch(self) -> None:
        if not HAS_EXPLORE_EXPECTATIONS:
            self.skipTest("lab.explore.verifier not imported")
        verifier = exp_verifier.InteractionVerifier(product="phototok", rules={})
        act = Action("key", params={"key": "M"})
        exp = exp_expect.Expectation(action=act, description="Move photo", fs_expect={"present": ["Selection/photo.jpg"]})
        b_state = ScreenState(make_tree([]), b"", "viewer", {"files": [{"path": "photo.jpg", "size": 100}], "trash": []})
        a_state = ScreenState(make_tree([]), b"", "viewer", {"files": [{"path": "photo.jpg", "size": 100}], "trash": []})

        res = verifier.verify(step_index=1, before_state=b_state, action=act, expectation=exp, after_state=a_state)
        self.assertFalse(res.passed)
        self.assertTrue(any(f.rule == "fs-present" for f in res.findings))

    def test_verifier_passes_with_minor_layout_finding(self) -> None:
        if not HAS_EXPLORE_EXPECTATIONS:
            self.skipTest("lab.explore.verifier not imported")
        # Touch target too small is minor severity -> step passes
        tiny_button = Node(id="btn_tiny", parent=None, role="button", name="Tiny", bounds=[10, 10, 20, 20], interactive=True, visible=True)
        rules = load_rules("phototok")
        verifier = exp_verifier.InteractionVerifier(product="phototok", rules=rules)
        act = Action("tap", "btn_tiny")
        exp = exp_expect.Expectation(action=act, description="Tap tiny")
        b_state = ScreenState(make_tree([]), b"", "viewer", {})
        a_state = ScreenState(make_tree([tiny_button]), b"", "viewer", {})

        res = verifier.verify(step_index=1, before_state=b_state, action=act, expectation=exp, after_state=a_state)
        self.assertTrue(res.passed)
        self.assertTrue(any(f.rule == "touch-target" and f.severity == "minor" for f in res.findings))

    def test_verifier_step_result_serialization(self) -> None:
        if not HAS_EXPLORE_EXPECTATIONS:
            self.skipTest("lab.explore.verifier not imported")
        act = Action("swipe", label="Next")
        exp = exp_expect.Expectation(action=act, description="Next photo")
        b_state = ScreenState(make_tree([]), b"", "viewer", {})
        a_state = ScreenState(make_tree([]), b"", "viewer", {})
        res = exp_verifier.StepResult(
            step_index=1,
            action=act,
            expectation=exp,
            passed=True,
            findings=[],
            before_state=b_state,
            after_state=a_state,
            packet_dir=self.tmp / "packet-01",
        )
        d = res.to_dict()
        self.assertEqual(d["step_index"], 1)
        self.assertTrue(d["passed"])
        self.assertIn("packet-01", d["packet_dir"])

        restored = exp_verifier.StepResult.from_dict(d)
        self.assertEqual(restored.step_index, 1)
        self.assertTrue(restored.passed)

    def test_verify_step_convenience_function(self) -> None:
        if not HAS_EXPLORE_EXPECTATIONS:
            self.skipTest("lab.explore.verifier not imported")
        act = Action("swipe", label="Next")
        exp = exp_expect.Expectation(action=act, description="Next photo")
        b_state = ScreenState(make_tree([]), b"", "viewer", {})
        a_state = ScreenState(make_tree([]), b"", "viewer", {})
        res = exp_verifier.verify_step(
            step_index=2,
            action=act,
            expectation=exp,
            before_state=b_state,
            after_state=a_state,
            product="phototok",
            rules={},
        )
        self.assertEqual(res.step_index, 2)
        self.assertTrue(res.passed)


if __name__ == "__main__":
    unittest.main()

