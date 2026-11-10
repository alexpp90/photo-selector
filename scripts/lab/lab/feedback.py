"""Feedback oracle: after an action, the screen must say what happened.

Every product answers a delete, copy, move or undo with transient feedback (a snackbar, a toast,
a flash). The ledger keeps finding actions that gave none (`missing-feedback`), or one that named
the wrong thing. A checkpoint taken right after the action must therefore show a feedback node
whose text names the action.

Per product, `scripts/lab/rules/<product>.yaml` has a `feedback:` section::

    feedback:
      surfaces: [selector_snackbar]      # id globs of the feedback container (node or ancestor)
      ignore_ids: [snackbar_undo]        # nodes under a surface that are not the message (UNDO)
      text: true                         # false: the tree exposes the surface but not its words
      actions:                           # action -> words, any one names it (case-insensitive,
        move: [Moved]                    # one list for every language the product ships)
        copy: [Copied]

A journey asks for it per checkpoint: `layout_expect.<checkpoint>.expect_feedback` is `true` (any
feedback), an action name, or a list of action names (the feedback names any one of them). The
explore verifier records the culling action of a step the same way.

A product whose tree exposes no feedback surface (empty `surfaces`) cannot be checked: the result
is `unchecked`, never a pass.
"""

from __future__ import annotations

import fnmatch
from dataclasses import dataclass, field
from typing import Any

from .model import Node, Tree

OK = "ok"
MISSING = "missing"  # no feedback on the screen
WRONG = "wrong"  # feedback on the screen, but it does not name the action
UNCHECKED = "unchecked"  # the oracle cannot look: no surface configured, or the action has no words


@dataclass
class FeedbackResult:
    status: str
    actions: list[str]
    texts: list[str] = field(default_factory=list)
    message: str = ""
    region: list[float] | None = None


def normalize_expectation(expected: Any) -> list[str] | None:
    """`True` -> `[]` (any feedback); an action or a list of them -> those; falsy -> None (no claim)."""
    if expected is None or expected is False:
        return None
    if expected is True:
        return []
    if isinstance(expected, str):
        return [expected]
    return [str(a) for a in expected]


def _matches(node: Node, patterns: list[str], by_id: dict[str, Node]) -> bool:
    visited: set[str] = set()
    cur: Node | None = node
    while cur is not None and cur.id not in visited:
        if any(fnmatch.fnmatchcase(cur.id, p) for p in patterns):
            return True
        visited.add(cur.id)
        cur = by_id.get(cur.parent) if cur.parent else None
    return False


def feedback_nodes(tree: Tree, cfg: dict[str, Any]) -> list[Node]:
    """Visible nodes on a feedback surface, the surface itself included, minus `ignore_ids`."""
    surfaces = [str(s) for s in cfg.get("surfaces") or []]
    ignore = [str(s) for s in cfg.get("ignore_ids") or []]
    by_id = {n.id: n for n in tree.nodes}
    return [
        n for n in tree.nodes
        if n.visible and n.w >= 1 and n.h >= 1
        and _matches(n, surfaces, by_id) and not (ignore and _matches(n, ignore, by_id))
    ]


def check_feedback(tree: Tree, rules: dict[str, Any], expected: Any) -> FeedbackResult | None:
    """Judge `tree` against an expected feedback; None when `expected` makes no claim."""
    actions = normalize_expectation(expected)
    if actions is None:
        return None
    cfg = rules.get("feedback") or {}
    if not cfg.get("surfaces"):
        return FeedbackResult(
            UNCHECKED, actions,
            message="the product's tree exposes no feedback surface (rules `feedback.surfaces` is empty)",
        )
    nodes = feedback_nodes(tree, cfg)
    if not nodes:
        return FeedbackResult(MISSING, actions, message="no feedback (snackbar/toast) on the screen after the action")
    region = nodes[0].bounds
    if cfg.get("text", True) is False:
        return FeedbackResult(OK, actions, message="feedback shown (its words are not in the tree)", region=region)
    texts = [t for n in nodes for t in (n.text.strip(), n.name.strip()) if t]
    if not texts:
        return FeedbackResult(MISSING, actions, message="the feedback surface shows no text", region=region)
    if not actions:
        return FeedbackResult(OK, actions, texts, region=region)
    known = cfg.get("actions") or {}
    words = [str(w).casefold() for a in actions for w in known.get(a, [])]
    if not words:
        return FeedbackResult(
            UNCHECKED, actions, texts,
            message=f"rules `feedback.actions` has no words for {', '.join(actions)}", region=region,
        )
    joined = " ".join(texts).casefold()
    if any(w in joined for w in words):
        return FeedbackResult(OK, actions, texts, region=region)
    return FeedbackResult(
        WRONG, actions, texts,
        message=f"feedback {texts!r} does not name {' or '.join(actions)}", region=region,
    )
