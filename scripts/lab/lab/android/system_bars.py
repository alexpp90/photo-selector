"""The system bars (status bar, navigation bar) the device reports, as screen-pixel rectangles.

A tap on a bar is the system's, not the app's: in landscape the 3-button navigation bar sits on
the right edge, and Recents took a tap meant for Jackpix's `settings_button` (2026-11-05, Pixel
4a, explore steps 33 and 34). Two sources, both read from the device and neither from a label:

- the app window's own dump: when the window draws the bar's background, uiautomator lists a
  `statusBarBackground` / `navigationBarBackground` node with the bar's bounds (seen in portrait
  on the Pixel 4a; the landscape dump has none);
- the insets `dumpsys window` prints for the display, one `InsetsSource` per bar with its frame.
  Its layout changed between releases (Android 13, captured on the Pixel 4a in landscape:
  `InsetsSource type=ITYPE_NAVIGATION_BAR frame=[2208,0][2340,1080] visible=true`; older braced
  `mType=ITYPE_NAVIGATION_BAR, mFrame=[..]`; Android 17, captured on the Xperia XA2:
  `InsetsSource id=74310001 type=navigationBars frame=[1794,0][1920,1080] visible=true`, camelCase
  types). Both line forms are replayed from captures; only the Android 12 braced form is read from
  AOSP's `InsetsSource.toString()`, and `tests/test_system_bars.py` says which is which.
  Only the status and navigation bars count: a display cutout, the gesture and the tappable-element
  sources are not bars, and a tap on a cutout strip is delivered to the app.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

# (left, top, right, bottom) in screen pixels
Rect = tuple[int, int, int, int]


@dataclass(frozen=True)
class SystemBar:
    kind: str  # "status" or "navigation"
    rect: Rect

    def contains(self, x: float, y: float) -> bool:
        left, top, right, bottom = self.rect
        return left <= x < right and top <= y < bottom


_FRAME = r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"
# Android 12-14: `InsetsSource: {mId=..., mType=navigationBars, mFrame=[0,2208][1080,2340], mVisible=true, ...}`
_SOURCE_BRACED = re.compile(r"InsetsSource: \{([^{}]*)\}")
# Pixel 4a, Android 13 (captured, tests/data): `InsetsSource type=ITYPE_NAVIGATION_BAR frame=[2208,0][2340,1080] visible=true insetsRoundedCornerFrame=false`.
# Android 17 (captured, Xperia XA2) adds an id and camelCase types: `InsetsSource id=74310001 type=navigationBars frame=[1794,0][1920,1080] visible=true flags= sideHint=RIGHT`
_SOURCE_LINE = re.compile(r"InsetsSource (?:id=\S+ )?type=(\w+) frame=" + _FRAME + r"(?: visible=(true|false))?")
_BODY_TYPE = re.compile(r"mType=(\w+)")
_BODY_FRAME = re.compile(r"mFrame=" + _FRAME)
_BODY_VISIBLE = re.compile(r"mVisible=(true|false)")
# A bar window drawn by the app itself (`android:id/navigationBarBackground`), bounds in screen px.
_NODE_BAR = re.compile(r'resource-id="[^"]*?(statusBar|navigationBar)Background"[^>]*?bounds="' + _FRAME)


def _kind(type_name: str) -> str | None:
    name = type_name.lower().replace("_", "").removeprefix("itype")
    if name in ("navigationbar", "navigationbars"):  # ITYPE_NAVIGATION_BAR, navigationBars
        return "navigation"
    if name in ("statusbar", "statusbars"):  # ITYPE_STATUS_BAR, statusBars
        return "status"
    return None


def _bar(kind: str | None, frame: tuple[str, str, str, str]) -> SystemBar | None:
    left, top, right, bottom = (int(v) for v in frame)
    if kind is None or right <= left or bottom <= top:
        return None
    return SystemBar(kind, (left, top, right, bottom))


def bars_from_insets(dumpsys_window: str) -> list[SystemBar]:
    """The visible status and navigation bars of `dumpsys window`, each once."""
    found: list[SystemBar] = []
    for m in _SOURCE_BRACED.finditer(dumpsys_window):
        body = m.group(1)
        kind, frame, visible = _BODY_TYPE.search(body), _BODY_FRAME.search(body), _BODY_VISIBLE.search(body)
        if kind and frame and not (visible and visible.group(1) == "false"):
            found.append(_bar(_kind(kind.group(1)), frame.groups()))  # type: ignore[arg-type]
    for m in _SOURCE_LINE.finditer(dumpsys_window):
        if m.group(6) != "false":
            found.append(_bar(_kind(m.group(1)), m.groups()[1:5]))  # type: ignore[arg-type]
    return _unique(found)


def bars_from_dump(xml: str) -> list[SystemBar]:
    """The bars the app window's own uiautomator dump lists."""
    found = [
        _bar("navigation" if m.group(1) == "navigationBar" else "status", m.groups()[1:5])  # type: ignore[arg-type]
        for m in _NODE_BAR.finditer(xml)
    ]
    return _unique(found)


def _unique(bars: list[SystemBar | None]) -> list[SystemBar]:
    out: list[SystemBar] = []
    for bar in bars:
        if bar is not None and bar not in out:
            out.append(bar)
    return out
