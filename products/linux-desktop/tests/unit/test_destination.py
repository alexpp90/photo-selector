"""Where photos go: one resolution shared by Move/Copy and Preferences (R-LINUX-PREF-03).

Adheres to:
- R-LINUX-PREF-03: Culling destination choice (working folder or custom folder), resolved path
  shown live and taken from the same function the file operations use
- R-LINUX-CULL-03 / R-LINUX-CULL-04: Move and Copy write to the resolved destination

GTK-free except the tests marked ``STUBBED``: those create real widgets (never presented) and run
only under ``xvfb-run``, so no window ever opens on the developer's desktop.
"""

import json
import os
from pathlib import Path
from unittest.mock import MagicMock

import pytest

from photo_selector_linux.core import destination as dest
from photo_selector_linux.core.destination import (
    MODE_CUSTOM,
    MODE_WORKING,
    describe_custom_folder,
    describe_destination,
    display_path,
    resolve_destination,
)
from photo_selector_linux.core.settings import SettingsStore
from photo_selector_linux.ui import preferences as preferences_module
from photo_selector_linux.ui.preferences import PreferencesDialog
from photo_selector_linux.viewmodels.culling_workspace import CullingWorkspaceViewModel

STUBBED = isinstance(preferences_module.Gtk, MagicMock)
HOME = Path("/home/alex")


# -- the resolution function -----------------------------------------------------------------


def test_working_folder_with_an_open_folder_resolves_inside_it(tmp_path: Path):
    res = resolve_destination(MODE_WORKING, "Selection", tmp_path / "Trip", None)
    assert res.root == tmp_path / "Trip"
    assert res.path == tmp_path / "Trip" / "Selection"
    assert res.problem is None and res.ok


def test_working_folder_without_an_open_folder_has_no_path_but_no_problem():
    res = resolve_destination(MODE_WORKING, "Selection", None, None)
    assert res.root is None and res.path is None and res.problem is None
    assert not res.ok  # nothing to write to until a folder is open
    assert describe_destination(res, HOME) == (
        "«Selection» inside the folder you open (no folder open yet)"
    )


def test_custom_folder_resolves_inside_the_custom_folder_not_the_open_one(tmp_path: Path):
    custom = tmp_path / "Archive"
    custom.mkdir()
    res = resolve_destination(MODE_CUSTOM, "Picks", tmp_path / "Trip", custom)
    assert res.root == custom
    assert res.path == custom / "Picks"
    assert res.ok


def test_custom_folder_resolves_without_an_open_folder(tmp_path: Path):
    custom = tmp_path / "Archive"
    custom.mkdir()
    assert resolve_destination(MODE_CUSTOM, "Selection", None, custom).path == custom / "Selection"


@pytest.mark.parametrize("raw", ["", "   ", "/", "\\", None, 7])
def test_blank_or_invalid_selection_name_falls_back_to_the_default(tmp_path: Path, raw):
    res = resolve_destination(MODE_WORKING, raw, tmp_path, None)
    assert res.selection_name == "Selection"
    assert res.path == tmp_path / "Selection"


def test_selection_name_is_one_path_component(tmp_path: Path):
    res = resolve_destination(MODE_WORKING, " Keeps/ ", tmp_path, None)
    assert res.selection_name == "Keeps"


def test_custom_folder_that_no_longer_exists_is_a_problem_not_a_destination(tmp_path: Path):
    gone = tmp_path / "unplugged-drive"
    res = resolve_destination(MODE_CUSTOM, "Selection", tmp_path, gone)
    assert res.problem == dest.PROBLEM_MISSING and not res.ok
    assert describe_destination(res, tmp_path.parent).startswith("Not found: ")
    assert not gone.exists()  # resolving never creates it


def test_custom_path_that_is_a_file_is_a_problem(tmp_path: Path):
    f = tmp_path / "file.txt"
    f.write_text("x")
    res = resolve_destination(MODE_CUSTOM, "Selection", tmp_path, f)
    assert res.problem == dest.PROBLEM_NOT_A_FOLDER
    assert describe_custom_folder(res, tmp_path.parent).startswith("Not a folder: ")


@pytest.mark.skipif(not hasattr(os, "geteuid") or os.geteuid() == 0, reason="root ignores modes")
def test_custom_folder_without_write_permission_is_a_problem(tmp_path: Path):
    ro = tmp_path / "readonly"
    ro.mkdir()
    ro.chmod(0o500)
    try:
        res = resolve_destination(MODE_CUSTOM, "Selection", tmp_path, ro)
    finally:
        ro.chmod(0o700)
    assert res.problem == dest.PROBLEM_NOT_WRITABLE and not res.ok


def test_custom_mode_without_a_folder_is_a_problem(tmp_path: Path):
    res = resolve_destination(MODE_CUSTOM, "Selection", tmp_path, None)
    assert res.problem == dest.PROBLEM_UNSET and not res.ok
    assert describe_destination(res) == "No folder chosen"


def test_unknown_mode_is_the_working_folder(tmp_path: Path):
    assert resolve_destination("bogus", "Selection", tmp_path, None).mode == MODE_WORKING


def test_display_path_shortens_the_home_directory():
    assert display_path(HOME / "Pictures" / "Trip" / "Selection", HOME) == "~/Pictures/Trip/Selection"
    assert display_path(HOME, HOME) == "~"
    assert display_path(Path("/mnt/card/DCIM"), HOME) == "/mnt/card/DCIM"
    res = resolve_destination(MODE_WORKING, "Selection", HOME / "Pictures" / "Trip", None)
    assert describe_destination(res, HOME) == "~/Pictures/Trip/Selection/"


def test_custom_row_text_shows_a_stored_folder_even_while_working_mode_is_selected(tmp_path: Path):
    custom = tmp_path / "Archive"
    custom.mkdir()
    res = resolve_destination(MODE_WORKING, "Selection", tmp_path, custom)
    assert describe_custom_folder(res, tmp_path.parent) == f"~/{tmp_path.name}/Archive"
    gone = resolve_destination(MODE_WORKING, "Selection", tmp_path, tmp_path / "x")
    assert describe_custom_folder(gone, tmp_path.parent).startswith("Not found: ")


# -- settings migration -----------------------------------------------------------------------


def test_no_stored_destination_reads_as_working_folder(tmp_path: Path):
    path = tmp_path / "settings.json"
    path.write_text(json.dumps({"selection_folder_name": "Picks"}), encoding="utf-8")
    store = SettingsStore(path=path)
    assert store.destination_mode == MODE_WORKING
    assert store.external_destination_folder is None


def test_existing_custom_destination_reads_as_custom_mode(tmp_path: Path):
    path = tmp_path / "settings.json"
    path.write_text(json.dumps({"external_destination_folder": "/mnt/archive"}), encoding="utf-8")
    store = SettingsStore(path=path)
    assert store.destination_mode == MODE_CUSTOM
    assert store.external_destination_folder == "/mnt/archive"


def test_explicit_working_mode_keeps_the_stored_custom_folder(tmp_path: Path):
    path = tmp_path / "settings.json"
    store = SettingsStore(path=path)
    store.update(external_destination_folder="/mnt/archive", destination_mode=MODE_WORKING)
    reloaded = SettingsStore(path=path)
    assert reloaded.destination_mode == MODE_WORKING
    assert reloaded.external_destination_folder == "/mnt/archive"


def test_custom_mode_never_exists_without_a_folder(tmp_path: Path):
    store = SettingsStore(path=tmp_path / "settings.json")
    store.update(destination_mode=MODE_CUSTOM)
    assert store.destination_mode == MODE_WORKING
    store.update(external_destination_folder="/mnt/archive")
    assert store.destination_mode == MODE_CUSTOM
    store.update(external_destination_folder=None)
    assert store.destination_mode == MODE_WORKING
    store.update(destination_mode="nonsense")
    assert store.destination_mode == MODE_WORKING


# -- Move/Copy go where the resolution says --------------------------------------------------


def _card(tmp_path: Path):
    card = tmp_path / "card"
    card.mkdir()
    for name in ("IMG_0001.JPG", "IMG_0002.JPG"):
        (card / name).write_bytes(name.encode())
    return card


def _vm(tmp_path: Path, **settings_kwargs):
    store = SettingsStore(path=tmp_path / "settings.json")
    if settings_kwargs:
        store.update(**settings_kwargs)
    return CullingWorkspaceViewModel(settings=store), store


def test_move_and_copy_in_working_mode_go_to_selection_inside_the_open_folder(tmp_path: Path):
    card = _card(tmp_path)
    vm, _store = _vm(tmp_path)
    vm.load_directory(card)
    expected = vm.resolve_destination().path
    assert expected == card.resolve() / "Selection"
    assert vm.cull_copy()
    assert (expected / "IMG_0001.JPG").exists()
    assert vm.cull_move()
    assert (expected / "IMG_0002.JPG").exists()


def test_move_and_copy_in_custom_mode_go_to_the_resolved_custom_destination(tmp_path: Path):
    card = _card(tmp_path)
    archive = tmp_path / "Archive"
    archive.mkdir()
    vm, store = _vm(tmp_path, selection_folder_name="Keeps")
    store.update(external_destination_folder=str(archive))
    vm.load_directory(card)
    expected = vm.resolve_destination().path
    assert expected == archive / "Keeps"
    assert vm.cull_copy()
    assert (expected / "IMG_0001.JPG").exists()
    assert vm.cull_move()
    assert (expected / "IMG_0002.JPG").exists()
    assert not (card / "Keeps").exists()


def test_switching_back_to_working_takes_effect_at_once(tmp_path: Path):
    card = _card(tmp_path)
    archive = tmp_path / "Archive"
    archive.mkdir()
    vm, store = _vm(tmp_path, external_destination_folder=str(archive))
    vm.load_directory(card)
    assert vm.resolve_destination().root == archive
    store.update(destination_mode=MODE_WORKING)
    assert vm.resolve_destination().root == card.resolve()
    assert vm.cull_copy()
    assert (card.resolve() / "Selection" / "IMG_0001.JPG").exists()


def test_unreachable_custom_folder_refuses_the_operation_and_keeps_the_photo(tmp_path: Path):
    card = _card(tmp_path)
    gone = tmp_path / "unplugged"
    vm, _store = _vm(tmp_path, external_destination_folder=str(gone))
    vm.load_directory(card)
    notes = []
    vm.connect("notification", lambda _vm, msg: notes.append((msg, vm.last_notification_severity)))
    before = list(vm.candidates)

    assert vm.cull_move() is False
    assert vm.cull_copy() is False

    assert vm.candidates == before  # nothing optimistic happened
    assert (card / "IMG_0001.JPG").exists()
    assert not gone.exists()  # a vanished mount point is not recreated as a local folder
    assert len(notes) == 2
    assert all(sev == "error" and msg.startswith("Cannot ") and "Not found" in msg for msg, sev in notes)


def test_legacy_external_destination_root_argument_is_custom_mode(tmp_path: Path):
    card = _card(tmp_path)
    ext = tmp_path / "ssd"
    ext.mkdir()
    vm = CullingWorkspaceViewModel(external_destination_root=ext)
    vm.load_directory(card)
    assert vm.resolve_destination().path == ext / "Selection"


def test_viewmodel_disconnect_removes_only_the_registered_callback():
    vm = CullingWorkspaceViewModel()
    calls = []

    def cb(_vm):
        calls.append(1)

    vm.connect("state-changed", cb)
    vm.disconnect("state-changed", cb)
    vm.disconnect("state-changed", cb)  # second call is a no-op
    vm.disconnect("unknown", cb)
    vm._notify_state_changed()
    assert calls == []


# -- Preferences ------------------------------------------------------------------------------


class _Chooser:
    """A folder picker that answers at once and never opens a window."""

    def __init__(self, answer):
        self.answer = answer
        self.calls = 0

    def __call__(self, on_chosen):
        self.calls += 1
        on_chosen(self.answer)


def test_preferences_show_the_destination_the_workspace_will_use(tmp_path: Path):
    card = _card(tmp_path)
    vm, store = _vm(tmp_path)
    dlg = PreferencesDialog(settings=store, viewmodel=vm, folder_chooser=_Chooser(None))
    assert dlg.destination_text() == (
        "«Selection» inside the folder you open (no folder open yet)"
    )
    vm.load_directory(card)
    assert dlg.destination_resolution() == vm.resolve_destination()
    assert dlg.destination_text() == describe_destination(vm.resolve_destination())
    assert dlg.destination_text().endswith("card/Selection/")


def test_preferences_text_follows_a_folder_opened_while_they_are_open(tmp_path: Path):
    first, second = tmp_path / "first", tmp_path / "second"
    first.mkdir()
    second.mkdir()
    vm, store = _vm(tmp_path)
    dlg = PreferencesDialog(settings=store, viewmodel=vm, folder_chooser=_Chooser(None))
    vm.load_directory(first)
    shown_first = dlg.row_destination.get_subtitle() if not STUBBED else dlg.destination_text()
    assert "first" in shown_first
    vm.load_directory(second)
    shown_second = dlg.row_destination.get_subtitle() if not STUBBED else dlg.destination_text()
    assert "second" in shown_second and "first" not in shown_second


def test_closing_preferences_stops_following_the_workspace(tmp_path: Path):
    vm, store = _vm(tmp_path)
    dlg = PreferencesDialog(settings=store, viewmodel=vm, folder_chooser=_Chooser(None))
    assert dlg._on_viewmodel_state_changed in vm._state_callbacks
    assert dlg._on_close_request() is False
    assert dlg._on_viewmodel_state_changed not in vm._state_callbacks


def test_choosing_custom_without_a_stored_folder_opens_the_picker_and_cancel_keeps_working(tmp_path: Path):
    vm, store = _vm(tmp_path)
    chooser = _Chooser(None)
    dlg = PreferencesDialog(settings=store, viewmodel=vm, folder_chooser=chooser)
    dlg.select_destination_mode(MODE_CUSTOM)
    assert chooser.calls == 1
    assert store.destination_mode == MODE_WORKING
    assert store.external_destination_folder is None


def test_choosing_custom_then_a_folder_switches_to_custom_and_shows_the_path(tmp_path: Path):
    archive = tmp_path / "Archive"
    archive.mkdir()
    vm, store = _vm(tmp_path)
    chooser = _Chooser(archive)
    dlg = PreferencesDialog(settings=store, viewmodel=vm, folder_chooser=chooser)
    dlg.select_destination_mode(MODE_CUSTOM)
    assert store.destination_mode == MODE_CUSTOM
    assert store.external_destination_folder == str(archive)
    assert vm.destination_mode == MODE_CUSTOM  # the workspace follows at once
    assert dlg.destination_text() == describe_destination(vm.resolve_destination())
    assert dlg.destination_text().endswith("Archive/Selection/")
    assert dlg.custom_folder_text().endswith("Archive")


def test_switching_back_to_working_keeps_the_custom_folder_and_needs_no_picker(tmp_path: Path):
    archive = tmp_path / "Archive"
    archive.mkdir()
    vm, store = _vm(tmp_path, external_destination_folder=str(archive))
    chooser = _Chooser(None)
    dlg = PreferencesDialog(settings=store, viewmodel=vm, folder_chooser=chooser)
    dlg.select_destination_mode(MODE_WORKING)
    assert store.destination_mode == MODE_WORKING
    assert store.external_destination_folder == str(archive)
    dlg.select_destination_mode(MODE_CUSTOM)  # stored folder: no picker
    assert chooser.calls == 0
    assert store.destination_mode == MODE_CUSTOM


def test_change_button_picks_a_new_folder_and_selects_custom(tmp_path: Path):
    old, new = tmp_path / "old", tmp_path / "new"
    old.mkdir()
    new.mkdir()
    vm, store = _vm(tmp_path, external_destination_folder=str(old), destination_mode=MODE_WORKING)
    dlg = PreferencesDialog(settings=store, viewmodel=vm, folder_chooser=_Chooser(new))
    dlg.choose_custom_folder()
    assert store.external_destination_folder == str(new)
    assert store.destination_mode == MODE_CUSTOM


def test_a_failing_picker_never_escapes_into_the_signal_handler(tmp_path: Path):
    vm, store = _vm(tmp_path)

    def broken(_cb):
        raise RuntimeError("portal gone")

    dlg = PreferencesDialog(settings=store, viewmodel=vm, folder_chooser=broken)
    dlg.choose_custom_folder()
    assert store.destination_mode == MODE_WORKING


def test_unreachable_custom_folder_is_shown_as_a_warning_not_a_destination(tmp_path: Path):
    gone = tmp_path / "unplugged"
    vm, store = _vm(tmp_path, external_destination_folder=str(gone))
    dlg = PreferencesDialog(settings=store, viewmodel=vm, folder_chooser=_Chooser(None))
    assert dlg.destination_text().startswith("Not found: ")
    assert dlg.custom_folder_text().startswith("Not found: ")
    assert dlg.destination_resolution().ok is False


def test_blank_selection_name_shows_the_default_in_the_resolved_path(tmp_path: Path):
    vm, store = _vm(tmp_path)
    dlg = PreferencesDialog(settings=store, viewmodel=vm, folder_chooser=_Chooser(None))
    dlg.set_selection_folder_name("   ")
    assert vm.resolve_destination().selection_name == "Selection"
    assert dlg.destination_resolution().selection_name == "Selection"


def test_preferences_without_a_workspace_resolve_from_settings_alone(tmp_path: Path):
    archive = tmp_path / "Archive"
    archive.mkdir()
    store = SettingsStore(path=tmp_path / "settings.json")
    store.update(external_destination_folder=str(archive))
    dlg = PreferencesDialog(settings=store, folder_chooser=_Chooser(None))
    assert dlg.destination_resolution().path == archive / "Selection"


@pytest.mark.skipif(STUBBED, reason="needs real GTK widgets (xvfb-run); the window is never presented")
def test_real_widgets_follow_the_choice_without_a_feedback_loop(tmp_path: Path):
    archive = tmp_path / "Archive"
    archive.mkdir()
    vm, store = _vm(tmp_path)
    chooser = _Chooser(archive)
    dlg = PreferencesDialog(settings=store, viewmodel=vm, folder_chooser=chooser)
    assert dlg.check_working.get_active() and not dlg.check_custom.get_active()

    dlg.check_custom.set_active(True)  # the user's click: toggled -> picker -> folder chosen
    assert chooser.calls == 1
    assert store.destination_mode == MODE_CUSTOM
    assert dlg.check_custom.get_active() and not dlg.check_working.get_active()
    assert dlg.row_destination.get_subtitle().endswith("Archive/Selection/")
    assert not dlg.icon_destination_warning.get_visible()

    dlg.check_working.set_active(True)
    assert store.destination_mode == MODE_WORKING
    assert dlg.check_working.get_active()
    assert store.external_destination_folder == str(archive)
    assert chooser.calls == 1


@pytest.mark.skipif(STUBBED, reason="needs real GTK widgets (xvfb-run); the window is never presented")
def test_real_widgets_cancelled_picker_leaves_working_folder_selected(tmp_path: Path):
    vm, store = _vm(tmp_path)
    dlg = PreferencesDialog(settings=store, viewmodel=vm, folder_chooser=_Chooser(None))
    dlg.check_custom.set_active(True)
    assert store.destination_mode == MODE_WORKING
    assert dlg.check_working.get_active() and not dlg.check_custom.get_active()


@pytest.mark.skipif(STUBBED, reason="needs real GTK widgets (xvfb-run); the window is never presented")
def test_real_widgets_warning_icon_shows_only_for_a_problem(tmp_path: Path):
    gone = tmp_path / "unplugged"
    vm, store = _vm(tmp_path, external_destination_folder=str(gone))
    dlg = PreferencesDialog(settings=store, viewmodel=vm, folder_chooser=_Chooser(None))
    assert dlg.icon_destination_warning.get_visible()
    assert dlg.row_destination.has_css_class("error")
    store.update(destination_mode=MODE_WORKING)
    dlg.refresh_destination()
    assert not dlg.icon_destination_warning.get_visible()
    assert not dlg.row_destination.has_css_class("error")
