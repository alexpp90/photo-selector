"""A start that remembers (plan 1.3): recent folders, a dropped folder, a dialog that starts where
the last one was. Settings written by 0.2.0 (no ``recent_folders`` key) load unchanged."""

import json
from pathlib import Path
from unittest.mock import MagicMock

import pytest

from photo_selector_linux.core.settings import RECENT_FOLDERS_MAX, SettingsStore
from photo_selector_linux.ui import window as window_module

REAL = window_module.HAS_GI and not isinstance(window_module.Gtk, MagicMock)


def test_settings_of_0_2_0_load_unchanged_and_gain_an_empty_list(tmp_path: Path):
    path = tmp_path / "settings.json"
    path.write_text(json.dumps({"selection_folder_name": "Keepers", "auto_advance": False, "future": 1}))
    store = SettingsStore(path=path, auto_load=True)
    assert store.selection_folder_name == "Keepers" and store.auto_advance is False
    assert store.recent_folders == []
    store.save()
    assert json.loads(path.read_text())["future"] == 1  # unknown keys still survive


def test_the_last_five_folders_are_kept_newest_first_without_repeats(tmp_path: Path):
    store = SettingsStore(path=tmp_path / "settings.json")
    for n in range(7):
        store.remember_folder(tmp_path / f"f{n}")
    store.remember_folder(tmp_path / "f4")
    assert len(store.recent_folders) == RECENT_FOLDERS_MAX
    assert store.recent_folders[0] == str(tmp_path / "f4")
    assert len(set(store.recent_folders)) == RECENT_FOLDERS_MAX
    reloaded = SettingsStore(path=tmp_path / "settings.json", auto_load=True)
    assert reloaded.recent_folders == store.recent_folders


def test_a_garbled_recent_list_is_ignored(tmp_path: Path):
    path = tmp_path / "settings.json"
    path.write_text(json.dumps({"recent_folders": ["/a", 3, "", "/a", None, "/b"]}))
    assert SettingsStore(path=path, auto_load=True).recent_folders == ["/a", "/b"]


@pytest.mark.skipif(not REAL, reason="needs real Gtk under a display server")
def test_opening_a_folder_remembers_it_the_start_card_lists_it_and_a_click_reopens_it(tmp_path: Path):
    from gi.repository import Gtk

    kept = tmp_path / "Lisbon"
    kept.mkdir()
    gone = tmp_path / "Gone"
    store = SettingsStore(path=tmp_path / "settings.json")
    store.update(recent_folders=[str(kept), str(gone)])
    win = window_module.MainWindow(settings=store)

    def rows(widget, found):
        child = widget.get_first_child()
        while child is not None:
            if isinstance(child, Gtk.Button) and child.get_tooltip_text() in (str(kept), str(gone)):
                found.append(child)
            rows(child, found)
            child = child.get_next_sibling()
        return found

    buttons = rows(win.canvas_view, [])
    assert len(buttons) == 2
    by_tip = {b.get_tooltip_text(): b for b in buttons}
    assert by_tip[str(kept)].get_sensitive() and not by_tip[str(gone)].get_sensitive()
    by_tip[str(kept)].emit("clicked")
    assert win.viewmodel.current_folder == kept.resolve() or win.viewmodel.current_folder == kept
    assert store.recent_folders[0] == str(kept)


@pytest.mark.skipif(not REAL, reason="needs real Gtk under a display server")
def test_a_dropped_folder_opens(tmp_path: Path):
    from gi.repository import Gio

    folder = tmp_path / "Dropped"
    folder.mkdir()
    win = window_module.MainWindow(settings=SettingsStore(path=tmp_path / "settings.json"))
    assert win._on_folder_dropped(None, Gio.File.new_for_path(str(folder)), 0, 0) is True
    assert Path(win.viewmodel.current_folder).name == "Dropped"
    assert win._on_folder_dropped(None, None, 0, 0) is False
