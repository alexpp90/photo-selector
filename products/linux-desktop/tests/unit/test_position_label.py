"""'Photo N of M': M is the folder's photo count, fixed for the folder, and N follows the work done."""

from pathlib import Path

from photo_selector_linux.core.file_ops import FileCullingManager
from photo_selector_linux.core.settings import SettingsStore
from photo_selector_linux.viewmodels.culling_workspace import CullingWorkspaceViewModel


def _vm(tmp_path: Path, count: int = 6):
    folder = tmp_path / "card"
    folder.mkdir()
    for number in range(1, count + 1):
        (folder / f"DSC_{number:04d}.JPG").write_bytes(b"x%d" % number)
    store = SettingsStore(path=tmp_path / "settings.json")
    store.view_order = "natural"
    vm = CullingWorkspaceViewModel(settings=store, file_manager=FileCullingManager(trash_dir=tmp_path / "trash"))
    return vm, folder


def test_no_folder_reads_zero_of_zero(tmp_path: Path):
    vm, _folder = _vm(tmp_path)
    assert vm.position_label == "Photo 0 of 0"


def test_m_stays_the_folders_count_while_photos_are_filed(tmp_path: Path):
    vm, folder = _vm(tmp_path)
    vm.load_directory(folder)
    assert vm.position_label == "Photo 1 of 6"
    vm.cull_move()
    vm.cull_trash()
    assert vm.position_label == "Photo 3 of 6"  # two decided, the third photo is on screen
    vm.nav_next()
    assert vm.position_label == "Photo 4 of 6"
    vm.undo()  # the trash comes back: the list is longer, the folder's count the same
    assert vm.position_label.endswith(" of 6")


def test_a_copied_photo_stays_in_the_list_and_in_the_count(tmp_path: Path):
    vm, folder = _vm(tmp_path)
    vm.load_directory(folder)
    vm.cull_copy()
    assert vm.position_label == "Photo 2 of 6"


def test_everything_decided_reads_m_of_m_and_a_new_folder_starts_over(tmp_path: Path):
    vm, folder = _vm(tmp_path, 3)
    vm.load_directory(folder)
    for _ in range(3):
        vm.cull_move()
    assert vm.position_label == "Photo 3 of 3"
    other = tmp_path / "other"
    other.mkdir()
    (other / "A.JPG").write_bytes(b"a")
    vm.load_directory(other)
    assert vm.position_label == "Photo 1 of 1"
