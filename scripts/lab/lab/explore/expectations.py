"""What an interaction should do, recorded before it runs.

The verifier compares the screen after the action with this record. An expectation only claims
what the recorder can tell from the screen it saw: with no photo bound to the action, no
filesystem or counter outcome is expected, so nothing is checked against a guess.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set, Tuple

from .actions import Action, ScreenState

# Standard UI node IDs representing abnormal runtime termination or errors
STANDARD_FORBIDDEN_UI_NODES: List[str] = [
    "crash_dialog",
    "anr_dialog",
    "error_banner",
]

# Test tags of the photo counter: Frameduel's control block, Jackpix's viewer HUD, the mock.
HUD_COUNTER_IDS = ("position_counter", "viewer.counter", "counter_hud")
_COUNTER_RE = re.compile(r"\s*(\d+)\s*/\s*(\d+)(\+?)\s*")

_CAMEL = re.compile(r"(?<=[a-z0-9])(?=[A-Z])")
_MODIFIER_UNDO = {"ctrl", "control", "meta", "cmd", "super"}
_KEYS_NEXT = {"RIGHT", "DOWN", "J", "L", "PAGE_DOWN", "PAGEDOWN", "SPACE"}
_KEYS_PREV = {"LEFT", "UP", "K", "H", "PAGE_UP", "PAGEUP"}
_KEYS_DELETE = {"DELETE", "DEL", "BACKSPACE"}


def read_counter(state: ScreenState) -> Optional[Tuple[int, Optional[int]]]:
    """`(0-based index, photo count)` from the HUD counter node, or None when it is not shown.

    Only the counter node is read, and only its whole text: `1/250` in an EXIF card is not a
    position. A count with a trailing `+` ("3 / 40+", the feed is still loading) has no count.
    """
    for node in state.tree.nodes:
        if node.id not in HUD_COUNTER_IDS:
            continue
        for text in (node.text, node.name):
            m = _COUNTER_RE.fullmatch(text or "")
            if m:
                return int(m.group(1)) - 1, (None if m.group(3) else int(m.group(2)))
    return None


def _words(*texts: Optional[str]) -> Set[str]:
    """Lower-case words of ids and labels: `btn_discard`, `moveToSelection` and "Move to" split alike."""
    words: Set[str] = set()
    for text in texts:
        if text:
            words.update(p.lower() for p in re.split(r"[^A-Za-z0-9]+", _CAMEL.sub(" ", text)) if p)
    return words


@dataclass
class Expectation:
    """What should be true after one action."""

    action: Action
    description: str
    expected_index_delta: Optional[int] = None
    expected_feed_count_delta: Optional[int] = None
    fs_expect: Dict[str, Any] = field(default_factory=dict)
    required_ui_nodes: List[str] = field(default_factory=list)
    forbidden_ui_nodes: List[str] = field(default_factory=list)

    def __post_init__(self) -> None:
        # Standard error dialogs are forbidden after every interaction.
        for node_id in STANDARD_FORBIDDEN_UI_NODES:
            if node_id not in self.forbidden_ui_nodes:
                self.forbidden_ui_nodes.append(node_id)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "action": self.action.to_dict(),
            "description": self.description,
            "expected_index_delta": self.expected_index_delta,
            "expected_feed_count_delta": self.expected_feed_count_delta,
            "fs_expect": dict(self.fs_expect),
            "required_ui_nodes": list(self.required_ui_nodes),
            "forbidden_ui_nodes": list(self.forbidden_ui_nodes),
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> Expectation:
        return cls(
            action=Action.from_dict(data["action"]),
            description=str(data.get("description", "")),
            expected_index_delta=data.get("expected_index_delta"),
            expected_feed_count_delta=data.get("expected_feed_count_delta"),
            fs_expect=dict(data.get("fs_expect", {})),
            required_ui_nodes=list(data.get("required_ui_nodes", [])),
            forbidden_ui_nodes=list(data.get("forbidden_ui_nodes", [])),
        )


class ExpectationRecorder:
    """Records the expectation of an action on the screen it is about to run on."""

    def __init__(self, product: str = "phototok") -> None:
        self.product = product

    def record(
        self,
        before_state: ScreenState,
        action: Action,
        active_photo: Optional[str] = None,
        feed_index: Optional[int] = None,
        feed_count: Optional[int] = None,
    ) -> Expectation:
        """The expectation of `action`; the feed position comes from `before_state` unless given."""
        if feed_index is None and feed_count is None:
            counter = read_counter(before_state)
            if counter is not None:
                feed_index, feed_count = counter

        act_type = action.action_type.lower()
        key = str(action.params.get("key", "")).upper()
        mods = {str(m).lower() for m in action.params.get("modifiers", [])}
        # A key means exactly what the key is; its label ("Tab (Next Focus)") is prose.
        words = _words(action.target_id, action.label) if act_type != "key" else set()
        direction = str(action.params.get("direction", "")).lower()

        if self.product == "phototok" and act_type == "swipe" and direction in ("left", "right"):
            # What a horizontal swipe does (favourite, copy, move, delete with a confirmation)
            # depends on the user's swipe settings, so only the safety oracles apply.
            # Checked before the label, whose words ("Discard") name only the default.
            return Expectation(action=action, description=f"Swipe {direction}: outcome depends on the swipe settings")
        if (key == "Z" and mods & _MODIFIER_UNDO) or "undo" in words:
            return self.record_cull(before_state, action, active_photo, "undo")
        if key in _KEYS_DELETE or words & {"delete", "discard", "trash"}:
            return self.record_cull(before_state, action, active_photo, "delete")
        if (key == "M" and not mods) or words & {"keep", "move"}:
            return self.record_cull(before_state, action, active_photo, "move")
        if (key == "C" and not mods) or "copy" in words:
            return self.record_cull(before_state, action, active_photo, "copy")


        if (
            act_type in ("swipe", "scroll")
            or (key in _KEYS_NEXT | _KEYS_PREV and not mods)
            or words & {"next", "prev", "previous", "forward"}
        ):
            return self.record_navigation(before_state, action, feed_index, feed_count)

        return Expectation(
            action=action,
            description=action.label or f"Execute {action.action_type}",
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
        """Directional feed navigation: one photo on, or one back, clamped at the ends."""
        key = str(action.params.get("key", "")).upper()
        direction = str(action.params.get("direction", "")).lower()
        words = _words(action.target_id, action.label)
        is_prev = direction in ("down", "left", "prev", "previous") or key in _KEYS_PREV or bool(words & {"prev", "previous"})

        if is_prev:
            if feed_index is not None and feed_index <= 0:
                delta, desc = 0, "At the start of the feed, navigation stays on the first photo"
            else:
                delta, desc = -1, "Feed returns to the previous photo; index decreases by 1"
        elif feed_index is not None and feed_count is not None and feed_index >= feed_count - 1:
            delta, desc = 0, "At the end of the feed, navigation stays on the last photo"
        else:
            delta, desc = 1, "Feed advances to the next photo; index increases by 1"

        return Expectation(
            action=action,
            description=desc,
            expected_index_delta=delta,
            expected_feed_count_delta=0,
            fs_expect={"unchanged": True},
        )

    @staticmethod
    def _extract_active_photo(before_state: ScreenState) -> Optional[str]:
        """The file whose name appears on screen, when exactly one of the folder's files does."""
        files = [
            str(f["path"])
            for f in (before_state.fs_snapshot or {}).get("files", [])
            if isinstance(f, dict) and f.get("path") and not str(f["path"]).startswith("Selection/")
        ]
        shown = {
            name
            for node in before_state.tree.nodes
            for name in files
            if name in f"{node.name or ''} {node.text or ''}"
        }
        return next(iter(shown)) if len(shown) == 1 else None

    def record_cull(
        self,
        before_state: ScreenState,
        action: Action,
        active_photo: Optional[str] = None,
        cull_action: Optional[str] = None,
    ) -> Expectation:
        """Move, copy, delete or undo. Without a photo bound to the action, nothing is expected of the files."""
        kind = (cull_action or "move").lower()
        photo = active_photo or self._extract_active_photo(before_state)
        descriptions = {
            "move": "Active photo moved to Selection/; feed count decreases by 1",
            "copy": "Active photo copied to Selection/; feed count unchanged",
            "delete": "Active photo deleted; feed count decreases by 1",
            "undo": "Restores the previously culled photo into the feed",
        }
        if kind not in descriptions:
            raise ValueError(f"unknown cull action {cull_action!r}")
        if photo is None:
            counts = _bundle_counts(before_state, kind) if self.product in _COUNTS_PRODUCTS else None
            if counts is None:
                return Expectation(action=action, description=f"{descriptions[kind]} (no photo identified on screen)")
            return Expectation(
                action=action,
                description=f"{descriptions[kind]} (checked by counts: no photo named on screen)",
                fs_expect={"counts": counts},
            )

        if kind == "move":
            index, count = 0, -1
            fs: Dict[str, Any] = {"absent": [photo], "present": [f"Selection/{photo}"]}
        elif kind == "copy":
            index, count = 1, 0
            fs = {"present": [photo, f"Selection/{photo}"]}
        elif kind == "delete":
            index, count = 0, -1
            fs = {"absent": [photo]}
            if self.product in _COUNTS_PRODUCTS:  # only there does the snapshot list the Trash
                fs["trashed"] = [photo]
        else:
            index, count = 0, 1
            fs = {"present": [photo], "absent": [f"Selection/{photo}"]}
        return Expectation(
            action=action,
            description=descriptions[kind],
            expected_index_delta=index,
            expected_feed_count_delta=count,
            fs_expect=fs,
        )


# Products whose working folder and Trash the driver snapshots on the host, where the counts
# check was proven against the live app. Android trashes through MediaStore (`.trashed-*` names,
# no Trash list in the snapshot), so counts there would be wrong.
_COUNTS_PRODUCTS = {"linux-desktop"}


def feed_bundles(fs_snapshot: Optional[Dict[str, Any]]) -> Optional[Set[str]]:
    """The photos left in the feed, one per bundle: path without extension, outside Selection/.

    A RAW+JPEG pair is one photo, so companions do not change the count.
    """
    if not fs_snapshot or not isinstance(fs_snapshot.get("files"), list):
        return None
    bundles: Set[str] = set()
    for f in fs_snapshot["files"]:
        path = str(f.get("path", "")) if isinstance(f, dict) else ""
        name = path.rsplit("/", 1)[-1]
        if not path or path.startswith("Selection/") or name.startswith("."):
            continue
        bundles.add(path.rsplit(".", 1)[0] if "." in name else path)
    return bundles


def _bundle_counts(before_state: ScreenState, kind: str) -> Optional[Dict[str, Any]]:
    """What a cull must do to the folder when the photo it acts on cannot be named.

    The feed loses exactly one photo on move/delete and none on copy; Selection/ (move, copy) or
    the Trash (delete) gains files. Collision suffixes and the Trash's own renaming make the
    exact names unpredictable, so only growth is required there. An empty feed changes nothing.
    Undo depends on what was culled before, so it gets no expectation.
    """
    if kind == "undo":
        return None
    feed = feed_bundles(before_state.fs_snapshot)
    if feed is None:
        return None
    if not feed:
        return {"feed": 0, "selection": 0, "trash": 0}
    return {
        "move": {"feed": -1, "selection": "+", "trash": 0},
        "copy": {"feed": 0, "selection": "+", "trash": 0},
        "delete": {"feed": -1, "selection": 0, "trash": "+"},
    }[kind]

