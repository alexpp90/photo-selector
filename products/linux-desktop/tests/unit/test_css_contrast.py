"""Text on a literal fill in the studio CSS reaches WCAG AA (4.5:1) — UX-LIN-1."""

import re

from photo_selector_linux.ui.styling import STUDIO_DARK_CSS

_HEX = re.compile(r"^#([0-9A-Fa-f]{6})$")


def _luminance(hex_colour: str) -> float:
    channels = [int(hex_colour[i : i + 2], 16) / 255 for i in (0, 2, 4)]
    lin = [c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4 for c in channels]
    return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2]


def _ratio(a: str, b: str) -> float:
    hi, lo = sorted((_luminance(a), _luminance(b)), reverse=True)
    return (hi + 0.05) / (lo + 0.05)


def _pairs():
    css = re.sub(r"/\*.*?\*/", "", STUDIO_DARK_CSS, flags=re.S)
    for m in re.finditer(r"([^{}]+)\{([^{}]*)\}", css):
        selector, body = m.group(1).strip(), m.group(2)
        props = dict(re.findall(r"(?<![-\w])(background-color|color)\s*:\s*([^;]+);", body))
        bg, fg = props.get("background-color", "").strip(), props.get("color", "").strip()
        if _HEX.match(bg) and _HEX.match(fg):
            yield selector, bg[1:], fg[1:]


def test_the_css_has_literal_pairs_to_check():
    selectors = [s for s, _, _ in _pairs()]
    assert ".action-btn-move" in selectors
    assert ".score-badge-sharp" in selectors


def test_every_literal_background_and_text_pair_reaches_4_5_to_1():
    failures = [
        f"{sel}: #{fg} on #{bg} = {_ratio(bg, fg):.2f}:1"
        for sel, bg, fg in _pairs()
        if _ratio(bg, fg) < 4.5
    ]
    assert not failures, failures
