"""A settings file written by an older release migrates, and keeps migrating (every product has one).

Each release added keys; an older file lacks them. Loading must apply the old values, give the new
keys the defaults a returning user expects (an old file keeps the file-name order it always had),
and the first save must write the full current shape without dropping what was there.
"""

import json
from pathlib import Path

from photo_selector_linux.core.settings import ORDER_NAME, ORDER_NEWEST, SettingsStore

#: Settings of the first release: only the three preference keys it knew.
FIRST_RELEASE = {"selection_folder_name": "Keepers", "auto_advance": False, "blurry_threshold": 20}


def _write(path: Path, data) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data), encoding="utf-8")
    return path


def test_the_first_release_file_loads_with_defaults_for_the_newer_keys(tmp_path: Path):
    store = SettingsStore(path=_write(tmp_path / "settings.json", FIRST_RELEASE))
    assert store.selection_folder_name == "Keepers"
    assert store.auto_advance is False
    assert store.blurry_threshold == 20
    assert store.view_order == ORDER_NAME  # an existing file keeps the order it always had
    assert store.recent_folders == []
    assert store.destination_mode == "working"
    assert store.stop_sharpness_threshold is None


def test_a_new_install_without_a_file_opens_newest_first(tmp_path: Path):
    assert SettingsStore(path=tmp_path / "missing.json").view_order == ORDER_NEWEST


def test_the_first_save_writes_the_full_shape_and_it_reads_back_the_same(tmp_path: Path):
    path = _write(tmp_path / "settings.json", FIRST_RELEASE)
    store = SettingsStore(path=path)
    store.save()
    written = json.loads(path.read_text(encoding="utf-8"))
    assert {"view_order", "recent_folders", "destination_mode", "cache_limit_mb"} <= set(written)
    again = SettingsStore(path=path)
    assert again.to_dict() == store.to_dict()
    assert again.view_order == ORDER_NAME


def test_values_of_the_wrong_type_fall_back_instead_of_breaking_the_load(tmp_path: Path):
    path = _write(
        tmp_path / "settings.json",
        {"blurry_threshold": "high", "cache_limit_mb": None, "recent_folders": "not a list", "view_order": 7},
    )
    store = SettingsStore(path=path)
    assert store.blurry_threshold == 35.0
    assert store.cache_limit_mb == 512.0
    assert store.recent_folders == []
    assert store.view_order == ORDER_NAME


def test_the_stored_cache_limit_still_loads_when_its_row_is_hidden(tmp_path: Path):
    """The cache-size row leaves Preferences, the stored value is kept and still read."""
    path = _write(tmp_path / "settings.json", {"cache_limit_mb": 2048})
    store = SettingsStore(path=path)
    assert store.cache_limit_mb == 2048
    store.update(auto_advance=False)
    assert json.loads(path.read_text(encoding="utf-8"))["cache_limit_mb"] == 2048
