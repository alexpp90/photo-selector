"""What it means when an action takes the user out of the app, decided by machine facts only.

A step that ends with another app in front was a minor `app-left` lead for every cause, which
made the expected ones noise (2026-11-05, Jackpix on a Pixel 4a: Back at the feed brought the
launcher; the Impressum row opened the browser, REQ-TOK-ARCH.20). The facts used here, none of
them a label or an id list:

- the action's type (a key code or a tap, never its caption);
- the screen mode the product's own rules gave the screen *before* the action;
- the packages the window in front names, against the device's launcher (the HOME intent's
  resolver) and its default browser (the resolver of an `https` VIEW intent).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import FrozenSet

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
) -> AppLeftVerdict:
    """`front` is every package the window in front names; `home` and `browsers` are the device's."""
    if front and action_type == "back" and front <= home and screen_mode in ROOT_SCREEN_MODES.get(product, frozenset()):
        return AppLeftVerdict(EXPECTED, "Back at the root screen brings the launcher to the front")
    if front and action_type in ("tap", "click") and front <= browsers:
        return AppLeftVerdict(OUTBOUND_LINK, f"the tap opened the default browser ({', '.join(sorted(front))})")
    return AppLeftVerdict(LEAD, "")
