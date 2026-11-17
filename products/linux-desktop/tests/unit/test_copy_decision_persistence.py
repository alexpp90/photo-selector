"""A Copy decision survives reopening the folder (maintainer decision D3, FILE_OPERATIONS.md §5)."""

from pathlib import Path

from photo_selector_linux.core.copy_decisions import CopyDecisionStore
from photo_selector_linux.core.file_ops import FileCullingManager
from photo_selector_linux.core.settings import SettingsStore
from photo_selector_linux.viewmodels.culling_workspace import CullingWorkspaceViewModel

TOTAL = 6


def _session(tmp_path: Path, create: bool = True):
    folder = tmp_path / "card"
    folder.mkdir(exist_ok=True)
    for number in range(1, TOTAL + 1 if create else 1):
        path = folder / f"DSC_{number:04d}.JPG"
        if not path.exists():
            path.write_bytes(b"x%d" % number)
    store = SettingsStore(path=tmp_path / "config" / "settings.json")
    store.view_order = "natural"
    vm = CullingWorkspaceViewModel(
        settings=store, file_manager=FileCullingManager(trash_dir=tmp_path / "trash")
    )
    vm.load_directory(folder)
    return vm, folder


def _counts(vm):
    return (vm.selected_count, vm.trashed_count, vm.remaining_count)


def test_copied_photos_stay_decided_after_reopening(tmp_path: Path):
    vm, folder = _session(tmp_path)
    vm.cull_copy()
    vm.cull_copy()
    assert _counts(vm) == (2, 0, TOTAL - 2)

    again, _ = _session(tmp_path)
    assert _counts(again) == (2, 0, TOTAL - 2)
    assert [c.copied for c in again.candidates] == [True, True] + [False] * (TOTAL - 2)
    # A later Keep of a copied photo files nothing twice, also after the reopen.
    assert again.cull_move() is False
    selection = folder / "Selection"
    assert sorted(p.name for p in selection.iterdir() if not p.name.startswith(".")) == [
        "DSC_0001.JPG",
        "DSC_0002.JPG",
    ]


def test_undo_of_the_copy_makes_the_photo_open_again_after_reopening(tmp_path: Path):
    vm, _folder = _session(tmp_path)
    vm.cull_copy()
    vm.undo()
    again, _ = _session(tmp_path)
    assert _counts(again) == (0, 0, TOTAL)


def test_a_copy_removed_from_the_selection_is_not_a_decision(tmp_path: Path):
    vm, folder = _session(tmp_path)
    vm.cull_copy()
    (folder / "Selection" / "DSC_0001.JPG").unlink()
    again, _ = _session(tmp_path)
    assert _counts(again) == (0, 0, TOTAL)


def test_a_different_file_under_the_same_name_is_not_a_decision(tmp_path: Path):
    vm, folder = _session(tmp_path)
    vm.cull_copy()
    (folder / "DSC_0001.JPG").write_bytes(b"a completely different and longer file")
    again, _ = _session(tmp_path)
    assert _counts(again) == (0, 0, TOTAL)


def test_the_decision_is_local_state_not_written_into_the_photo(tmp_path: Path):
    vm, folder = _session(tmp_path)
    vm.cull_copy()
    assert (folder / "DSC_0001.JPG").read_bytes() == b"x1"
    assert not list(folder.glob("*.xmp"))
    assert (tmp_path / "config" / "copy-decisions.json").is_file()


def test_a_photo_moved_away_is_forgotten_on_the_next_scan(tmp_path: Path):
    vm, folder = _session(tmp_path)
    vm.cull_copy()
    key_store = CopyDecisionStore(tmp_path / "config" / "copy-decisions.json")
    assert key_store.folder_entries(vm.current_folder)
    for photo in folder.glob("*.JPG"):
        photo.unlink()
    _session(tmp_path, create=False)
    assert not CopyDecisionStore(tmp_path / "config" / "copy-decisions.json").folder_entries(
        vm.current_folder
    )


def test_an_unreadable_store_starts_empty(tmp_path: Path):
    path = tmp_path / "copy-decisions.json"
    path.write_text("{not json")
    store = CopyDecisionStore(path)
    assert store.folder_entries(tmp_path) == {}
    store.record(tmp_path, "a.jpg", 1, None)
    entry = CopyDecisionStore(path).folder_entries(tmp_path)["a.jpg"]
    assert entry["size"] == 1 and entry["at"] > 0


def test_the_store_is_bounded_by_folders(tmp_path: Path):
    from photo_selector_linux.core import copy_decisions as module

    store = CopyDecisionStore(tmp_path / "c.json")
    for n in range(module.MAX_FOLDERS + 5):
        store.record(Path(f"/f{n}"), "a.jpg", 1, None)
    assert not store.folder_entries(Path("/f0"))
    assert store.folder_entries(Path(f"/f{module.MAX_FOLDERS + 4}"))


def test_a_reopened_copy_says_where_and_when(tmp_path: Path):
    """The Copied mark's tooltip names the folder and the date, also in a later session."""
    from photo_selector_linux.ui.info_hud import copied_text

    vm, folder = _session(tmp_path)
    vm.cull_copy()
    again, _ = _session(tmp_path)
    photo = again.candidates[0]
    assert photo.copied and photo.copied_to == str(folder / "Selection" / "DSC_0001.JPG")
    assert photo.copied_at and photo.copied_at > 0
    assert copied_text(photo).startswith("Copied to Selection, ")
    assert copied_text(None) == "Copied to Selection"
