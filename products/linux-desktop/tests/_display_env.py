"""Keep GTK test windows off the user's real desktop (direct ``pytest`` runs).

``scripts/run_tests.sh`` already runs the GTK gates under a private Xvfb with ``WAYLAND_DISPLAY``
unset. A bare ``pytest`` from a GNOME Wayland session would not: GTK 4 prefers Wayland over
``DISPLAY`` and the test windows would open on the real monitor. ``PST_SHOW_GUI=1`` opts out.
"""

from collections.abc import MutableMapping


def keep_gui_off_real_desktop(env: MutableMapping[str, str]) -> None:
    """Make ``env`` safe for GTK tests, in place.

    With ``WAYLAND_DISPLAY`` set the caller is a live Wayland session. Its ``DISPLAY`` (if any) is
    that session's Xwayland, also the real desktop, so both are dropped: the tests then run
    against the stubs in ``conftest.py`` instead of opening windows. Without it, a ``DISPLAY`` is a
    private X server (Xvfb) and stays; the backend is pinned to x11 either way.
    """
    if env.get("PST_SHOW_GUI") == "1":
        return
    if env.pop("WAYLAND_DISPLAY", None):
        env.pop("DISPLAY", None)
    env["GDK_BACKEND"] = "x11"
