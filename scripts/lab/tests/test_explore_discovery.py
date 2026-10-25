"""Comprehensive E2E and unit tests for Exploratory Interaction Discovery & Execution (M1).

Covers Features 2-7 across Tiers 1-4:
- Feature 2: Live Screen & UI Inspection
- Feature 3: Candidate Interaction: Taps & Clicks
- Feature 4: Candidate Interaction: Gestures & Swipes
- Feature 5: Candidate Interaction: Hardware Shortcuts
- Feature 6: Candidate Interaction: Lifecycle & Window
- Feature 7: Seeded PRNG Interaction Selector

The drivers are in `test_explore_driver.py`, the real Linux session in `test_explore_e2e_smoke.py`.
"""

from __future__ import annotations

import os
import subprocess
import sys
import unittest
from pathlib import Path
from typing import List, Optional

LAB_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(LAB_DIR))

from lab.explore.actions import (
    Action,
    ActionCategory,
    CandidateInteraction,
    ScreenState,
)
from lab.explore.discovery import (
    ScreenInspectionResult,
    inspect_screen,
    normalize_product_name,
)
from lab.explore.selector import (
    ActionSelector,
    EmptyCandidatesError,
)
from lab.model import Node, Tree


# --- Synthetic Tree Helpers ---
def _root(w: int, h: int) -> Node:
    """A screen with one non-interactive root: an empty tree offers only back."""
    return Node(id="root", parent=None, role="frame", name="root", bounds=[0.0, 0.0, float(w), float(h)], interactive=False, visible=True)


def make_synthetic_tree(
    platform: str = "android",
    window_w: float = 1080,
    window_h: float = 1920,
    screen_mode: str = "viewer",
    nodes: Optional[List[Node]] = None,
) -> Tree:
    if nodes is None:
        nodes = []
        if screen_mode == "landing":
            nodes = [
                Node(id="card_camera", parent=None, role="button", name="Phone Camera",
                     bounds=[40, 200, 1000, 250], interactive=True, visible=True),
                Node(id="card_sd", parent=None, role="button", name="SD Card / USB Reader",
                     bounds=[40, 480, 1000, 250], interactive=True, visible=True),
                Node(id="card_folder", parent=None, role="button", name="Folder or Cloud",
                     bounds=[40, 760, 1000, 250], interactive=True, visible=True),
            ]
        elif screen_mode == "viewer":
            nodes = [
                Node(id="viewer.photo", parent=None, role="image", name="Current Photo",
                     bounds=[0, 150, 1080, 1500], interactive=True, visible=True),
                Node(id="btn_keep", parent=None, role="button", name="Keep",
                     bounds=[80, 1700, 200, 80], interactive=True, visible=True),
                Node(id="btn_discard", parent=None, role="button", name="Discard",
                     bounds=[320, 1700, 200, 80], interactive=True, visible=True),
                Node(id="btn_revert", parent=None, role="button", name="Revert",
                     bounds=[560, 1700, 200, 80], interactive=True, visible=True),
            ]
        elif screen_mode in ("three_up", "selector"):
            nodes = [
                Node(id="selector.frame.current", parent=None, role="frame", name="Current",
                     bounds=[240, 50, 600, 400], interactive=True, visible=True),
                Node(id="selector.frame.previous", parent=None, role="frame", name="Previous",
                     bounds=[50, 470, 450, 350], interactive=True, visible=True),
                Node(id="selector.frame.next", parent=None, role="frame", name="Next",
                     bounds=[580, 470, 450, 350], interactive=True, visible=True),
                Node(id="btn_undo", parent=None, role="button", name="Undo",
                     bounds=[50, 850, 120, 50], interactive=True, visible=True),
            ]
    return Tree(platform=platform, window={"w": window_w, "h": window_h, "scale": 2.0, "unit": "dp"}, nodes=nodes)


# =====================================================================
# Tier 1 & 2: Feature 2 - Live Screen & UI Inspection
# =====================================================================
class TestLiveScreenInspection(unittest.TestCase):
    def test_screen_state_contract_initialization(self) -> None:
        tree = make_synthetic_tree(screen_mode="landing")
        state = ScreenState(
            tree=tree,
            screenshot_bytes=b"\x89PNG\r\n\x1a\nfake",
            screen_mode="landing",
            fs_snapshot={"files": ["photo1.jpg"], "selection": []},
            logs=["App launched"],
        )
        self.assertEqual(state.screen_mode, "landing")
        self.assertEqual(len(state.tree.nodes), 3)
        self.assertEqual(state.tree.platform, "android")
        self.assertEqual(len(state.logs), 1)

    def test_inspect_screen_detects_phototok_landing(self) -> None:
        tree = make_synthetic_tree(screen_mode="landing")
        res = inspect_screen(tree, "phototok")
        self.assertEqual(res.screen_mode, "landing")
        node_ids = {n.id for n in tree.nodes}
        self.assertTrue("card_camera" in node_ids or any("card" in n.id for n in tree.nodes))

    def test_inspect_screen_detects_viewer_mode(self) -> None:
        tree = make_synthetic_tree(screen_mode="viewer")
        res = inspect_screen(tree, "phototok")
        self.assertEqual(res.screen_mode, "viewer")
        photo_nodes = [n for n in tree.nodes if n.role == "image" or "photo" in n.id]
        self.assertEqual(len(photo_nodes), 1)
        self.assertEqual(photo_nodes[0].id, "viewer.photo")

    def test_inspect_screen_detects_frameduel_three_up(self) -> None:
        tree = make_synthetic_tree(screen_mode="three_up", window_w=1480, window_h=924)
        res = inspect_screen(tree, "android-desktop")
        self.assertEqual(res.screen_mode, "selector")
        frame_ids = {n.id for n in tree.nodes if n.role == "frame"}
        self.assertIn("selector.frame.current", frame_ids)
        self.assertIn("selector.frame.previous", frame_ids)
        self.assertIn("selector.frame.next", frame_ids)

    def test_inspect_screen_empty_tree_boundary(self) -> None:
        tree = Tree(platform="android", window={"w": 1080, "h": 1920}, nodes=[])
        res = inspect_screen(tree, "phototok")
        self.assertEqual(len(tree.nodes), 0)
        self.assertEqual(tree.window_w, 1080)
        self.assertEqual(tree.window_h, 1920)
        self.assertIsInstance(res, ScreenInspectionResult)
        self.assertEqual([c.action.action_type for c in res.candidate_interactions], ["back"])

    def test_jackpix_is_offered_no_keyboard_shortcuts(self) -> None:
        tree = Tree(platform="android", window={"w": 1080, "h": 1920}, nodes=[_root(1080, 1920)])
        res = inspect_screen(tree, "phototok")
        self.assertEqual([c for c in res.candidate_interactions if c.category == "key"], [])
        frameduel = inspect_screen(Tree(platform="android", window={"w": 1080, "h": 1920}, nodes=[_root(1080, 1920)]), "android-desktop")
        self.assertTrue([c for c in frameduel.candidate_interactions if c.category == "key"])

    def test_inspect_screen_empty_linux_window_offers_only_back(self) -> None:
        # Blitzlens' Duplicate Finder exports no nodes: main-window keys would go to it.
        tree = Tree(platform="linux", window={"w": 690, "h": 510}, nodes=[])
        res = inspect_screen(tree, "linux-desktop")
        self.assertEqual([c.action.action_type for c in res.candidate_interactions], ["back"])

    def test_inspect_screen_corrupted_coordinates_sanitization(self) -> None:
        nodes = [
            Node(id="offscreen_neg", parent=None, role="button", name="Neg",
                 bounds=[-50.0, -100.0, 200.0, 80.0], interactive=True, visible=True),
            Node(id="offscreen_pos", parent=None, role="button", name="Pos",
                 bounds=[3000.0, 3000.0, 100.0, 100.0], interactive=True, visible=True),
            Node(id="nan_node", parent=None, role="button", name="NaN",
                 bounds=[float("nan"), 100.0, 100.0, 50.0], interactive=True, visible=True),
            Node(id="inf_node", parent=None, role="button", name="Inf",
                 bounds=[100.0, float("inf"), 100.0, 50.0], interactive=True, visible=True),
            Node(id="valid_btn", parent=None, role="button", name="Valid",
                 bounds=[100.0, 100.0, 100.0, 50.0], interactive=True, visible=True),
        ]
        tree = Tree(platform="linux", window={"w": 1920, "h": 1080}, nodes=nodes)
        res = inspect_screen(tree, "linux-desktop")
        target_ids = [c.action.target_id for c in res.candidate_interactions]
        self.assertIn("valid_btn", target_ids)
        self.assertNotIn("offscreen_neg", target_ids)
        self.assertNotIn("offscreen_pos", target_ids)
        self.assertNotIn("nan_node", target_ids)
        self.assertNotIn("inf_node", target_ids)

    def test_inspect_screen_massive_tree_stress(self) -> None:
        nodes = [
            Node(id=f"item_{i}", parent=None, role="button", name=f"Item {i}",
                 bounds=[float(i % 100), float(i // 100), 50.0, 50.0], interactive=True, visible=True)
            for i in range(2000)
        ]
        tree = Tree(platform="android", window={"w": 1080, "h": 1920}, nodes=nodes)
        self.assertEqual(len(tree.nodes), 2000)
        res = inspect_screen(tree, "phototok")
        self.assertGreater(len(res.candidate_interactions), 0)

    def test_legacy_desktop_rejection(self) -> None:
        tree = Tree(platform="desktop", window={"w": 800, "h": 600}, nodes=[])
        with self.assertRaises(ValueError):
            normalize_product_name("desktop")
        with self.assertRaises(ValueError):
            inspect_screen(tree, "desktop")


# =====================================================================
# Tier 1 & 2: Feature 3 - Candidate Interaction: Taps & Clicks
# =====================================================================
class TestCandidateTapsAndClicks(unittest.TestCase):
    def test_action_contract_dataclass(self) -> None:
        action = Action(
            action_type="tap",
            target_id="btn_keep",
            params={"x": 180, "y": 1740},
            label="Tap Keep Button",
        )
        self.assertEqual(action.action_type, "tap")
        self.assertEqual(action.target_id, "btn_keep")
        self.assertEqual(action.params["x"], 180)
        self.assertEqual(action.summary(), "[tap] on btn_keep Tap Keep Button")

    def test_action_immutability(self) -> None:
        action = Action(action_type="click", target_id="btn_discard")
        with self.assertRaises((AttributeError, TypeError)):
            action.action_type = "swipe"  # type: ignore[misc]

    def test_action_hashability_and_equality_with_dict(self) -> None:
        a1 = Action("tap", "btn_ok", {"x": 100, "y": 200}, "OK")
        a2 = Action("tap", "btn_ok", {"x": 100, "y": 200}, "OK")
        a3 = Action("tap", "btn_ok", {"x": 100, "y": 201}, "OK")
        self.assertEqual(a1, a2)
        self.assertNotEqual(a1, a3)
        s = {a1, a2, a3}
        self.assertEqual(len(s), 2)
        self.assertIn(a1, s)

    def test_candidate_interaction_hashability(self) -> None:
        ci1 = CandidateInteraction(
            action=Action("tap", "btn_ok"),
            category="element",
            description="Tap OK",
            target_bounds=[0, 0, 48, 48],
        )
        ci2 = CandidateInteraction(
            action=Action("tap", "btn_ok"),
            category="element",
            description="Tap OK",
            target_bounds=[0, 0, 48, 48],
        )
        self.assertEqual(ci1, ci2)
        self.assertEqual(len({ci1, ci2}), 1)

    def test_min_touch_target_filtering_android(self) -> None:
        valid_btn = Node(id="btn_ok", parent=None, role="button", name="OK",
                         bounds=[100, 100, 48, 48], interactive=True, visible=True)
        tiny_btn = Node(id="btn_tiny", parent=None, role="button", name="Tiny",
                        bounds=[200, 100, 32, 32], interactive=True, visible=True)
        tol_btn = Node(id="btn_tol", parent=None, role="button", name="Tol",
                       bounds=[300, 100, 47.8, 48], interactive=True, visible=True)
        tree = Tree(platform="android", window={"w": 1080, "h": 1920}, nodes=[valid_btn, tiny_btn, tol_btn])
        res = inspect_screen(tree, "phototok")
        elem_ids = [c.action.target_id for c in res.filter_by_category("element")]
        self.assertIn("btn_ok", elem_ids)
        self.assertIn("btn_tol", elem_ids)
        self.assertNotIn("btn_tiny", elem_ids)

    def test_min_touch_target_filtering_desktop(self) -> None:
        btn = Node(id="btn_menu", parent=None, role="button", name="Menu",
                   bounds=[10, 10, 24, 24], interactive=True, visible=True)
        tiny_menu = Node(id="btn_tiny_menu", parent=None, role="button", name="Tiny",
                         bounds=[50, 10, 16, 16], interactive=True, visible=True)
        tree = Tree(platform="linux", window={"w": 1920, "h": 1080}, nodes=[btn, tiny_menu])
        res = inspect_screen(tree, "linux-desktop")
        elem_ids = [c.action.target_id for c in res.filter_by_category("element")]
        self.assertIn("btn_menu", elem_ids)
        self.assertNotIn("btn_tiny_menu", elem_ids)

    def test_visible_bounds_clipping_rejects_offscreen_sliver(self) -> None:
        # A button that has 100x50 unclipped bounds, but 99% off-screen (only 1px visible width)
        sliver_btn = Node(id="btn_sliver", parent=None, role="button", name="Sliver",
                          bounds=[-99.0, 100.0, 100.0, 50.0], interactive=True, visible=True)
        tree = Tree(platform="android", window={"w": 1080, "h": 1920}, nodes=[sliver_btn])
        res = inspect_screen(tree, "phototok")
        elem_ids = [c.action.target_id for c in res.filter_by_category("element")]
        self.assertNotIn("btn_sliver", elem_ids)

    def test_invisible_or_disabled_nodes_excluded(self) -> None:
        node_hidden = Node(id="hidden", parent=None, role="button", name="Hidden",
                           bounds=[0, 0, 100, 100], interactive=True, visible=False)
        node_disabled = Node(id="disabled", parent=None, role="button", name="Disabled",
                             bounds=[0, 0, 100, 100], interactive=False, visible=True)
        tree = Tree(platform="android", window={"w": 1080, "h": 1920}, nodes=[node_hidden, node_disabled])
        res = inspect_screen(tree, "phototok")
        elem_ids = [c.action.target_id for c in res.filter_by_category("element")]
        self.assertNotIn("hidden", elem_ids)
        self.assertNotIn("disabled", elem_ids)

    def test_zero_size_node_handling(self) -> None:
        zero_node = Node(id="zero", parent=None, role="button", name="Zero",
                         bounds=[10, 10, 0, 0], interactive=True, visible=True)
        tree = Tree(platform="android", window={"w": 1080, "h": 1920}, nodes=[zero_node])
        res = inspect_screen(tree, "phototok")
        elem_ids = [c.action.target_id for c in res.filter_by_category("element")]
        self.assertNotIn("zero", elem_ids)

    def test_active_modal_scrim_exclusion(self) -> None:
        bg_btn = Node(id="bg_cull", parent=None, role="button", name="Cull", bounds=[10, 10, 80, 50], interactive=True, visible=True)
        dlg = Node(id="direct_delete_dialog", parent=None, role="container", name="Dialog", bounds=[100, 100, 400, 300], interactive=False, visible=True, overlay=True)
        dlg_btn = Node(id="btn_confirm", parent="direct_delete_dialog", role="button", name="Confirm", bounds=[120, 200, 100, 50], interactive=True, visible=True)
        tree = Tree(platform="android", window={"w": 1080, "h": 1920}, nodes=[bg_btn, dlg, dlg_btn])
        res = inspect_screen(tree, "phototok")
        self.assertEqual(res.screen_mode, "dialog")
        self.assertEqual(res.active_modal, "direct_delete_dialog")
        elem_ids = [c.action.target_id for c in res.filter_by_category("element")]
        self.assertIn("btn_confirm", elem_ids)
        self.assertNotIn("bg_cull", elem_ids)


# =====================================================================
# Tier 1 & 2: Feature 4 - Candidate Interaction: Gestures & Swipes
# =====================================================================
class TestCandidateGesturesAndSwipes(unittest.TestCase):
    def test_phototok_vertical_feed_swipes_payload(self) -> None:
        tree = Tree(platform="android", window={"w": 1080, "h": 1920},
                    nodes=[Node(id="viewer.photo", parent=None, role="image", name="Feed", bounds=[0, 100, 1080, 1600], interactive=True, visible=True)])
        res = inspect_screen(tree, "phototok")
        gestures = res.filter_by_category("gesture")
        swipe_up = next((g.action for g in gestures if g.action.params.get("direction") == "up"), None)
        swipe_down = next((g.action for g in gestures if g.action.params.get("direction") == "down"), None)
        self.assertIsNotNone(swipe_up)
        self.assertIsNotNone(swipe_down)
        self.assertEqual(swipe_up.action_type, "swipe")
        self.assertLess(swipe_up.params["y2"], swipe_up.params["y1"])
        self.assertGreater(swipe_down.params["y2"], swipe_down.params["y1"])

    def test_phototok_horizontal_swipes_keep_discard(self) -> None:
        tree = Tree(platform="android", window={"w": 1080, "h": 1920},
                    nodes=[Node(id="viewer.photo", parent=None, role="image", name="Feed", bounds=[0, 100, 1080, 1600], interactive=True, visible=True)])
        res = inspect_screen(tree, "phototok")
        gestures = res.filter_by_category("gesture")
        swipe_right = next((g.action for g in gestures if g.action.params.get("direction") == "right"), None)
        swipe_left = next((g.action for g in gestures if g.action.params.get("direction") == "left"), None)
        self.assertIsNotNone(swipe_right)
        self.assertIsNotNone(swipe_left)
        self.assertGreater(swipe_right.params["x2"], swipe_right.params["x1"])
        self.assertLess(swipe_left.params["x2"], swipe_left.params["x1"])

    def test_frameduel_three_up_neighbour_taps(self) -> None:
        tree = make_synthetic_tree(screen_mode="three_up", window_w=1480, window_h=924)
        res = inspect_screen(tree, "android-desktop")
        gestures = res.filter_by_category("gesture")
        tap_prev = next((g.action for g in gestures if g.action.target_id == "selector.frame.previous"), None)
        tap_next = next((g.action for g in gestures if g.action.target_id == "selector.frame.next"), None)
        self.assertIsNotNone(tap_prev)
        self.assertIsNotNone(tap_next)
        self.assertEqual(tap_prev.target_id, "selector.frame.previous")
        self.assertEqual(tap_next.target_id, "selector.frame.next")

    def test_pinch_and_zoom_gestures(self) -> None:
        tree = Tree(platform="android", window={"w": 1080, "h": 1920},
                    nodes=[Node(id="viewer.photo", parent=None, role="image", name="Feed", bounds=[0, 100, 1080, 1600], interactive=True, visible=True)])
        res = inspect_screen(tree, "phototok")
        double_tap = next((g.action for g in res.filter_by_category("gesture") if g.action.params.get("double_tap")), None)
        self.assertIsNotNone(double_tap)
        self.assertEqual(double_tap.params.get("count"), 2)

    def test_swipe_boundaries_strictly_inside_window(self) -> None:
        w, h = 1080, 1920
        tree = Tree(platform="android", window={"w": w, "h": h},
                    nodes=[Node(id="viewer.photo", parent=None, role="image", name="Feed", bounds=[0, 0, w, h], interactive=True, visible=True)])
        res = inspect_screen(tree, "phototok")
        for g in res.filter_by_category("gesture"):
            if g.action.action_type == "swipe":
                for key in ("x1", "x2"):
                    if key in g.action.params:
                        self.assertTrue(0 <= g.action.params[key] <= w)
                for key in ("y1", "y2"):
                    if key in g.action.params:
                        self.assertTrue(0 <= g.action.params[key] <= h)

    def test_phototok_swipes_clamped_with_negative_offset_node(self) -> None:
        # Panned photo with negative offset: px = -200, pw = 600 -> end_x for discard would be negative without clamping
        tree = Tree(platform="android", window={"w": 1080, "h": 1920},
                    nodes=[Node(id="viewer.photo", parent=None, role="image", name="Feed", bounds=[-200, 100, 600, 1600], interactive=True, visible=True)])
        res = inspect_screen(tree, "phototok")
        for g in res.filter_by_category("gesture"):
            if g.action.action_type == "swipe":
                for key in ("start_x", "end_x", "start_y", "end_y", "x1", "x2", "y1", "y2"):
                    if key in g.action.params:
                        val = g.action.params[key]
                        self.assertGreaterEqual(val, 0.0)
                        if "x" in key:
                            self.assertLessEqual(val, 1080.0)
                        if "y" in key:
                            self.assertLessEqual(val, 1920.0)


# =====================================================================
# Tier 1 & 2: Feature 5 - Candidate Interaction: Hardware Shortcuts
# =====================================================================
class TestCandidateHardwareShortcuts(unittest.TestCase):
    def test_navigation_shortcuts(self) -> None:
        tree = Tree(platform="linux", window={"w": 1920, "h": 1080},
                    nodes=[Node(id="slot_current_image", parent=None, role="image", name="Curr", bounds=[100, 100, 800, 600], interactive=True, visible=True)])
        res = inspect_screen(tree, "linux-desktop")
        keys = {c.action.params.get("key") for c in res.filter_by_category("key")}
        for k in ["Left", "Right", "H", "L", "J", "K"]:
            self.assertIn(k, keys)

    def test_culling_shortcuts(self) -> None:
        tree = Tree(platform="linux", window={"w": 1920, "h": 1080},
                    nodes=[Node(id="slot_current_image", parent=None, role="image", name="Curr", bounds=[100, 100, 800, 600], interactive=True, visible=True)])
        res = inspect_screen(tree, "linux-desktop")
        keys = {c.action.params.get("key") for c in res.filter_by_category("key")}
        for k in ["M", "C", "Delete"]:
            self.assertIn(k, keys)

    def test_layout_switch_shortcuts(self) -> None:
        tree = Tree(platform="linux", window={"w": 1920, "h": 1080},
                    nodes=[Node(id="slot_current_image", parent=None, role="image", name="Curr", bounds=[100, 100, 800, 600], interactive=True, visible=True)])
        res = inspect_screen(tree, "linux-desktop")
        keys = {c.action.params.get("key") for c in res.filter_by_category("key")}
        for k in ["1", "2", "3"]:
            self.assertIn(k, keys)

    def test_undo_shortcuts_cross_platform(self) -> None:
        tree_linux = Tree(platform="linux", window={"w": 1920, "h": 1080},
                          nodes=[Node(id="slot_current_image", parent=None, role="image", name="Curr", bounds=[100, 100, 800, 600], interactive=True, visible=True)])
        res_linux = inspect_screen(tree_linux, "linux-desktop")
        undo_linux = next((c.action for c in res_linux.filter_by_category("key") if c.action.params.get("key") == "z"), None)
        self.assertIsNotNone(undo_linux)
        self.assertIn("ctrl", undo_linux.params.get("modifiers", []))

        tree_mac = Tree(platform="macos", window={"w": 1440, "h": 900},
                        nodes=[Node(id="single_image_viewport", parent=None, role="image", name="Curr", bounds=[100, 100, 800, 600], interactive=True, visible=True)])
        res_mac = inspect_screen(tree_mac, "macos-desktop")
        undo_mac = next((c.action for c in res_mac.filter_by_category("key") if c.action.params.get("key") == "z"), None)
        self.assertIsNotNone(undo_mac)
        self.assertIn("cmd", undo_mac.params.get("modifiers", []))

    def test_text_input_suppression(self) -> None:
        field = Node(id="folder_path_input", parent=None, role="input", name="Path", bounds=[10, 10, 300, 40], interactive=True, visible=True)
        field.focused = True  # type: ignore[attr-defined]
        tree = Tree(platform="linux", window={"w": 1920, "h": 1080}, nodes=[field])
        res = inspect_screen(tree, "linux-desktop")
        keys = [c.action.params.get("key") for c in res.filter_by_category("key")]
        self.assertEqual(keys, ["Escape"])

    def test_an_input_that_is_only_on_screen_leaves_the_shortcuts_alone(self) -> None:
        tree = Tree(platform="linux", window={"w": 1920, "h": 1080},
                    nodes=[Node(id="folder_path_input", parent=None, role="input", name="Path", bounds=[10, 10, 300, 40], interactive=True, visible=True),
                           Node(id="slot_current_image", parent=None, role="image", name="Curr", bounds=[100, 100, 800, 600], interactive=True, visible=True)])
        res = inspect_screen(tree, "linux-desktop")
        keys = {c.action.params.get("key") for c in res.filter_by_category("key")}
        self.assertTrue({"M", "Right", "Delete"} <= keys)

    def test_shortcuts_are_a_key_and_modifiers_never_one_string(self) -> None:
        tree = make_synthetic_tree(platform="linux", screen_mode="viewer")
        res = inspect_screen(tree, "linux-desktop", allow_folder_picker=True)
        combos = {}
        for c in res.filter_by_category("key"):
            if c.action.params.get("shortcut"):
                combos[c.action.params["shortcut"]] = (c.action.params["key"], c.action.params["modifiers"])
        self.assertEqual(combos["Ctrl+O"], ("o", ["ctrl"]))
        self.assertEqual(combos["Ctrl+D"], ("d", ["ctrl"]))
        self.assertEqual(combos["Ctrl+,"], (",", ["ctrl"]))
        self.assertEqual(combos["Ctrl+Z"], ("z", ["ctrl"]))
        for c in res.filter_by_category("key"):
            key = str(c.action.params["key"])
            self.assertTrue(len(key) == 1 or "+" not in key, key)

    def test_macos_shortcuts_use_cmd(self) -> None:
        res = inspect_screen(make_synthetic_tree(platform="macos", screen_mode="viewer"), "macos-desktop", allow_folder_picker=True)
        shortcuts = {c.action.params["shortcut"]: c.action.params["modifiers"]
                     for c in res.filter_by_category("key") if c.action.params.get("shortcut")}
        self.assertEqual(shortcuts["Cmd+O"], ["cmd"])

    def test_the_folder_picker_shortcut_is_offered_only_when_allowed(self) -> None:
        tree = make_synthetic_tree(platform="linux", screen_mode="viewer")
        shortcuts = lambda res: {c.action.params.get("shortcut") for c in res.filter_by_category("key")}  # noqa: E731
        self.assertNotIn("Ctrl+O", shortcuts(inspect_screen(tree, "linux-desktop")))
        self.assertIn("Ctrl+O", shortcuts(inspect_screen(tree, "linux-desktop", allow_folder_picker=True)))


# =====================================================================
# Tier 1 & 2: Feature 6 - Candidate Interaction: Lifecycle & Window
# =====================================================================
class TestCandidateLifecycleAndWindow(unittest.TestCase):
    def test_system_back_action(self) -> None:
        tree = Tree(platform="android", window={"w": 1080, "h": 1920}, nodes=[])
        res = inspect_screen(tree, "phototok")
        back = next((c for c in res.filter_by_category("lifecycle") if c.action.action_type == "back"), None)
        self.assertIsNotNone(back)
        self.assertEqual(back.action.action_type, "back")

    def test_background_and_reentry_action(self) -> None:
        tree = Tree(platform="android", window={"w": 1080, "h": 1920}, nodes=[_root(1080, 1920)])
        res = inspect_screen(tree, "phototok")
        bg = next((c for c in res.filter_by_category("lifecycle") if c.action.action_type == "background"), None)
        self.assertIsNotNone(bg)
        self.assertEqual(bg.action.params["duration_s"], 1.0)

    def test_window_resize_action(self) -> None:
        tree = Tree(platform="linux", window={"w": 1920, "h": 1080}, nodes=[_root(1920, 1080)])
        res = inspect_screen(tree, "linux-desktop")
        resize = next((c for c in res.filter_by_category("lifecycle") if c.action.action_type == "resize"), None)
        self.assertIsNotNone(resize)
        self.assertIn("width", resize.action.params)

    def test_device_rotation_action(self) -> None:
        tree = Tree(platform="android", window={"w": 1080, "h": 1920}, nodes=[_root(1080, 1920)])
        res = inspect_screen(tree, "phototok")
        rotate = next((c for c in res.filter_by_category("lifecycle") if c.action.action_type == "rotate"), None)
        self.assertIsNotNone(rotate)

    def test_lifecycle_action_types_whitelist(self) -> None:
        valid_types = {"tap", "click", "swipe", "key", "back", "background", "resize", "rotate"}
        tree = Tree(platform="android", window={"w": 1080, "h": 1920}, nodes=[])
        res = inspect_screen(tree, "phototok")
        for c in res.filter_by_category("lifecycle"):
            self.assertIn(c.action.action_type, valid_types)


# =====================================================================
# Tier 1 & 2: Feature 7 - Seeded PRNG Interaction Selector
# =====================================================================
class TestSeededInteractionSelector(unittest.TestCase):
    def setUp(self) -> None:
        self.cands = [
            Action("tap", "btn_keep", label="Keep"),
            Action("tap", "btn_discard", label="Discard"),
            Action("swipe", params={"direction": "up"}, label="Next"),
            Action("key", params={"key": "M"}, label="Move"),
            Action("back", label="Back"),
        ]

    def test_selector_reproducible_with_same_seed(self) -> None:
        s1 = ActionSelector(seed=42)
        s2 = ActionSelector(seed=42)
        r1 = [s1.select(self.cands) for _ in range(50)]
        r2 = [s2.select(self.cands) for _ in range(50)]
        self.assertEqual(r1, r2)

    def test_selector_diverges_with_different_seeds(self) -> None:
        s1 = ActionSelector(seed=42)
        s2 = ActionSelector(seed=99)
        r1 = [s1.select(self.cands) for _ in range(25)]
        r2 = [s2.select(self.cands) for _ in range(25)]
        self.assertNotEqual(r1, r2)

    def test_selector_unseeded_records_reproducible_seed(self) -> None:
        s1 = ActionSelector(seed=None)
        self.assertIsInstance(s1.seed, int)
        self.assertGreaterEqual(s1.seed, 0)
        s2 = ActionSelector(seed=s1.seed)
        r1 = [s1.select(self.cands) for _ in range(25)]
        r2 = [s2.select(self.cands) for _ in range(25)]
        self.assertEqual(r1, r2)

    def test_boundary_seeds(self) -> None:
        for seed_val in (0, -1, -42, 2**31 - 1, 2**63 - 1, 2**128):
            with self.subTest(seed=seed_val):
                s1 = ActionSelector(seed=seed_val)
                s2 = ActionSelector(seed=seed_val)
                self.assertEqual(
                    [s1.select(self.cands) for _ in range(10)],
                    [s2.select(self.cands) for _ in range(10)],
                )

    def test_invalid_seed_types_raise_type_error(self) -> None:
        for bad in (3.14, "42", [1, 2]):
            with self.subTest(bad=bad):
                with self.assertRaises(TypeError):
                    ActionSelector(seed=bad)  # type: ignore[arg-type]

    def test_selector_empty_candidates_raises_empty_candidates_error(self) -> None:
        s = ActionSelector(seed=42)
        with self.assertRaises(EmptyCandidatesError):
            s.select([])

    def test_selector_single_candidate_chosen_always(self) -> None:
        s = ActionSelector(seed=42)
        cands = [Action("back")]
        for _ in range(10):
            self.assertEqual(s.select(cands), cands[0])

    def test_candidate_input_order_insensitivity(self) -> None:
        c1 = list(self.cands)
        c2 = list(reversed(self.cands))
        s1 = ActionSelector(seed=777)
        s2 = ActionSelector(seed=777)
        self.assertEqual(
            [s1.select(c1) for _ in range(25)],
            [s2.select(c2) for _ in range(25)],
        )

    def test_loop_avoidance_noop_suppression(self) -> None:
        s = ActionSelector(seed=42)
        act_noop = Action("key", params={"key": "Left"})
        act_ok = Action("key", params={"key": "Right"})
        pair = [act_noop, act_ok]

        chosen1 = s.select(pair, screen_sig="screen_1")
        s.record_outcome(chosen1, screen_sig="screen_1", is_noop=True)

        for _ in range(20):
            self.assertNotEqual(s.select(pair, screen_sig="screen_1"), chosen1)

        # On screen_2, action is not suppressed
        screen_2_choices = {s.select(pair, screen_sig="screen_2") for _ in range(25)}
        self.assertIn(chosen1, screen_2_choices)

    def test_loop_avoidance_all_noops_fallback(self) -> None:
        s = ActionSelector(seed=42)
        single = [Action("tap", "disabled_widget")]
        s.record_outcome(single[0], screen_sig="stuck", is_noop=True)
        # Fallback ensures selection never deadlocks or raises
        chosen = s.select(single, screen_sig="stuck")
        self.assertEqual(chosen, single[0])

    def test_trace_export(self) -> None:
        s = ActionSelector(seed=123)
        chosen = s.select(self.cands, screen_sig="screen_a")
        s.record_outcome(chosen, screen_sig="screen_a", is_noop=False)
        trace = s.export_trace()
        self.assertEqual(len(trace), 1)
        self.assertEqual(trace[0]["step"], 1)
        self.assertEqual(trace[0]["screen_sig"], "screen_a")
        self.assertFalse(trace[0]["was_noop"])

    def test_action_selector_total_cat_weight_zero_fallback(self) -> None:
        s = ActionSelector(seed=42)
        # Force all category weights to 0
        s.category_weights = {cat: 0 for cat in ActionCategory}
        # Selector should fall back to uniform selection without raising ValueError
        chosen = s.select(self.cands, screen_sig="screen_zero")
        self.assertIn(chosen, self.cands)

    def test_action_canonical_key_non_primitive_path(self) -> None:
        act = Action("tap", "btn_open", params={"target_path": Path("/tmp/pstlab/photo.jpg")})
        key = act.canonical_key()
        self.assertIsInstance(key, tuple)
        self.assertIn("/tmp/pstlab/photo.jpg", key[2])

    def test_compute_screen_signature_node_role_none(self) -> None:
        s = ActionSelector(seed=42)
        nodes = [
            Node(id="n1", parent=None, role=None, name="Node 1", bounds=[0, 0, 100, 100], interactive=True, visible=True),  # type: ignore[arg-type]
            Node(id="n2", parent=None, role="button", name="Node 2", bounds=[0, 100, 100, 100], interactive=True, visible=True),
        ]
        tree = Tree(platform="android", window={"w": 1080, "h": 1920}, nodes=nodes)
        state = ScreenState(tree=tree, screenshot_bytes=b"", screen_mode="selector", fs_snapshot={})
        sig = s.compute_screen_signature(state)
        self.assertIsInstance(sig, str)
        self.assertEqual(len(sig), 12)


# =====================================================================
# Tier 3: Cross-Feature Combinations (Discovery & Drivers)
# =====================================================================
class TestDiscoveryCrossFeatureCombinations(unittest.TestCase):
    def test_seed_and_product_matrix_generation(self) -> None:
        products = ["phototok", "android-desktop", "macos-desktop", "linux-desktop"]
        seeds = [42, 100, 999]
        trees = {
            "phototok": make_synthetic_tree(platform="android", screen_mode="viewer", window_w=1080, window_h=1920),
            "android-desktop": make_synthetic_tree(platform="android", screen_mode="selector", window_w=1480, window_h=924),
            "macos-desktop": make_synthetic_tree(platform="macos", screen_mode="selector", window_w=1440, window_h=900),
            "linux-desktop": make_synthetic_tree(platform="linux", screen_mode="landing", window_w=1920, window_h=1080),
        }
        tested_combinations = 0
        for product in products:
            tree = trees[product]
            res = inspect_screen(tree, product)
            candidates = [c.action for c in res.candidate_interactions]
            self.assertGreater(len(candidates), 0, f"No candidates discovered for {product}")
            for seed in seeds:
                sel1 = ActionSelector(seed=seed)
                sel2 = ActionSelector(seed=seed)
                act1 = sel1.select(candidates)
                act2 = sel2.select(candidates)
                self.assertEqual(act1, act2, f"Seed {seed} not deterministic for {product}")
                self.assertIn(act1, candidates)
                tested_combinations += 1

        self.assertEqual(tested_combinations, 12)

    def test_action_sequence_replay_consistency(self) -> None:
        candidates = [
            Action(action_type="tap", target_id=f"node_{i}") for i in range(10)
        ]
        s1 = ActionSelector(seed=42)
        s2 = ActionSelector(seed=42)
        seq1 = [s1.select(candidates) for _ in range(15)]
        seq2 = [s2.select(candidates) for _ in range(15)]
        self.assertEqual(seq1, seq2)


# =====================================================================
# Safety and determinism of what discovery offers
# =====================================================================
class TestWhatDiscoveryNeverOffers(unittest.TestCase):
    def landing(self) -> Tree:
        def card(nid: str, y: float) -> Node:
            return Node(id=nid, parent=None, role="button", name=nid, bounds=[40, y, 1000, 250], interactive=True, visible=True)

        nodes = [Node(id="landing_logo", parent=None, role="image", name="", bounds=[40, 20, 200, 100], interactive=False, visible=True),
                 card("landing_phone_camera_card", 200), card("landing_sd_card_card", 480), card("landing_custom_folder_card", 760),
                 card("landing_start_browsing_button", 1040)]
        return Tree(platform="android", window={"w": 1080, "h": 1920, "scale": 2.0, "unit": "dp"}, nodes=nodes)

    def targets(self, **kwargs: bool) -> set:
        res = inspect_screen(self.landing(), "phototok", **kwargs)
        return {c.action.target_id for c in res.filter_by_category("element")}

    def test_the_camera_roll_card_is_never_offered(self) -> None:
        self.assertNotIn("landing_phone_camera_card", self.targets())
        self.assertNotIn("landing_phone_camera_card", self.targets(allow_folder_picker=True))
        self.assertIn("landing_start_browsing_button", self.targets())

    def test_controls_that_open_the_folder_picker_need_permission(self) -> None:
        refused = {"landing_sd_card_card", "landing_custom_folder_card"}
        self.assertFalse(refused & self.targets())
        self.assertTrue(refused <= self.targets(allow_folder_picker=True))

    def test_the_picker_controls_of_the_other_products_are_gated_too(self) -> None:
        for product, nid in (("android-desktop", "sidebar_open_folder"), ("linux-desktop", "btn_open")):
            node = Node(id=nid, parent=None, role="button", name="Open", bounds=[0, 0, 100, 100], interactive=True, visible=True)
            tree = Tree(platform="x", window={"w": 1000, "h": 800}, nodes=[node])
            self.assertNotIn(nid, {c.action.target_id for c in inspect_screen(tree, product).filter_by_category("element")})
            self.assertIn(nid, {c.action.target_id for c in inspect_screen(tree, product, allow_folder_picker=True).filter_by_category("element")})

    def test_a_desktop_window_has_no_background_or_rotate_action(self) -> None:
        for product in ("linux-desktop", "macos-desktop"):
            res = inspect_screen(Tree(platform="x", window={"w": 1920, "h": 1080}, nodes=[]), product)
            types = {c.action.action_type for c in res.filter_by_category("lifecycle")}
            self.assertFalse(types & {"background", "rotate", "swipe"}, product)


class TestScreenModeRules(unittest.TestCase):
    def test_the_settings_sheet_is_the_settings_screen_not_a_dialog(self) -> None:
        sheet = Node(id="PhoneSettingsSheet", parent=None, role="container", name="Settings", bounds=[0, 0, 1080, 1920], interactive=False, visible=True)
        res = inspect_screen(Tree(platform="android", window={"w": 1080, "h": 1920}, nodes=[sheet]), "phototok")
        self.assertIsNone(res.active_modal)
        self.assertEqual(res.screen_mode, "settings")

    def test_other_sheets_and_dialogs_are_still_modal(self) -> None:
        for nid in ("ExportSheet", "delete_dialog"):
            node = Node(id=nid, parent=None, role="container", name="", bounds=[0, 0, 500, 500], interactive=False, visible=True)
            res = inspect_screen(Tree(platform="android", window={"w": 1080, "h": 1920}, nodes=[node]), "phototok")
            self.assertEqual((res.active_modal, res.screen_mode), (nid, "dialog"))

    def test_the_same_tree_gives_the_same_mode_whatever_the_hash_seed(self) -> None:
        # No product rule matches, so the generic fallback decides; it must not follow set order.
        code = (
            "import sys; sys.path.insert(0, %r)\n"
            "from lab.model import Node, Tree\n"
            "from lab.explore.discovery import identify_screen_mode\n"
            "ids = ['empty_state', 'inspect_panel', 'photo_pane', 'frame_strip', 'learn_more', 'viewer_x']\n"
            "nodes = [Node(id=i, parent=None, role='container', name='', bounds=[0, 0, 10, 10], interactive=False, visible=True) for i in ids]\n"
            "tree = Tree(platform='x', window={'w': 100, 'h': 100}, nodes=nodes)\n"
            "print(identify_screen_mode(tree, 'phototok', {}, {n.id: n for n in nodes}))\n"
        ) % str(LAB_DIR)
        answers = set()
        for seed in ("0", "1", "2", "3", "42", "4711"):
            out = subprocess.run([sys.executable, "-I", "-c", code], capture_output=True, text=True,
                                 env={**os.environ, "PYTHONHASHSEED": seed}, check=True)
            answers.add(out.stdout.strip())
        self.assertEqual(answers, {"empty"})  # the first node in tree order that names a mode: 'empty_state'

    def test_classify_screen_agrees_with_inspect_screen(self) -> None:
        from lab.explore.discovery import classify_screen

        tree = make_synthetic_tree(screen_mode="viewer")
        self.assertEqual(classify_screen(tree, "phototok"), inspect_screen(tree, "phototok").screen_mode)


class TestSelectorUsesCandidateWeights(unittest.TestCase):
    def candidate(self, key: str, weight: float) -> CandidateInteraction:
        return CandidateInteraction(action=Action("key", params={"key": key}), category="key", weight=weight)

    def test_a_heavier_candidate_is_chosen_more_often(self) -> None:
        cands = [self.candidate("A", 1.0), self.candidate("B", 0.1)]
        picks = [ActionSelector(seed=s, avoid_loops=False).select(cands).params["key"] for s in range(400)]
        self.assertGreater(picks.count("A"), 5 * picks.count("B"))
        self.assertGreater(picks.count("B"), 0)

    def test_a_zero_weight_candidate_is_never_chosen_while_another_exists(self) -> None:
        cands = [self.candidate("A", 1.0), self.candidate("B", 0.0)]
        picks = {ActionSelector(seed=s).select(cands).params["key"] for s in range(100)}
        self.assertEqual(picks, {"A"})

    def test_all_zero_weights_still_select(self) -> None:
        cands = [self.candidate("A", 0.0), self.candidate("B", 0.0)]
        self.assertIn(ActionSelector(seed=1).select(cands).params["key"], {"A", "B"})

    def test_plain_actions_and_candidates_mix_and_return_actions(self) -> None:
        chosen = ActionSelector(seed=3).select([Action("back"), self.candidate("A", 1.0)])
        self.assertIsInstance(chosen, Action)

    def test_the_same_action_offered_twice_counts_once_with_its_larger_weight(self) -> None:
        sel = ActionSelector(seed=5)
        sel.select([self.candidate("A", 0.2), self.candidate("A", 1.0)])
        self.assertEqual(sel.history[0].candidate_count, 1)

    def test_candidate_order_does_not_change_the_choice(self) -> None:
        cands = [self.candidate(k, w) for k, w in (("A", 1.0), ("B", 0.5), ("C", 0.1))]
        a = [ActionSelector(seed=9).select(cands) for _ in range(3)]
        b = [ActionSelector(seed=9).select(list(reversed(cands))) for _ in range(3)]
        self.assertEqual(a, b)


class TestRecordOutcomeLearnsNoOps(unittest.TestCase):
    def state(self, ids: List[str], *, shot: bytes = b"", fs: Optional[dict] = None, mode: str = "viewer") -> ScreenState:
        nodes = [Node(id=i, parent=None, role="button", name=i, bounds=[0, 0, 60, 60], interactive=True, visible=True) for i in ids]
        return ScreenState(tree=Tree(platform="x", window={"w": 100, "h": 100}, nodes=nodes), screenshot_bytes=shot,
                           screen_mode=mode, fs_snapshot=fs or {})

    def test_an_action_that_changed_nothing_is_remembered_for_that_screen(self) -> None:
        sel = ActionSelector(seed=1)
        before = self.state(["a"])
        act = Action("key", params={"key": "Left"})
        other = Action("key", params={"key": "Right"})
        sel.select([act, other], screen_state=before)
        sel.record_outcome(act, before, self.state(["a"]))
        self.assertIn(act.canonical_key(), sel._known_noops[sel.compute_screen_signature(before)])
        self.assertTrue(sel.history[-1].was_noop)
        self.assertEqual({sel.select([act, other], screen_state=before) for _ in range(15)}, {other})

    def test_a_changed_tree_mode_files_or_picture_is_not_a_noop(self) -> None:
        for after in (self.state(["a", "b"]), self.state(["a"], mode="dialog"), self.state(["a"], fs={"files": ["x"]}),
                      self.state(["a"], shot=b"\x89PNG2")):
            with self.subTest(after=after.screen_mode, nodes=len(after.tree.nodes)):
                sel = ActionSelector(seed=1)
                before = self.state(["a"], shot=b"\x89PNG1")
                act = Action("key", params={"key": "Left"})
                sel.select([act], screen_state=before)
                sel.record_outcome(act, before, after)
                self.assertEqual(sel._known_noops, {})


if __name__ == "__main__":
    unittest.main()
