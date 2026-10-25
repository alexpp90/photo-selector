"""Pre-interaction expectation modeling and recording.

Establishes structured, deterministic state and oracle expectations
(screen transitions, photo positions, feed count deltas, counter deltas,
filesystem side-effects, and UI node invariants) before an action is executed.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from .actions import Action, ScreenState

# Standard UI node IDs representing abnormal runtime termination or errors
STANDARD_FORBIDDEN_UI_NODES: List[str] = [
    "crash_dialog",
    "anr_dialog",
    "error_banner",
]


@dataclass
class Expectation:
    """Explicit expectation recorded before an exploratory interaction settles.

    Conforms to PROJECT.md § Interface Contracts.
    Invariant: forbidden_ui_nodes must always include ["crash_dialog", "anr_dialog", "error_banner"].
    """

    action: Action
    description: str
    expected_index_delta: Optional[int] = None
    expected_feed_count_delta: Optional[int] = None
    expected_counter_deltas: Dict[str, int] = field(default_factory=dict)
    fs_expect: Dict[str, Any] = field(default_factory=dict)
    required_ui_nodes: List[str] = field(default_factory=list)
    forbidden_ui_nodes: List[str] = field(default_factory=list)
    ai_prompt: Optional[str] = None

    def __post_init__(self) -> None:
        # Guarantee invariant: standard error dialogs are forbidden across all interactions
        for node_id in STANDARD_FORBIDDEN_UI_NODES:
            if node_id not in self.forbidden_ui_nodes:
                self.forbidden_ui_nodes.append(node_id)

    def to_dict(self) -> Dict[str, Any]:
        """Serialize expectation to JSON-compatible dictionary."""
        if hasattr(self.action, "to_dict"):
            act_data = self.action.to_dict()
        else:
            act_data = {
                "action_type": getattr(self.action, "action_type", "tap"),
                "target_id": getattr(self.action, "target_id", None),
                "params": dict(getattr(self.action, "params", {})),
                "label": getattr(self.action, "label", ""),
            }
        return {
            "action": act_data,
            "description": self.description,
            "expected_index_delta": self.expected_index_delta,
            "expected_feed_count_delta": self.expected_feed_count_delta,
            "expected_counter_deltas": dict(self.expected_counter_deltas),
            "fs_expect": dict(self.fs_expect),
            "required_ui_nodes": list(self.required_ui_nodes),
            "forbidden_ui_nodes": list(self.forbidden_ui_nodes),
            "ai_prompt": self.ai_prompt,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> Expectation:
        """Deserialize expectation from dictionary."""
        return cls(
            action=Action.from_dict(data["action"]),
            description=str(data.get("description", "")),
            expected_index_delta=data.get("expected_index_delta"),
            expected_feed_count_delta=data.get("expected_feed_count_delta"),
            expected_counter_deltas=dict(data.get("expected_counter_deltas", {})),
            fs_expect=dict(data.get("fs_expect", {})),
            required_ui_nodes=list(data.get("required_ui_nodes", [])),
            forbidden_ui_nodes=list(data.get("forbidden_ui_nodes", [])),
            ai_prompt=data.get("ai_prompt"),
        )


class ExpectationRecorder:
    """Records structured expectations for candidate actions on a given screen state."""

    def __init__(self, product: str = "phototok") -> None:
        self.product = product

    def record(
        self,
        before_state: ScreenState,
        action: Action,
        active_photo: Optional[str] = None,
        feed_index: Optional[int] = None,
        feed_count: Optional[int] = None,
        history: Optional[List[Any]] = None,
    ) -> Expectation:
        """Record structured expectation for any selected exploratory interaction."""
        act_type = action.action_type.lower()
        key_name = str(action.params.get("key", "")).upper()
        modifiers = [str(m).lower() for m in action.params.get("modifiers", [])]

        # Check for undo interaction (Ctrl+Z / Cmd+Z / Undo button)
        is_undo = (
            (key_name == "Z" and any(m in ("ctrl", "control", "meta", "cmd") for m in modifiers))
            or (action.target_id and "undo" in action.target_id.lower())
            or ("undo" in action.label.lower())
        )
        if is_undo:
            return self.record_cull(
                before_state,
                action,
                active_photo=active_photo,
                cull_action="undo",
            )

        # Check for delete/trash actions BEFORE move (since "Move to Trash" contains "move")
        is_delete = (
            key_name in ("DEL", "DELETE", "BACKSPACE")
            or (action.target_id and any(d in action.target_id.lower() for d in ("discard", "btn_discard", "delete", "trash")))
            or ("discard" in action.label.lower() or "delete" in action.label.lower() or "trash" in action.label.lower())
            or (self.product == "phototok" and act_type == "swipe" and action.params.get("direction") == "left")
        )
        if is_delete:
            return self.record_cull(
                before_state,
                action,
                active_photo=active_photo,
                cull_action="delete",
            )

        # Check for cull actions (Move/Keep, Copy)
        is_move = (
            key_name == "M"
            or (action.target_id and any(k in action.target_id.lower() for k in ("keep", "btn_keep", "save", "move")))
            or ("keep" in action.label.lower() or "move" in action.label.lower())
            or (self.product == "phototok" and act_type == "swipe" and action.params.get("direction") == "right")
        )
        if is_move:
            return self.record_cull(
                before_state,
                action,
                active_photo=active_photo,
                cull_action="move",
            )

        is_copy = (
            key_name == "C"
            or (action.target_id and "copy" in action.target_id.lower())
            or ("copy" in action.label.lower())
        )
        if is_copy:
            return self.record_cull(
                before_state,
                action,
                active_photo=active_photo,
                cull_action="copy",
            )

        # Check for navigation actions (Swipes, directional arrows, Vim keys)
        nav_keys_next = {"RIGHT", "DOWN", "J", "L", "PAGE_DOWN", "SPACE"}
        nav_keys_prev = {"LEFT", "UP", "K", "H", "PAGE_UP"}

        is_nav = (
            act_type in ("swipe", "scroll")
            or key_name in nav_keys_next
            or key_name in nav_keys_prev
            or (action.target_id and any(n in action.target_id.lower() for n in ("next", "prev", "forward", "back")))
            or ("next" in action.label.lower() or "prev" in action.label.lower())
        )
        if is_nav:
            return self.record_navigation(
                before_state,
                action,
                feed_index=feed_index,
                feed_count=feed_count,
            )

        # Generic action (e.g. settings toggle, zoom, info toggle, dialog dismiss)
        desc = action.label or f"Execute {action.action_type}"
        return Expectation(
            action=action,
            description=desc,
            expected_index_delta=None,
            expected_feed_count_delta=0,
            fs_expect={"unchanged": True},
        )

    def record_navigation(
        self,
        before_state: ScreenState,
        action: Action,
        feed_index: Optional[int] = None,
        feed_count: Optional[int] = None,
    ) -> Expectation:
        """Record expectation for directional feed or photo navigation."""
        act_type = action.action_type.lower()
        key_name = str(action.params.get("key", "")).upper()
        direction = str(action.params.get("direction", "")).lower()
        label_lower = action.label.lower()

        # Determine forward vs backward direction
        is_prev = (
            direction in ("down", "left", "prev", "previous")
            or key_name in ("LEFT", "UP", "K", "H", "PAGE_UP")
            or "prev" in label_lower
            or "previous" in label_lower
        )

        if is_prev:
            # Moving backward
            if feed_index is not None and feed_index <= 0:
                # Boundary clamping at start of feed
                delta = 0
                desc = "At start of feed, navigation clamps to first photo without wrap"
            else:
                delta = -1
                desc = "Feed returns to previous photo; index decreases by 1"
        else:
            # Moving forward
            if feed_index is not None and feed_count is not None and feed_index >= feed_count - 1:
                # Boundary clamping at end of feed
                delta = 0
                desc = "At end of feed, navigation clamps to last photo without wrap"
            else:
                delta = 1
                desc = "Feed advances to next photo; index increases by 1"

        return Expectation(
            action=action,
            description=desc,
            expected_index_delta=delta,
            expected_feed_count_delta=0,
            fs_expect={"unchanged": True},
        )

    @staticmethod
    def _extract_active_photo(before_state: ScreenState) -> Optional[str]:
        """Try to bind the currently displayed photo filename from the UI tree and fs_snapshot."""
        fs_files = [
            str(f.get("path", ""))
            for f in (getattr(before_state, "fs_snapshot", None) or {}).get("files", [])
            if isinstance(f, dict) and f.get("path") and not str(f.get("path", "")).startswith("Selection/")
        ]
        if not fs_files:
            return None
        tree = getattr(before_state, "tree", None)
        if tree and hasattr(tree, "nodes"):
            for node in tree.nodes:
                text = f"{getattr(node, 'name', '') or ''} {getattr(node, 'text', '') or ''}"
                for fname in fs_files:
                    if fname in text:
                        return fname
        return None

    def record_cull(
        self,
        before_state: ScreenState,
        action: Action,
        active_photo: Optional[str] = None,
        cull_action: Optional[str] = None,
    ) -> Expectation:
        """Record expectation for culling interactions (move, copy, delete, undo)."""
        cull_type = (cull_action or "move").lower()
        bound_photo = active_photo or self._extract_active_photo(before_state)
        has_live_fs = bool((getattr(before_state, "fs_snapshot", None) or {}).get("files"))
        photo_name = bound_photo or (None if has_live_fs else "IMG_001.JPG")

        if cull_type == "move":
            return Expectation(
                action=action,
                description="Active photo moved to Selection/; feed count decreases by 1",
                expected_index_delta=0 if photo_name else None,
                expected_feed_count_delta=-1 if photo_name else None,
                expected_counter_deltas={"selected": 1},
                fs_expect={
                    "absent": [photo_name],
                    "present": [f"Selection/{photo_name}"],
                } if photo_name else {},
            )
        elif cull_type == "copy":
            return Expectation(
                action=action,
                description="Active photo copied to Selection/; feed count unchanged",
                expected_index_delta=1 if photo_name else None,
                expected_feed_count_delta=0 if photo_name else None,
                expected_counter_deltas={"selected": 1},
                fs_expect={
                    "present": [photo_name, f"Selection/{photo_name}"],
                } if photo_name else {},
            )
        elif cull_type == "delete":
            return Expectation(
                action=action,
                description="Active photo deleted; feed count decreases by 1",
                expected_index_delta=0 if photo_name else None,
                expected_feed_count_delta=-1 if photo_name else None,
                expected_counter_deltas={"trashed": 1},
                fs_expect={
                    "absent": [photo_name],
                    "trashed": [photo_name],
                } if photo_name else {},
            )
        elif cull_type == "undo":
            return Expectation(
                action=action,
                description="Restores previously culled photo back into feed",
                expected_index_delta=0 if photo_name else None,
                expected_feed_count_delta=1 if photo_name else None,
                expected_counter_deltas={"selected": -1},
                fs_expect={
                    "present": [photo_name],
                    "absent": [f"Selection/{photo_name}"],
                } if photo_name else {},
            )
        else:
            return Expectation(
                action=action,
                description=f"Cull action {cull_type}",
                expected_index_delta=0,
                expected_feed_count_delta=0,
                fs_expect={"unchanged": True},
            )


def record_navigation_expectation(
    before_state: ScreenState,
    action: Action,
    feed_index: Optional[int] = None,
    feed_count: Optional[int] = None,
) -> Expectation:
    """Convenience helper to record navigation expectation."""
    return ExpectationRecorder().record_navigation(
        before_state, action, feed_index=feed_index, feed_count=feed_count
    )


def record_cull_expectation(
    before_state: ScreenState,
    action: Action,
    active_photo: Optional[str] = None,
    cull_action: Optional[str] = None,
) -> Expectation:
    """Convenience helper to record culling expectation."""
    return ExpectationRecorder().record_cull(
        before_state, action, active_photo=active_photo, cull_action=cull_action
    )


def record_undo_expectation(
    before_state: ScreenState,
    action: Action,
    active_photo: Optional[str] = None,
) -> Expectation:
    """Convenience helper to record undo expectation."""
    return ExpectationRecorder().record_cull(
        before_state, action, active_photo=active_photo, cull_action="undo"
    )
