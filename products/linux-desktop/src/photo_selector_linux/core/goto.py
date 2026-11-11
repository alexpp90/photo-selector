"""Resolve what the user typed into "Go to photo" to a position in the current order.

Digits are the position shown in the "Photo N of M" pill (1-based). Anything else is matched
against the photos' file names: the whole name or the name without its extension first, then a
name that starts with the text, then one that contains it. Matching ignores case. The first
match in the current order wins, so the result is deterministic.
"""

from __future__ import annotations

from typing import Optional, Sequence


def resolve_goto(text: str, names: Sequence[str]) -> Optional[int]:
    """Index into ``names`` that ``text`` points at, or None when nothing matches."""
    query = text.strip()
    if not query or not names:
        return None
    if query.isdigit():
        position = int(query)
        return position - 1 if 1 <= position <= len(names) else None
    needle = query.casefold()
    lowered = [n.casefold() for n in names]
    stems = [n.rsplit(".", 1)[0] if "." in n else n for n in lowered]
    for matches in (
        lambda i: needle in (lowered[i], stems[i]),
        lambda i: lowered[i].startswith(needle),
        lambda i: needle in lowered[i],
    ):
        for index in range(len(names)):
            if matches(index):
                return index
    return None
