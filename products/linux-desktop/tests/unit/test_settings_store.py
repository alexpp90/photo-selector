"""Settings survive a crash during a write and a damaged file is kept (BL-D-5)."""

import json
import logging
from pathlib import Path
from unittest.mock import patch

from photo_selector_linux.core.settings import SettingsStore


def test_save_replaces_the_file_atomically(tmp_path: Path):
    path = tmp_path / "cfg" / "settings.json"
    store = SettingsStore(config_path=path, auto_load=False)
    store.update(selection_folder_name="Keepers")
    store.update(selection_folder_name="Best")

    assert json.loads(path.read_text())["selection_folder_name"] == "Best"
    assert sorted(p.name for p in path.parent.iterdir()) == ["settings.json"], "no temp file left behind"


def test_a_crash_while_writing_keeps_the_previous_settings(tmp_path: Path):
    path = tmp_path / "settings.json"
    store = SettingsStore(config_path=path, auto_load=False)
    store.update(selection_folder_name="Keepers")

    with patch("os.replace", side_effect=OSError("killed")):
        store.update(selection_folder_name="Lost")

    assert SettingsStore.load(path).selection_folder_name == "Keepers"
    assert [p.name for p in tmp_path.iterdir()] == ["settings.json"]


def test_a_damaged_file_is_logged_and_kept(tmp_path: Path, caplog):
    path = tmp_path / "settings.json"
    path.write_text('{"selection_folder_name": "Keepers"', encoding="utf-8")  # cut off mid-write
    with caplog.at_level(logging.WARNING):
        store = SettingsStore.load(path)

    assert store.selection_folder_name == "Selection"  # defaults apply
    assert "settings" in caplog.text.lower() and str(path) in caplog.text
    assert (tmp_path / "settings.json.corrupt").read_text() == '{"selection_folder_name": "Keepers"'
