"""The rest of plan phase 1: the drop outline (1.3), the card notice (1.6), the sweep spinner and
"Stop at sharpness" in the order panel (1.7)."""

import os
from pathlib import Path
from unittest.mock import MagicMock

import pytest

from photo_selector_linux.core import volumes
from photo_selector_linux.core.settings import SettingsStore
from photo_selector_linux.ui import window as window_module
from photo_selector_linux.viewmodels import culling_workspace as workspace_module
from photo_selector_linux.viewmodels.culling_workspace import CullingWorkspaceViewModel

REAL = window_module.HAS_GI and not isinstance(window_module.Gtk, MagicMock)


def test_the_selection_stays_on_the_card_only_for_a_removable_source_on_the_same_volume(tmp_path):
    source = tmp_path / "card"
    source.mkdir()
    selection = source / "Selection"
    assert volumes.selection_stays_on_card(source, selection, removable=lambda p: True)
    assert not volumes.selection_stays_on_card(source, selection, removable=lambda p: False)
    elsewhere = tmp_path / "elsewhere"
    elsewhere.mkdir()
    assert not volumes.selection_stays_on_card(
        source, elsewhere / "Selection", removable=lambda p: True,
        device_of=lambda p: 2 if p == elsewhere else 1,
    )
    assert not volumes.selection_stays_on_card(source, None, removable=lambda p: True)


def _card(tmp_path: Path) -> Path:
    card = tmp_path / "card"
    card.mkdir()
    for number, name in enumerate(("IMG_0001.JPG", "IMG_0002.JPG")):
        (card / name).write_bytes(name.encode())
        os.utime(card / name, (1_700_000_000 + number, 1_700_000_000 + number))
    return card


def _notes(vm):
    notes = []
    vm.connect(
        "notification",
        lambda _vm, msg: notes.append(msg) if vm.last_notification_severity == "status" else None,
    )
    return notes


def test_the_first_message_says_the_selection_is_on_the_card_and_stays_in_the_slot(tmp_path, monkeypatch):
    store = SettingsStore(path=tmp_path / "settings.json")
    store.view_order = "natural"
    monkeypatch.setattr(workspace_module, "selection_stays_on_card", lambda source, selection: True)
    vm = CullingWorkspaceViewModel(settings=store)
    notes = _notes(vm)
    vm.load_directory(_card(tmp_path))
    assert vm.cull_copy() and vm.cull_copy()
    assert notes[0].endswith(workspace_module.ON_CARD_NOTE)
    assert "Selection/" in notes[0]
    assert len(notes[0]) <= len("Copied to ") + workspace_module.MESSAGE_PATH_CHARS
    assert workspace_module.ON_CARD_NOTE not in notes[1]  # said once per folder


def test_no_card_notice_when_the_selection_is_elsewhere(tmp_path, monkeypatch):
    store = SettingsStore(path=tmp_path / "settings.json")
    store.view_order = "natural"
    monkeypatch.setattr(workspace_module, "selection_stays_on_card", lambda source, selection: False)
    vm = CullingWorkspaceViewModel(settings=store)
    notes = _notes(vm)
    vm.load_directory(_card(tmp_path))
    assert vm.cull_copy()
    assert workspace_module.ON_CARD_NOTE not in notes[0]


@pytest.mark.skipif(not REAL, reason="needs real Gtk under a display server")
def test_the_canvas_is_outlined_while_a_folder_is_dragged_over_and_cleared_on_leave_or_drop(tmp_path):
    from gi.repository import Gdk

    win = window_module.MainWindow(settings=SettingsStore(path=tmp_path / "settings.json"))
    canvas = win.canvas_view
    assert not canvas.has_css_class(window_module.DROP_ACTIVE_CLASS)
    assert win._on_drop_enter(None, 0, 0) == Gdk.DragAction.COPY
    assert canvas.has_css_class(window_module.DROP_ACTIVE_CLASS)
    win._on_drop_leave(None)
    assert not canvas.has_css_class(window_module.DROP_ACTIVE_CLASS)
    win._on_drop_enter(None, 0, 0)
    win._on_folder_dropped(None, None, 0, 0)
    assert not canvas.has_css_class(window_module.DROP_ACTIVE_CLASS)


def test_the_outline_rule_is_in_the_stylesheet():
    from photo_selector_linux.ui import styling

    css = styling.STUDIO_DARK_CSS
    assert ".drop-target-active" in css and "outline" in css


@pytest.mark.skipif(not REAL, reason="needs real Gtk under a display server")
def test_a_spinner_turns_beside_the_order_while_the_sweep_scores_and_stops_after(tmp_path):
    win = window_module.MainWindow(settings=SettingsStore(path=tmp_path / "settings.json"))
    assert not win.order_spinner.get_visible()
    win.viewmodel._scoring_inflight = {"a": object(), "b": object()}
    win.viewmodel._scoring_total, win.viewmodel._scoring_done = 5, 3
    win._on_viewmodel_state_changed(win.viewmodel)
    assert win.order_spinner.get_visible() and win.order_spinner.get_spinning()
    assert "Scoring 3 of 5" in win.btn_order.get_tooltip_text()
    win.viewmodel._scoring_inflight = {}
    win._on_viewmodel_state_changed(win.viewmodel)
    assert not win.order_spinner.get_visible() and not win.order_spinner.get_spinning()
    assert "Scoring" not in win.btn_order.get_tooltip_text()
