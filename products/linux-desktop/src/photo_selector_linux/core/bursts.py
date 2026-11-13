"""Which photos are one burst (decision #31, plan 2.4): neighbours in time less than a second apart.

Display only: nothing here groups, hides or acts on photos. A burst is a chain of consecutive
shots (in capture-time order) with every gap under ``BURST_GAP``; a photo with no neighbour that
close has no cue. Only real capture times count: a photo without one (no EXIF, or not read yet) is
never placed in a burst, and a file time is never used as a stand-in. Where a camera wrote whole
seconds only (no ``SubSecTimeOriginal``) two shots in one second still chain, but a gap of one
second cannot be told from 0.2 s, so such bursts are found less often, never invented.
"""

from __future__ import annotations

from typing import Any, Dict, Iterable, List, Tuple

#: Neighbours closer than this (seconds) belong to one burst.
BURST_GAP = 1.0


def burst_positions(photos: Iterable[Any]) -> Dict[str, Tuple[int, int]]:
    """``{photo.id: (position, size)}`` for every photo in a burst of two or more (1-based)."""
    timed = sorted(
        (p for p in photos if p.capture_time is not None),
        key=lambda p: (p.capture_time, p.filename),
    )
    result: Dict[str, Tuple[int, int]] = {}
    chain: List[Any] = []

    def close() -> None:
        if len(chain) >= 2:
            for index, member in enumerate(chain, start=1):
                result[member.id] = (index, len(chain))

    for photo in timed:
        if chain and photo.capture_time - chain[-1].capture_time >= BURST_GAP:
            close()
            chain = []
        chain.append(photo)
    close()
    return result


def burst_text(position: int, size: int) -> str:
    return f"{position} of {size}"


def burst_spoken(position: int, size: int) -> str:
    return f"Burst, photo {position} of {size}"
