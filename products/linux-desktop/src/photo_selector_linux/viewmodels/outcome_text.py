"""The words of a Move, Copy or Trash outcome, shown and spoken (decision #33 (1)).

``[USER 2026-11-12]`` decision #33 (1): a confirmation names the companions and the destination.
One formatter makes both texts, so the visible line and what a screen reader says cannot drift:

==============  ==================================  =============================================================
                visible (action bar, 4 s)           spoken
==============  ==================================  =============================================================
companions      ``Moved IMG_0001 +2 · Selection``   ``Moved IMG_0001.CR3 and 2 companion files to Selection``
none            ``Moved IMG_0001 · Selection``      ``Moved IMG_0001.CR3 to Selection``
Copy            ``Copied`` for ``Moved``            same
Trash           ``Moved IMG_0001 +2 · Trash``       ``Moved IMG_0001.CR3 and 2 companion files to Trash``
==============  ==================================  =============================================================

"companions" is spoken only: the visible "+N" fits the budget. The first Move or Copy of a folder
shows the full destination path instead of the folder's name and leaves the file name out (the bar's
slot is bounded, LIN-7); the spoken text keeps the name.
"""

from __future__ import annotations

from typing import Any, Optional, Tuple

#: Longest file stem the visible line shows before it is shortened in the middle.
STEM_CHARS = 16


def short_stem(name: str, limit: int = STEM_CHARS) -> str:
    """The file name without its extension, shortened in the middle (first 4, last 7) if long, so
    the distinguishing end of a burst name stays visible: ``IMG_…_064000``."""
    stem = name.rsplit(".", 1)[0] if "." in name.strip(".") else name
    return stem if len(stem) <= limit else f"{stem[:4]}…{stem[-7:]}"


def companion_count(photo: Any, result: Any) -> int:
    """Files that travelled with the primary one (the primary itself is not counted)."""
    pairs = list(getattr(result, "affected_paths", None) or [])
    return max(0, (len(pairs) if pairs else len(photo.all_paths)) - 1)


def _plural(n: int) -> str:
    return f"{n} companion file" + ("" if n == 1 else "s")


def outcome_texts(
    verb: str,
    photo: Any,
    companions: int,
    destination: str,
    *,
    first_place: Optional[str] = None,
    new_name: Optional[str] = None,
    on_card: bool = False,
) -> Tuple[str, str]:
    """``(visible, spoken)`` for a finished file operation.

    ``destination`` is the folder's name ("Selection", "Trash"). ``first_place`` is the full path of
    the first transfer of a folder (said once, replaces the file name in the visible line).
    ``new_name`` is the name a collision gave the primary file.
    """
    plus = f" +{companions}" if companions else ""
    if first_place is not None:
        visible = f"{verb}{plus} · {first_place}"
    elif new_name is not None:
        visible = f"{verb} as {short_stem(new_name)}{plus} · {destination}"
    else:
        visible = f"{verb} {short_stem(photo.filename)}{plus} · {destination}"
    spoken = f"{verb} {photo.filename}"
    if companions:
        spoken += f" and {_plural(companions)}"
    spoken += f" to {destination}"
    if new_name is not None:
        spoken += f", named {new_name}"
    if on_card:
        spoken += ", on this card"
    return visible, spoken
