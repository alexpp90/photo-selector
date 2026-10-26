"""Interaction discovery, candidate action enumeration, and screen inspection.

Inspects live normalized UI trees across Jackpix (phototok), Frameduel (android-desktop),
Lensjet (macos-desktop), and Blitzlens (linux-desktop), detecting active screen modes,
active modals, and enumerating interactive elements, gestural actions, keyboard shortcuts,
and lifecycle operations.
"""

from __future__ import annotations

import fnmatch
import math
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

import yaml

from ..layout_lint import TOUCH_TOLERANCE, cut_by_scroll, intersection_rect, is_ancestor, visible_bounds
from ..model import Node, Tree
from ..usability.driver import parse_key
from .actions import Action, CandidateInteraction

RULES_DIR = Path(__file__).resolve().parent.parent.parent / "rules"

SUPPORTED_PRODUCTS = {"phototok", "android-desktop", "macos-desktop", "linux-desktop"}

# Jackpix's "Phone Camera" card opens the device's real camera roll: a lab run must never offer it,
# on any device (the card needs a camera roll the lab does not create; see `requires_dirs`).
# Screen mode a driver reports when the system folder picker, not the app, is in front. The
# explorer only leaves it (Back cancels without granting a folder), whatever opened it: picker
# triggers without a test tag, such as Jackpix's localized "Quellordner" row, slip past ids.
SYSTEM_PICKER_MODE = "system-picker"

NEVER_OFFERED_IDS = frozenset({"landing_phone_camera_card", "card_camera"})

# Controls that open the system folder picker. The explorer cannot drive the picker, and on a
# personal device a wrong pick would hand the app a real folder, so they are offered only when
# the caller says the picker is safe (`allow_folder_picker`).
FOLDER_PICKER_IDS = frozenset({
    "landing_custom_folder_card", "landing_sd_card_card", "reselect_folder_button",
    "sidebar_open_folder", "btn_open_folder", "btn_open", "card_folder", "card_sd",
})

# Ids that end in "Sheet" but are screens of their own, not a modal over another screen.
SCREEN_LEVEL_SHEETS = frozenset({"phonesettingssheet"})


def normalize_product_name(product: str) -> str:
    """Normalize product aliases and reject excluded legacy products."""
    p = product.lower().replace("_", "-").strip()
    if p in ("desktop", "legacy-desktop", "photo-selector-toolbox"):
        raise ValueError("Legacy Python desktop is explicitly excluded from exploratory testing.")
    if p in ("phototok", "jackpix"):
        return "phototok"
    if p in ("android-desktop", "frameduel"):
        return "android-desktop"
    if p in ("macos-desktop", "macos", "lensjet"):
        return "macos-desktop"
    if p in ("linux-desktop", "linux", "blitzlens"):
        return "linux-desktop"
    return p


def load_discovery_rules(product: str) -> Dict[str, Any]:
    """Load matching yaml rules from scripts/lab/rules/."""
    norm = normalize_product_name(product)
    rule_file_name = {
        "phototok": "phototok.yaml",
        "android-desktop": "android-desktop.yaml",
        "macos-desktop": "macos.yaml",
        "linux-desktop": "linux.yaml",
    }.get(norm, f"{norm}.yaml")

    path = RULES_DIR / rule_file_name
    if not path.is_file():
        return {
            "min_target": 48 if "android" in norm or norm == "phototok" else 24,
            "overlay_ids": [],
            "frame_ids": [],
        }
    return yaml.safe_load(path.read_text(encoding="utf-8")) or {}


@dataclass
class ScreenInspectionResult:
    """Comprehensive screen inspection outcome including screen mode and candidate interactions."""

    product: str
    screen_mode: str  # "landing", "viewer", "selector", "inspect", "empty", "dialog", "fullscreen", "settings", "duplicates", "statistics", "unknown"
    active_modal: Optional[str] = None
    candidate_interactions: List[CandidateInteraction] = field(default_factory=list)
    interactive_nodes: List[Node] = field(default_factory=list)
    tree: Tree = field(default_factory=lambda: Tree(platform="unknown", window={"w": 0, "h": 0}))
    warnings: List[str] = field(default_factory=list)

    @property
    def actions(self) -> List[Action]:
        return [c.action for c in self.candidate_interactions]

    def filter_by_category(self, category: str) -> List[CandidateInteraction]:
        return [c for c in self.candidate_interactions if c.category == category]

    def to_dict(self) -> Dict[str, Any]:
        return {
            "product": self.product,
            "screen_mode": self.screen_mode,
            "active_modal": self.active_modal,
            "total_candidates": len(self.candidate_interactions),
            "candidates": [c.to_dict() for c in self.candidate_interactions],
            "warnings": list(self.warnings),
        }


def _find_node_label(node: Node, by_id: Dict[str, Node]) -> str:
    """Find effective display text or name from node or immediate descendants."""
    if node.name and node.name.strip():
        return node.name.strip()
    if node.text and node.text.strip():
        return node.text.strip()
    for child in by_id.values():
        if child.parent == node.id:
            if child.name and child.name.strip():
                return child.name.strip()
            if child.text and child.text.strip():
                return child.text.strip()
    return node.id


def detect_active_modal(tree: Tree, rules: Dict[str, Any], by_id: Dict[str, Node]) -> Optional[str]:
    """Identify if a modal dialog, sheet, or overlay is currently active and covering the screen."""
    overlay_ids = set(rules.get("overlay_ids", []))
    modal_patterns = ["*_dialog", "*_modal", "*Sheet", "Shortcuts*"]

    # 1. Search for explicit dialog/modal nodes that are visible
    for node in tree.nodes:
        if not node.visible:
            continue
        nid = node.id
        is_pattern_modal = nid.lower() not in SCREEN_LEVEL_SHEETS and any(
            fnmatch.fnmatchcase(nid.lower(), pat.lower()) for pat in modal_patterns
        )
        if (nid in overlay_ids and ("dialog" in nid.lower() or "sheet" in nid.lower() or "guide" in nid.lower() or "tutorial" in nid.lower())) or is_pattern_modal:
            if node.w > 0 and node.h > 0:
                return nid

    # 2. Check for nodes explicitly marked overlay=True that have positive area
    for node in tree.nodes:
        if node.overlay and node.visible and (node.w > 100 and node.h > 100):
            has_interactive = any(child.parent == node.id and child.interactive for child in by_id.values())
            if has_interactive or "dialog" in node.id.lower():
                return node.id

    return None


def identify_screen_mode(
    tree: Tree,
    product: str,
    rules: Dict[str, Any],
    by_id: Dict[str, Node],
    active_modal: Optional[str] = None,
) -> str:
    """Determine the active screen mode across all four active products."""
    norm_product = normalize_product_name(product)
    node_ids = set(by_id.keys())

    # If an active modal is blocking, mode is "dialog"
    if active_modal:
        return "dialog"

    if norm_product == "phototok":
        if any(nid in node_ids for nid in ("landing_logo", "landing_phone_camera_card", "landing_custom_folder_card", "card_camera", "card_sd", "card_folder")):
            return "landing"
        if "empty_folder_warning_card" in node_ids or "empty_folder_icon" in node_ids:
            return "empty"
        if any(nid in node_ids for nid in ("selection_tab_favourites", "selection_tab_selection", "selection_title")):
            return "selection"
        if "PhoneSettingsSheet" in node_ids or "settings_title" in node_ids:
            return "settings"
        if any(nid in node_ids for nid in ("viewer.photo", "phone_top_bar", "viewer_bottom_bar", "btn_keep", "btn_discard")):
            return "viewer"

    elif norm_product == "android-desktop":
        if any(nid in node_ids for nid in ("learn_tab_bar", "composition_overlay_canvas", "back_to_cull_button")):
            return "inspect"
        if "fullscreen_close" in node_ids or "zoom_navigator" in node_ids:
            return "fullscreen"
        if any(nid in node_ids for nid in ("image_region", "selector.frame.current", "selector.frame.previous", "selector.frame.next", "move_button", "position_counter")):
            return "selector"
        if "sidebar_open_folder" in node_ids and not any("frame" in nid for nid in node_ids):
            return "empty"

    elif norm_product == "macos-desktop":
        if "EmptyDropTargetView" in node_ids or "empty_drop_target" in node_ids:
            return "empty"
        if "FolderLoadingView" in node_ids:
            return "loading"
        if any(nid in node_ids for nid in ("single_image_viewport", "slot_current_image", "slot_previous_image", "slot_current")):
            return "viewer"
        if "DuplicateFinderView" in node_ids:
            return "duplicates"
        if "LibraryStatisticsView" in node_ids:
            return "statistics"
        if "SettingsView" in node_ids:
            return "settings"

    elif norm_product == "linux-desktop":
        if any(nid in node_ids for nid in ("slot_current", "slot_current_image", "btn_move", "btn_copy")):
            return "viewer"
        if "btn_open_folder" in node_ids and not any("slot" in nid for nid in node_ids):
            return "empty"
        if "AdwPreferencesWindow" in node_ids:
            return "settings"
        if "DuplicateFinderDialog" in node_ids:
            return "duplicates"
        if "LibraryStatisticsDialog" in node_ids:
            return "statistics"
        if "GtkShortcutsWindow" in node_ids:
            return "shortcuts"

    # Generic heuristics, in tree order: the first node that names a mode decides, so the same
    # tree gives the same mode in every process (a set would iterate in hash-seed order).
    for node in tree.nodes:
        nid = node.id.lower()
        if "inspect" in nid or "learn" in nid:
            return "inspect"
        if "viewer" in nid or "photo" in nid or "frame" in nid:
            return "viewer"
        if "empty" in nid:
            return "empty"

    return "unknown"


def classify_screen(tree: Tree, product: str) -> str:
    """The screen mode of `tree`, by the same rules `inspect_screen` uses (for the drivers)."""
    norm = normalize_product_name(product)
    rules = load_discovery_rules(norm)
    by_id = {node.id: node for node in tree.nodes}
    return identify_screen_mode(tree, norm, rules, by_id, detect_active_modal(tree, rules, by_id))


def enumerate_interactive_elements(
    tree: Tree,
    product: str,
    rules: Dict[str, Any],
    by_id: Dict[str, Node],
    active_modal: Optional[str],
    screen_mode: str,
    warnings: List[str],
    allow_folder_picker: bool = False,
) -> Tuple[List[CandidateInteraction], List[Node]]:
    """Enumerate taps and clicks on qualified visible interactive controls.

    Never offered: `NEVER_OFFERED_IDS` (the real camera roll) and, unless `allow_folder_picker`,
    the `FOLDER_PICKER_IDS` controls.
    """
    norm_product = normalize_product_name(product)
    is_android = norm_product in ("phototok", "android-desktop")
    action_type = "tap" if is_android else "click"
    min_target = float(rules.get("min_target", 48.0 if is_android else 24.0))

    candidates: List[CandidateInteraction] = []
    qualified_nodes: List[Node] = []

    try:
        w_val = float(tree.window_w)
        win_w = 0.0 if math.isnan(w_val) or math.isinf(w_val) or w_val <= 0 else w_val
    except (TypeError, ValueError):
        win_w = 0.0
    try:
        h_val = float(tree.window_h)
        win_h = 0.0 if math.isnan(h_val) or math.isinf(h_val) or h_val <= 0 else h_val
    except (TypeError, ValueError):
        win_h = 0.0

    for node in tree.nodes:
        if not node.interactive or not node.visible:
            continue
        if node.id in NEVER_OFFERED_IDS or (not allow_folder_picker and node.id in FOLDER_PICKER_IDS):
            continue

        # Filter out nodes with corrupted NaN, Inf or invalid bounds
        if not node.bounds or len(node.bounds) != 4:
            continue
        if any(not isinstance(v, (int, float)) or math.isnan(v) or math.isinf(v) for v in node.bounds):
            continue

        # 1. Clipped node filtering via ancestor intersections
        v_rect = visible_bounds(node, by_id)
        if v_rect is None or v_rect[2] <= 0 or v_rect[3] <= 0:
            continue
        if any(not isinstance(v, (int, float)) or math.isnan(v) or math.isinf(v) for v in v_rect):
            continue

        # 2. Viewport boundary clipping
        if win_w > 0 and win_h > 0:
            v_rect = intersection_rect(v_rect, [0.0, 0.0, win_w, win_h])
            if v_rect is None or v_rect[2] <= 0 or v_rect[3] <= 0:
                continue
        elif v_rect[0] < 0 or v_rect[1] < 0 or v_rect[2] <= 0 or v_rect[3] <= 0:
            continue

        # Check cut_by_scroll
        if cut_by_scroll(node, by_id):
            if v_rect[3] < 12.0:  # less than 12 dp/px remaining visible height
                warnings.append(f"Node {node.id!r} cut by scroll below minimum usable surface.")
                continue

        # 3. Minimum target floor filtering (with touch tolerance on visible clipped bounds)
        if v_rect[2] < min_target - TOUCH_TOLERANCE or v_rect[3] < min_target - TOUCH_TOLERANCE:
            continue

        # 3. Active modal scrim / overlay containment
        is_modal_element = False
        if active_modal:
            if not (node.id == active_modal or is_ancestor(active_modal, node, by_id) or node.overlay):
                # Background node behind modal scrim -> exclude
                continue
            is_modal_element = True

        qualified_nodes.append(node)

        # 4. Compute center interaction coordinates
        cx = round(v_rect[0] + v_rect[2] / 2.0, 1)
        cy = round(v_rect[1] + v_rect[3] / 2.0, 1)
        label_text = _find_node_label(node, by_id)
        action_label = f"{'Tap' if is_android else 'Click'} {label_text}"

        action = Action(
            action_type=action_type,
            target_id=node.id,
            params={"x": cx, "y": cy, "bounds": v_rect},
            label=action_label,
        )

        candidates.append(
            CandidateInteraction(
                action=action,
                category="element",
                description=f"{action_type.capitalize()} on control {node.id} ({label_text})",
                target_node=node,
                target_bounds=v_rect,
                weight=1.0,
                screen_mode=screen_mode,
                is_modal_action=is_modal_element,
            )
        )

    return candidates, qualified_nodes


def enumerate_gestures(
    tree: Tree,
    product: str,
    rules: Dict[str, Any],
    by_id: Dict[str, Node],
    active_modal: Optional[str],
    screen_mode: str,
) -> List[CandidateInteraction]:
    """Enumerate gestures appropriate for the active product and screen mode."""
    if active_modal:
        return []  # Gestures suppressed while modal is active

    norm_product = normalize_product_name(product)
    try:
        w_raw = float(tree.window_w)
        h_raw = float(tree.window_h)
        win_w = 1080.0 if math.isnan(w_raw) or math.isinf(w_raw) or w_raw <= 0 else max(w_raw, 360.0)
        win_h = 1920.0 if math.isnan(h_raw) or math.isinf(h_raw) or h_raw <= 0 else max(h_raw, 640.0)
    except (TypeError, ValueError):
        win_w, win_h = 1080.0, 1920.0

    candidates: List[CandidateInteraction] = []

    # --- Jackpix Gestures ---
    if norm_product == "phototok" and screen_mode in ("viewer", "unknown"):
        photo_node = by_id.get("viewer.photo")
        px = photo_node.x if photo_node else 0.0
        py = photo_node.y if photo_node else 0.0
        pw = photo_node.w if photo_node else win_w
        ph = photo_node.h if photo_node else win_h
        cx = int(max(0.0, min(win_w, px + pw / 2.0)))
        cy = int(max(0.0, min(win_h, py + ph / 2.0)))

        # 1. Vertical Paging (Swipe Up / Down)
        v_start_y = int(max(0.0, min(win_h, py + ph * 0.75)))
        v_end_y = int(max(0.0, min(win_h, py + ph * 0.25)))
        candidates.append(
            CandidateInteraction(
                action=Action(
                    action_type="swipe",
                    target_id="viewer.photo",
                    params={
                        "direction": "up",
                        "start_x": cx,
                        "start_y": v_start_y,
                        "end_x": cx,
                        "end_y": v_end_y,
                        "x1": cx,
                        "y1": v_start_y,
                        "x2": cx,
                        "y2": v_end_y,
                        "duration_ms": 250,
                    },
                    label="Swipe Up (Next Photo)",
                ),
                category="gesture",
                description="Vertical swipe up to page next photo in feed",
                weight=1.0,
                screen_mode=screen_mode,
            )
        )
        candidates.append(
            CandidateInteraction(
                action=Action(
                    action_type="swipe",
                    target_id="viewer.photo",
                    params={
                        "direction": "down",
                        "start_x": cx,
                        "start_y": v_end_y,
                        "end_x": cx,
                        "end_y": v_start_y,
                        "x1": cx,
                        "y1": v_end_y,
                        "x2": cx,
                        "y2": v_start_y,
                        "duration_ms": 250,
                    },
                    label="Swipe Down (Previous Photo)",
                ),
                category="gesture",
                description="Vertical swipe down to page previous photo in feed",
                weight=1.0,
                screen_mode=screen_mode,
            )
        )

        # 2. Horizontal Culling Swipes (Keep / Discard)
        k_start_x = int(max(0.0, min(win_w, px + pw * 0.25)))
        k_end_x = int(max(0.0, min(win_w, px + pw * 0.85)))
        candidates.append(
            CandidateInteraction(
                action=Action(
                    action_type="swipe",
                    target_id="viewer.photo",
                    params={
                        "direction": "right",
                        "start_x": k_start_x,
                        "start_y": cy,
                        "end_x": k_end_x,
                        "end_y": cy,
                        "x1": k_start_x,
                        "y1": cy,
                        "x2": k_end_x,
                        "y2": cy,
                        "duration_ms": 250,
                    },
                    label="Swipe Right (Keep/Selection)",
                ),
                category="gesture",
                description="Horizontal swipe right to keep candidate in Selection/",
                weight=1.0,
                screen_mode=screen_mode,
            )
        )
        d_start_x = int(max(0.0, min(win_w, px + pw * 0.75)))
        d_end_x = int(max(0.0, min(win_w, px + pw * 0.15)))
        candidates.append(
            CandidateInteraction(
                action=Action(
                    action_type="swipe",
                    target_id="viewer.photo",
                    params={
                        "direction": "left",
                        "start_x": d_start_x,
                        "start_y": cy,
                        "end_x": d_end_x,
                        "end_y": cy,
                        "x1": d_start_x,
                        "y1": cy,
                        "x2": d_end_x,
                        "y2": cy,
                        "duration_ms": 250,
                    },
                    label="Swipe Left (Discard/Delete)",
                ),
                category="gesture",
                description="Horizontal swipe left to discard/trash candidate photo",
                weight=1.0,
                screen_mode=screen_mode,
            )
        )

        # 3. Double-tap Zoom Toggle
        candidates.append(
            CandidateInteraction(
                action=Action(
                    action_type="tap",
                    target_id="viewer.photo",
                    params={"x": cx, "y": cy, "double_tap": True, "count": 2},
                    label="Double-tap Zoom Toggle",
                ),
                category="gesture",
                description="Double tap feed photo to toggle zoom",
                weight=0.5,
                screen_mode=screen_mode,
            )
        )

    # --- Frameduel Gestures ---
    elif norm_product == "android-desktop" and screen_mode in ("selector", "viewer", "inspect", "three_up", "unknown"):
        # 1. 1-over-2 Frame Taps
        if "selector.frame.previous" in by_id:
            fn = by_id["selector.frame.previous"]
            if fn.visible and fn.w > 0 and fn.h > 0:
                candidates.append(
                    CandidateInteraction(
                        action=Action(
                            action_type="tap",
                            target_id="selector.frame.previous",
                            params={"x": round(fn.x + fn.w / 2.0, 1), "y": round(fn.y + fn.h / 2.0, 1)},
                            label="Tap Previous Frame (Navigate Prev)",
                        ),
                        category="gesture",
                        description="Tap left neighbour frame to select previous photo",
                        weight=1.0,
                        screen_mode=screen_mode,
                    )
                )
        if "selector.frame.next" in by_id:
            fn = by_id["selector.frame.next"]
            if fn.visible and fn.w > 0 and fn.h > 0:
                candidates.append(
                    CandidateInteraction(
                        action=Action(
                            action_type="tap",
                            target_id="selector.frame.next",
                            params={"x": round(fn.x + fn.w / 2.0, 1), "y": round(fn.y + fn.h / 2.0, 1)},
                            label="Tap Next Frame (Navigate Next)",
                        ),
                        category="gesture",
                        description="Tap right neighbour frame to select next photo",
                        weight=1.0,
                        screen_mode=screen_mode,
                    )
                )

        # 2. Horizontal Navigation Swipes across Image Region
        img_node = by_id.get("image_region") or by_id.get("selector.frame.current")
        if img_node and img_node.visible and img_node.w > 0 and img_node.h > 0:
            cx = int(max(0.0, min(win_w, img_node.x + img_node.w / 2.0)))
            cy = int(max(0.0, min(win_h, img_node.y + img_node.h / 2.0)))
            sl_start_x = int(max(0.0, min(win_w, img_node.x + img_node.w * 0.8)))
            sl_end_x = int(max(0.0, min(win_w, img_node.x + img_node.w * 0.2)))
            candidates.append(
                CandidateInteraction(
                    action=Action(
                        action_type="swipe",
                        target_id=img_node.id,
                        params={
                            "direction": "left",
                            "start_x": sl_start_x,
                            "start_y": cy,
                            "end_x": sl_end_x,
                            "end_y": cy,
                            "x1": sl_start_x,
                            "y1": cy,
                            "x2": sl_end_x,
                            "y2": cy,
                            "duration_ms": 200,
                        },
                        label="Swipe Left (Next Photo)",
                    ),
                    category="gesture",
                    description="Swipe left across active frame to advance next",
                    weight=0.8,
                    screen_mode=screen_mode,
                )
            )
            sr_start_x = int(max(0.0, min(win_w, img_node.x + img_node.w * 0.2)))
            sr_end_x = int(max(0.0, min(win_w, img_node.x + img_node.w * 0.8)))
            candidates.append(
                CandidateInteraction(
                    action=Action(
                        action_type="swipe",
                        target_id=img_node.id,
                        params={
                            "direction": "right",
                            "start_x": sr_start_x,
                            "start_y": cy,
                            "end_x": sr_end_x,
                            "end_y": cy,
                            "x1": sr_start_x,
                            "y1": cy,
                            "x2": sr_end_x,
                            "y2": cy,
                            "duration_ms": 200,
                        },
                        label="Swipe Right (Previous Photo)",
                    ),
                    category="gesture",
                    description="Swipe right across active frame to navigate previous",
                    weight=0.8,
                    screen_mode=screen_mode,
                )
            )

    # --- Desktop Viewport Slot Gestures / Clicks ---
    elif norm_product in ("macos-desktop", "linux-desktop") and screen_mode in ("viewer", "workspace", "selector", "unknown"):
        # A slot is clicked only where the app says it takes clicks: Blitzlens' slot frames are
        # plain containers (no click handler), so a click there navigates nowhere by design.
        def clickable(*ids: str) -> Optional[Node]:
            return next((by_id[i] for i in ids if i in by_id and by_id[i].interactive), None)

        prev_slot = clickable("slot_previous", "slot_previous_image")
        next_slot = clickable("slot_next", "slot_next_image")
        cur_slot = clickable("slot_current", "slot_current_image", "single_image_viewport")

        if prev_slot and prev_slot.visible and prev_slot.w > 0:
            candidates.append(
                CandidateInteraction(
                    action=Action(
                        action_type="click",
                        target_id=prev_slot.id,
                        params={"x": round(prev_slot.x + prev_slot.w / 2.0, 1), "y": round(prev_slot.y + prev_slot.h / 2.0, 1)},
                        label="Click Previous Slot (Activate)",
                    ),
                    category="gesture",
                    description="Click previous neighbour slot in multi-up view",
                    weight=1.0,
                    screen_mode=screen_mode,
                )
            )
        if next_slot and next_slot.visible and next_slot.w > 0:
            candidates.append(
                CandidateInteraction(
                    action=Action(
                        action_type="click",
                        target_id=next_slot.id,
                        params={"x": round(next_slot.x + next_slot.w / 2.0, 1), "y": round(next_slot.y + next_slot.h / 2.0, 1)},
                        label="Click Next Slot (Activate)",
                    ),
                    category="gesture",
                    description="Click next neighbour slot in multi-up view",
                    weight=1.0,
                    screen_mode=screen_mode,
                )
            )
        if cur_slot and cur_slot.visible and cur_slot.w > 0:
            cx = round(cur_slot.x + cur_slot.w / 2.0, 1)
            cy = round(cur_slot.y + cur_slot.h / 2.0, 1)
            candidates.append(
                CandidateInteraction(
                    action=Action(
                        action_type="click",
                        target_id=cur_slot.id,
                        params={"x": cx, "y": cy, "double_click": True, "count": 2},
                        label="Double-click Slot (1:1 Zoom Toggle)",
                    ),
                    category="gesture",
                    description="Double click active slot to toggle 1:1 actual pixel zoom",
                    weight=0.5,
                    screen_mode=screen_mode,
                )
            )

    return candidates


def _shortcut(spec: str) -> Dict[str, Any]:
    """`"Ctrl+O"` as the key action params: key and modifiers apart, the spelling kept for reports."""
    key, mods = parse_key(spec)
    key = key.lower() if len(key) == 1 else key
    return {"key": key, "modifiers": list(_MODIFIER_PARAM[m] for m in mods), "shortcut": spec}


# `parse_key` normalises modifiers to macOS-style names; the actions carry the short spellings.
_MODIFIER_PARAM = {"command": "cmd", "control": "ctrl", "shift": "shift", "option": "alt", "super": "super"}


def has_focused_input(tree: Tree) -> bool:
    """A text input holds the keyboard focus (nodes of a tree without focus data never do)."""
    return any(
        node.role == "input" and node.visible and getattr(node, "focused", False) for node in tree.nodes
    )


def enumerate_keyboard_shortcuts(
    tree: Tree,
    product: str,
    active_modal: Optional[str],
    screen_mode: str,
    by_id: Dict[str, Node],
    allow_folder_picker: bool = False,
) -> List[CandidateInteraction]:
    """Enumerate hardware keyboard shortcuts appropriate for the platform and modal state.

    Shortcuts with a modifier are key + modifiers (`{"key": "o", "modifiers": ["ctrl"]}`), never
    one string a driver would have to split.
    """
    norm_product = normalize_product_name(product)
    is_macos = norm_product == "macos-desktop"
    cmd_mod = "Cmd" if is_macos else "Ctrl"
    candidates: List[CandidateInteraction] = []

    # A focused text input takes every key: only Escape gets out of it. An input that is merely
    # on screen leaves the shortcuts alone.
    if has_focused_input(tree):
        return [
            CandidateInteraction(
                action=Action(action_type="key", params={"key": "Escape"}, label="Key Escape (Exit Focus)"),
                category="key",
                description="Escape key to dismiss focus from text input",
                weight=1.0,
                screen_mode=screen_mode,
            )
        ]

    # Modal Active Keyboard Handling
    if active_modal:
        candidates.append(
            CandidateInteraction(
                action=Action(action_type="key", params={"key": "Escape"}, label="Key Escape (Dismiss Modal)"),
                category="key",
                description="Escape key to dismiss active dialog or overlay",
                weight=1.0,
                screen_mode=screen_mode,
                is_modal_action=True,
            )
        )
        candidates.append(
            CandidateInteraction(
                action=Action(action_type="key", params={"key": "Return"}, label="Key Return (Confirm Modal)"),
                category="key",
                description="Return key to confirm active dialog action",
                weight=1.0,
                screen_mode=screen_mode,
                is_modal_action=True,
            )
        )
        candidates.append(
            CandidateInteraction(
                action=Action(action_type="key", params={"key": "Tab"}, label="Key Tab (Next Focus)"),
                category="key",
                description="Tab key to cycle focus inside active dialog",
                weight=0.5,
                screen_mode=screen_mode,
                is_modal_action=True,
            )
        )
        return candidates

    # Jackpix is gesture-first and handles no keys of its own: a desktop shortcut there would be
    # judged against a copy or move the app never offers.
    if norm_product == "phototok":
        return candidates

    # Primary Viewport Shortcuts
    if screen_mode in ("viewer", "selector", "inspect", "workspace", "three_up", "unknown"):
        # 1. Navigation (Arrows & Vim Keys)
        nav_keys = [
            ("Right", "Arrow Right (Next Photo)", 1.0),
            ("Left", "Arrow Left (Previous Photo)", 1.0),
            ("Down", "Arrow Down (Next Photo)", 0.8),
            ("Up", "Arrow Up (Previous Photo)", 0.8),
            ("L", "Vim L (Next Photo)", 0.9),
            ("H", "Vim H (Previous Photo)", 0.9),
            ("J", "Vim J (Next Triplet/Row)", 0.7),
            ("K", "Vim K (Previous Triplet/Row)", 0.7),
        ]
        for key, desc, wt in nav_keys:
            candidates.append(
                CandidateInteraction(
                    action=Action(action_type="key", params={"key": key}, label=f"Key {key}"),
                    category="key",
                    description=desc,
                    weight=wt,
                    screen_mode=screen_mode,
                )
            )

        # 2. Culling Actions
        cull_keys = [
            ("M", "Move Active Photo to Selection", 1.0),
            ("C", "Copy Active Photo to Selection", 1.0),
            ("Delete", "Move Active Photo to Trash", 1.0),
        ]
        for key, desc, wt in cull_keys:
            candidates.append(
                CandidateInteraction(
                    action=Action(action_type="key", params={"key": key}, label=f"Key {key} ({desc})"),
                    category="key",
                    description=desc,
                    weight=wt,
                    screen_mode=screen_mode,
                )
            )

        # 3. Layout Switching (Desktop / Tablet multi-up)
        if norm_product in ("android-desktop", "macos-desktop", "linux-desktop"):
            layout_keys = [
                ("1", "Switch to 1-Up Single Frame", 0.7),
                ("2", "Switch to 2-Up Champion/Challenger", 0.7),
                ("3", "Switch to 3-Up Focus Mode (One-over-Two)", 0.7),
            ]
            for key, desc, wt in layout_keys:
                candidates.append(
                    CandidateInteraction(
                        action=Action(action_type="key", params={"key": key}, label=f"Key {key} ({desc})"),
                        category="key",
                        description=desc,
                        weight=wt,
                        screen_mode=screen_mode,
                    )
                )

        # 4. View Toggles & Inspection
        view_keys = [
            ("Space", "Toggle 1:1 Actual Pixel Zoom", 0.8),
            ("Escape", "Reset Zoom / Dismiss Guidance", 0.8),
            ("I", "Toggle Info / Optical Goodness HUD", 0.6),
            ("F", "Toggle Filmstrip Drawer", 0.6),
            ("S", "Toggle Synchronized Pan", 0.5),
            ("?", "Open Shortcuts / Coach Help", 0.5),
        ]
        for key, desc, wt in view_keys:
            candidates.append(
                CandidateInteraction(
                    action=Action(action_type="key", params={"key": key}, label=f"Key {key}"),
                    category="key",
                    description=desc,
                    weight=wt,
                    screen_mode=screen_mode,
                )
            )

        # 5. Undo
        undo_key = f"{cmd_mod}+Z"
        candidates.append(
            CandidateInteraction(
                action=Action(action_type="key", params=_shortcut(f"{cmd_mod}+Z"), label=f"Key {undo_key} (Undo)"),
                category="key",
                description="Undo last culling operation",
                weight=0.8,
                screen_mode=screen_mode,
            )
        )

    # 6. Global Utility Shortcuts
    util_keys = [
        (f"{cmd_mod}+O", "Open Folder Picker Dialog", 0.4),
        (f"{cmd_mod}+D", "Open Duplicate Finder", 0.4),
        (f"{cmd_mod}+L", "Open Library Statistics", 0.4),
        (f"{cmd_mod}+,", "Open Settings / Preferences", 0.4),
    ]
    for key, desc, wt in util_keys:
        if key.endswith("+O") and not allow_folder_picker:
            continue
        candidates.append(
            CandidateInteraction(
                action=Action(action_type="key", params=_shortcut(key), label=f"Key {key}"),
                category="key",
                description=desc,
                weight=wt,
                screen_mode=screen_mode,
            )
        )

    return candidates


def enumerate_lifecycle_actions(
    tree: Tree,
    product: str,
    active_modal: Optional[str],
    screen_mode: str,
    is_tablet: bool = False,
    is_freeform: bool = False,
) -> List[CandidateInteraction]:
    """Enumerate lifecycle operations: back navigation, background/resume, resize, rotate."""
    norm_product = normalize_product_name(product)
    candidates: List[CandidateInteraction] = []

    # 1. Back Navigation
    candidates.append(
        CandidateInteraction(
            action=Action(action_type="back", label="System Back Navigation"),
            category="lifecycle",
            description="System back key event (dismiss modal or return to previous view)",
            weight=0.4 if active_modal else 0.2,
            screen_mode=screen_mode,
            is_modal_action=bool(active_modal),
        )
    )

    # 2. App Backgrounding and Resume (a desktop window has no background state to resume from)
    if norm_product in ("phototok", "android-desktop"):
        candidates.append(
            CandidateInteraction(
                action=Action(action_type="background", params={"duration_s": 1.0}, label="Background App & Resume"),
                category="lifecycle",
                description="Send application to background for 1s then resume",
                weight=0.2,
                screen_mode=screen_mode,
            )
        )

    # 3. Window Resize (Desktop or Android DeX / ChromeOS Freeform)
    if norm_product in ("macos-desktop", "linux-desktop") or is_freeform:
        try:
            w_raw = float(tree.window_w)
            h_raw = float(tree.window_h)
            cur_w = 1280.0 if math.isnan(w_raw) or math.isinf(w_raw) or w_raw <= 0 else w_raw
            cur_h = 800.0 if math.isnan(h_raw) or math.isinf(h_raw) or h_raw <= 0 else h_raw
        except (TypeError, ValueError):
            cur_w, cur_h = 1280.0, 800.0
        alt_w = int(cur_w * 0.85) if cur_w > 1000 else int(cur_w * 1.15)
        alt_h = int(cur_h * 0.85) if cur_h > 700 else int(cur_h * 1.15)
        candidates.append(
            CandidateInteraction(
                action=Action(
                    action_type="resize",
                    params={"width": alt_w, "height": alt_h, "w": alt_w, "h": alt_h},
                    label=f"Resize Window to {alt_w}x{alt_h}",
                ),
                category="lifecycle",
                description="Resize application window bounds",
                weight=0.2,
                screen_mode=screen_mode,
            )
        )

    # 4. Device Orientation Rotation (Android Phone / Tablet)
    if norm_product in ("phototok", "android-desktop"):
        candidates.append(
            CandidateInteraction(
                action=Action(
                    action_type="rotate",
                    params={"toggle": True, "orientation": "landscape"},
                    label="Rotate Screen Orientation",
                ),
                category="lifecycle",
                description="Toggle screen rotation between portrait and landscape",
                weight=0.2,
                screen_mode=screen_mode,
            )
        )

    return candidates


def inspect_screen(
    tree: Tree,
    product: str,
    rules: Optional[Dict[str, Any]] = None,
    is_tablet: bool = False,
    is_freeform: bool = False,
    allow_folder_picker: bool = False,
) -> ScreenInspectionResult:
    """Inspect live screen state, classify screen mode, and enumerate candidate interactions.

    `allow_folder_picker` offers the controls and the shortcut that open the system folder
    picker; leave it off on a personal device.
    """
    norm_product = normalize_product_name(product)
    active_rules = rules if rules is not None else load_discovery_rules(norm_product)
    by_id = {node.id: node for node in tree.nodes}
    warnings: List[str] = []

    # 1. Detect Active Modal / Dialog / Scrim
    active_modal = detect_active_modal(tree, active_rules, by_id)

    # 2. Identify Active Screen Mode
    screen_mode = identify_screen_mode(tree, norm_product, active_rules, by_id, active_modal)

    # 3. Enumerate Interactive Elements
    elem_candidates, interactive_nodes = enumerate_interactive_elements(
        tree, norm_product, active_rules, by_id, active_modal, screen_mode, warnings, allow_folder_picker
    )

    # 4. Enumerate Gestures
    gesture_candidates = enumerate_gestures(tree, norm_product, active_rules, by_id, active_modal, screen_mode)

    # 5. Enumerate Keyboard Shortcuts
    key_candidates = enumerate_keyboard_shortcuts(
        tree, norm_product, active_modal, screen_mode, by_id, allow_folder_picker
    )

    # 6. Enumerate Lifecycle Actions
    lifecycle_candidates = enumerate_lifecycle_actions(tree, norm_product, active_modal, screen_mode, is_tablet, is_freeform)

    all_candidates = elem_candidates + gesture_candidates + key_candidates + lifecycle_candidates
    if not tree.nodes:
        # Nothing on screen is known (a window that exports no nodes, such as Blitzlens'
        # Duplicate Finder): any other key would be aimed at a screen we cannot see, and its
        # expectation would be about the main window. Only leaving it is a sound step.
        all_candidates = [c for c in lifecycle_candidates if c.action.action_type == "back"]
        warnings.append("empty tree: only back is offered")

    return ScreenInspectionResult(
        product=norm_product,
        screen_mode=screen_mode,
        active_modal=active_modal,
        candidate_interactions=all_candidates,
        interactive_nodes=interactive_nodes,
        tree=tree,
        warnings=warnings,
    )
