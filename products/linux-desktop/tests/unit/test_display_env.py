"""Test windows must never open on the user's real desktop (backlog 2026-11-09)."""

import os

from _display_env import keep_gui_off_real_desktop


def test_wayland_session_is_dropped_and_x11_forced():
    env = {"WAYLAND_DISPLAY": "wayland-0", "DISPLAY": ":0"}
    keep_gui_off_real_desktop(env)
    assert "WAYLAND_DISPLAY" not in env
    assert "DISPLAY" not in env  # the session's Xwayland is the real desktop too
    assert env["GDK_BACKEND"] == "x11"


def test_private_xvfb_display_is_kept():
    env = {"DISPLAY": ":99"}
    keep_gui_off_real_desktop(env)
    assert env == {"DISPLAY": ":99", "GDK_BACKEND": "x11"}


def test_show_gui_leaves_the_environment_alone():
    env = {"WAYLAND_DISPLAY": "wayland-0", "DISPLAY": ":0", "PST_SHOW_GUI": "1"}
    keep_gui_off_real_desktop(env)
    assert env == {"WAYLAND_DISPLAY": "wayland-0", "DISPLAY": ":0", "PST_SHOW_GUI": "1"}


def test_the_environment_the_gtk_tests_run_in_has_no_wayland():
    """What GTK sees in this very process (conftest has run): no compositor to open on."""
    if os.environ.get("PST_SHOW_GUI") == "1":
        return
    assert "WAYLAND_DISPLAY" not in os.environ
    assert os.environ.get("GDK_BACKEND") == "x11"
