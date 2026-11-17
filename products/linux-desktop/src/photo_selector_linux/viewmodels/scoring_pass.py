"""The scoring pass behind "lowest sharpness first" (split out of ``culling_workspace.py``)."""

from __future__ import annotations

import logging

from typing import Optional, Tuple

from photo_selector_linux.core.models import (
    CandidatePhoto,
    QualityScores,
)
from photo_selector_linux.viewmodels.capture_pass import resort_every
from photo_selector_linux.core.settings import (
    ORDER_LOWEST_SHARPNESS,
)


logger = logging.getLogger(__name__)


class ScoringPassMixin:
    """Scores every photo of the folder in the background and re-sorts as results arrive."""

    @property
    def is_scoring(self) -> bool:
        return bool(self._scoring_inflight)

    @property
    def scoring_progress(self) -> Tuple[int, int]:
        """Photos scored so far and photos this sweep must score (for the order button's tooltip)."""
        return self._scoring_done, self._scoring_total

    def _cancel_scoring_pass(self) -> None:
        """Stop the background pass: folder switch, back to natural order."""
        self._scoring_generation += 1
        cancel, self._cancel_scoring = self._cancel_scoring, None
        if cancel is not None:
            cancel()
        self._scoring_inflight = {}
        self._scoring_total = self._scoring_done = self._scoring_since_resort = self._scoring_new = 0
        self.scoring_confirmation_pending = False
        self._scoring_anchor = None

    def _begin_scoring_pass(self) -> None:
        """Score every photo that has no score yet, so the order covers the whole folder (UC-13).

        Photos already scored by viewing are skipped. A remote/cloud folder is read in full only
        after the user chooses the order a second time (U-31).
        """
        if self.sort_order != ORDER_LOWEST_SHARPNESS or self.score_runner is None:
            return
        todo = [
            c for c in self.candidates
            if c.scores is None and c.id not in self._scoring_inflight
        ]
        if not todo:
            self._finish_scoring_pass()
            return
        if self.is_remote_folder and not self._scoring_confirmed:
            self.scoring_confirmation_pending = True
            self._notify("Cloud / Remote folder: choose this order again to score every photo")
            return
        self.scoring_confirmation_pending = False
        generation = self._scoring_generation
        for photo in todo:
            self._scoring_inflight[photo.id] = photo
        self._scoring_total += len(todo)
        self._notify(f"Scoring {len(todo)} photos, centre of frame…")

        def on_scored(photo: CandidatePhoto, scores: Optional[QualityScores]) -> None:
            self._on_photo_scored(generation, photo, scores)

        cancel = self.score_runner(todo, on_scored)
        # The runner may have delivered everything inline; keep only a live pass's cancel handle.
        if generation == self._scoring_generation and self._scoring_inflight:
            self._cancel_scoring = cancel

    def _on_photo_scored(
        self, generation: int, photo: CandidatePhoto, scores: Optional[QualityScores]
    ) -> None:
        if generation != self._scoring_generation:
            return
        self._scoring_inflight.pop(photo.id, None)
        self._scoring_done += 1
        self._scoring_since_resort += 1
        if scores is not None and photo.scores is None:
            photo.scores = scores
            self._scoring_new += 1
        if not self._scoring_inflight:
            self._finish_scoring_pass()
        elif self._scoring_since_resort >= resort_every(self._scoring_total, 10 if self._scoring_total <= 200 else 50):
            self._resort_keeping_view()
            self._notify_state_changed()

    def _resort_keeping_view(self) -> None:
        """Re-sort by the scores that exist now; the photo on screen stays on screen."""
        shown = self.current_photo
        self._apply_sort_order()
        if shown is not None:
            idx = self._index_of(shown)
            if idx is not None:
                self.current_index = idx
        self._scoring_since_resort = 0

    def _finish_scoring_pass(self) -> None:
        if self.is_scanning:
            return  # photos still arriving; the scan's completion starts the next pass
        total = self._scoring_new
        self._resort_keeping_view()
        self._cancel_scoring = None
        self._scoring_total = self._scoring_done = self._scoring_new = 0
        anchor, self._scoring_anchor = self._scoring_anchor, None
        if anchor is not None and self.current_photo is anchor:
            self.current_index = 0  # not navigated meanwhile: start the sweep at the lowest
        if total:  # [USER 2026-11-12] decision #33 (4): the outcome line, nothing at 0
            self._notify(f"Scored {total} more", "status")
        self._notify_state_changed()
