"""Text load per screen and per control: the "text-load" criterion of the usability panel.

`metrics.screen_metrics` counts the words a screen asks the user to read. A screen of 5 words
can still put a label on every icon. This module looks at each control: a button, a bar item or
a settings row. For each one it records how many visible words it shows and whether it shows
text next to an icon, measured against the budgets in `docs/shared/UI_TEXT.md`
(`scripts/ui_text_budget.json`). Spoken labels (`content-desc`) are never counted.
"""

from __future__ import annotations

import json
import re
import xml.etree.ElementTree as ET
from functools import lru_cache
from pathlib import Path
from typing import Any

_BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
_WORD = re.compile(r"[^\W\d_]+(?:['’-][^\W\d_]+)*", re.UNICODE)
# A full-width control with several lines is a list row (settings), not a button.
ROW_WIDTH_FRACTION = 0.7
ICON_MAX_DP = 64.0


@lru_cache(maxsize=1)
def budgets() -> dict[str, Any]:
    from ..paths import repo_root

    path = repo_root() / "scripts" / "ui_text_budget.json"
    return json.loads(path.read_text(encoding="utf-8"))["budgets"]


def words(text: str) -> int:
    return len(_WORD.findall(text))


def _bounds(el: ET.Element) -> tuple[int, int, int, int] | None:
    m = _BOUNDS.match(el.get("bounds", ""))
    if not m:
        return None
    b = tuple(int(g) for g in m.groups())
    return b if b[2] > b[0] and b[3] > b[1] else None  # type: ignore[return-value]


def _is_control(el: ET.Element) -> bool:
    rid = el.get("resource-id", "").split("/")[-1].lower()
    return (
        el.get("clickable") == "true"
        or el.get("long-clickable") == "true"
        or rid.endswith("button")
        or rid.endswith("_btn")
    )


def _language(locale: str) -> str:
    return "de" if locale.lower().startswith("de") else "en"


def analyse(xml_text: str, density_dpi: int = 420, locale: str = "en") -> dict[str, Any]:
    """Per-control text facts and flags for one uiautomator dump."""
    start = xml_text.find("<")
    root = ET.fromstring(xml_text[start:] if start > 0 else xml_text)
    px_per_dp = density_dpi / 160.0 if density_dpi > 0 else 1.0
    lang = _language(locale)
    b = budgets()
    screen_w = 0
    for el in root.iter("node"):
        bb = _bounds(el)
        if bb:
            screen_w = max(screen_w, bb[2])

    controls: list[dict[str, Any]] = []
    claimed: set[int] = set()

    def visit(el: ET.Element) -> None:
        if el.tag == "node" and _is_control(el) and id(el) not in claimed:
            bb = _bounds(el)
            if bb:
                texts, icon = [], False
                for n in el.iter("node"):
                    claimed.add(id(n))
                    t = (n.get("text") or "").strip()
                    nb = _bounds(n)
                    if t and t not in texts:
                        texts.append(t)
                    elif not t and n.get("content-desc") and nb and n is not el:
                        w_dp, h_dp = (nb[2] - nb[0]) / px_per_dp, (nb[3] - nb[1]) / px_per_dp
                        if max(w_dp, h_dp) <= ICON_MAX_DP:
                            icon = True
                if not texts and el.get("content-desc"):
                    icon = icon or not (el.get("text") or "")
                is_row = screen_w > 0 and (bb[2] - bb[0]) >= ROW_WIDTH_FRACTION * screen_w and len(texts) >= 2
                kind = "row" if is_row else "button"
                n_words = sum(words(t) for t in texts)
                n_chars = sum(len(t) for t in texts)
                if kind == "row":
                    max_words = b["setting_title"]["words"] + b["setting_summary"]["words"]
                    max_chars = b["setting_title"][lang] + b["setting_summary"][lang]
                else:
                    max_words, max_chars = b["action"]["words"], b["action"][lang]
                flags = []
                if icon and texts:
                    flags.append("labelled-icon")
                if n_words > max_words or n_chars > max_chars:
                    flags.append("wordy")
                if kind == "button" and any(re.search(r"[.!?]\s*$", t) for t in texts):
                    flags.append("sentence")
                controls.append({
                    "id": el.get("resource-id", "").split("/")[-1],
                    "desc": el.get("content-desc", ""),
                    "kind": kind,
                    "texts": texts,
                    "words": n_words,
                    "chars": n_chars,
                    "icon": icon,
                    "budget_words": max_words,
                    "flags": flags,
                })
                return
        for child in el:
            visit(child)

    visit(root)
    flagged = [c for c in controls if c["flags"]]
    return {
        "locale": locale,
        "controls": controls,
        "controls_with_text": sum(1 for c in controls if c["texts"]),
        "control_words": sum(c["words"] for c in controls),
        "max_control_words": max((c["words"] for c in controls), default=0),
        "labelled_icons": sum(1 for c in controls if "labelled-icon" in c["flags"]),
        "wordy_controls": sum(1 for c in controls if "wordy" in c["flags"]),
        "flagged": [{"id": c["id"] or c["desc"], "texts": c["texts"], "flags": c["flags"]} for c in flagged],
        "verdict": "over" if flagged else "ok",
    }
