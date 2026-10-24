"""UI entry points for the lowest-sharpness-first view order (UC-13).

Adheres to:
- R-LINUX-CULL-02: Lowest-Sharpness View Order (main menu radio + B key)
- R-LINUX-KEY-05: Inspection Hotkeys (B)
"""

from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest

from photo_selector_linux.core.models import CandidatePhoto, QualityScores
from photo_selector_linux.ui import canvas as canvas_module
from photo_selector_linux.ui import window as window_module

# Without a display conftest.py stubs Gtk; with one (Linux CI under xvfb-run) the real widgets run.
STUBBED = isinstance(canvas_module.Gtk, MagicMock)


def _photo(tmp_path: Path, name: str, sharpness: float) -> CandidatePhoto:
    return CandidatePhoto(
        primary_path=tmp_path / name,
        scores=QualityScores(
            sharpness=sharpness, noise=1.0, highlight_clipping=0.0, shadow_clipping=0.0
        ),
    )


def _window(tmp_path: Path, monkeypatch):
    win = window_module.MainWindow()
    monkeypatch.setattr(win, "show_toast", MagicMock())
    win.viewmodel.candidates = [
        _photo(tmp_path, "DSC_0001.JPG", 80.0),
        _photo(tmp_path, "DSC_0002.JPG", 10.0),
        _photo(tmp_path, "DSC_0003.JPG", 40.0),
    ]
    return win


def _names(win):
    return [c.filename for c in win.viewmodel.candidates]


def test_menu_action_handler_switches_order(tmp_path, monkeypatch):
    win = _window(tmp_path, monkeypatch)
    win._on_view_order_action(None, SimpleNamespace(get_string=lambda: "lowest_sharpness_first"))
    assert win.viewmodel.sort_order == "lowest_sharpness_first"
    assert _names(win) == ["DSC_0002.JPG", "DSC_0003.JPG", "DSC_0001.JPG"]

    win._on_view_order_action(None, SimpleNamespace(get_string=lambda: "natural"))
    assert win.viewmodel.sort_order == "natural"
    assert _names(win) == ["DSC_0001.JPG", "DSC_0002.JPG", "DSC_0003.JPG"]


def test_unknown_order_is_ignored(tmp_path, monkeypatch):
    win = _window(tmp_path, monkeypatch)
    win.set_view_order("bogus")
    assert win.viewmodel.sort_order == "natural"


def test_b_key_toggles_order_through_the_router(tmp_path, monkeypatch):
    win = _window(tmp_path, monkeypatch)
    assert win.router.engine.dispatch(0x62, 0) is True
    assert win.viewmodel.sort_order == "lowest_sharpness_first"
    assert _names(win)[0] == "DSC_0002.JPG"
    assert win.router.engine.dispatch(0x42, 0) is True
    assert win.viewmodel.sort_order == "natural"
    assert win.show_toast.call_count == 2


def test_shortcuts_sheet_lists_b():
    source = (
        Path(window_module.__file__).with_name("shortcuts_dialog.py").read_text(encoding="utf-8")
    )
    assert '"B"' in source


@pytest.mark.skipif(STUBBED, reason="needs the real GTK 4 / Gio action machinery (xvfb-run)")
def test_running_app_menu_action_and_key_switch_order(tmp_path, monkeypatch):
    from gi.repository import GLib

    win = _window(tmp_path, monkeypatch)
    action = win.lookup_action("view_order")
    assert action is not None
    assert action.get_state().get_string() == "natural"

    action.activate(GLib.Variant.new_string("lowest_sharpness_first"))
    assert win.viewmodel.sort_order == "lowest_sharpness_first"
    assert action.get_state().get_string() == "lowest_sharpness_first"

    win.router.engine.dispatch(0x62, 0)
    assert win.viewmodel.sort_order == "natural"
    assert action.get_state().get_string() == "natural"
