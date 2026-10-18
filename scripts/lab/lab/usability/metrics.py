"""Objective per-screen numbers that back the personas' opinions.

Opinions differ; word counts do not. A persona saying "too much to read" is weighed against
how many words the screen really asks the user to read, and how long that takes.
"""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from typing import Any

# On-screen reading of UI copy, not prose: ~210 words per minute.
WORDS_PER_SECOND = 3.5
MIN_TARGET_DP = 48.0
# A screen asking for more than this is flagged; the personas decide whether it was worth it.
TEXT_HEAVY_WORDS = 40
LONG_BLOCK_WORDS = 20

_BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
_WORD = re.compile(r"[\w'’-]+", re.UNICODE)


def _bounds(el: ET.Element) -> tuple[int, int, int, int] | None:
    m = _BOUNDS.match(el.get("bounds", ""))
    if not m:
        return None
    b = (int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4)))
    return b if b[2] > b[0] and b[3] > b[1] else None


def _has_label(el: ET.Element) -> bool:
    return any((n.get("text") or n.get("content-desc")) for n in el.iter("node"))


def count_words(text: str) -> int:
    return len(_WORD.findall(text))


def screen_metrics(xml_text: str, density_dpi: int = 420) -> dict[str, Any]:
    """Visible text, words, reading time and touch-target facts of one uiautomator dump."""
    start = xml_text.find("<")
    root = ET.fromstring(xml_text[start:] if start > 0 else xml_text)
    px_per_dp = density_dpi / 160.0 if density_dpi > 0 else 1.0

    blocks: list[str] = []
    seen: set[tuple[str, tuple[int, int, int, int]]] = set()
    controls = small = unlabeled = 0
    for el in root.iter("node"):
        b = _bounds(el)
        if b is None:
            continue
        text = (el.get("text") or "").strip()
        if text and (text, b) not in seen:
            seen.add((text, b))
            blocks.append(text)
        if el.get("clickable") == "true" or el.get("long-clickable") == "true":
            controls += 1
            w_dp, h_dp = (b[2] - b[0]) / px_per_dp, (b[3] - b[1]) / px_per_dp
            if min(w_dp, h_dp) < MIN_TARGET_DP:
                small += 1
            if not _has_label(el):
                unlabeled += 1

    word_counts = [count_words(t) for t in blocks]
    words = sum(word_counts)
    longest = max(word_counts, default=0)
    return {
        "texts": blocks,
        "words": words,
        "text_blocks": len(blocks),
        "longest_block_words": longest,
        "reading_seconds": round(words / WORDS_PER_SECOND, 1),
        "controls": controls,
        "small_targets": small,
        "unlabeled_controls": unlabeled,
        "text_heavy": words > TEXT_HEAVY_WORDS or longest > LONG_BLOCK_WORDS,
    }
