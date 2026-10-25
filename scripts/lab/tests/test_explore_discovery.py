"""Comprehensive E2E and unit tests for Exploratory Interaction Discovery & Execution (M1).

Covers Features 2, 3, 4, 5, 6, 7, 8, 9, 10 across Tiers 1-4:
- Feature 2: Live Screen & UI Inspection
- Feature 3: Candidate Interaction: Taps & Clicks
- Feature 4: Candidate Interaction: Gestures & Swipes
- Feature 5: Candidate Interaction: Hardware Shortcuts
- Feature 6: Candidate Interaction: Lifecycle & Window
- Feature 7: Seeded PRNG Interaction Selector
- Feature 8: Direct Android Device Driver
- Feature 9: Direct Linux Headless Driver
- Feature 10: Direct macOS Desktop Driver
"""

from __future__ import annotations

import json
import math
import os
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from unittest.mock import MagicMock, patch
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

LAB_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(LAB_DIR))

from lab.explore.actions import (
    Action,
    ActionCategory,
    CandidateInteraction,
    ScreenState,
    categorize_action,
)
from lab.explore.discovery import (
    ScreenInspectionResult,
    detect_active_modal,
    enumerate_gestures,
    enumerate_interactive_elements,
    enumerate_keyboard_shortcuts,
    enumerate_lifecycle_actions,
    identify_screen_mode,
    inspect_screen,
    load_discovery_rules,
    normalize_product_name,
)
from lab.explore.driver import (
    AndroidDeviceDriver,
    AppCrashedError,
    DeviceDriver,
    DriverError,
    DriverExecutionError,
    DriverSettlingError,
    DriverTimeoutError,
    LinuxDeviceDriver,
    MacOSDeviceDriver,
    MockDeviceDriver,
)
from lab.explore.selector import (
    ActionSelector,
    EmptyCandidatesError,
    SelectionPolicy,
    SelectionRecord,
)
from lab.model import Node, Tree
from lab.paths import ensure_android_lab_path, ensure_under, lab_tmp_root


# --- Synthetic Tree Helpers ---
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
        tree = Tree(platform="linux", window={"w": 1920, "h": 1080},
                    nodes=[Node(id="folder_path_input", parent=None, role="input", name="Path", bounds=[10, 10, 300, 40], interactive=True, visible=True)])
        res = inspect_screen(tree, "linux-desktop")
        keys = [c.action.params.get("key") for c in res.filter_by_category("key")]
        self.assertEqual(keys, ["Escape"])


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
        tree = Tree(platform="android", window={"w": 1080, "h": 1920}, nodes=[])
        res = inspect_screen(tree, "phototok")
        bg = next((c for c in res.filter_by_category("lifecycle") if c.action.action_type == "background"), None)
        self.assertIsNotNone(bg)
        self.assertEqual(bg.action.params["duration_s"], 1.0)

    def test_window_resize_action(self) -> None:
        tree = Tree(platform="linux", window={"w": 1920, "h": 1080}, nodes=[])
        res = inspect_screen(tree, "linux-desktop")
        resize = next((c for c in res.filter_by_category("lifecycle") if c.action.action_type == "resize"), None)
        self.assertIsNotNone(resize)
        self.assertIn("width", resize.action.params)

    def test_device_rotation_action(self) -> None:
        tree = Tree(platform="android", window={"w": 1080, "h": 1920}, nodes=[])
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
# Tier 1 & 2: Feature 8 - Direct Android Device Driver
# =====================================================================
class TestDirectAndroidDeviceDriver(unittest.TestCase):
    def test_android_lab_path_safety_check(self) -> None:
        valid_path = "/sdcard/PSTLab/photo1.jpg"
        self.assertEqual(ensure_android_lab_path(valid_path), valid_path)
        with self.assertRaises(ValueError):
            ensure_android_lab_path("/sdcard/DCIM/Camera/photo1.jpg")
        with self.assertRaises(ValueError):
            ensure_android_lab_path("/data/system/users/0/")

    def test_android_driver_no_global_hub_lock_used(self) -> None:
        mock_adb = MagicMock()
        mock_adb.density.return_value = 320
        serial = "CQ30012CMV"
        driver = AndroidDeviceDriver(serial=serial, app_id="com.phototok.lab", adb=mock_adb)
        with patch("lab.explore.driver.device_lock") as mock_lock:
            lock_instance = MagicMock()
            mock_lock.return_value = lock_instance
            driver.setup()
            mock_lock.assert_called_once_with(serial, driver.holder_name)
            self.assertNotIn("android-hub", mock_lock.call_args[0][0])
            lock_instance.__enter__.assert_called_once()
            driver.teardown()
            lock_instance.__exit__.assert_called_once()

    def test_android_tap_command_structure(self) -> None:
        mock_adb = MagicMock()
        driver = AndroidDeviceDriver(serial="emulator-5554", app_id="com.phototok.lab", adb=mock_adb)
        driver._density = 320  # scale = 320 / 160 = 2.0
        # Test with explicit dp coordinates from discovery.py
        action = Action("tap", "btn_keep", params={"x": 270.0, "y": 480.0})
        driver._handle_tap(action)
        mock_adb.run.assert_called_with("shell", "input", "tap", "540", "960")

        # Test with different density (480 dpi -> scale = 3.0)
        driver._density = 480
        action2 = Action("tap", "btn_keep", params={"x": 100.0, "y": 150.0})
        driver._handle_tap(action2)
        mock_adb.run.assert_called_with("shell", "input", "tap", "300", "450")

        # Test NaN coordinate raises DriverExecutionError
        with self.assertRaises(DriverExecutionError):
            driver._handle_tap(Action("tap", params={"x": float("nan"), "y": 100.0}))

    def test_android_swipe_command_structure(self) -> None:
        mock_adb = MagicMock()
        driver = AndroidDeviceDriver(serial="emulator-5554", app_id="com.phototok.lab", adb=mock_adb)
        driver._density = 320  # scale = 2.0
        # Explicit swipe coordinates in dp
        action = Action("swipe", params={"x1": 50.0, "y1": 100.0, "x2": 50.0, "y2": 400.0, "duration_ms": 300})
        driver._handle_swipe(action)
        mock_adb.run.assert_called_with("shell", "input", "swipe", "100", "200", "100", "800", "300")

        # Directional swipe fallback based on window size
        driver._window_size = (360, 640)
        driver._handle_swipe(Action("swipe", params={"direction": "up", "duration_ms": 250}))
        last_args = mock_adb.run.call_args[0]
        self.assertEqual(last_args[:3], ("shell", "input", "swipe"))
        self.assertEqual(last_args[-1], "250")

        # NaN coordinate raises DriverExecutionError
        with self.assertRaises(DriverExecutionError):
            driver._handle_swipe(Action("swipe", params={"x1": float("nan"), "y1": 0, "x2": 0, "y2": 0}))

    def test_android_keyevent_command_structure(self) -> None:
        mock_adb = MagicMock()
        driver = AndroidDeviceDriver(serial="emulator-5554", app_id="com.phototok.lab", adb=mock_adb)
        # Key action without modifiers
        driver._handle_key(Action("key", params={"key": "m"}))
        mock_adb.key.assert_called_with("KEYCODE_M")

        # Key action with modifier
        driver._handle_key(Action("key", params={"key": "z", "modifiers": ["ctrl"]}))
        mock_adb.key_combo.assert_called_with("KEYCODE_CTRL_LEFT", "KEYCODE_Z")

        # Back action via execute_action
        driver.execute_action(Action("back"))
        mock_adb.key.assert_called_with("KEYCODE_BACK")

    def test_android_driver_setup_exception_releases_lock(self) -> None:
        mock_adb = MagicMock()
        mock_adb.ensure_ready.side_effect = RuntimeError("ADB connection lost")
        driver = AndroidDeviceDriver(serial="leak-test-device", app_id="com.phototok.lab", adb=mock_adb)
        with patch("lab.explore.driver.device_lock") as mock_lock:
            lock_instance = MagicMock()
            mock_lock.return_value = lock_instance
            with self.assertRaises(RuntimeError):
                driver.setup()
            lock_instance.__exit__.assert_called_once()
            self.assertIsNone(driver._lock_ctx)

    def test_android_driver_setup_lock_cleanup_on_base_exception(self) -> None:
        mock_adb = MagicMock()
        mock_adb.ensure_ready.side_effect = KeyboardInterrupt("Ctrl+C simulation")
        driver = AndroidDeviceDriver(serial="ctrl-c-device", app_id="com.phototok.lab", adb=mock_adb)
        with patch("lab.explore.driver.device_lock") as mock_lock:
            lock_instance = MagicMock()
            mock_lock.return_value = lock_instance
            with self.assertRaises(KeyboardInterrupt):
                driver.setup()
            lock_instance.__enter__.assert_called_once()
            lock_instance.__exit__.assert_called_once()
            self.assertIsNone(driver._lock_ctx)

    def test_android_driver_handle_resize_dimensions_contract(self) -> None:
        mock_adb = MagicMock()
        mock_adb.app_task.return_value = (42, "freeform", (50, 50, 850, 650), (1920, 1080))
        driver = AndroidDeviceDriver(serial="dex-device", app_id="com.photoselectortoolbox.lab", adb=mock_adb)
        driver._density = 160  # scale = 1.0

        # Test resize with discovery params: {"width": 800, "height": 600, "w": 800, "h": 600}
        driver._handle_resize(Action("resize", params={"width": 800, "height": 600, "w": 800, "h": 600}))
        mock_adb.resize_task.assert_called_with(42, 50, 50, 850, 650)

        # Test explicit bounds
        driver._handle_resize(Action("resize", params={"bounds": (0, 0, 1000, 700)}))
        mock_adb.resize_task.assert_called_with(42, 0, 0, 1000, 700)

    def test_android_driver_handle_rotate_contract(self) -> None:
        mock_adb = MagicMock()
        mock_adb.run.side_effect = lambda *args, **kwargs: "0" if "get" in args else ""
        driver = AndroidDeviceDriver(serial="rotate-device", app_id="com.phototok.lab", adb=mock_adb)

        # Test toggle from portrait (0) to landscape (1)
        driver._handle_rotate(Action("rotate", params={"toggle": True, "orientation": "landscape"}))
        mock_adb.run.assert_any_call("shell", "settings", "put", "system", "user_rotation", "1")

        # Test toggle from landscape (1) to portrait (0)
        mock_adb.reset_mock()
        mock_adb.run.side_effect = lambda *args, **kwargs: "1" if "get" in args else ""
        driver._handle_rotate(Action("rotate", params={"toggle": True, "orientation": "landscape"}))
        mock_adb.run.assert_any_call("shell", "settings", "put", "system", "user_rotation", "0")

        # Test explicit rotation parameter
        mock_adb.reset_mock()
        driver._handle_rotate(Action("rotate", params={"rotation": 3}))
        mock_adb.run.assert_any_call("shell", "settings", "put", "system", "user_rotation", "3")

    def test_android_driver_retry_backoff_and_crash_detection(self) -> None:
        class FakeAdb:
            def __init__(self) -> None:
                self.calls = 0

            def run_bytes(self, *args: str, timeout: int = 30) -> bytes:
                self.calls += 1
                return b"ERROR: could not get idle state."

            def run(self, *args: str, timeout: int = 30) -> str:
                if "crash" in args:
                    return "FATAL EXCEPTION: main\nProcess: com.phototok.lab\njava.lang.NullPointerException"
                return ""

        driver = AndroidDeviceDriver(serial="fake-device", app_id="com.phototok.lab", adb=FakeAdb(), dump_retries=2)
        with self.assertRaises(AppCrashedError):
            driver._dump_tree()

    def test_android_driver_dump_tree_parse_error_retry(self) -> None:
        broken_xml = b'<hierarchy rotation="0"><node index="0" text="hello"</hierarchy>'
        valid_xml = (
            b'<hierarchy rotation="0">'
            b'<node index="0" text="hello" class="android.widget.TextView" '
            b'package="com.phototok.lab" bounds="[0,0][1080,1920]" clickable="false" />'
            b'</hierarchy>'
        )

        class RetryingAdb:
            def __init__(self) -> None:
                self.calls = 0

            def run_bytes(self, *args: str, timeout: int = 30) -> bytes:
                self.calls += 1
                if self.calls == 1:
                    return broken_xml
                return valid_xml

            def run(self, *args: str, timeout: int = 30) -> str:
                return ""

        driver = AndroidDeviceDriver(serial="fake-device", app_id="com.phototok.lab", adb=RetryingAdb(), dump_retries=3)
        with patch("time.sleep"):
            tree = driver._dump_tree()
        self.assertIsNotNone(tree)
        self.assertEqual(driver.adb.calls, 2)

    def test_android_driver_dump_tree_persistent_parse_error_raises_settling_error(self) -> None:
        broken_xml = b'<hierarchy rotation="0"><node index="0" text="hello"</hierarchy>'

        class AlwaysBrokenAdb:
            def __init__(self) -> None:
                self.calls = 0

            def run_bytes(self, *args: str, timeout: int = 30) -> bytes:
                self.calls += 1
                return broken_xml

            def run(self, *args: str, timeout: int = 30) -> str:
                return ""

        driver = AndroidDeviceDriver(serial="fake-device", app_id="com.phototok.lab", adb=AlwaysBrokenAdb(), dump_retries=2)
        with patch("time.sleep"):
            with self.assertRaises(DriverSettlingError) as ctx:
                driver._dump_tree()
        self.assertIn("ParseError", str(ctx.exception))
        self.assertEqual(driver.adb.calls, 2)


# =====================================================================
# Tier 1 & 2: Feature 9 - Direct Linux Headless Driver
# =====================================================================
class TestDirectLinuxHeadlessDriver(unittest.TestCase):
    def test_linux_xvfb_display_environment(self) -> None:
        executed = []
        def fake_exec(cmd: list[str], env: dict[str, str]) -> None:
            executed.append((cmd, env))

        driver = LinuxDeviceDriver(
            display=":42",
            exec_fn=fake_exec,
            fixture_dir=Path(tempfile.mkdtemp(dir=lab_tmp_root())),
            out_dir=Path(tempfile.mkdtemp(dir=lab_tmp_root())),
            settle_ms=0,
        )
        driver.setup()
        self.assertEqual(driver._env["DISPLAY"], ":42")
        self.assertEqual(driver._env["GSK_RENDERER"], "cairo")
        self.assertEqual(driver._env["ADW_DISABLE_PORTAL"], "1")
        self.assertEqual(driver._env["GDK_DEBUG"], "no-portals")
        self.assertEqual(driver._env["GTK_USE_PORTAL"], "0")
        self.assertEqual(driver._env["PST_LAB"], "1")
        driver.teardown()

    def test_linux_xdotool_click_command(self) -> None:
        executed_cmds = []
        def fake_exec(cmd: list[str], env: dict[str, str]) -> None:
            executed_cmds.append(cmd)

        driver = LinuxDeviceDriver(
            exec_fn=fake_exec,
            fixture_dir=Path(tempfile.mkdtemp(dir=lab_tmp_root())),
            out_dir=Path(tempfile.mkdtemp(dir=lab_tmp_root())),
            settle_ms=0,
        )
        driver.execute_action(Action("click", params={"x": 500, "y": 400}))
        self.assertEqual(executed_cmds, [["xdotool", "mousemove", "--sync", "500", "400", "click", "1"]])

        # Validate NaN coordinate raises DriverExecutionError
        with self.assertRaises(DriverExecutionError):
            driver.execute_action(Action("click", params={"x": float("nan"), "y": 400}))

    def test_linux_xdotool_key_command(self) -> None:
        executed_cmds = []
        def fake_exec(cmd: list[str], env: dict[str, str]) -> None:
            executed_cmds.append(cmd)

        driver = LinuxDeviceDriver(
            exec_fn=fake_exec,
            fixture_dir=Path(tempfile.mkdtemp(dir=lab_tmp_root())),
            out_dir=Path(tempfile.mkdtemp(dir=lab_tmp_root())),
            settle_ms=0,
        )
        driver.execute_action(Action("key", params={"key": "m"}))
        self.assertEqual(executed_cmds, [["xdotool", "key", "--clearmodifiers", "m"]])

    def test_linux_dbus_lab_checkpoint_action(self) -> None:
        executed_cmds = []
        def fake_exec(cmd: list[str], env: dict[str, str]) -> None:
            executed_cmds.append(cmd)

        driver = LinuxDeviceDriver(
            exec_fn=fake_exec,
            fixture_dir=Path(tempfile.mkdtemp(dir=lab_tmp_root())),
            out_dir=Path(tempfile.mkdtemp(dir=lab_tmp_root())),
            settle_ms=0,
        )
        driver.capture_state()
        self.assertTrue(any(
            cmd[0] == "gapplication" and "lab-checkpoint" in cmd
            for cmd in executed_cmds
        ))

    def test_linux_host_path_safety_guard(self) -> None:
        root = lab_tmp_root()
        safe_path = root / "run1" / "step.png"
        resolved = ensure_under(safe_path, root)
        self.assertEqual(resolved, safe_path.resolve())

        # Traversal outside lab root raises ValueError
        escaped_path = root / ".." / "escaped.png"
        with self.assertRaises(ValueError):
            ensure_under(escaped_path, root)

        # Foreign system path raises ValueError
        with self.assertRaises(ValueError):
            ensure_under(Path("/etc/passwd"), root)

    def test_linux_driver_exec_fn_mock(self) -> None:
        executed_cmds = []

        def fake_exec(cmd: list[str], env: dict[str, str]) -> None:
            executed_cmds.append(cmd)

        driver = LinuxDeviceDriver(
            app_id="app.alpatix.Blitzlens",
            fixture_dir=Path(tempfile.mkdtemp(dir=lab_tmp_root())),
            out_dir=Path(tempfile.mkdtemp(dir=lab_tmp_root())),
            exec_fn=fake_exec,
            settle_ms=0,
        )
        driver.setup()
        driver.execute_action(Action("key", params={"key": "m"}))
        driver.execute_action(Action("click", params={"x": 500, "y": 400}))
        driver.capture_state()
        driver.teardown()

        self.assertTrue(any("xdotool" in cmd and "m" in cmd for cmd in executed_cmds))
        self.assertTrue(any("xdotool" in cmd and "click" in cmd for cmd in executed_cmds))
        self.assertTrue(any("lab-checkpoint" in cmd for cmd in executed_cmds))

    def test_linux_driver_setup_cleanup_on_base_exception(self) -> None:
        class CustomTermination(BaseException):
            pass

        # 1. KeyboardInterrupt during setup cleans up lab_dir
        driver_ki = LinuxDeviceDriver(
            display=":99",
            exec_fn=lambda c, e: (_ for _ in ()).throw(KeyboardInterrupt("SIGINT during setup")),
            settle_ms=0,
        )
        with self.assertRaises(KeyboardInterrupt):
            with driver_ki:
                pass
        self.assertFalse(driver_ki.lab_dir.exists())

        # 2. Custom BaseException during setup cleans up lab_dir
        driver_custom = LinuxDeviceDriver(
            display=":99",
            exec_fn=lambda c, e: (_ for _ in ()).throw(CustomTermination("Abrupt exit")),
            settle_ms=0,
        )
        with self.assertRaises(CustomTermination):
            driver_custom.setup()
        self.assertFalse(driver_custom.lab_dir.exists())

        # 3. Process termination when exception occurs after Xvfb launch
        mock_xvfb = MagicMock()
        mock_xvfb.poll.return_value = None
        mock_app = MagicMock()
        mock_app.poll.return_value = None

        driver_proc = LinuxDeviceDriver(display=":99", settle_ms=0)
        with patch("shutil.which", return_value="/usr/bin/Xvfb"), \
             patch("subprocess.Popen", side_effect=[mock_xvfb, mock_app]), \
             patch.object(driver_proc, "settle", side_effect=KeyboardInterrupt("Halt during settle")):
            with self.assertRaises(KeyboardInterrupt):
                driver_proc.setup()

        mock_xvfb.terminate.assert_called_once()
        mock_app.terminate.assert_called_once()
        self.assertFalse(driver_proc.lab_dir.exists())

    def test_linux_driver_concurrent_directory_isolation(self) -> None:
        def fake_exec(cmd: list[str], env: dict[str, str]) -> None:
            pass

        d1 = LinuxDeviceDriver(display=":91", exec_fn=fake_exec, settle_ms=0)
        d2 = LinuxDeviceDriver(display=":92", exec_fn=fake_exec, settle_ms=0)
        d3 = LinuxDeviceDriver(display=":91", exec_fn=fake_exec, settle_ms=0)

        # Unique paths even for identical display names
        self.assertNotEqual(d1.lab_dir, d2.lab_dir)
        self.assertNotEqual(d1.lab_dir, d3.lab_dir)
        self.assertNotEqual(d2.lab_dir, d3.lab_dir)

        d1.setup()
        d2.setup()
        self.assertTrue(d1.lab_dir.exists())
        self.assertTrue(d2.lab_dir.exists())

        # Teardown of d1 must not wipe d2's lab_dir
        d1.teardown()
        self.assertFalse(d1.lab_dir.exists())
        self.assertTrue(d2.lab_dir.exists())

        d2.teardown()
        self.assertFalse(d2.lab_dir.exists())

    def test_linux_driver_teardown_process_lookup_error_resilience(self) -> None:
        driver = LinuxDeviceDriver(display=":99", settle_ms=0)
        driver.lab_dir.mkdir(parents=True, exist_ok=True)

        mock_app = MagicMock()
        mock_app.poll.return_value = None
        mock_app.terminate.side_effect = ProcessLookupError("No such process")

        mock_xvfb = MagicMock()
        mock_xvfb.poll.return_value = None

        driver._app_proc = mock_app
        driver._xvfb_proc = mock_xvfb

        # Teardown must not crash, must attempt xvfb termination, and must clean lab_dir
        driver.teardown()

        mock_app.terminate.assert_called_once()
        mock_xvfb.terminate.assert_called_once()
        self.assertFalse(driver.lab_dir.exists())

    def test_linux_driver_teardown_xvfb_process_lookup_error_resilience(self) -> None:
        driver = LinuxDeviceDriver(display=":99", settle_ms=0)
        driver.lab_dir.mkdir(parents=True, exist_ok=True)

        mock_app = MagicMock()
        mock_app.poll.return_value = None

        mock_xvfb = MagicMock()
        mock_xvfb.poll.return_value = None
        mock_xvfb.terminate.side_effect = OSError("No such process")

        driver._app_proc = mock_app
        driver._xvfb_proc = mock_xvfb

        driver.teardown()

        mock_app.terminate.assert_called_once()
        mock_xvfb.terminate.assert_called_once()
        self.assertFalse(driver.lab_dir.exists())



# =====================================================================
# Tier 1 & 2: Feature 10 - Direct macOS Desktop Driver
# =====================================================================
class TestDirectMacOSDesktopDriver(unittest.TestCase):
    def test_macos_scenario_json_command(self) -> None:
        driver = MacOSDeviceDriver(
            app_id="app.alpatix.lensjet",
            fixture_dir=Path(tempfile.mkdtemp(dir=lab_tmp_root())),
            out_dir=Path(tempfile.mkdtemp(dir=lab_tmp_root())),
            settle_ms=0,
        )
        driver.execute_action(Action("click", "slot_current"))
        driver.execute_action(Action("key", params={"key": "m"}))
        driver.execute_action(Action("back"))
        self.assertEqual(driver.action_history, [
            {"action": "waitLoaded"},
            {"action": "click", "target": "slot_current"},
            {"action": "key", "key": "m"},
            {"action": "key", "key": "escape"},
        ])

    def test_macos_cli_flags_structure(self) -> None:
        executed_cmds = []
        captured_envs = []
        def fake_exec(cmd: list[str], env: dict[str, str]) -> None:
            executed_cmds.append(cmd)
            captured_envs.append(env)

        driver = MacOSDeviceDriver(
            app_id="app.alpatix.lensjet",
            fixture_dir=Path(tempfile.mkdtemp(dir=lab_tmp_root())),
            out_dir=Path(tempfile.mkdtemp(dir=lab_tmp_root())),
            exec_fn=fake_exec,
            settle_ms=0,
        )
        driver.capture_state()
        self.assertTrue(len(executed_cmds) > 0)
        cmd = executed_cmds[0]
        self.assertIn("--ui-scenario", cmd)
        self.assertIn("--artifacts", cmd)
        self.assertIn("-cullingDestinationFolderName", cmd)
        self.assertIn("Selection", cmd)
        self.assertIn("-externalCullingDestinationURL", cmd)
        self.assertEqual(captured_envs[0]["PST_SHOW_GUI"], "0")

    def test_macos_remote_ssh_host_resolution(self) -> None:
        executed_cmds: list[list[str]] = []
        captured_envs: list[dict[str, str]] = []

        def fake_exec(cmd: list[str], env: dict[str, str]) -> None:
            executed_cmds.append(cmd)
            captured_envs.append(env)

        driver = MacOSDeviceDriver(
            app_id="app.alpatix.lensjet",
            fixture_dir=Path(tempfile.mkdtemp(dir=lab_tmp_root())),
            out_dir=Path(tempfile.mkdtemp(dir=lab_tmp_root())),
            remote_host="MacBook-Air-von-Alexander.local",
            remote_user="pst-lab",
            exec_fn=fake_exec,
            settle_ms=0,
        )
        self.assertEqual(driver.remote_host, "MacBook-Air-von-Alexander.local")
        self.assertEqual(driver.remote_user, "pst-lab")
        driver.setup()
        driver.capture_state()
        self.assertGreater(len(executed_cmds), 0)
        ssh_cmd = executed_cmds[0]
        self.assertEqual(ssh_cmd[0], "ssh")
        self.assertEqual(ssh_cmd[1], "pst-lab@MacBook-Air-von-Alexander.local")
        self.assertIn("--ui-scenario", ssh_cmd)
        url_idx = ssh_cmd.index("-externalCullingDestinationURL")
        self.assertEqual(ssh_cmd[url_idx + 1], "''")
        self.assertEqual(captured_envs[0]["PST_SHOW_GUI"], "0")

    def test_macos_remote_ssh_subprocess_invocation_when_exec_fn_none(self) -> None:
        driver = MacOSDeviceDriver(
            app_id="app.alpatix.lensjet",
            fixture_dir=Path(tempfile.mkdtemp(dir=lab_tmp_root())),
            out_dir=Path(tempfile.mkdtemp(dir=lab_tmp_root())),
            remote_host="MacBook-Air-von-Alexander.local",
            remote_user="pst-lab",
            app_binary=None,
            exec_fn=None,
            settle_ms=0,
        )
        with patch("subprocess.run") as mock_subproc:
            driver.setup()
            state = driver.capture_state()
            self.assertEqual(mock_subproc.call_count, 1)
            args, kwargs = mock_subproc.call_args
            cmd = args[0]
            self.assertEqual(cmd[0], "ssh")
            self.assertEqual(cmd[1], "pst-lab@MacBook-Air-von-Alexander.local")
            self.assertEqual(cmd[2], "PhotoSelectorApp")
            self.assertIn("--ui-scenario", cmd)
            self.assertIn("-externalCullingDestinationURL", cmd)
            url_idx = cmd.index("-externalCullingDestinationURL")
            self.assertEqual(cmd[url_idx + 1], "''")
            self.assertFalse(kwargs.get("check"))
            self.assertEqual(kwargs.get("timeout"), 60)
            self.assertEqual(kwargs.get("env", {}).get("PST_SHOW_GUI"), "0")
            self.assertIsNotNone(state)

    def test_macos_remote_ssh_quoting_with_spaces(self) -> None:
        space_dir = Path(tempfile.mkdtemp(prefix="dir with spaces ", dir=lab_tmp_root()))
        executed_cmds: list[list[str]] = []

        def fake_exec(cmd: list[str], env: dict[str, str]) -> None:
            executed_cmds.append(cmd)

        driver = MacOSDeviceDriver(
            app_id="app.alpatix.lensjet",
            fixture_dir=space_dir,
            remote_host="192.168.1.50",
            remote_user="tester",
            exec_fn=fake_exec,
            settle_ms=0,
        )
        driver.capture_state()
        self.assertEqual(len(executed_cmds), 1)
        ssh_cmd = executed_cmds[0]
        self.assertEqual(ssh_cmd[0], "ssh")
        self.assertEqual(ssh_cmd[1], "tester@192.168.1.50")
        for arg in ssh_cmd[2:]:
            if " " in arg:
                self.assertTrue(
                    (arg.startswith("'") and arg.endswith("'")) or (arg.startswith('"') and arg.endswith('"')),
                    f"Argument containing space was not shell-quoted: {arg}",
                )
        url_idx = ssh_cmd.index("-externalCullingDestinationURL")
        self.assertEqual(ssh_cmd[url_idx + 1], "''")

    def test_macos_hit_target_size_rule(self) -> None:
        valid_btn = Node(id="btn_pill", parent=None, role="button", name="Exit",
                         bounds=[100, 100, 24, 24], interactive=True, visible=True)
        tiny_btn = Node(id="btn_tiny_close", parent=None, role="button", name="Close",
                        bounds=[150, 100, 16, 16], interactive=True, visible=True)
        clipped_btn = Node(id="btn_clipped_edge", parent=None, role="button", name="Offscreen",
                           bounds=[-15, 100, 30, 30], interactive=True, visible=True)
        tree = Tree(platform="macos", window={"w": 1440, "h": 900, "unit": "pt", "scale": 2.0},
                    nodes=[valid_btn, tiny_btn, clipped_btn])
        res = inspect_screen(tree, "macos-desktop")
        elem_ids = [c.action.target_id for c in res.filter_by_category("element")]
        self.assertIn("btn_pill", elem_ids)
        self.assertNotIn("btn_tiny_close", elem_ids)
        self.assertNotIn("btn_clipped_edge", elem_ids)

    def test_macos_driver_exec_fn_mock(self) -> None:
        executed_cmds = []

        def fake_exec(cmd: list[str], env: dict[str, str]) -> None:
            executed_cmds.append(cmd)

        driver = MacOSDeviceDriver(
            app_id="app.alpatix.lensjet",
            fixture_dir=Path(tempfile.mkdtemp(dir=lab_tmp_root())),
            out_dir=Path(tempfile.mkdtemp(dir=lab_tmp_root())),
            exec_fn=fake_exec,
            settle_ms=0,
        )
        driver.setup()
        driver.execute_action(Action("key", params={"key": "m"}))
        driver.capture_state()
        driver.teardown()

        self.assertTrue(any("PhotoSelectorApp" in cmd[0] for cmd in executed_cmds))


# =====================================================================
# Offline Synthetic Mock Driver
# =====================================================================
class TestMockDeviceDriver(unittest.TestCase):
    def test_mock_driver_lifecycle_and_transitions(self) -> None:
        driver = MockDeviceDriver(product="phototok", total_photos=3)
        with driver:
            state0 = driver.capture_state()
            self.assertEqual(state0.screen_mode, "landing")
            self.assertEqual(len(state0.fs_snapshot["files"]), 3)

            # Tap enters viewer
            driver.execute_action(Action("tap", "card_folder"))
            state1 = driver.capture_state()
            self.assertEqual(state1.screen_mode, "viewer")
            self.assertEqual(driver.current_index, 0)

            # Swipe up advances to next photo
            driver.execute_action(Action("swipe", params={"direction": "up"}))
            self.assertEqual(driver.current_index, 1)

            # Key M moves current photo to Selection/
            driver.execute_action(Action("key", params={"key": "M"}))
            state2 = driver.capture_state()
            selection_files = [f["path"] for f in state2.fs_snapshot["files"] if "Selection/" in f["path"]]
            self.assertIn("Selection/photo_02.jpg", selection_files)

            # Key Ctrl+Z undoes move
            driver.execute_action(Action("key", params={"key": "Z", "modifiers": ["ctrl"]}))
            state3 = driver.capture_state()
            selection_files_after = [f["path"] for f in state3.fs_snapshot["files"] if "Selection/" in f["path"]]
            self.assertNotIn("Selection/photo_02.jpg", selection_files_after)

    def test_mock_driver_fault_injection(self) -> None:
        driver_fail = MockDeviceDriver(fail_on_action="tap")
        with self.assertRaises(DriverExecutionError):
            driver_fail.execute_action(Action("tap", "btn_fail"))

        driver_timeout = MockDeviceDriver(timeout_on_capture=True)
        with self.assertRaises(DriverTimeoutError):
            driver_timeout.capture_state()

        driver_settle = MockDeviceDriver(settle_failure_count=1)
        with self.assertRaises(DriverSettlingError):
            driver_settle.capture_state()

    def test_mock_driver_preserves_caller_supplied_directories(self) -> None:
        caller_work = Path(tempfile.mkdtemp(prefix="caller_work_test_", dir=lab_tmp_root()))
        caller_out = Path(tempfile.mkdtemp(prefix="caller_out_test_", dir=lab_tmp_root()))
        (caller_work / "custom_file.txt").write_text("custom work", encoding="utf-8")
        (caller_out / "custom_report.json").write_text("{}", encoding="utf-8")

        driver = MockDeviceDriver(working_dir=caller_work, out_dir=caller_out)
        self.assertFalse(driver._owns_working_dir)
        self.assertFalse(driver._owns_out_dir)

        driver.setup()
        driver.teardown()

        self.assertTrue(caller_work.exists())
        self.assertTrue(caller_out.exists())
        self.assertTrue((caller_work / "custom_file.txt").exists())
        self.assertTrue((caller_out / "custom_report.json").exists())

        shutil.rmtree(caller_work, ignore_errors=True)
        shutil.rmtree(caller_out, ignore_errors=True)

    def test_mock_driver_cleans_up_auto_created_directories(self) -> None:
        driver = MockDeviceDriver()
        self.assertTrue(driver._owns_working_dir)
        self.assertTrue(driver._owns_out_dir)

        driver.setup()
        work_dir = driver.working_dir
        out_dir = driver.out_dir
        self.assertTrue(work_dir.exists())
        self.assertTrue(out_dir.exists())

        driver.teardown()
        self.assertFalse(work_dir.exists())
        self.assertFalse(out_dir.exists())


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


if __name__ == "__main__":
    unittest.main()
