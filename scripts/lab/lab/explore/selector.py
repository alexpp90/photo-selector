"""PRNG Action Selector and Weighting Engine for Exploratory Testing.

Provides seeded, reproducible pseudo-random action selection with two-tier
categorical balancing, candidate canonicalization, and loop avoidance heuristics.
"""

from __future__ import annotations

import hashlib
import json
import random
import secrets
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional, Sequence, Set, Tuple

from .actions import Action, ActionCategory, categorize_action


class SelectionPolicy(str, Enum):
    """Selection policies for choosing from candidate interactions."""

    UNIFORM = "uniform"
    CATEGORICAL_WEIGHTED = "categorical"


class EmptyCandidatesError(ValueError):
    """Raised when select() is invoked with an empty candidate action list."""


@dataclass
class SelectionRecord:
    """Historical trace record of a single action selection decision."""

    step_index: int
    seed_at_step: int
    chosen_action: Action
    candidate_count: int
    screen_signature: str
    was_noop: bool = False


class ActionSelector:
    """Pseudo-random action selection engine with reproducibility and loop avoidance."""

    DEFAULT_CATEGORY_WEIGHTS: Dict[ActionCategory, int] = {
        ActionCategory.TAP: 3500,       # 35% target
        ActionCategory.GESTURE: 3000,   # 30% target
        ActionCategory.KEYBOARD: 2500,  # 25% target
        ActionCategory.LIFECYCLE: 1000, # 10% target
    }

    def __init__(
        self,
        seed: Optional[int] = None,
        policy: SelectionPolicy | str = SelectionPolicy.CATEGORICAL_WEIGHTED,
        category_weights: Optional[Dict[ActionCategory, int]] = None,
        max_consecutive_repeats: int = 2,
        avoid_loops: bool = True,
    ) -> None:
        if seed is not None and not isinstance(seed, int):
            raise TypeError(f"Seed must be an int or None, got {type(seed).__name__}")

        if seed is None:
            # Generate explicit positive 31-bit integer seed so reproduction logs record concrete int
            self.seed = secrets.randbelow(2**31 - 1)
        else:
            self.seed = seed

        self._rng = random.Random(self.seed)
        self.policy = SelectionPolicy(policy)
        self.max_consecutive_repeats = max(1, int(max_consecutive_repeats))
        self.avoid_loops = bool(avoid_loops)

        self.category_weights = dict(self.DEFAULT_CATEGORY_WEIGHTS)
        if category_weights:
            for cat, w in category_weights.items():
                if w < 0:
                    raise ValueError(f"Category weight must be non-negative, got {w} for {cat}")
                self.category_weights[ActionCategory(cat)] = int(w)

        self.history: List[SelectionRecord] = []
        self._known_noops: Dict[str, Set[Tuple[str, str, str, str]]] = {}
        self.step_counter = 0

    def compute_screen_signature(self, screen_state: Any) -> str:
        """Derive stable 12-char SHA-256 signature from screen state."""
        if hasattr(screen_state, "signature") and screen_state.signature:
            return str(screen_state.signature)
        if hasattr(screen_state, "tree") and screen_state.tree:
            nodes = getattr(screen_state.tree, "nodes", [])
            items = sorted(
                (n.id, (n.text or "").strip()[:40], (n.role or ""), bool(n.interactive))
                for n in nodes
                if getattr(n, "interactive", False) or getattr(n, "visible", True)
            )
            mode = getattr(screen_state, "screen_mode", "")
            payload = json.dumps({"mode": mode, "items": items}, sort_keys=True)
            return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:12]
        return "default_screen"

    def record_outcome(
        self,
        action: Action,
        screen_sig: Optional[str] = None,
        before_state: Optional[Any] = None,
        after_state: Optional[Any] = None,
        is_noop: Optional[bool] = None,
    ) -> None:
        """Record post-execution outcome to inform subsequent loop avoidance."""
        sig = screen_sig or (self.compute_screen_signature(before_state) if before_state else "default_screen")

        # Determine no-op status if not explicitly passed
        if is_noop is None and before_state is not None and after_state is not None:
            sig_before = self.compute_screen_signature(before_state)
            sig_after = self.compute_screen_signature(after_state)
            mode_before = getattr(before_state, "screen_mode", "")
            mode_after = getattr(after_state, "screen_mode", "")
            fs_before = getattr(before_state, "fs_snapshot", {})
            fs_after = getattr(after_state, "fs_snapshot", {})
            is_noop = (sig_before == sig_after and mode_before == mode_after and fs_before == fs_after)
        else:
            is_noop = bool(is_noop)

        if is_noop:
            self._known_noops.setdefault(sig, set()).add(action.canonical_key())

        if self.history:
            self.history[-1].was_noop = is_noop

    def _calculate_candidate_weight(self, cand: Action, screen_sig: str) -> int:
        """Calculate dynamic damping weight for candidate based on history."""
        if not self.avoid_loops:
            return 100

        # Known no-op penalty on current screen signature
        if cand.canonical_key() in self._known_noops.get(screen_sig, set()):
            return 0

        # Repetitive action penalty
        if self.history:
            last_record = self.history[-1]
            if cand == last_record.chosen_action:
                consecutive = 0
                for rec in reversed(self.history):
                    if rec.chosen_action == last_record.chosen_action:
                        consecutive += 1
                    else:
                        break
                if consecutive >= self.max_consecutive_repeats:
                    return 10  # 90% damping penalty

            # Ping-Pong oscillation penalty: [A, B, A] -> penalize B
            if len(self.history) >= 3:
                a1 = self.history[-3].chosen_action
                b1 = self.history[-2].chosen_action
                a2 = self.history[-1].chosen_action
                if a1 == a2 and a1 != b1 and cand == b1:
                    return 20  # 80% damping penalty

        return 100

    def select(
        self,
        candidates: Sequence[Action],
        screen_state: Optional[Any] = None,
        screen_sig: Optional[str] = None,
    ) -> Action:
        """Select next interaction deterministically from candidates."""
        if not candidates:
            raise EmptyCandidatesError("Cannot select action: candidate action list is empty")

        sig = screen_sig or (self.compute_screen_signature(screen_state) if screen_state else "default_screen")
        self.step_counter += 1

        # Canonicalize order to ensure immunity to PYTHONHASHSEED process divergence
        unique_cands = sorted(list(dict.fromkeys(candidates)), key=lambda a: a.canonical_key())

        # Compute dynamic weights
        cand_weights = {c: self._calculate_candidate_weight(c, sig) for c in unique_cands}

        # Fallback: if loop avoidance suppressed ALL candidates, reset to uniform
        if sum(cand_weights.values()) == 0:
            cand_weights = {c: 100 for c in unique_cands}

        if self.policy == SelectionPolicy.UNIFORM:
            chosen = self._select_uniform(unique_cands, cand_weights)
        else:
            chosen = self._select_categorical(unique_cands, cand_weights)

        # Log selection decision in reproduction trace
        self.history.append(
            SelectionRecord(
                step_index=self.step_counter,
                seed_at_step=self.seed,
                chosen_action=chosen,
                candidate_count=len(unique_cands),
                screen_signature=sig,
            )
        )
        return chosen

    def _select_uniform(self, candidates: List[Action], weights: Dict[Action, int]) -> Action:
        total = sum(weights[c] for c in candidates)
        r = self._rng.randrange(total)
        cum = 0
        for c in candidates:
            cum += weights[c]
            if r < cum:
                return c
        return candidates[-1]

    def _select_categorical(self, candidates: List[Action], weights: Dict[Action, int]) -> Action:
        groups: Dict[ActionCategory, List[Action]] = {cat: [] for cat in ActionCategory}
        for c in candidates:
            groups[categorize_action(c)].append(c)

        # Filter active categories that possess candidate weight > 0
        active_cats = [
            cat for cat in ActionCategory
            if any(weights[c] > 0 for c in groups[cat])
        ]
        if not active_cats:
            active_cats = [cat for cat in ActionCategory if groups[cat]]

        total_cat_weight = sum(self.category_weights[cat] for cat in active_cats)
        if total_cat_weight == 0:
            return self._select_uniform(candidates, weights)
        r = self._rng.randrange(total_cat_weight)
        cum = 0
        selected_cat = active_cats[-1]
        for cat in active_cats:
            cum += self.category_weights[cat]
            if r < cum:
                selected_cat = cat
                break

        # Select uniformly among weighted candidates in chosen category
        cands_in_cat = groups[selected_cat]
        cat_total = sum(weights[c] for c in cands_in_cat)
        if cat_total == 0:
            return cands_in_cat[self._rng.randrange(len(cands_in_cat))]

        r = self._rng.randrange(cat_total)
        cum = 0
        for c in cands_in_cat:
            cum += weights[c]
            if r < cum:
                return c
        return cands_in_cat[-1]

    def export_trace(self) -> List[Dict[str, Any]]:
        """Export reproduction trace of all decisions taken during session."""
        return [
            {
                "step": rec.step_index,
                "screen_sig": rec.screen_signature,
                "action": rec.chosen_action.to_dict(),
                "action_sig": rec.chosen_action.signature,
                "was_noop": rec.was_noop,
            }
            for rec in self.history
        ]
