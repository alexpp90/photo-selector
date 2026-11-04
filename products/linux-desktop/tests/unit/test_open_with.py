"""'Open with Blitzlens' on photos, and a startup that fails loudly (BL-B-2, BL-Q-1).

Adheres to:
- R-LINUX-UI-01: AdwApplication Lifecycle
- R-LINUX-META-01: Supported File Formats (the .desktop file registers every one of them)
"""

import sys
import types
from pathlib import Path
from types import SimpleNamespace

import pytest

from photo_selector_linux import __main__ as entry
from photo_selector_linux.viewmodels.culling_workspace import CullingWorkspaceViewModel


def _fake_application(monkeypatch, run):
    module = types.ModuleType("photo_selector_linux.ui.application")

    class App:
        def run(self, args):
            return run(args)

    module.PhotoSelectorApplication = App
    monkeypatch.setitem(sys.modules, "photo_selector_linux.ui.application", module)


def test_several_photos_from_the_file_manager_are_accepted(tmp_path, monkeypatch):
    a, b = tmp_path / "a.jpg", tmp_path / "b.jpg"
    a.write_bytes(b"x")
    b.write_bytes(b"x")
    seen = []
    _fake_application(monkeypatch, lambda args: seen.append(args) or 0)

    assert entry.main([str(a), str(b)]) == 0
    assert seen[0][1:] == [str(a.resolve()), str(b.resolve())]


def test_a_missing_path_is_an_error(tmp_path, capsys):
    assert entry.main([str(tmp_path / "nope.jpg")]) == 1
    assert "does not exist" in capsys.readouterr().err


def test_a_crash_while_running_is_not_reported_as_ready(tmp_path, monkeypatch):
    def boom(args):
        raise RuntimeError("bad css")

    _fake_application(monkeypatch, boom)
    with pytest.raises(RuntimeError, match="bad css"):
        entry.main([str(tmp_path)])


def test_the_exit_status_of_the_application_is_returned(tmp_path, monkeypatch):
    _fake_application(monkeypatch, lambda args: 3)
    assert entry.main([str(tmp_path)]) == 3


def test_missing_gtk_exits_non_zero(tmp_path, monkeypatch, capsys):
    monkeypatch.setitem(sys.modules, "photo_selector_linux.ui.application", None)  # import raises ImportError
    assert entry.main([str(tmp_path)]) == 1
    assert "GTK 4" in capsys.readouterr().err


def _photos(folder: Path, count: int = 5):
    for i in range(count):
        (folder / f"IMG_{i:04d}.JPG").write_bytes(b"x")


def test_opening_a_photo_loads_its_folder_and_lands_on_it(tmp_path):
    _photos(tmp_path)
    vm = CullingWorkspaceViewModel()
    vm.load_directory(tmp_path, select=tmp_path / "IMG_0003.JPG")
    assert vm.current_folder == tmp_path.resolve()
    assert [c.filename for c in vm.candidates][:1] == ["IMG_0000.JPG"] and len(vm.candidates) == 5
    assert vm.current_photo.filename == "IMG_0003.JPG"


def test_the_threaded_scan_lands_on_the_photo_too(tmp_path):
    _photos(tmp_path, 40)  # more than one batch
    pending = []
    vm = CullingWorkspaceViewModel(scan_idle_add=lambda fn, *a: pending.append((fn, a)))
    vm.load_directory(tmp_path, select=tmp_path / "IMG_0035.JPG")
    vm._scan_thread.join(timeout=5)
    for fn, args in pending:
        fn(*args)
    assert vm.current_photo.filename == "IMG_0035.JPG"


def test_a_companion_selects_its_shot(tmp_path):
    (tmp_path / "A.ARW").write_bytes(b"x")
    (tmp_path / "B.ARW").write_bytes(b"x")
    (tmp_path / "B.JPG").write_bytes(b"x")
    vm = CullingWorkspaceViewModel()
    vm.load_directory(tmp_path, select=tmp_path / "B.JPG")
    assert vm.current_photo.filename == "B.ARW"


def test_window_maps_a_file_to_its_folder(tmp_path):
    from photo_selector_linux.ui import window as window_module

    _photos(tmp_path)
    win = window_module.MainWindow()
    win.viewmodel.scan_idle_add = None  # synchronous scan for the assertion
    win.open_directory(tmp_path / "IMG_0002.JPG")
    assert win.viewmodel.current_folder == tmp_path.resolve()
    assert win.viewmodel.current_photo.filename == "IMG_0002.JPG"


def test_application_open_hands_the_first_file_to_the_window(tmp_path):
    from photo_selector_linux.ui.application import PhotoSelectorApplication

    photo = tmp_path / "IMG_0001.JPG"
    photo.write_bytes(b"x")
    opened = []
    app = PhotoSelectorApplication.__new__(PhotoSelectorApplication)
    app.window = SimpleNamespace(open_directory=opened.append, present=lambda: None)
    app.do_activate = lambda: None
    files = [SimpleNamespace(get_path=lambda: str(photo)), SimpleNamespace(get_path=lambda: "/other")]
    PhotoSelectorApplication.do_open(app, files, 2, "")
    assert opened == [photo]
