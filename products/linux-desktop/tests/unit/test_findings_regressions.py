"""Regression guards for findings that had no test (ledger rows F-BL-010, -027, -035, -051).

Adheres to:
- R-LINUX-META-04: Lightroom -Edit / -Enhanced-NR derivatives bind to their shot
- R-LINUX-CULL-03/04/05/07: Move, Copy, Trash and Undo act on the whole bundle
- R-LINUX-CULL-05: Trash through GIO
- R-LINUX-KEY-05: Inspection hotkeys (Tab, F, S)
- R-LINUX-PREF-02: Configurable preferences
"""

from pathlib import Path
from unittest.mock import MagicMock

import pytest

from photo_selector_linux.core import file_ops
from photo_selector_linux.core.file_ops import FileCullingManager
from photo_selector_linux.core.scanner import DirectoryScanner
from photo_selector_linux.ui import preferences as preferences_module
from photo_selector_linux.ui.keyboard_router import KeyboardRouter, KeyboardRouterEngine

STUBBED = isinstance(preferences_module.Gtk, MagicMock) or not preferences_module.HAS_GI


# ── F-BL-010: the -Enhanced-NR derivative travels with its shot ──────────────

SHOT = "DSC0050.ARW"


@pytest.fixture(params=["DSC0050-Enhanced-NR.dng", "DSC0050_Enhanced-NR.dng"])
def derivatives(request):
    return (request.param, "DSC0050-Edit.tif", "DSC0050.xmp")


def _names(folder: Path) -> set:
    """File names in a folder, without the hidden marker the Selection folder carries."""
    return {p.name for p in folder.iterdir() if not p.name.startswith(".")}


def _shot(tmp_path: Path, derivatives):
    root = tmp_path / "shoot"
    root.mkdir()
    (root / SHOT).write_bytes(b"raw")
    for name in derivatives:
        (root / name).write_bytes(b"derivative " + name.encode())
    (root / "DSC0051.ARW").write_bytes(b"other shot")
    candidates = DirectoryScanner().scan_directory(root)
    shot = next(c for c in candidates if any(p.name == SHOT for p in c.all_paths))
    return root, shot


def test_a_move_takes_the_enhanced_nr_derivatives_along_and_undo_brings_them_back(tmp_path, derivatives):
    root, shot = _shot(tmp_path, derivatives)
    assert {p.name for p in shot.all_paths} == {SHOT, *derivatives}
    manager = FileCullingManager(trash_dir=tmp_path / "Trash")
    record = manager.move_candidate(shot, root)
    selection = root / "Selection"
    assert _names(selection) == {SHOT, *derivatives}
    assert [p.name for p in root.iterdir() if p.is_file()] == ["DSC0051.ARW"]
    assert manager.undo_culling(record, shot) is True
    assert {p.name for p in root.iterdir() if p.is_file()} == {SHOT, "DSC0051.ARW", *derivatives}


def test_a_copy_takes_the_enhanced_nr_derivatives_along(tmp_path, derivatives):
    root, shot = _shot(tmp_path, derivatives)
    FileCullingManager(trash_dir=tmp_path / "Trash").copy_candidate(shot, root)
    assert _names(root / "Selection") == {SHOT, *derivatives}
    assert all((root / name).exists() for name in (SHOT, *derivatives)), "a copy leaves the originals"


def test_trash_takes_the_enhanced_nr_derivatives_along(tmp_path, derivatives):
    root, shot = _shot(tmp_path, derivatives)
    trash = tmp_path / "Trash"
    FileCullingManager(trash_dir=trash).trash_candidate(shot)
    assert _names(trash / "files") == {SHOT, *derivatives}
    assert [p.name for p in root.iterdir() if p.is_file()] == ["DSC0051.ARW"]


# ── F-BL-027: XDG_DATA_HOME must not switch off the GIO trash ────────────────


def test_a_custom_xdg_data_home_still_trashes_through_gio(tmp_path, monkeypatch):
    photo = tmp_path / "a.jpg"
    photo.write_bytes(b"x")
    through_gio = []

    def fake_gio_trash(self, src):
        through_gio.append(src)
        files = tmp_path / "gio-trash" / "files"
        info = tmp_path / "gio-trash" / "info"
        files.mkdir(parents=True, exist_ok=True)
        info.mkdir(parents=True, exist_ok=True)
        moved = files / src.name
        src.rename(moved)
        entry = info / f"{src.name}.trashinfo"
        entry.write_text("[Trash Info]\n")
        return moved, entry

    monkeypatch.setattr(file_ops, "_HAVE_GIO", True)
    monkeypatch.setattr(FileCullingManager, "_trash_via_gio", fake_gio_trash)
    monkeypatch.setenv("XDG_DATA_HOME", str(tmp_path / "data"))
    from photo_selector_linux.core.models import CandidatePhoto

    FileCullingManager().trash_candidate(CandidatePhoto(primary_path=photo))
    assert through_gio == [photo]


# ── F-BL-035: a key the router consumes does something; the stop threshold has a UI ──────────

#: (name, keyval, state): every single-key and Ctrl binding of the engine.
KEYS = [
    ("M", 0x6D, 0), ("C", 0x63, 0), ("Delete", 0xFFFF, 0), ("Backspace", 0xFF08, 0),
    ("Left", 0xFF51, 0), ("Right", 0xFF53, 0), ("H", 0x68, 0), ("J", 0x6A, 0), ("K", 0x6B, 0), ("L", 0x6C, 0),
    ("1", 0x31, 0), ("2", 0x32, 0), ("3", 0x33, 0),
    ("Space", 0x20, 0), ("Escape", 0xFF1B, 0), ("I", 0x69, 0), ("B", 0x62, 0), ("G", 0x67, 0), ("?", 0x3F, 0),
    ("Ctrl+Z", 0x7A, 4), ("Ctrl+O", 0x6F, 4), ("Ctrl+D", 0x64, 4), ("Ctrl+L", 0x6C, 4), ("Ctrl+,", 0x2C, 4),
]


@pytest.mark.parametrize("name,keyval,state", KEYS, ids=[k[0] for k in KEYS])
def test_a_key_the_router_consumes_has_an_effect(name, keyval, state):
    """Consumed means handled: the key must reach the window or the view model, not an empty stub."""
    window = MagicMock()
    viewmodel = MagicMock()
    viewmodel.sort_order = "natural"
    viewmodel.scoring_confirmation_pending = False
    router = KeyboardRouter(window=window, viewmodel=viewmodel)
    consumed = router.engine.dispatch(keyval, state)
    assert consumed is True, f"{name} is not bound"
    assert window.mock_calls or viewmodel.mock_calls, f"{name} is consumed but nothing happens"


def test_tab_f_and_s_are_not_bound():
    """R-LINUX-KEY-05 lists Space, Escape, I and B: Tab is focus traversal, F and S have no feature."""
    router = KeyboardRouter(window=MagicMock(), viewmodel=MagicMock())
    for keyval in (KeyboardRouterEngine.KEY_TAB, 0x66, 0x73):
        assert router.engine.dispatch(keyval, 0) is False


@pytest.mark.skipif(STUBBED, reason="needs real Gtk/Adw under a display server")
def test_a_preferences_control_sets_the_sweep_stop_threshold(tmp_path):
    from gi.repository import Adw
    from photo_selector_linux.core.settings import SettingsStore

    Adw.init()
    settings = SettingsStore(path=tmp_path / "settings.json", auto_load=False)
    dialog = preferences_module.PreferencesDialog(settings=settings)
    try:
        row = dialog.row_stop_sharpness
        assert row.get_title() == "Stop at sharpness"
        assert row.get_value() == 0.0  # off
        row.set_value(80.0)
        assert settings.stop_sharpness_threshold == 80.0
        row.set_value(0.0)
        assert settings.stop_sharpness_threshold is None
    finally:
        dialog.destroy()


# ── F-BL-051: titles with "<" or "&" are parsed as Pango markup ──────────────


@pytest.mark.skipif(STUBBED, reason="needs real Gtk/Adw under a display server")
def test_preferences_titles_are_valid_markup_or_not_markup(tmp_path):
    import gtk_layout_probe as probe
    from gi.repository import Adw, GLib, Pango
    from photo_selector_linux.core.settings import SettingsStore

    Adw.init()
    dialog = preferences_module.PreferencesDialog(settings=SettingsStore(path=tmp_path / "s.json", auto_load=False))
    bad = []
    try:
        for widget in probe.walk(dialog):
            # A group's get_title() returns the text after parsing, so groups are covered by the
            # rendered-label test below; rows keep the raw title.
            if isinstance(widget, Adw.PreferencesRow) and hasattr(widget, "get_use_markup"):
                markup = widget.get_use_markup()
            else:
                continue
            title = widget.get_title()
            if markup and title:
                try:
                    Pango.parse_markup(title, -1, "\0")
                except GLib.Error:
                    bad.append(title)
    finally:
        dialog.destroy()
    assert not bad, f"titles that are not valid markup: {bad}"


@pytest.mark.skipif(STUBBED, reason="needs real Gtk/Adw under a display server")
def test_preferences_titles_show_the_literal_lt_and_ampersand(tmp_path):
    """The rendered text, not the property: "Blurry Threshold (< 35.0)" and "Memory & Prefetch"."""
    import gtk_layout_probe as probe
    from gi.repository import Adw, Gtk
    from photo_selector_linux.core.settings import SettingsStore

    Adw.init()
    dialog = preferences_module.PreferencesDialog(settings=SettingsStore(path=tmp_path / "s.json", auto_load=False))
    try:
        dialog.present()
        probe.pump(0.3)
        texts = [w.get_text() for w in probe.walk(dialog) if isinstance(w, Gtk.Label)]
        assert "Blurry Threshold (< 35.0)" in texts
        assert "Memory & Prefetch" in texts
    finally:
        dialog.destroy()


# ── F-BL sweep of `except Exception: pass`: a failing observer is logged, not swallowed ───────


def test_a_failing_settings_observer_is_logged_and_the_others_still_run(tmp_path, caplog):
    from photo_selector_linux.core.settings import SettingsStore

    store = SettingsStore(path=tmp_path / "s.json", auto_load=False)
    seen = []

    def broken(_store):
        raise RuntimeError("observer bug")

    store.connect(broken)
    store.connect(lambda _store: seen.append(True))
    with caplog.at_level("ERROR"):
        store.update(auto_advance=False)
    assert seen == [True]
    assert any("observer bug" in (r.exc_text or "") for r in caplog.records)


def test_a_failing_view_model_callback_is_logged_and_the_others_still_run(caplog):
    from photo_selector_linux.viewmodels.culling_workspace import CullingWorkspaceViewModel

    vm = CullingWorkspaceViewModel()
    got = []

    def broken(*_args):
        raise RuntimeError("view bug")

    vm.connect("state-changed", broken)
    vm.connect("state-changed", lambda _vm: got.append("state"))
    vm.connect("notification", broken)
    vm.connect("notification", lambda _vm, message: got.append(message))
    with caplog.at_level("ERROR"):
        vm._notify_state_changed()
        vm._notify("hello")
    assert got == ["state", "hello"]
    assert sum("view bug" in (r.exc_text or "") for r in caplog.records) == 2


def test_a_leftover_trash_info_file_after_a_restore_is_logged(tmp_path, monkeypatch, caplog):
    manager = FileCullingManager(trash_dir=tmp_path / "Trash")
    trash_files = tmp_path / "Trash" / "files"
    trash_info = tmp_path / "Trash" / "info"
    trash_files.mkdir(parents=True)
    trash_info.mkdir()
    photo = tmp_path / "a.jpg"
    photo.write_bytes(b"x")
    from photo_selector_linux.core.models import CandidatePhoto

    candidate = CandidatePhoto(primary_path=photo)
    record = manager.trash_candidate(candidate)
    real_unlink = Path.unlink

    def refuse_info(self, *args, **kwargs):
        if self.suffix == ".trashinfo":
            raise OSError("read-only info")
        return real_unlink(self, *args, **kwargs)

    monkeypatch.setattr(Path, "unlink", refuse_info)
    with caplog.at_level("WARNING"):
        manager.undo_culling(record)
    assert photo.exists()
    assert any("could not remove its Trash info file" in r.getMessage() for r in caplog.records)
