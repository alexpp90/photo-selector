"""Companions in the confirmations, shown and spoken (decision #33 (1)): the UX-notes table, row by row."""

from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock

from photo_selector_linux.core.models import CandidatePhoto, CullingActionType, CullingRecord
from photo_selector_linux.ui.action_bar import ActionBarView
from photo_selector_linux.viewmodels.culling_workspace import CullingWorkspaceViewModel
from photo_selector_linux.viewmodels.outcome_text import outcome_texts, short_stem

PHOTO = SimpleNamespace(filename="IMG_0001.CR3")


def test_the_table_of_the_ux_notes():
    assert outcome_texts("Moved", PHOTO, 2, "Selection") == (
        "Moved IMG_0001 +2 · Selection",
        "Moved IMG_0001.CR3 and 2 companion files to Selection",
    )
    assert outcome_texts("Moved", PHOTO, 0, "Selection") == (
        "Moved IMG_0001 · Selection",
        "Moved IMG_0001.CR3 to Selection",
    )
    assert outcome_texts("Copied", PHOTO, 1, "Selection") == (
        "Copied IMG_0001 +1 · Selection",
        "Copied IMG_0001.CR3 and 1 companion file to Selection",
    )
    assert outcome_texts("Moved", PHOTO, 2, "Trash") == (
        "Moved IMG_0001 +2 · Trash",
        "Moved IMG_0001.CR3 and 2 companion files to Trash",
    )


def test_the_visible_line_never_says_companions_and_a_long_name_keeps_the_plus_and_the_place():
    long = SimpleNamespace(filename="IMG_20260814_064000.ARW")
    visible, spoken = outcome_texts("Moved", long, 12, "Selection")
    assert visible == "Moved IMG_…_064000 +12 · Selection"
    assert "companion" not in visible and "companion files" in spoken
    assert spoken.startswith("Moved IMG_20260814_064000.ARW")  # spoken: the whole name
    assert short_stem("a.jpg") == "a"


def _vm(kind: str):
    primary = Path("/tmp/d/IMG_0001.CR3")
    photo = CandidatePhoto(
        primary_path=primary, companion_paths=[Path("/tmp/d/IMG_0001.JPG"), Path("/tmp/d/IMG_0001.xmp")]
    )
    other = CandidatePhoto(primary_path=Path("/tmp/d/IMG_0002.CR3"))
    sel = Path("/tmp/d/Selection")
    record = CullingRecord(
        photo_id=photo.id,
        action_type={"move": CullingActionType.MOVE, "trash": CullingActionType.TRASH}[kind],
        original_primary_path=primary,
        affected_paths=[(p, sel / p.name) for p in photo.all_paths],
    )
    manager = MagicMock()
    manager.move_candidate.return_value = record
    manager.trash_candidate.return_value = record
    vm = CullingWorkspaceViewModel(file_manager=manager)
    vm.candidates = [photo, other]
    vm.current_folder = Path("/tmp/d")
    vm._destination_said_generation = vm._folder_generation
    return vm


def test_the_view_model_hands_the_bar_both_texts_and_the_bar_announces_the_spoken_one():
    vm = _vm("move")
    bar = ActionBarView(viewmodel=vm)
    announced = []
    bar._announce = announced.append
    vm.cull_move()
    assert bar.status_text == "Moved IMG_0001 +2 · Selection"
    assert announced[-1] == "Moved IMG_0001.CR3 and 2 companion files to Selection"


def test_trash_outcome_likewise():
    vm = _vm("trash")
    bar = ActionBarView(viewmodel=vm)
    announced = []
    bar._announce = announced.append
    vm.cull_trash()
    assert bar.status_text == "Moved IMG_0001 +2 · Trash"
    assert announced[-1] == "Moved IMG_0001.CR3 and 2 companion files to Trash"


def test_a_message_without_a_spoken_form_is_announced_as_it_reads():
    vm = _vm("move")
    bar = ActionBarView(viewmodel=vm)
    announced = []
    bar._announce = announced.append
    vm._notify("Undid move for IMG_0001.CR3", "status")
    assert announced[-1] == "Undid move for IMG_0001.CR3"
