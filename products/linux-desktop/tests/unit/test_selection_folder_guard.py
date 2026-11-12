"""Move and Copy inside an opened Selection folder must not nest a second Selection.

Opening ``Selection/`` for a second pass and pressing Keep used to file into
``Selection/Selection/``, which the scan excludes, so the keeper seemed to vanish.
Trash and Undo keep working there.
"""

from pathlib import Path
from unittest.mock import MagicMock

from photo_selector_linux.core.destination import (
    PROBLEM_IS_SELECTION,
    describe_destination,
    resolve_destination,
)
from photo_selector_linux.core.models import CullingActionType, CullingRecord
from photo_selector_linux.core.settings import SettingsStore
from photo_selector_linux.viewmodels.culling_workspace import CullingWorkspaceViewModel


def _opened_selection(tmp_path: Path, marker: bool = True) -> Path:
    selection = tmp_path / "Selection"
    selection.mkdir()
    for number in (1, 2, 3):
        (selection / f"IMG_000{number}.JPG").write_bytes(b"x%d" % number)
    if marker:
        (selection / ".photoselector-selection").write_bytes(b"")
    return selection


def _vm(tmp_path: Path):
    store = SettingsStore(path=tmp_path / "settings.json")
    store.view_order = "natural"
    vm = CullingWorkspaceViewModel(settings=store)
    notes = []
    vm.connect("notification", lambda _vm, msg: notes.append((msg, vm.last_notification_severity)))
    return vm, notes


def _no_nested_selection(selection: Path) -> bool:
    return not (selection / "Selection").exists()


def test_move_and_copy_in_an_opened_selection_folder_are_refused_with_a_reason(tmp_path: Path):
    selection = _opened_selection(tmp_path)
    vm, notes = _vm(tmp_path)
    vm.load_directory(selection)
    before = list(vm.candidates)

    assert vm.cull_move() is False
    assert vm.cull_copy() is False

    assert vm.candidates == before
    assert _no_nested_selection(selection)
    assert (selection / "IMG_0001.JPG").exists()
    refusals = [(m, sev) for m, sev in notes if sev == "error"]
    assert len(refusals) == 2
    for message, severity in refusals:
        assert severity == "error"
        assert "Selection folder" in message and "parent" in message
    assert vm.selected_count == 0 and vm.copied_count == 0


def test_the_folder_name_alone_marks_a_selection_folder_too(tmp_path: Path):
    selection = _opened_selection(tmp_path, marker=False)
    vm, _notes = _vm(tmp_path)
    vm.load_directory(selection)
    assert vm.cull_move() is False
    assert _no_nested_selection(selection)


def test_trash_and_undo_still_work_in_an_opened_selection_folder(tmp_path: Path):
    selection = _opened_selection(tmp_path)
    manager = MagicMock()
    vm = CullingWorkspaceViewModel(
        settings=SettingsStore(path=tmp_path / "settings.json"), file_manager=manager
    )
    vm.load_directory(selection)
    assert vm.resolve_destination().problem == PROBLEM_IS_SELECTION
    photo = vm.current_photo
    manager.trash_candidate.return_value = CullingRecord(
        photo_id=photo.id,
        action_type=CullingActionType.TRASH,
        original_primary_path=photo.primary_path,
        affected_paths=[(photo.primary_path, photo.primary_path)],
    )

    assert vm.cull_trash() is True  # the destination guard never applies to Trash
    manager.trash_candidate.assert_called_once()
    assert vm.undo() is True


def test_an_ordinary_folder_is_not_refused(tmp_path: Path):
    card = tmp_path / "card"
    card.mkdir()
    (card / "IMG_0001.JPG").write_bytes(b"x")
    vm, _notes = _vm(tmp_path)
    vm.load_directory(card)
    assert vm.resolve_destination().problem is None
    assert vm.cull_move() is True


def test_the_resolution_says_why_in_the_preferences_line(tmp_path: Path):
    selection = _opened_selection(tmp_path)
    res = resolve_destination("working", "Selection", selection, None)
    assert res.problem == PROBLEM_IS_SELECTION and not res.ok
    assert "Selection folder" in describe_destination(res)
