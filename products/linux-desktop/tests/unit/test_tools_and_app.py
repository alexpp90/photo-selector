"""Unit tests for tools dialogs, application controller, remote mount awareness (U-31), and U-32..U-35 features.

Adheres to:
- R-LINUX-TOOLS-01: Streaming Cryptographic Duplicate Finder Dialog
- R-LINUX-TOOLS-02: Library Statistics Dialog with Optical & Sharpness Histograms
- R-LINUX-UI-01: PhotoSelectorApplication Action Wiring
- R-LINUX-META-05 / U-32: .photoselector-selection Marker File Exclusion
- R-LINUX-META-07 / U-31: Remote & FUSE Mount Awareness
"""

import json
from pathlib import Path
from unittest.mock import MagicMock

import pytest

from photo_selector_linux.core.file_ops import (
    SELECTION_MARKER_FILENAME,
    FileCullingManager,
)
from photo_selector_linux.core.gvfs_names import is_remote_filesystem
from photo_selector_linux.core.models import (
    CandidatePhoto,
    ExposureMetadata,
    QualityScores,
)
from photo_selector_linux.core.scanner import DirectoryScanner
from photo_selector_linux.core.statistics import (
    calculate_library_statistics,
)
from photo_selector_linux.ui.application import PhotoSelectorApplication
from photo_selector_linux.ui.tools_dialogs import (
    DuplicateFinderDialog,
    LibraryStatisticsDialog,
)
from photo_selector_linux.ui.window import MainWindow
from photo_selector_linux.viewmodels.culling_workspace import CullingWorkspaceViewModel


def test_is_remote_filesystem_detects_gvfs_and_mountinfo(tmp_path: Path):
    """Verify is_remote_filesystem detects GVFS roots and FUSE/network filesystems from mountinfo (U-31)."""
    gvfs_root = tmp_path / "gvfs"
    gvfs_folder = gvfs_root / "google-drive:host=gmail.com,user=alex" / "DCIM"
    gvfs_folder.mkdir(parents=True)

    assert is_remote_filesystem(gvfs_folder, roots=(gvfs_root,)) is True

    rclone_mount = tmp_path / "mnt" / "cloud_drive"
    rclone_mount.mkdir(parents=True)
    fake_mountinfo = (
        f"42 25 0:38 / {rclone_mount.resolve()} rw,nosuid,nodev,relatime - fuse.rclone remote:bucket rw\n"
    )
    assert is_remote_filesystem(rclone_mount, mountinfo_text=fake_mountinfo, roots=()) is True

    local_folder = tmp_path / "local_photos"
    local_folder.mkdir()
    local_mountinfo = "25 1 259:2 / / rw,relatime - ext4 /dev/nvme0n1p2 rw\n"
    assert is_remote_filesystem(local_folder, mountinfo_text=local_mountinfo, roots=()) is False


def test_duplicate_finder_dialog_background_worker_and_remote_confirmation(tmp_path: Path):
    """Verify DuplicateFinderDialog requires confirmation on remote folders and dispatches scan to worker (U-31)."""
    f1 = tmp_path / "dup1.jpg"
    f2 = tmp_path / "dup2.jpg"
    f3 = tmp_path / "unique.jpg"
    f1.write_bytes(b"identical-image-payload")
    f2.write_bytes(b"identical-image-payload")
    f3.write_bytes(b"different-image-payload")

    vm = CullingWorkspaceViewModel()
    vm.candidates = [
        CandidatePhoto(primary_path=f1),
        CandidatePhoto(primary_path=f2),
        CandidatePhoto(primary_path=f3),
    ]
    vm.is_remote_folder = True

    dispatched_jobs = []

    def tracking_dispatcher(job, done):
        dispatched_jobs.append(job)
        done(job(), None)

    dlg = DuplicateFinderDialog(viewmodel=vm, dispatcher=tracking_dispatcher)
    assert dlg.needs_remote_confirmation is True

    # Clicking scan on a remote folder prompts for confirmation first without running the full pass
    dlg._on_scan_clicked(None)
    assert dlg.awaiting_remote_confirmation is True
    assert len(dispatched_jobs) == 0
    assert "Remote / Cloud" in dlg.status_text

    # Confirming remote scan runs the worker and populates duplicate clusters
    dlg.confirm_remote_scan()
    assert dlg.awaiting_remote_confirmation is False
    assert len(dispatched_jobs) == 1
    assert len(dlg.clusters) == 1
    assert len(dlg.clusters[0].file_paths) == 2
    assert "Found 1 duplicate clusters" in dlg.status_text


def test_library_statistics_dialog_and_exif_sharpness_breakdown():
    """Verify LibraryStatisticsDialog and calculate_library_statistics compute EXIF x sharpness averages (U-35)."""
    p1 = CandidatePhoto(
        primary_path=Path("/tmp/a.jpg"),
        exif=ExposureMetadata(focal_length=35.0, aperture=2.8, shutter_speed=0.002),
        scores=QualityScores(sharpness=80.0, noise=1.0, highlight_clipping=0.0, shadow_clipping=0.0),
    )
    p2 = CandidatePhoto(
        primary_path=Path("/tmp/b.jpg"),
        exif=ExposureMetadata(focal_length=50.0, aperture=2.8, shutter_speed=0.001),
        scores=QualityScores(sharpness=60.0, noise=1.0, highlight_clipping=0.0, shadow_clipping=0.0),
    )
    p3 = CandidatePhoto(
        primary_path=Path("/tmp/c.jpg"),
        exif=ExposureMetadata(focal_length=85.0, aperture=1.4, shutter_speed=0.01),
        scores=QualityScores(sharpness=90.0, noise=1.0, highlight_clipping=0.0, shadow_clipping=0.0),
    )

    stats = calculate_library_statistics([p1, p2, p3])
    assert stats.sharpness_by_focal_length["35-69mm"] == 70.0
    assert stats.sharpness_by_focal_length["70-199mm"] == 90.0
    assert stats.sharpness_by_aperture["f/2.8-f/4.0"] == 70.0
    assert stats.sharpness_by_aperture["< f/2.0"] == 90.0
    assert stats.sharpness_by_shutter_speed["1/1000s-1/250s"] == 70.0
    assert stats.sharpness_by_shutter_speed["1/250s-1/60s"] == 90.0

    vm = CullingWorkspaceViewModel()
    vm.candidates = [p1, p2, p3]
    vm.is_remote_folder = True

    dlg = LibraryStatisticsDialog(viewmodel=vm, confirm_remote=False)
    assert dlg.awaiting_remote_confirmation is True
    assert dlg.stats is None

    dlg.confirm_remote_scan()
    assert dlg.awaiting_remote_confirmation is False
    assert dlg.stats is not None
    assert dlg.stats.sharpness_by_focal_length["35-69mm"] == 70.0


def test_selection_marker_file_written_and_excluded_by_scanner(tmp_path: Path):
    """Verify FileCullingManager writes .photoselector-selection and DirectoryScanner excludes marked folders (U-32)."""
    photo = tmp_path / "DSC_0001.JPG"
    photo.write_bytes(b"jpeg")
    cand = CandidatePhoto(primary_path=photo)

    mgr = FileCullingManager()
    mgr.copy_candidate(cand, tmp_path, selection_folder_name="CustomExportFolder")

    marker = tmp_path / "CustomExportFolder" / SELECTION_MARKER_FILENAME
    assert marker.exists()

    # Even a fresh DirectoryScanner with default excluded_names excludes CustomExportFolder via the marker file
    scanner = DirectoryScanner()
    scanned = scanner.scan_directory(tmp_path)
    assert [c.filename for c in scanned] == ["DSC_0001.JPG"]


def test_external_destination_card_summary_and_decision_log(tmp_path: Path):
    """Verify external destination root, card_summary() safe-to-format check, and JSONL decision log (U-33, U-35)."""
    card = tmp_path / "sd_card"
    ext_dest = tmp_path / "ssd_backup"
    log_path = tmp_path / "decisions.jsonl"
    card.mkdir()
    ext_dest.mkdir()

    p1 = card / "IMG_0001.JPG"
    p1.write_bytes(b"img1")

    vm = CullingWorkspaceViewModel(
        external_destination_root=ext_dest,
        decision_log_path=log_path,
    )
    vm.load_directory(card)
    assert vm.card_summary()["safe_to_format"] is False

    vm.cull_move()
    assert (ext_dest / "Selection" / "IMG_0001.JPG").exists()
    summary = vm.card_summary()
    assert summary == {
        "kept": 1,
        "trashed": 0,
        "remaining": 0,
        "queued": 0,
        "safe_to_format": True,
    }

    lines = log_path.read_text(encoding="utf-8").strip().splitlines()
    assert len(lines) == 1
    entry = json.loads(lines[0])
    assert entry["action"] == "move"
    assert entry["filename"] == "IMG_0001.JPG"


def test_lowest_sharpness_first_sort_and_stop_threshold(tmp_path: Path):
    """Verify lowest_sharpness_first sort order and stop_sharpness_threshold notification (U-34)."""
    vm = CullingWorkspaceViewModel()
    c_sharp = CandidatePhoto(
        primary_path=tmp_path / "DSC_0001.JPG",
        scores=QualityScores(sharpness=82.0, noise=1.0, highlight_clipping=0.0, shadow_clipping=0.0),
    )
    c_blurry = CandidatePhoto(
        primary_path=tmp_path / "DSC_0002.JPG",
        scores=QualityScores(sharpness=22.0, noise=1.0, highlight_clipping=0.0, shadow_clipping=0.0),
    )
    c_mid = CandidatePhoto(
        primary_path=tmp_path / "DSC_0003.JPG",
        scores=QualityScores(sharpness=55.0, noise=1.0, highlight_clipping=0.0, shadow_clipping=0.0),
    )
    vm.candidates = [c_sharp, c_blurry, c_mid]
    vm.stop_sharpness_threshold = 50.0
    notifications = []
    vm.connect("notification", lambda _vm, msg: notifications.append(msg))

    vm.set_sort_order("lowest_sharpness_first")
    assert [c.filename for c in vm.candidates] == ["DSC_0002.JPG", "DSC_0003.JPG", "DSC_0001.JPG"]
    assert vm.current_index == 0

    vm.nav_next()
    assert vm.current_photo is c_mid
    assert notifications == ["Reached sharpness threshold (50.0)"]


def test_application_actions_and_window_dialogs(tmp_path: Path):
    """Verify PhotoSelectorApplication registers actions and delegates to MainWindow dialogs."""
    app = PhotoSelectorApplication()
    win = MainWindow()
    app.window = win

    win.show_open_folder_dialog = MagicMock()
    win.show_preferences = MagicMock()
    win.show_shortcuts_guide = MagicMock()
    win.show_duplicate_finder = MagicMock()
    win.show_library_statistics = MagicMock()

    app._on_action_open_folder(None, None)
    win.show_open_folder_dialog.assert_called_once()

    app._on_action_preferences(None, None)
    win.show_preferences.assert_called_once()

    app._on_action_shortcuts(None, None)
    win.show_shortcuts_guide.assert_called_once()

    app._on_action_duplicate_finder(None, None)
    win.show_duplicate_finder.assert_called_once()

    app._on_action_statistics(None, None)
    win.show_library_statistics.assert_called_once()


# ── Remote folders: the confirmation has a control, and hashing leaves the main loop (BL-UX-1) ────


def _remote_vm(tmp_path: Path):
    f1, f2 = tmp_path / "dup1.jpg", tmp_path / "dup2.jpg"
    f1.write_bytes(b"identical-image-payload")
    f2.write_bytes(b"identical-image-payload")
    vm = CullingWorkspaceViewModel()
    vm.candidates = [CandidatePhoto(primary_path=f1), CandidatePhoto(primary_path=f2)]
    vm.is_remote_folder = True
    return vm


def test_duplicate_finder_second_click_on_a_remote_folder_scans(tmp_path: Path):
    """The button turns into "Scan anyway"; clicking it again runs the scan instead of repeating the notice."""
    ran = []
    dlg = DuplicateFinderDialog(
        viewmodel=_remote_vm(tmp_path),
        dispatcher=lambda job, done: (ran.append(1), done(job(), None)),
    )
    button = object()  # what GTK passes to the "clicked" handler

    dlg._on_scan_clicked(button)
    assert dlg.awaiting_remote_confirmation and ran == []
    assert dlg.scan_button_label == "Scan anyway"

    dlg._on_scan_clicked(button)
    assert ran == [1] and not dlg.awaiting_remote_confirmation
    assert len(dlg.clusters) == 1
    assert dlg.scan_button_label == "Scan for Duplicates"


def test_remote_confirmation_controls_exist_in_the_real_dialogs(tmp_path: Path):
    from photo_selector_linux.ui import tools_dialogs

    if not tools_dialogs.HAS_GI or isinstance(tools_dialogs.Gtk, MagicMock):
        pytest.skip("real GTK needed")
    vm = _remote_vm(tmp_path)

    stats = LibraryStatisticsDialog(viewmodel=vm)
    assert stats.get_content() is not None, "a blank window on a remote folder"
    assert stats.btn_confirm.get_label() == "Scan anyway"
    stats.btn_confirm.emit("clicked")
    assert not stats.awaiting_remote_confirmation and stats.stats is not None

    ran = []
    dlg = DuplicateFinderDialog(
        viewmodel=vm, dispatcher=lambda job, done: (ran.append(1), done(job(), None))
    )
    dlg.btn_scan.emit("clicked")
    assert dlg.btn_scan.get_label() == "Scan anyway" and ran == []
    dlg.btn_scan.emit("clicked")
    assert ran == [1] and dlg.btn_scan.get_label() == "Scan for Duplicates"


def test_window_scans_for_duplicates_off_the_main_thread(tmp_path: Path, monkeypatch):
    from photo_selector_linux.ui import file_worker, tools_dialogs
    from photo_selector_linux.ui.window import MainWindow

    seen = {}

    class Recorder:
        def __init__(self, **kwargs):
            seen.update(kwargs)

        def present(self):
            pass

    monkeypatch.setattr(tools_dialogs, "DuplicateFinderDialog", Recorder)
    MainWindow().show_duplicate_finder()
    assert seen["dispatcher"] is file_worker.run_on_thread


def test_run_on_thread_keeps_the_main_loop_free():
    import time

    from photo_selector_linux.core import image_source
    from photo_selector_linux.ui import file_worker, photo_loader

    if not (image_source.HAS_GEXIV2 and photo_loader.HAS_GI) or isinstance(photo_loader.GLib, MagicMock):
        pytest.skip("needs the real GLib")
    from gi.repository import GLib

    ticks, delivered = [], []
    timer = GLib.timeout_add(10, lambda: ticks.append(1) or True)
    context = GLib.MainContext.default()
    try:
        file_worker.run_on_thread(lambda: time.sleep(0.5) or "hashes", lambda r, e: delivered.append((r, e)))
        deadline = time.monotonic() + 10
        while not delivered and time.monotonic() < deadline:
            context.iteration(False)
            time.sleep(0.001)
    finally:
        GLib.source_remove(timer)
    assert delivered == [("hashes", None)]
    assert len(ticks) >= 15, f"main loop ran {len(ticks)} ticks while the job slept"
