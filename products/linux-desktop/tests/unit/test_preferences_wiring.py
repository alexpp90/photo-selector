"""Unit tests for SettingsStore persistence and PreferencesDialog / Workspace / Cache / HUD wiring.

Adheres to:
- R-LINUX-PREF-01: Decoupled AdwPreferencesWindow
- R-LINUX-PREF-02: Configurable Application Preferences
- R-LINUX-META-05: Dynamic Selection Subfolder Exclusion Invariant
"""

from pathlib import Path
from unittest.mock import MagicMock

from photo_selector_linux.core.models import (
    CullingActionType,
    CullingRecord,
    QualityScores,
)
from photo_selector_linux.core.settings import SettingsStore
from photo_selector_linux.ui.canvas import CanvasView
from photo_selector_linux.ui.photo_loader import PhotoTextureLoader
from photo_selector_linux.ui.preferences import PreferencesDialog
from photo_selector_linux.viewmodels.culling_workspace import CullingWorkspaceViewModel


def test_settings_store_persists_and_loads_from_xdg_config(tmp_path: Path, monkeypatch):
    """Verify SettingsStore reads and writes $XDG_CONFIG_HOME/photo-selector-linux/settings.json."""
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))

    store = SettingsStore.load()
    assert store.selection_folder_name == "Selection"
    assert store.auto_advance is True
    assert store.blurry_threshold == 35.0
    assert store.sharp_threshold == 70.0
    assert store.cache_limit_mb == 512.0

    store.update(
        selection_folder_name="Keeps",
        auto_advance=False,
        blurry_threshold=42.0,
        sharp_threshold=82.0,
        cache_limit_mb=768.0,
        external_destination_folder="/mnt/archive",
        stop_sharpness_threshold=65.0,
    )

    reloaded = SettingsStore.load()
    assert reloaded.selection_folder_name == "Keeps"
    assert reloaded.auto_advance is False
    assert reloaded.blurry_threshold == 42.0
    assert reloaded.sharp_threshold == 82.0
    assert reloaded.cache_limit_mb == 768.0
    assert reloaded.external_destination_folder == "/mnt/archive"
    assert reloaded.stop_sharpness_threshold == 65.0


def test_preferences_dialog_wires_controls_and_removes_bind_companions_toggle(tmp_path: Path):
    """Verify PreferencesDialog updates SettingsStore and does not expose a 'Bind Companion Files' toggle."""
    store = SettingsStore(path=tmp_path / "settings.json")
    dlg = PreferencesDialog(settings=store)

    # 'Bind Companion Files' toggle must not exist (companions always travel together per R-LINUX-CULL-03)
    assert not hasattr(dlg, "row_companions")

    dlg.row_sel_folder.set_text("Picks")
    dlg._on_selection_folder_changed(dlg.row_sel_folder)
    assert store.selection_folder_name == "Picks"

    dlg.row_auto_advance.set_active(False)
    dlg._on_auto_advance_changed(dlg.row_auto_advance)
    assert store.auto_advance is False

    dlg.scale_blurry.set_value(40.0)
    dlg._on_blurry_changed(dlg.scale_blurry)
    assert store.blurry_threshold == 40.0

    dlg.scale_sharp.set_value(78.0)
    dlg._on_sharp_changed(dlg.scale_sharp)
    assert store.sharp_threshold == 78.0

    dlg.scale_cache.set_value(1024.0)
    dlg._on_cache_changed(dlg.scale_cache)
    assert store.cache_limit_mb == 1024.0


def test_settings_propagate_to_workspace_scanner_cache_and_hud(tmp_path: Path):
    """Verify SettingsStore live-updates CullingWorkspaceViewModel, scanner exclusions, cache ceiling, and HUD."""
    store = SettingsStore(path=tmp_path / "settings.json")
    store.view_order = "natural"  # this test is about the scan's exclusions, not the order
    file_mgr = MagicMock()
    vm = CullingWorkspaceViewModel(file_manager=file_mgr, settings=store)
    loader = PhotoTextureLoader(max_mb=store.cache_limit_mb)
    canvas = CanvasView(viewmodel=vm, loader=loader)

    # Create a folder with a custom selection directory "Picks"
    shoot = tmp_path / "shoot"
    picks = shoot / "Picks"
    picks.mkdir(parents=True)
    (shoot / "IMG_0001.JPG").write_bytes(b"1")
    (shoot / "IMG_0002.JPG").write_bytes(b"2")
    (picks / "IMG_9999.JPG").write_bytes(b"already-picked")

    store.update(
        selection_folder_name="Picks",
        auto_advance=False,
        blurry_threshold=45.0,
        sharp_threshold=85.0,
        cache_limit_mb=256.0,
    )

    assert vm.selection_folder_name == "Picks"
    assert vm.auto_advance is False
    assert "picks" in vm.scanner.excluded_names
    assert loader.cache.max_bytes == 256 * 1024 * 1024

    # Scanner excludes "Picks/"
    vm.load_directory(shoot)
    assert [c.filename for c in vm.candidates] == ["IMG_0001.JPG", "IMG_0002.JPG"]

    # Copy with auto_advance=False keeps current_index at 0 and passes selection_folder_name="Picks"
    c0 = vm.candidates[0]
    file_mgr.copy_candidate.return_value = CullingRecord(
        photo_id=c0.id,
        action_type=CullingActionType.COPY,
        original_primary_path=c0.primary_path,
        affected_paths=[(c0.primary_path, picks / c0.filename)],
    )
    vm.cull_copy()
    assert vm.current_index == 0
    file_mgr.copy_candidate.assert_called_once_with(
        c0, shoot.resolve(), selection_folder="Picks"
    )

    # InfoHUD respects updated blurry/sharp thresholds (score 40.0 is Blurry when blurry_threshold=45.0)
    c0.scores = QualityScores(
        sharpness=40.0,
        noise=1.0,
        highlight_clipping=0.0,
        shadow_clipping=0.0,
        blurry_threshold=store.blurry_threshold,
        sharp_threshold=store.sharp_threshold,
    )
    canvas._render_view()
    assert canvas.hud.score_badge_text == "Sharpness: 40.0"
