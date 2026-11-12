"""The four states of the canvas (plan 1.1) and the done screen (plan 1.2).

The view model names the state, the canvas draws one hero per state with its own text. The widget
tests build the real tree under ``xvfb-run``; on the stubs only the state model is checked.
"""

from pathlib import Path
from unittest.mock import MagicMock

import pytest

from photo_selector_linux.core.settings import SettingsStore
from photo_selector_linux.ui import canvas as canvas_module
from photo_selector_linux.viewmodels.culling_workspace import CullingWorkspaceViewModel

REAL = canvas_module.HAS_GI and not isinstance(canvas_module.Gtk, MagicMock)


def _vm(tmp_path: Path) -> CullingWorkspaceViewModel:
    store = SettingsStore(path=tmp_path / "settings.json")
    store.view_order = "natural"
    return CullingWorkspaceViewModel(settings=store)


def _folder(tmp_path: Path, photos: int) -> Path:
    folder = tmp_path / "Lisbon"
    folder.mkdir()
    for n in range(photos):
        (folder / f"IMG_000{n + 1}.JPG").write_bytes(b"x%d" % n)
    return folder


def test_the_state_follows_the_folder_the_scan_and_the_decisions(tmp_path: Path):
    vm = _vm(tmp_path)
    assert vm.canvas_state == "start"
    vm.load_directory(_folder(tmp_path, 2))
    assert vm.canvas_state == "photos"
    assert vm.cull_move() and vm.cull_move()
    assert vm.candidates == [] and vm.canvas_state == "done"
    assert vm.undo() and vm.canvas_state == "photos"


def test_an_empty_folder_is_not_done_and_a_scan_in_flight_is_reading(tmp_path: Path):
    vm = _vm(tmp_path)
    vm.load_directory(_folder(tmp_path, 0))
    assert vm.canvas_state == "empty"
    vm.is_scanning = True
    assert vm.canvas_state == "reading"
    vm.is_scanning = False
    vm.scan_failed = True
    assert vm.canvas_state == "unreadable"


def test_the_summary_names_the_selection_and_gives_no_verdict_off_a_card(tmp_path: Path):
    vm = _vm(tmp_path)
    vm.load_directory(_folder(tmp_path, 1))
    vm.cull_move()
    summary = vm.card_summary()
    assert summary["kept"] == 1 and summary["remaining"] == 0
    assert summary["selection_path"] == vm.resolve_destination().path
    assert summary["verdict"] == "none"


@pytest.mark.skipif(not REAL, reason="needs real Gtk under a display server")
@pytest.mark.parametrize("state", ["start", "reading", "empty", "unreadable", "done"])
def test_each_state_renders_its_own_named_hero_with_its_own_text(state, tmp_path: Path):
    from gi.repository import Gtk

    vm = _vm(tmp_path)
    vm.current_folder = None if state == "start" else tmp_path / "Lisbon"
    vm.current_folder_name = "Lisbon"
    vm.is_scanning = state == "reading"
    vm.scan_failed = state == "unreadable"
    vm.selected_count, vm.trashed_count = (3, 1) if state == "done" else (0, 0)
    view = canvas_module.CanvasView(viewmodel=vm)
    view.set_state_card_actions(lambda: None, lambda: None)  # redraws the card with its buttons

    names, texts = [], []

    def walk(widget):
        child = widget.get_first_child()
        while child is not None:
            names.append(child.get_name())
            if isinstance(child, Gtk.Label):
                texts.append(child.get_text())
            walk(child)
            child = child.get_next_sibling()

    walk(view)
    assert f"canvas_state_{state}" in names
    joined = " | ".join(texts)
    expected = {
        "start": ["Drop a folder", "Open Folder…"],
        "reading": ["Reading Lisbon"],
        "empty": ["No photos", "Lisbon"],
        "unreadable": ["Can't read folder"],
        "done": ["Done", "3 Selected", "1 Trashed", "Show in Files", "Open Folder…"],
    }[state]
    for text in expected:
        assert text in joined or text in " ".join(
            b.get_label() or "" for b in _buttons(view)
        ), (state, text, joined)
    assert "No photographs" not in joined and "No photos loaded" not in joined


def _buttons(root):
    from gi.repository import Gtk

    found = []

    def walk(widget):
        child = widget.get_first_child()
        while child is not None:
            if isinstance(child, Gtk.Button):
                found.append(child)
            walk(child)
            child = child.get_next_sibling()

    walk(root)
    return found


@pytest.mark.skipif(not REAL, reason="needs real Gtk under a display server")
def test_the_start_card_of_a_real_window_opens_the_folder_dialog(tmp_path: Path, monkeypatch):
    from photo_selector_linux.ui import window as window_module

    opened = []
    monkeypatch.setattr(window_module.MainWindow, "show_open_folder_dialog", lambda self: opened.append(1))
    win = window_module.MainWindow(settings=SettingsStore(path=tmp_path / "settings.json"))
    button = next(b for b in _buttons(win.canvas_view) if b.get_name() == "btn_state_open_folder")
    button.emit("clicked")
    assert opened == [1]
