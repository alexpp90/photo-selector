"""What it means when an action takes the user out of the app, decided by machine facts only.

A step that ends with another app in front was a minor `app-left` lead for every cause, which
made the expected ones noise (2026-11-05, Jackpix on a Pixel 4a: Back at the feed brought the
launcher; the Impressum row opened the browser, REQ-TOK-ARCH.20). The facts used here, none of
them a label or an id list:

- the action's type (a key code or a tap, never its caption);
- the screen mode the product's own rules gave the screen *before* the action;
- the packages the window in front names, against the device's launcher (the HOME intent's
  resolver) and its default browser (the resolver of an `https` VIEW intent);
- the package of the top resumed activity. A launcher's window also names the apps of its
  widgets (2026-11-06, Pixel Tablet: clock, Keep and search beside the Pixel launcher), so when
  that activity is the launcher's or the browser's, it decides.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import FrozenSet, Optional

# Screen modes (`discovery.identify_screen_mode`) whose Back leaves the app by design: the root of
# the product's navigation, with no sheet, dialog or overlay above it (an active modal is mode
# "dialog" and a Settings sheet "settings", so neither is here).
ROOT_SCREEN_MODES = {
    "phototok": frozenset({"viewer", "landing", "empty"}),
    "android-desktop": frozenset({"selector", "empty"}),
}

EXPECTED = "expected"  # no finding
OUTBOUND_LINK = "outbound-link"  # an informational lead
LEAD = "app-left"  # unexplained: a lead to triage


_TOP_TASK_PACKAGE = re.compile(r"(?:topResumedActivity|mResumedActivity)[=:]\s*\S+\s+\S+\s+([\w.]+)/")


def top_task_package(top_task: str) -> Optional[str]:
    """The package of a `topResumedActivity=ActivityRecord{… u0 <package>/<activity> t2}` line."""
    m = _TOP_TASK_PACKAGE.search(top_task or "")
    return m.group(1) if m else None


@dataclass(frozen=True)
class AppLeftVerdict:
    kind: str
    reason: str


def classify_app_left(
    action_type: str,
    screen_mode: str,
    product: str,
    front: FrozenSet[str],
    home: FrozenSet[str],
    browsers: FrozenSet[str],
    top_package: Optional[str] = None,
) -> AppLeftVerdict:
    """`front` is every package the window in front names; `home` and `browsers` are the device's;
    `top_package` is the top resumed activity's package (`top_task_package`), when it was read."""

    def is_one_of(packages: FrozenSet[str]) -> bool:
        return bool(packages) and ((bool(front) and front <= packages) or top_package in packages)

    if action_type == "back" and is_one_of(home) and screen_mode in ROOT_SCREEN_MODES.get(product, frozenset()):
        return AppLeftVerdict(EXPECTED, "Back at the root screen brings the launcher to the front")
    if action_type in ("tap", "click") and is_one_of(browsers):
        return AppLeftVerdict(OUTBOUND_LINK, f"the tap opened the default browser ({top_package if top_package in browsers else ', '.join(sorted(front))})")
    return AppLeftVerdict(LEAD, "")
