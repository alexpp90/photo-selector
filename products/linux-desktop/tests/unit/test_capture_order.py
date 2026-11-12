"""Capture time is what "Newest first" and "Oldest first" go by (plan 2.2); the file time is the fallback."""

import os
import time
from pathlib import Path
from unittest.mock import MagicMock

import pytest

from photo_selector_linux.core.metadata import parse_capture_time, read_metadata_pil
from photo_selector_linux.core.models import CandidatePhoto, ExposureMetadata
from photo_selector_linux.core.settings import SettingsStore
from photo_selector_linux.ui import window as window_module
from photo_selector_linux.viewmodels.capture_pass import order_basis
from photo_selector_linux.viewmodels.culling_workspace import CullingWorkspaceViewModel

REAL = window_module.HAS_GI and not isinstance(window_module.Gtk, MagicMock)


def test_the_exif_stamp_and_its_subsecond_digits_make_one_time():
    whole, sub_whole = parse_capture_time("2026:08:14 06:40:00")
    assert sub_whole is False
    frac, sub = parse_capture_time("2026:08:14 06:40:00", "25")
    assert sub is True and frac == pytest.approx(whole + 0.25)
    assert parse_capture_time("2026:08:14 06:40:00", "123")[0] == pytest.approx(whole + 0.123)
    assert parse_capture_time("garbage") == (None, False)
    assert parse_capture_time(None) == (None, False)


def test_pillow_reads_the_capture_time_of_a_jpeg(tmp_path: Path):
    from PIL import Image

    img = Image.new("RGB", (8, 8))
    exif = Image.Exif()
    sub = exif.get_ifd(0x8769)
    sub[0x9003] = "2026:08:14 06:40:01"
    sub[0x9291] = "50"
    path = tmp_path / "a.jpg"
    img.save(path, exif=exif)
    meta = read_metadata_pil(path)
    assert meta.captured_at == pytest.approx(parse_capture_time("2026:08:14 06:40:01", "50")[0])
    assert meta.captured_subsecond is True


def _folder(tmp_path: Path, stamps):
    """Files that all carry one modification time but different capture times."""
    folder = tmp_path / "card"
    folder.mkdir()
    for name in stamps:
        (folder / name).write_bytes(b"x")
        os.utime(folder / name, (1_700_000_000, 1_700_000_000))
    return folder


def _runner(stamps, calls):
    def run(photos, on_read):
        calls.append([p.filename for p in photos])
        for p in photos:
            on_read(p, ExposureMetadata(captured_at=stamps[p.filename]))
        return lambda: None
    return run


def _vm(tmp_path: Path, order: str):
    store = SettingsStore(path=tmp_path / "settings.json")
    store.view_order = order
    return CullingWorkspaceViewModel(settings=store)


def test_a_folder_with_one_modification_time_opens_in_capture_order(tmp_path: Path):
    stamps = {"a.jpg": 300.0, "b.jpg": 100.0, "c.jpg": 200.0}
    folder = _folder(tmp_path, stamps)
    vm = _vm(tmp_path, "newest")
    calls = []
    vm.capture_runner = _runner(stamps, calls)
    vm.load_directory(folder)
    assert [c.filename for c in vm.candidates] == ["a.jpg", "c.jpg", "b.jpg"]  # newest first
    assert vm.order_basis == "capture time"
    vm.set_sort_order("oldest")
    assert [c.filename for c in vm.candidates] == ["b.jpg", "c.jpg", "a.jpg"]
    assert len(calls) == 1  # read once, not again for the other order


def test_the_photo_on_screen_stays_on_screen_while_capture_times_arrive(tmp_path: Path):
    stamps = {"a.jpg": 300.0, "b.jpg": 100.0, "c.jpg": 200.0}
    folder = _folder(tmp_path, stamps)
    vm = _vm(tmp_path, "newest")
    pending = []
    vm.capture_runner = lambda photos, on_read: (pending.extend((p, on_read) for p in photos), lambda: None)[1]
    vm.load_directory(folder)
    vm.nav_next()
    shown = vm.current_photo
    assert vm.order_basis == "reading capture times"
    for photo, on_read in pending:
        on_read(photo, ExposureMetadata(captured_at=stamps[photo.filename]))
    assert vm.current_photo is shown


def test_a_photo_without_a_capture_time_falls_back_to_its_file_time():
    with_time = CandidatePhoto(primary_path=Path("/t/a.jpg"), exif=ExposureMetadata(captured_at=500.0), modified_at=1.0)
    without = CandidatePhoto(primary_path=Path("/t/b.jpg"), modified_at=700.0)
    assert with_time.order_time == 500.0 and without.order_time == 700.0
    assert order_basis([with_time, without], reading=False) == "capture time, file time for 1"
    assert order_basis([without], reading=False) == "file time"
    assert order_basis([with_time], reading=False) == "capture time"


def test_a_cloud_folder_reads_nothing_until_the_user_chooses_the_order(tmp_path: Path):
    stamps = {"a.jpg": 300.0, "b.jpg": 100.0}
    folder = _folder(tmp_path, stamps)
    vm = _vm(tmp_path, "newest")
    calls = []
    vm.capture_runner = _runner(stamps, calls)
    vm.is_remote_folder = True
    vm._describe_folder = lambda scanner, resolved: (True, "Drive")  # type: ignore[assignment]
    vm.load_directory(folder)
    vm.is_remote_folder = True
    assert calls == []
    vm.set_sort_order("newest")
    assert len(calls) == 1


@pytest.mark.skipif(not REAL, reason="needs real Gtk under a display server")
def test_the_order_says_what_it_goes_by(tmp_path: Path):
    from gi.repository import GLib

    stamps = {"a.jpg": 300.0, "b.jpg": 100.0}
    folder = _folder(tmp_path, stamps)
    win = window_module.MainWindow(settings=SettingsStore(path=tmp_path / "settings.json"))
    win.viewmodel.capture_runner = _runner(stamps, [])
    win.viewmodel.load_directory(folder)
    deadline = time.monotonic() + 5
    context = GLib.MainContext.default()
    while win.viewmodel.is_scanning and time.monotonic() < deadline:
        context.iteration(False)
    win._on_viewmodel_state_changed(win.viewmodel)
    assert win.btn_order.get_tooltip_text() == "Order: Newest first, by capture time (S)"
    assert win._order_menu_basis == "capture time"
    win.destroy()
