"""The background read of capture times behind "Newest first" and "Oldest first" (plan 2.2).

File times are what a card copy rewrites, so a folder whose files all carry one modification time
has no order. The pass reads ``DateTimeOriginal`` (and the sub-second digits) of every photo that
has no EXIF yet and hands each result back on the main loop; the view model applies it and
re-sorts while the photo on screen stays on screen. The sort key is the capture time with the file
time as fallback (``CandidatePhoto.order_time``). On a cloud folder nothing runs by itself: a read
costs a download, so the pass starts only when the user chooses the order.
"""

from __future__ import annotations

from typing import Any, Callable, Iterable, List, Optional, Set

#: Photos read between two re-sorts (the last one always re-sorts).
RESORT_EVERY = 25


class CapturePass:
    """One folder's pass; ``runner(photos, on_read)`` is the loader's ``run_capture_pass``."""

    def __init__(self, on_progress: Callable[[], None], on_done: Callable[[], None]) -> None:
        self.runner: Optional[Callable[..., Callable[[], None]]] = None
        self._on_progress = on_progress
        self._on_done = on_done
        self._generation = 0
        self._inflight: Set[str] = set()
        self._requested: Set[str] = set()
        self._cancel: Optional[Callable[[], None]] = None
        self._since_resort = 0

    @property
    def reading(self) -> bool:
        return bool(self._inflight)

    def cancel(self, forget: bool = False) -> None:
        """Stop the pass (a new folder, another order). ``forget`` also drops what was requested."""
        self._generation += 1
        cancel, self._cancel = self._cancel, None
        if cancel is not None:
            cancel()
        self._inflight = set()
        self._since_resort = 0
        if forget:
            self._requested = set()

    def start(self, photos: Iterable[Any], is_remote: bool, user_requested: bool) -> bool:
        """Read the capture time of every photo that has no EXIF yet; False when nothing starts."""
        if self.runner is None or (is_remote and not user_requested):
            return False
        todo: List[Any] = [p for p in photos if p.exif is None and p.id not in self._requested]
        if not todo:
            return False
        generation = self._generation
        for photo in todo:
            self._inflight.add(photo.id)
            self._requested.add(photo.id)

        def on_read(photo: Any, meta: Any) -> None:
            self._read(generation, photo, meta)

        cancel = self.runner(todo, on_read)
        # The runner may have delivered everything inline; keep only a live pass's cancel handle.
        if generation == self._generation and self._inflight:
            self._cancel = cancel
        return True

    def _read(self, generation: int, photo: Any, meta: Any) -> None:
        if generation != self._generation:
            return
        self._inflight.discard(photo.id)
        if meta is not None and photo.exif is None:
            photo.exif = meta
        self._since_resort += 1
        if not self._inflight:
            self._cancel = None
            self._since_resort = 0
            self._on_done()
        elif self._since_resort >= RESORT_EVERY:
            self._since_resort = 0
            self._on_progress()


def order_basis(photos: List[Any], reading: bool) -> str:
    """What "Newest first" / "Oldest first" order by right now, for the order button's tooltip."""
    if reading:
        return "reading capture times"
    known = sum(1 for p in photos if p.capture_time is not None)
    if photos and known == len(photos):
        return "capture time"
    if known == 0:
        return "file time"
    return f"capture time, file time for {len(photos) - known}"
