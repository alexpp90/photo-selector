"""Whether a freshly launched app already shows what `_ensure_folder.yaml` would bring it to.

The per-product `_ensure_folder.yaml` costs 17-39 s per journey on the 2026-11-09 full run
(33 flows, 1289 s from launch to the flow's end: a Maestro start-up of about 10 s plus the
flow's own optional waits for dialogs that mostly never came). The lab already dumps the UI
tree for every checkpoint, so one dump after launch can tell whether the flow has anything to
do: the screen it works toward (the Frameduel workspace, the Jackpix feed) is on screen and none
of the things it dismisses or answers is.

Conservative by construction: only `READY` skips the flow; anything else (a dialog, a guide, the
landing page, the folder picker, an unknown or not yet drawn screen) runs the flow as before.
Dialog windows are dumped as the only window and export no test tags, so an open dialog hides
the marker and cannot read as ready; the text blockers are a second guard.
"""

from __future__ import annotations

import re
import time
from pathlib import Path
from typing import Callable

from .tree_android import foreground_packages

READY = "ready"
NEEDS_FLOW = "needs-flow"
PENDING = "pending"  # nothing recognisable yet (still drawing, or a screen the lab does not know)

# product -> test tag of the screen the ensure flow waits for last.
READY_MARKERS = {
    "android-desktop": "image_region",
    "phototok": "viewer.photo",
}
# Test tags and texts of what the flows dismiss, answer or work through; any of them on screen
# means the flow has work to do. Keep in step with products/android/<p>/tests/e2e/_ensure_folder.yaml.
BLOCKER_IDS = {
    "android-desktop": re.compile(r"^coach_overlay_"),
    "phototok": re.compile(r"^(landing_|reselect_folder_button$|controls_guide$|gesture_tutorial_)"),
}
BLOCKER_TEXTS = re.compile(
    r"Uncompleted deletions|Show new|Neue zeigen|Start with Newest"
    r"|Link both|Beide verknüpfen|Show both, linked|Link actions across both"
)

# The flow waits up to 4 s for the "N new photos" dialog after the feed shows; the ready
# screen must stay unchanged that long before the flow is skipped.
STABLE_FOR_S = 4.0
# Give up waiting for a recognisable screen after this long and run the flow.
PROBE_TIMEOUT_S = 8.0
PROBE_INTERVAL_S = 0.5


def classify_start_screen(xml_text: str, product: str, app_id: str) -> str:
    """`READY`, `NEEDS_FLOW` or `PENDING` for one uiautomator dump taken right after launch."""
    if app_id not in foreground_packages(xml_text):
        return PENDING  # the picker, the launcher or the shade is in front: the flow decides
    ids = set(re.findall(r'resource-id="([^"]*)"', xml_text))
    blockers = BLOCKER_IDS.get(product)
    if (blockers and any(blockers.search(i) for i in ids)) or BLOCKER_TEXTS.search(xml_text):
        return NEEDS_FLOW
    marker = READY_MARKERS.get(product)
    return READY if marker and marker in ids else PENDING


def app_already_ready(
    product: str,
    app_id: str,
    dump: Callable[[], str],
    *,
    sleep: Callable[[float], None] = time.sleep,
    clock: Callable[[], float] = time.monotonic,
    stable_for_s: float | None = None,
    timeout_s: float | None = None,
    interval_s: float | None = None,
) -> bool:
    """Poll the screen; True only when it showed `READY` continuously for `stable_for_s`.

    `dump` returns the current uiautomator XML (and may raise: a failed dump means "do not skip").
    Returns False at the first `NEEDS_FLOW`, when `READY` flickers away, or at `timeout_s`.
    """
    stable_for_s = STABLE_FOR_S if stable_for_s is None else stable_for_s
    timeout_s = PROBE_TIMEOUT_S if timeout_s is None else timeout_s
    interval_s = PROBE_INTERVAL_S if interval_s is None else interval_s
    start = clock()
    ready_since: float | None = None
    while True:
        try:
            verdict = classify_start_screen(dump(), product, app_id)
        except Exception:  # a dump that fails is not evidence of a ready screen
            return False
        now = clock()
        if verdict == NEEDS_FLOW:
            return False
        if verdict == READY:
            ready_since = now if ready_since is None else ready_since
            if now - ready_since >= stable_for_s:
                return True
        elif ready_since is not None:
            return False  # was ready, is no longer: something came up, let the flow handle it
        if now - start >= timeout_s:
            return False
        sleep(interval_s)


def dump_text(adb: object, scratch: Path) -> str:
    """One uiautomator dump of the foreground window as text (the file is `scratch`)."""
    adb.dump_tree(scratch)  # type: ignore[attr-defined]
    return scratch.read_text(encoding="utf-8")
