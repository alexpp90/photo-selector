"""Action data models and categorization for exploratory interaction testing.

Conforms to PROJECT.md interface contracts (M1 <-> M2) while providing
deterministic hashing, equality, and JSON serialization.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional, Tuple

from ..model import Node, Tree


class ActionCategory(str, Enum):
    """Broad categories for exploratory action weighting and distribution."""

    TAP = "tap"              # Interactive element taps, clicks, long presses
    GESTURE = "gesture"      # Viewport swipes, scrolls, pinches, pans
    KEYBOARD = "keyboard"    # Hardware keys and shortcuts
    LIFECYCLE = "lifecycle"  # Navigation back, background/resume, resize, rotate


@dataclass(frozen=True)
class Action:
    """An executable interaction on a target product screen.

    Conforms to PROJECT.md § Interface Contracts.
    Uses custom __hash__ and __eq__ to handle dict params without TypeError: unhashable type: 'dict'.
    """

    action_type: str  # "tap", "click", "swipe", "key", "back", "background", "resize", "rotate"
    target_id: Optional[str] = None
    params: Dict[str, Any] = field(default_factory=dict)
    label: str = ""

    def canonical_key(self) -> Tuple[str, str, str, str]:
        """Deterministic tie-breaking key for canonical sorting, equality, and hashing."""
        params_str = json.dumps(self.params, sort_keys=True, default=str) if self.params else ""
        return (self.action_type, self.target_id or "", params_str, self.label or "")

    @property
    def signature(self) -> str:
        """Compact string signature for deduplication and logging (e.g. 'tap:btn_cull')."""
        target = self.target_id or self.params.get("key") or self.params.get("direction") or ""
        return f"{self.action_type}:{target}" if target else self.action_type

    def __hash__(self) -> int:
        return hash(self.canonical_key())

    def __eq__(self, other: object) -> bool:
        if not isinstance(other, Action):
            return False
        return self.canonical_key() == other.canonical_key()

    def summary(self) -> str:
        """Human-readable summary string."""
        tgt = f" on {self.target_id}" if self.target_id else ""
        return f"[{self.action_type}]{tgt} {self.label}".strip()

    def to_dict(self) -> Dict[str, Any]:
        """Serialize action to JSON-compatible dictionary."""
        data: Dict[str, Any] = {"action_type": self.action_type}
        if self.target_id is not None:
            data["target_id"] = self.target_id
        if self.params:
            data["params"] = dict(self.params)
        if self.label:
            data["label"] = self.label
        return data

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> Action:
        """Deserialize action from dictionary."""
        return cls(
            action_type=str(data["action_type"]),
            target_id=data.get("target_id"),
            params=dict(data.get("params", {})),
            label=str(data.get("label", "")),
        )


def categorize_action(action: Action) -> ActionCategory:
    """Map an Action to its high-level ActionCategory."""
    act_type = action.action_type.lower()
    if act_type in ("tap", "click", "long_press", "double_tap"):
        return ActionCategory.TAP
    if act_type in ("swipe", "scroll", "pinch_zoom", "drag_pan"):
        return ActionCategory.GESTURE
    if act_type in ("key", "hotkey", "shortcut"):
        return ActionCategory.KEYBOARD
    if act_type in ("back", "background", "resume", "resize", "rotate"):
        return ActionCategory.LIFECYCLE
    return ActionCategory.TAP


@dataclass
class CandidateInteraction:
    """An enumerated candidate interaction available on the current screen."""

    action: Action
    category: str  # "element", "gesture", "key", "lifecycle" or ActionCategory value
    description: str = ""
    target_node: Optional[Node] = None
    target_bounds: Optional[List[float]] = None
    weight: float = 1.0
    screen_mode: str = ""
    is_modal_action: bool = False

    def canonical_key(self) -> Tuple[Any, ...]:
        bounds_tuple = tuple(self.target_bounds) if self.target_bounds else ()
        return (
            self.action.canonical_key(),
            self.category,
            self.description,
            bounds_tuple,
            self.weight,
            self.screen_mode,
            self.is_modal_action,
        )

    def __hash__(self) -> int:
        return hash(self.canonical_key())

    def __eq__(self, other: object) -> bool:
        if not isinstance(other, CandidateInteraction):
            return False
        return self.canonical_key() == other.canonical_key()

    def to_dict(self) -> Dict[str, Any]:
        """Serialize candidate interaction to dictionary."""
        return {
            "action": self.action.to_dict(),
            "category": self.category,
            "description": self.description,
            "target_bounds": list(self.target_bounds) if self.target_bounds else None,
            "weight": self.weight,
            "screen_mode": self.screen_mode,
            "is_modal_action": self.is_modal_action,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> CandidateInteraction:
        """Deserialize candidate interaction from dictionary."""
        return cls(
            action=Action.from_dict(data["action"]),
            category=str(data.get("category", "element")),
            description=str(data.get("description", "")),
            target_bounds=data.get("target_bounds"),
            weight=float(data.get("weight", 1.0)),
            screen_mode=str(data.get("screen_mode", "")),
            is_modal_action=bool(data.get("is_modal_action", False)),
        )


@dataclass
class ScreenState:
    """Live state captured from a device immediately before or after an action.

    Conforms to PROJECT.md § Interface Contracts: M1 <-> M2.
    """

    tree: Tree
    screenshot_bytes: bytes
    screen_mode: str  # e.g. "landing", "viewer", "selector", "inspect", "empty", "dialog"
    fs_snapshot: Dict[str, Any]  # Working directory file list & markers
    logs: List[str] = field(default_factory=list)
    # The app's own `(0-based index, photo count)`, when the driver reads it beside the tree
    # (Blitzlens writes it with every checkpoint); None where only the tree's counter can tell.
    position: Optional[Tuple[int, Optional[int]]] = None
