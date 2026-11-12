"""A copied photo is decided (``[USER 2026-11-12]`` decision #29, plan 1.4 and 1.5).

Reproduces the review's 12-photo run: Trash once, Move three, Copy two read "5 Selected · 1 Trashed ·
8 Remaining", and moving the copied photos later filed ``DSC_0003-2.JPG`` next to ``DSC_0003.JPG``.
Selected + Trashed + Remaining must always be the number of photos, a copied photo leaves
Remaining, and Move or Copy on a photo already copied files nothing twice.
"""

from pathlib import Path

from photo_selector_linux.core.file_ops import FileCullingManager
from photo_selector_linux.core.settings import SettingsStore
from photo_selector_linux.viewmodels.culling_workspace import CullingWorkspaceViewModel

TOTAL = 12


def _vm(tmp_path: Path):
    folder = tmp_path / "card"
    folder.mkdir()
    for number in range(1, TOTAL + 1):
        (folder / f"DSC_{number:04d}.JPG").write_bytes(b"x%d" % number)
    store = SettingsStore(path=tmp_path / "settings.json")
    store.view_order = "natural"
    vm = CullingWorkspaceViewModel(
        settings=store, file_manager=FileCullingManager(trash_dir=tmp_path / "trash")
    )
    notes = []
    vm.connect("notification", lambda _vm, msg: notes.append((msg, vm.last_notification_severity)))
    vm.load_directory(folder)
    return vm, folder, notes


def _sum(vm) -> int:
    return vm.selected_count + vm.trashed_count + vm.remaining_count


def _go_to(vm, number: int) -> None:
    assert vm.go_to_photo(f"DSC_{number:04d}")


def _files(selection: Path) -> list:
    return sorted(p.name for p in selection.iterdir() if p.is_file() and not p.name.startswith("."))


def test_the_review_run_counts_each_photo_once(tmp_path: Path):
    vm, folder, _notes = _vm(tmp_path)
    assert (vm.selected_count, vm.trashed_count, vm.remaining_count) == (0, 0, TOTAL)

    vm.cull_trash()  # DSC_0001
    vm.cull_move()  # 2
    vm.cull_move()  # 3
    vm.cull_move()  # 4
    vm.cull_copy()  # 5 stays in the list, marked
    vm.cull_copy()  # 6
    assert (vm.selected_count, vm.trashed_count, vm.remaining_count) == (5, 1, 6)
    assert _sum(vm) == TOTAL

    # The review pressed Keep on the two copied photos later: nothing is filed a second time.
    for number in (5, 6):
        _go_to(vm, number)
        vm.cull_move()
        assert _sum(vm) == TOTAL
    selection = folder / "Selection"
    assert _files(selection) == [f"DSC_{n:04d}.JPG" for n in (2, 3, 4, 5, 6)]
    assert (vm.selected_count, vm.trashed_count, vm.remaining_count) == (5, 1, 6)

    for _ in range(TOTAL):  # bounded: a counter that never reaches zero must fail, not hang
        if not vm.remaining_count:
            break
        vm.cull_move()
        assert _sum(vm) == TOTAL
    assert vm.selected_count == TOTAL - 1 and vm.remaining_count == 0
    assert len(_files(selection)) == TOTAL - 1  # no "-2" files
    assert not [n for n in _files(selection) if "-2" in n]


def test_a_second_copy_says_already_copied_and_files_nothing(tmp_path: Path):
    vm, folder, notes = _vm(tmp_path)
    vm.cull_copy()
    _go_to(vm, 1)
    assert vm.cull_copy() is False
    assert notes[-1][0] == "Already copied"
    assert (vm.selected_count, vm.copied_count, vm.remaining_count) == (1, 1, TOTAL - 1)
    assert _files(folder / "Selection") == ["DSC_0001.JPG"]
    assert (folder / "DSC_0001.JPG").exists()  # Copy leaves the original


def test_the_latest_decision_wins_when_a_copied_photo_is_trashed_and_undo_restores_it(tmp_path: Path):
    vm, folder, _notes = _vm(tmp_path)
    vm.cull_copy()
    _go_to(vm, 1)
    vm.cull_trash()
    assert (vm.selected_count, vm.trashed_count, vm.remaining_count) == (0, 1, TOTAL - 1)
    assert _sum(vm) == TOTAL
    vm.undo()
    assert (vm.selected_count, vm.trashed_count, vm.remaining_count) == (1, 0, TOTAL - 1)
    assert _sum(vm) == TOTAL


def test_undoing_a_copy_makes_the_photo_open_again(tmp_path: Path):
    vm, _folder, _notes = _vm(tmp_path)
    vm.cull_copy()
    assert vm.remaining_count == TOTAL - 1
    vm.undo()
    assert (vm.selected_count, vm.copied_count, vm.remaining_count) == (0, 0, TOTAL)
    _go_to(vm, 1)
    assert vm.cull_move() is True


def test_when_only_copied_photos_are_left_the_folder_is_done(tmp_path: Path):
    vm, _folder, _notes = _vm(tmp_path)
    for _ in range(TOTAL):
        if not vm.remaining_count:
            break
        vm.cull_copy()
    assert vm.remaining_count == 0 and vm.selected_count == TOTAL
    assert vm.canvas_state == "done"
    vm.undo()
    assert vm.canvas_state == "photos"


def test_the_info_card_and_the_pills_say_what_copied_means(tmp_path: Path):
    import pytest

    from photo_selector_linux.ui import info_hud
    from photo_selector_linux.ui.action_bar import ActionBarView

    if not info_hud.HAS_GI or type(info_hud.Gtk).__name__ == "MagicMock":
        pytest.skip("real GTK needed")
    vm, _folder, _notes = _vm(tmp_path)
    hud = info_hud.InfoHUD(viewmodel=vm)
    hud.update_photo(vm.current_photo)
    assert hud.copied_mark.get_visible() is False
    vm.cull_copy()
    copied = vm.candidates[0]
    hud.update_photo(copied)
    assert hud.copied_mark.get_visible() is True and hud.is_copied
    assert hud.copied_mark.get_tooltip_text() == "Copied to Selection"
    hud.update_photo(vm.candidates[1])
    assert hud.copied_mark.get_visible() is False

    bar = ActionBarView(viewmodel=vm)
    assert "moved or copied" in bar.btn_selected.get_tooltip_text()
    assert bar.lbl_trashed.get_tooltip_text() and bar.lbl_remaining.get_tooltip_text()
