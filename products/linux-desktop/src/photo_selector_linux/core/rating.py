"""An existing star rating, read and shown, never written (decision #32, plan 3.1).

Where the rating comes from, in order: the sidecar's ``xmp:Rating``, the file's embedded XMP
(``Xmp.xmp.Rating``), then the EXIF rating (``Exif.Image.Rating``). ``0`` and "none" mean unrated and
show nothing; ``-1`` is the XMP value for "rejected". Nothing in this module opens a file for writing.
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any, Optional

REJECTED = -1
#: A sidecar bigger than this is not read for a rating (it is a malformed or foreign file).
MAX_SIDECAR_BYTES = 4 * 1024 * 1024

_ATTRIBUTE = re.compile(r"""xmp:Rating\s*=\s*["']\s*(-?\d+)\s*["']""")
_ELEMENT = re.compile(r"<xmp:Rating>\s*(-?\d+)\s*</xmp:Rating>")


def clamp_rating(value: Any) -> Optional[int]:
    """-1 (rejected) or 0..5 for a readable value, else None. A larger value is not a star rating."""
    try:
        number = int(float(str(value).strip().split()[0]))
    except (ValueError, IndexError):
        return None
    return number if -1 <= number <= 5 else None


def parse_xmp_rating(text: Optional[str]) -> Optional[int]:
    """``xmp:Rating`` of an XMP packet or sidecar (attribute or element form), or None."""
    if not text:
        return None
    match = _ATTRIBUTE.search(text) or _ELEMENT.search(text)
    return clamp_rating(match.group(1)) if match else None


def read_sidecar_rating(sidecar: Optional[Path]) -> Optional[int]:
    """The rating in a ``.xmp`` sidecar, or None (no sidecar, unreadable, too big, no rating)."""
    if sidecar is None:
        return None
    try:
        if sidecar.stat().st_size > MAX_SIDECAR_BYTES:
            return None
        return parse_xmp_rating(sidecar.read_text(encoding="utf-8", errors="replace"))
    except OSError:
        return None


def resolve_rating(sidecar_rating: Optional[int], embedded_rating: Optional[int]) -> Optional[int]:
    """The rating to show: the sidecar's wins (it is what a catalogue wrote last), then the file's."""
    return sidecar_rating if sidecar_rating is not None else embedded_rating


def stars(rating: Optional[int]) -> int:
    """Filled stars to draw: 1..5, or 0 for no row (unrated, rejected and unknown draw no stars)."""
    return rating if rating is not None and 1 <= rating <= 5 else 0


def spoken(rating: Optional[int]) -> str:
    """What a screen reader says for the row ("" when there is no row)."""
    if rating == REJECTED:
        return "Rejected"
    return f"Rating {rating} of 5" if stars(rating) else ""
