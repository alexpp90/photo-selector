"""Unit tests for transactional file operations, atomic moves, copy, and trash.

Adheres to:
- R-LINUX-CULL-03: Atomic Multi-File Move & Cross-Filesystem Rollback Safety
- R-LINUX-CULL-04: Atomic Multi-File Copy to Selection & Staged Rollback
- R-LINUX-CULL-05: FreeDesktop Safe Trash Integration
- R-LINUX-CULL-06: Transactional Copy-Undo Safety Invariant
- R-LINUX-CULL-07: Transactional Move and Trash Undo
"""

import os
import shutil
from pathlib import Path
from unittest.mock import MagicMock, patch
import pytest

from photo_selector_linux.core.file_ops import (
    FileCullingError,
    FileCullingManager,
    InsufficientDiskSpaceError,
    _apply_bundle_suffix,
    _copy_file,
    _move_no_overwrite,
    _resolve_bundle_destinations,
)
from photo_selector_linux.core.models import (
    CandidatePhoto,
    CullingActionType,
    PhotoStatus,
)
from photo_selector_linux.core.scanner import DirectoryScanner


def test_intra_filesystem_atomic_move(tmp_path: Path):
    """Verify intra-filesystem move moves primary + companions to Selection/ (R-LINUX-CULL-03)."""
    raw = tmp_path / "DSC_001.ARW"
    jpg = tmp_path / "DSC_001.JPG"
    xmp = tmp_path / "DSC_001.xmp"
    raw.write_bytes(b"raw-data")
    jpg.write_bytes(b"jpg-data")
    xmp.write_bytes(b"xmp-data")

    candidate = CandidatePhoto(primary_path=raw, companion_paths=[jpg, xmp])
    mgr = FileCullingManager()
    record = mgr.move_candidate(candidate, tmp_path)

    assert candidate.status == PhotoStatus.SELECTED
    assert record.action_type == CullingActionType.MOVE
    assert not raw.exists()
    assert not jpg.exists()
    assert not xmp.exists()

    sel_dir = tmp_path / "Selection"
    assert (sel_dir / "DSC_001.ARW").exists()
    assert (sel_dir / "DSC_001.JPG").exists()
    assert (sel_dir / "DSC_001.xmp").exists()


def test_self_move_guard(tmp_path: Path):
    """Verify self-move is an idempotent no-op preserving file without deletion (R-LINUX-CULL-03)."""
    sel_dir = tmp_path / "Selection"
    sel_dir.mkdir()
    target = sel_dir / "DSC_002.ARW"
    target.write_bytes(b"keep-content")

    candidate = CandidatePhoto(primary_path=target, companion_paths=[])
    mgr = FileCullingManager()
    record = mgr.move_candidate(candidate, tmp_path)
    assert record.action_type == CullingActionType.MOVE

    assert target.exists()
    assert target.read_bytes() == b"keep-content"
    assert candidate.status == PhotoStatus.SELECTED


def test_intra_filesystem_move_rollback_on_failure(tmp_path: Path):
    """Verify atomic rollback restores already-moved companions if subsequent move fails (R-LINUX-CULL-03)."""
    raw = tmp_path / "DSC_003.ARW"
    jpg = tmp_path / "DSC_003.JPG"
    raw.write_bytes(b"raw")
    jpg.write_bytes(b"jpg")

    candidate = CandidatePhoto(primary_path=raw, companion_paths=[jpg])
    mgr = FileCullingManager()

    orig_move = _move_no_overwrite
    call_count = [0]

    def mock_move(src, dst):
        call_count[0] += 1
        if call_count[0] == 2:
            raise PermissionError("Simulated write failure on companion")
        return orig_move(src, dst)

    with patch("photo_selector_linux.core.file_ops._move_no_overwrite", side_effect=mock_move):
        with pytest.raises(FileCullingError):
            mgr.move_candidate(candidate, tmp_path)

    # Rollback must restore all files to original source locations
    assert raw.exists()
    assert jpg.exists()
    assert not (tmp_path / "Selection" / "DSC_003.ARW").exists()


def test_cross_filesystem_preflight_space_check(tmp_path: Path):
    """Verify pre-flight capacity check raises InsufficientDiskSpaceError before disk mutation (R-LINUX-CULL-03).

    Only a cross-mount move copies bytes, so the destination is made to look like another device.
    """
    raw = tmp_path / "DSC_004.ARW"
    raw.write_bytes(b"x" * 1024)
    candidate = CandidatePhoto(primary_path=raw, companion_paths=[])
    mgr = FileCullingManager(dev_of=lambda p: 2 if "Selection" in str(p) else 1)

    mock_stat = MagicMock()
    mock_stat.f_bavail = 1
    mock_stat.f_frsize = 10  # 10 bytes available < 1024 bytes required

    with patch("os.statvfs", return_value=mock_stat):
        with pytest.raises(InsufficientDiskSpaceError):
            mgr.move_candidate(candidate, tmp_path)

    # Original file remains completely untouched
    assert raw.exists()


def test_cross_filesystem_two_phase_staging_and_rollback(tmp_path: Path):
    """Verify cross-mount staging failure cleans destination replicas, keeping sources intact (R-LINUX-CULL-03)."""
    raw = tmp_path / "DSC_005.ARW"
    jpg = tmp_path / "DSC_005.JPG"
    raw.write_bytes(b"raw")
    jpg.write_bytes(b"jpg")

    candidate = CandidatePhoto(primary_path=raw, companion_paths=[jpg])
    mgr = FileCullingManager()

    # Simulate cross-mount by faking different st_dev
    real_stat = Path.stat

    def mock_stat(self_path, *args, **kwargs):
        st = real_stat(self_path, *args, **kwargs)
        if "Selection" in str(self_path):
            st_mock = MagicMock()
            st_mock.st_dev = 999  # Different filesystem device ID
            st_mock.st_size = getattr(st, "st_size", 0)
            st_mock.st_mode = getattr(st, "st_mode", 0)
            return st_mock
        return st

    call_copy = [0]
    orig_copyfileobj = shutil.copyfileobj

    def mock_copyfileobj(fsrc, fdst, *args, **kwargs):
        call_copy[0] += 1
        if call_copy[0] == 2:
            raise IOError("Simulated network/device disconnect during companion staging")
        return orig_copyfileobj(fsrc, fdst, *args, **kwargs)

    with patch.object(Path, "stat", mock_stat), \
         patch("shutil.copyfileobj", side_effect=mock_copyfileobj):
        with pytest.raises(FileCullingError):
            mgr.move_candidate(candidate, tmp_path)

    # Invariant: All sources must remain intact! Destination replicas must be unlinked!
    assert raw.exists()
    assert jpg.exists()
    sel_raw = tmp_path / "Selection" / "DSC_005.ARW"
    assert not sel_raw.exists()


def test_atomic_copy_to_selection(tmp_path: Path):
    """Verify copy replicates primary + companions to Selection/ while preserving sources (R-LINUX-CULL-04)."""
    raw = tmp_path / "DSC_006.ARW"
    jpg = tmp_path / "DSC_006.JPG"
    raw.write_bytes(b"raw-data")
    jpg.write_bytes(b"jpg-data")

    candidate = CandidatePhoto(primary_path=raw, companion_paths=[jpg])
    mgr = FileCullingManager()
    record = mgr.copy_candidate(candidate, tmp_path)

    assert candidate.status == PhotoStatus.COPIED
    assert record.action_type == CullingActionType.COPY
    # Sources preserved
    assert raw.exists()
    assert jpg.exists()
    # Destination created
    assert (tmp_path / "Selection" / "DSC_006.ARW").exists()
    assert (tmp_path / "Selection" / "DSC_006.JPG").exists()


def test_copy_rollback_on_failure(tmp_path: Path):
    """Verify failed copy unlinks destination replicas without touching sources (R-LINUX-CULL-04)."""
    raw = tmp_path / "DSC_007.ARW"
    jpg = tmp_path / "DSC_007.JPG"
    raw.write_bytes(b"raw")
    jpg.write_bytes(b"jpg")

    candidate = CandidatePhoto(primary_path=raw, companion_paths=[jpg])
    mgr = FileCullingManager()

    call_count = [0]
    orig_copyfileobj = shutil.copyfileobj

    def mock_copyfileobj(fsrc, fdst, *args, **kwargs):
        call_count[0] += 1
        if call_count[0] == 2:
            raise IOError("Simulated copy failure")
        return orig_copyfileobj(fsrc, fdst, *args, **kwargs)

    with patch("shutil.copyfileobj", side_effect=mock_copyfileobj):
        with pytest.raises(FileCullingError):
            mgr.copy_candidate(candidate, tmp_path)

    assert raw.exists()
    assert jpg.exists()
    assert not (tmp_path / "Selection" / "DSC_007.ARW").exists()


def test_copy_undo_source_preservation_invariant(tmp_path: Path):
    """Verify copy undo deletes ONLY destination replicas in Selection/; sources NEVER deleted (R-LINUX-CULL-06)."""
    raw = tmp_path / "SOURCE_008.ARW"
    jpg = tmp_path / "SOURCE_008.JPG"
    raw.write_bytes(b"precious-source-raw")
    jpg.write_bytes(b"precious-source-jpg")

    candidate = CandidatePhoto(primary_path=raw, companion_paths=[jpg])
    mgr = FileCullingManager()
    record = mgr.copy_candidate(candidate, tmp_path)

    sel_raw = tmp_path / "Selection" / "SOURCE_008.ARW"
    sel_jpg = tmp_path / "Selection" / "SOURCE_008.JPG"
    assert sel_raw.exists()
    assert sel_jpg.exists()

    # Execute Undo
    undone = mgr.undo_culling(record, candidate)
    assert undone is True
    assert candidate.status == PhotoStatus.CANDIDATE

    # Invariant: Selection replicas deleted
    assert not sel_raw.exists()
    assert not sel_jpg.exists()

    # ABSOLUTE SAFETY INVARIANT: Original source files MUST BE INTACT!
    assert raw.exists()
    assert jpg.exists()
    assert raw.read_bytes() == b"precious-source-raw"
    assert jpg.read_bytes() == b"precious-source-jpg"


def test_move_undo_restores_all_companions(tmp_path: Path):
    """Verify move undo returns all files from Selection/ back to source paths (R-LINUX-CULL-07)."""
    raw = tmp_path / "ORIG_009.ARW"
    jpg = tmp_path / "ORIG_009.JPG"
    raw.write_bytes(b"raw-data")
    jpg.write_bytes(b"jpg-data")

    candidate = CandidatePhoto(primary_path=raw, companion_paths=[jpg])
    mgr = FileCullingManager()
    record = mgr.move_candidate(candidate, tmp_path)

    assert not raw.exists()
    assert not jpg.exists()
    assert (tmp_path / "Selection" / "ORIG_009.ARW").exists()

    # Execute Move Undo
    undone = mgr.undo_culling(record, candidate)
    assert undone is True
    assert candidate.status == PhotoStatus.CANDIDATE

    # Files restored to original locations
    assert raw.exists()
    assert jpg.exists()
    assert not (tmp_path / "Selection" / "ORIG_009.ARW").exists()
    assert not (tmp_path / "Selection" / "ORIG_009.JPG").exists()


def test_cross_filesystem_move_commit_partial_failure_preserves_destinations(tmp_path: Path):
    """Verify that if source unlinking fails during Phase 2 Commit, destination replicas are NEVER deleted."""
    raw = tmp_path / "DSC_010.ARW"
    jpg = tmp_path / "DSC_010.JPG"
    raw.write_bytes(b"precious-raw-bytes" * 50)
    jpg.write_bytes(b"precious-jpg-bytes" * 50)

    candidate = CandidatePhoto(primary_path=raw, companion_paths=[jpg])
    mgr = FileCullingManager()

    real_stat = Path.stat

    def mock_stat(self_path, *args, **kwargs):
        st = real_stat(self_path, *args, **kwargs)
        if "Selection" in str(self_path):
            st_mock = MagicMock()
            st_mock.st_dev = 999
            st_mock.st_size = getattr(st, "st_size", 0)
            st_mock.st_mode = getattr(st, "st_mode", 0)
            return st_mock
        return st

    orig_unlink = Path.unlink
    unlink_count = [0]

    def fail_on_second_source_unlink(self_path):
        unlink_count[0] += 1
        if unlink_count[0] == 2:
            raise PermissionError("Simulated second companion source unlink error (e.g. read-only SD-card lock)")
        return orig_unlink(self_path)

    with patch.object(Path, "stat", mock_stat), \
         patch.object(Path, "unlink", fail_on_second_source_unlink):
        with pytest.raises(FileCullingError) as exc_info:
            mgr.move_candidate(candidate, tmp_path)
        assert "destination replicas preserved" in str(exc_info.value)

    # CRITICAL ZERO DATA LOSS INVARIANT:
    # 1. Primary RAW destination replica must exist and have full content
    sel_raw = tmp_path / "Selection" / "DSC_010.ARW"
    assert sel_raw.exists()
    assert sel_raw.read_bytes() == b"precious-raw-bytes" * 50

    # 2. Companion JPG destination replica must exist and have full content
    sel_jpg = tmp_path / "Selection" / "DSC_010.JPG"
    assert sel_jpg.exists()
    assert sel_jpg.read_bytes() == b"precious-jpg-bytes" * 50

    # 3. First source was unlinked, second source remains intact
    assert not raw.exists()
    assert jpg.exists()


def test_cross_filesystem_move_staging_failure_cleans_inflight_and_staged_replicas(tmp_path: Path):
    """Verify failed cross-mount staging unlinks in-flight partial file and staged files (R-LINUX-CULL-03)."""
    raw = tmp_path / "DSC_011.ARW"
    jpg = tmp_path / "DSC_011.JPG"
    raw.write_bytes(b"raw-data-complete")
    jpg.write_bytes(b"jpg-data-complete")

    candidate = CandidatePhoto(primary_path=raw, companion_paths=[jpg])
    mgr = FileCullingManager()

    real_stat = Path.stat

    def mock_stat(self_path, *args, **kwargs):
        st = real_stat(self_path, *args, **kwargs)
        if "Selection" in str(self_path):
            st_mock = MagicMock()
            st_mock.st_dev = 999
            st_mock.st_size = getattr(st, "st_size", 0)
            st_mock.st_mode = getattr(st, "st_mode", 0)
            return st_mock
        return st

    orig_copyfileobj = shutil.copyfileobj
    call_copy = [0]

    def mock_copyfileobj_fail_midstream(fsrc, fdst, *args, **kwargs):
        call_copy[0] += 1
        if call_copy[0] == 2:
            fdst.write(b"PARTIAL_CORRUPT_BYTES")
            fdst.flush()
            raise OSError("No space left on device")
        return orig_copyfileobj(fsrc, fdst, *args, **kwargs)

    with patch.object(Path, "stat", mock_stat), \
         patch("shutil.copyfileobj", side_effect=mock_copyfileobj_fail_midstream):
        with pytest.raises(FileCullingError):
            mgr.move_candidate(candidate, tmp_path)

    # Invariant: Neither staged RAW nor partial in-flight JPG may remain in Selection/
    assert not (tmp_path / "Selection" / "DSC_011.ARW").exists()
    assert not (tmp_path / "Selection" / "DSC_011.JPG").exists()

    # Sources remain 100% intact
    assert raw.exists() and raw.read_bytes() == b"raw-data-complete"
    assert jpg.exists() and jpg.read_bytes() == b"jpg-data-complete"


def test_copy_staging_failure_cleans_inflight_and_staged_replicas(tmp_path: Path):
    """Verify failed copy unlinks in-flight partial file and staged files (R-LINUX-CULL-04)."""
    raw = tmp_path / "DSC_012.ARW"
    jpg = tmp_path / "DSC_012.JPG"
    raw.write_bytes(b"raw-data")
    jpg.write_bytes(b"jpg-data")

    candidate = CandidatePhoto(primary_path=raw, companion_paths=[jpg])
    mgr = FileCullingManager()

    orig_copyfileobj = shutil.copyfileobj
    call_copy = [0]

    def mock_copyfileobj_fail_midstream(fsrc, fdst, *args, **kwargs):
        call_copy[0] += 1
        if call_copy[0] == 2:
            fdst.write(b"CORRUPT")
            fdst.flush()
            raise OSError("No space left on device")
        return orig_copyfileobj(fsrc, fdst, *args, **kwargs)

    with patch("shutil.copyfileobj", side_effect=mock_copyfileobj_fail_midstream):
        with pytest.raises(FileCullingError):
            mgr.copy_candidate(candidate, tmp_path)

    assert not (tmp_path / "Selection" / "DSC_012.ARW").exists()
    assert not (tmp_path / "Selection" / "DSC_012.JPG").exists()
    assert raw.exists() and jpg.exists()


def test_trash_and_undo_restores_all_companions(tmp_path: Path):
    """Verify trash_candidate moves files to FreeDesktop Trash and undo_culling restores them (R-LINUX-CULL-05/07)."""
    trash_dir = tmp_path / "Trash"
    raw = tmp_path / "DSC_013.ARW"
    jpg = tmp_path / "DSC_013.JPG"
    xmp = tmp_path / "DSC_013.xmp"
    raw.write_bytes(b"raw-trash-payload")
    jpg.write_bytes(b"jpg-trash-payload")
    xmp.write_bytes(b"xmp-trash-payload")

    candidate = CandidatePhoto(primary_path=raw, companion_paths=[jpg, xmp])
    mgr = FileCullingManager(trash_dir=trash_dir)
    record = mgr.trash_candidate(candidate)

    assert candidate.status == PhotoStatus.TRASHED
    assert record.action_type == CullingActionType.TRASH
    assert not raw.exists()
    assert not jpg.exists()
    assert not xmp.exists()
    assert (trash_dir / "files" / "DSC_013.ARW").exists()
    assert (trash_dir / "files" / "DSC_013.JPG").exists()
    assert (trash_dir / "files" / "DSC_013.xmp").exists()
    assert (trash_dir / "info" / "DSC_013.ARW.trashinfo").exists()

    # Execute Trash Undo
    undone = mgr.undo_culling(record, candidate)
    assert undone is True
    assert candidate.status == PhotoStatus.CANDIDATE

    # Files physically restored to original locations and .trashinfo removed
    assert raw.exists() and raw.read_bytes() == b"raw-trash-payload"
    assert jpg.exists() and jpg.read_bytes() == b"jpg-trash-payload"
    assert xmp.exists() and xmp.read_bytes() == b"xmp-trash-payload"
    assert not (trash_dir / "files" / "DSC_013.ARW").exists()
    assert not (trash_dir / "info" / "DSC_013.ARW.trashinfo").exists()


def test_apply_bundle_suffix_companion_variants():
    """Verify _apply_bundle_suffix preserves companion pairing across all sidecar and edit forms."""
    assert _apply_bundle_suffix("IMG_0001.CR3", "IMG_0001", 2) == "IMG_0001-2.CR3"
    assert _apply_bundle_suffix("IMG_0001.JPG", "IMG_0001", 2) == "IMG_0001-2.JPG"
    assert _apply_bundle_suffix("IMG_0001.CR3.xmp", "IMG_0001", 2) == "IMG_0001-2.CR3.xmp"
    assert _apply_bundle_suffix("IMG_0001.xmp", "IMG_0001", 2) == "IMG_0001-2.xmp"
    assert _apply_bundle_suffix("IMG_0001-Edit.tif", "IMG_0001", 2) == "IMG_0001-2-Edit.tif"
    assert _apply_bundle_suffix("IMG_0001_Edit.tif", "IMG_0001", 2) == "IMG_0001-2_Edit.tif"
    assert _apply_bundle_suffix("IMG_0001-Enhanced-NR.dng", "IMG_0001", 2) == "IMG_0001-2-Enhanced-NR.dng"
    assert _apply_bundle_suffix("IMG_0001_Enhanced-NR.dng", "IMG_0001", 2) == "IMG_0001-2_Enhanced-NR.dng"


def test_move_two_same_named_bundles_in_sequence_preserves_both_and_pairing(tmp_path: Path):
    """Moving two different bundles named IMG_0001.* in sequence keeps both (IMG_0001.* and IMG_0001-2.*)."""
    cam_a = tmp_path / "cam_a"
    cam_b = tmp_path / "cam_b"
    cam_a.mkdir()
    cam_b.mkdir()

    a_cr3 = cam_a / "IMG_0001.CR3"
    a_jpg = cam_a / "IMG_0001.JPG"
    a_xmp = cam_a / "IMG_0001.CR3.xmp"
    a_edit = cam_a / "IMG_0001-Edit.tif"
    a_cr3.write_bytes(b"cam-a-cr3")
    a_jpg.write_bytes(b"cam-a-jpg")
    a_xmp.write_bytes(b"cam-a-xmp")
    a_edit.write_bytes(b"cam-a-edit")

    b_cr3 = cam_b / "IMG_0001.CR3"
    b_jpg = cam_b / "IMG_0001.JPG"
    b_xmp = cam_b / "IMG_0001.CR3.xmp"
    b_edit = cam_b / "IMG_0001-Edit.tif"
    b_cr3.write_bytes(b"cam-b-cr3")
    b_jpg.write_bytes(b"cam-b-jpg")
    b_xmp.write_bytes(b"cam-b-xmp")
    b_edit.write_bytes(b"cam-b-edit")

    cand_a = CandidatePhoto(primary_path=a_cr3, companion_paths=[a_jpg, a_xmp, a_edit])
    cand_b = CandidatePhoto(primary_path=b_cr3, companion_paths=[b_jpg, b_xmp, b_edit])

    mgr = FileCullingManager()
    mgr.move_candidate(cand_a, tmp_path)
    mgr.move_candidate(cand_b, tmp_path)

    sel_dir = tmp_path / "Selection"
    assert (sel_dir / "IMG_0001.CR3").read_bytes() == b"cam-a-cr3"
    assert (sel_dir / "IMG_0001.JPG").read_bytes() == b"cam-a-jpg"
    assert (sel_dir / "IMG_0001.CR3.xmp").read_bytes() == b"cam-a-xmp"
    assert (sel_dir / "IMG_0001-Edit.tif").read_bytes() == b"cam-a-edit"

    assert (sel_dir / "IMG_0001-2.CR3").read_bytes() == b"cam-b-cr3"
    assert (sel_dir / "IMG_0001-2.JPG").read_bytes() == b"cam-b-jpg"
    assert (sel_dir / "IMG_0001-2.CR3.xmp").read_bytes() == b"cam-b-xmp"
    assert (sel_dir / "IMG_0001-2-Edit.tif").read_bytes() == b"cam-b-edit"

    # Verify DirectoryScanner pairs both bundles cleanly when scanning Selection/ directly
    scanned = DirectoryScanner().scan_directory(sel_dir)
    scanned_by_name = {c.filename: c for c in scanned}
    assert set(scanned_by_name.keys()) == {"IMG_0001.CR3", "IMG_0001-2.CR3"}
    assert sorted(p.name for p in scanned_by_name["IMG_0001.CR3"].companion_paths) == [
        "IMG_0001-Edit.tif",
        "IMG_0001.CR3.xmp",
        "IMG_0001.JPG",
    ]
    assert sorted(p.name for p in scanned_by_name["IMG_0001-2.CR3"].companion_paths) == [
        "IMG_0001-2-Edit.tif",
        "IMG_0001-2.CR3.xmp",
        "IMG_0001-2.JPG",
    ]


def test_copy_when_companion_already_exists_suffixes_entire_bundle(tmp_path: Path):
    """When any member (e.g. IMG_0001.JPG) already exists in Selection/, the whole bundle gets -2."""
    sel_dir = tmp_path / "Selection"
    sel_dir.mkdir()
    existing_jpg = sel_dir / "IMG_0001.JPG"
    existing_jpg.write_bytes(b"preexisting-jpg")

    cr3 = tmp_path / "IMG_0001.CR3"
    jpg = tmp_path / "IMG_0001.JPG"
    xmp = tmp_path / "IMG_0001.CR3.xmp"
    cr3.write_bytes(b"new-cr3")
    jpg.write_bytes(b"new-jpg")
    xmp.write_bytes(b"new-xmp")

    candidate = CandidatePhoto(primary_path=cr3, companion_paths=[jpg, xmp])
    mgr = FileCullingManager()
    mgr.copy_candidate(candidate, tmp_path)

    assert existing_jpg.read_bytes() == b"preexisting-jpg"
    assert not (sel_dir / "IMG_0001.CR3").exists()
    assert not (sel_dir / "IMG_0001.CR3.xmp").exists()
    assert (sel_dir / "IMG_0001-2.CR3").read_bytes() == b"new-cr3"
    assert (sel_dir / "IMG_0001-2.JPG").read_bytes() == b"new-jpg"
    assert (sel_dir / "IMG_0001-2.CR3.xmp").read_bytes() == b"new-xmp"


def test_copy_then_undo_with_preexisting_destination_preserves_preexisting_file(tmp_path: Path):
    """Copy-then-undo when IMG_0001.JPG pre-existed in Selection/ deletes only IMG_0001-2.JPG."""
    sel_dir = tmp_path / "Selection"
    sel_dir.mkdir()
    preexisting_jpg = sel_dir / "IMG_0001.JPG"
    preexisting_jpg.write_bytes(b"preexisting-selection-jpg")

    src_jpg = tmp_path / "IMG_0001.JPG"
    src_jpg.write_bytes(b"candidate-source-jpg")

    candidate = CandidatePhoto(primary_path=src_jpg, companion_paths=[])
    mgr = FileCullingManager()
    record = mgr.copy_candidate(candidate, tmp_path)

    copied_dst = sel_dir / "IMG_0001-2.JPG"
    assert copied_dst.exists() and copied_dst.read_bytes() == b"candidate-source-jpg"
    assert preexisting_jpg.read_bytes() == b"preexisting-selection-jpg"

    assert mgr.undo_culling(record, candidate) is True

    assert not copied_dst.exists()
    assert preexisting_jpg.exists() and preexisting_jpg.read_bytes() == b"preexisting-selection-jpg"
    assert src_jpg.exists() and src_jpg.read_bytes() == b"candidate-source-jpg"


def test_move_then_undo_when_original_source_occupied_restores_with_suffix(tmp_path: Path):
    """When original source path is occupied before undo, existing file is kept and bundle restores as -2."""
    cr3 = tmp_path / "IMG_0001.CR3"
    jpg = tmp_path / "IMG_0001.JPG"
    xmp = tmp_path / "IMG_0001.CR3.xmp"
    cr3.write_bytes(b"moved-cr3")
    jpg.write_bytes(b"moved-jpg")
    xmp.write_bytes(b"moved-xmp")

    candidate = CandidatePhoto(primary_path=cr3, companion_paths=[jpg, xmp])
    mgr = FileCullingManager()
    record = mgr.move_candidate(candidate, tmp_path)

    # Simulate a new file imported at the original primary path before undo
    cr3.write_bytes(b"newly-imported-cr3-do-not-overwrite")

    assert mgr.undo_culling(record, candidate) is True

    # Existing occupying file is 100% preserved
    assert cr3.read_bytes() == b"newly-imported-cr3-do-not-overwrite"

    # Moved bundle restored under -2 suffix
    assert (tmp_path / "IMG_0001-2.CR3").read_bytes() == b"moved-cr3"
    assert (tmp_path / "IMG_0001-2.JPG").read_bytes() == b"moved-jpg"
    assert (tmp_path / "IMG_0001-2.CR3.xmp").read_bytes() == b"moved-xmp"
    assert candidate.primary_path == tmp_path / "IMG_0001-2.CR3"
    assert candidate.companion_paths == [tmp_path / "IMG_0001-2.JPG", tmp_path / "IMG_0001-2.CR3.xmp"]


def test_trash_then_undo_when_original_source_occupied_restores_with_suffix(tmp_path: Path):
    """When original path is occupied before trash undo, existing file is kept and trashed bundle restores as -2."""
    trash_dir = tmp_path / "Trash"
    cr3 = tmp_path / "IMG_0001.CR3"
    jpg = tmp_path / "IMG_0001.JPG"
    cr3.write_bytes(b"trashed-cr3")
    jpg.write_bytes(b"trashed-jpg")

    candidate = CandidatePhoto(primary_path=cr3, companion_paths=[jpg])
    mgr = FileCullingManager(trash_dir=trash_dir)
    record = mgr.trash_candidate(candidate)

    # Occupy original path before undo
    cr3.write_bytes(b"occupying-cr3")

    assert mgr.undo_culling(record, candidate) is True

    assert cr3.read_bytes() == b"occupying-cr3"
    assert (tmp_path / "IMG_0001-2.CR3").read_bytes() == b"trashed-cr3"
    assert (tmp_path / "IMG_0001-2.JPG").read_bytes() == b"trashed-jpg"
    assert candidate.primary_path == tmp_path / "IMG_0001-2.CR3"
    assert candidate.companion_paths == [tmp_path / "IMG_0001-2.JPG"]


def test_cross_mount_staging_with_preexisting_destination_name_stages_to_suffix_and_fsyncs(tmp_path: Path):
    """Cross-mount move with pre-existing destination name stages to -2, calls fsync, and preserves existing file."""
    sel_dir = tmp_path / "Selection"
    sel_dir.mkdir()
    preexisting = sel_dir / "IMG_0001.CR3"
    preexisting.write_bytes(b"preexisting-cr3")

    cr3 = tmp_path / "IMG_0001.CR3"
    jpg = tmp_path / "IMG_0001.JPG"
    cr3.write_bytes(b"cross-mount-cr3")
    jpg.write_bytes(b"cross-mount-jpg")

    candidate = CandidatePhoto(primary_path=cr3, companion_paths=[jpg])
    mgr = FileCullingManager()

    real_stat = Path.stat

    def mock_stat(self_path, *args, **kwargs):
        st = real_stat(self_path, *args, **kwargs)
        if "Selection" in str(self_path):
            st_mock = MagicMock()
            st_mock.st_dev = 999
            st_mock.st_size = getattr(st, "st_size", 0)
            st_mock.st_mode = getattr(st, "st_mode", 0)
            return st_mock
        return st

    fsync_calls = []
    orig_fsync = os.fsync

    def record_fsync(fd):
        fsync_calls.append(fd)
        return orig_fsync(fd)

    with patch.object(Path, "stat", mock_stat), \
         patch("os.fsync", side_effect=record_fsync):
        record = mgr.move_candidate(candidate, tmp_path)

    assert len(fsync_calls) == 2
    assert preexisting.read_bytes() == b"preexisting-cr3"
    assert (sel_dir / "IMG_0001-2.CR3").read_bytes() == b"cross-mount-cr3"
    assert (sel_dir / "IMG_0001-2.JPG").read_bytes() == b"cross-mount-jpg"
    assert not cr3.exists()
    assert not jpg.exists()
    assert record.affected_paths == [
        (cr3, (sel_dir / "IMG_0001-2.CR3").resolve()),
        (jpg, (sel_dir / "IMG_0001-2.JPG").resolve()),
    ]


def test_copy_file_and_move_no_overwrite_raise_file_exists_error(tmp_path: Path):
    """_copy_file (O_CREAT | O_EXCL) and _move_no_overwrite never overwrite an existing destination file."""
    src = tmp_path / "src.jpg"
    dst = tmp_path / "dst.jpg"
    src.write_bytes(b"new-bytes")
    dst.write_bytes(b"existing-precious-bytes")

    with pytest.raises(FileExistsError):
        _copy_file(src, dst)
    assert dst.read_bytes() == b"existing-precious-bytes"

    with pytest.raises(FileExistsError):
        _move_no_overwrite(src, dst)
    assert dst.read_bytes() == b"existing-precious-bytes"
    assert src.read_bytes() == b"new-bytes"


def test_no_os_replace_used_in_file_ops():
    """Verify file_ops.py contains no os.replace calls that could overwrite existing user files."""
    import ast
    import inspect
    from photo_selector_linux.core import file_ops

    tree = ast.parse(inspect.getsource(file_ops))
    replace_calls = [
        node for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
        and node.func.attr == "replace"
        and isinstance(node.func.value, ast.Name)
        and node.func.value.id == "os"
    ]
    assert replace_calls == []


def test_resolve_bundle_destinations_skips_occupied_suffixes(tmp_path: Path):
    """If both unsuffixed and -2 are occupied for any member, _resolve_bundle_destinations selects -3."""
    sel_dir = tmp_path / "Selection"
    sel_dir.mkdir()
    (sel_dir / "IMG_0001.CR3").write_bytes(b"v1")
    (sel_dir / "IMG_0001-2.JPG").write_bytes(b"v2-jpg")

    cr3 = tmp_path / "IMG_0001.CR3"
    jpg = tmp_path / "IMG_0001.JPG"
    cr3.write_bytes(b"v3-cr3")
    jpg.write_bytes(b"v3-jpg")

    candidate = CandidatePhoto(primary_path=cr3, companion_paths=[jpg])
    resolved = _resolve_bundle_destinations(candidate, sel_dir)
    assert resolved == [
        (cr3, (sel_dir / "IMG_0001-3.CR3").resolve()),
        (jpg, (sel_dir / "IMG_0001-3.JPG").resolve()),
    ]


def _make_card_mount_manager(card_mount: Path, home_root: Path, uid: int = 1000) -> FileCullingManager:
    card_res = card_mount.resolve()

    def _is_on_card(p: Path) -> bool:
        try:
            p.resolve().relative_to(card_res)
            return True
        except ValueError:
            return False

    return FileCullingManager(
        mount_root_for=lambda p: card_res if _is_on_card(p) else home_root.resolve(),
        dev_of=lambda p: 77 if _is_on_card(p) else 1,
        uid=uid,
    )


def test_card_mount_trash_and_undo_restores_bundle_and_ignores_home_trash_collision(
    tmp_path: Path, monkeypatch
):
    """Trashing RAW+JPG+XMP on a mounted card uses <card>/.Trash-$UID with relative Path= and restores cleanly."""
    xdg_data = tmp_path / "xdg_data"
    monkeypatch.setenv("XDG_DATA_HOME", str(xdg_data))
    home_trash = xdg_data / "Trash"
    (home_trash / "files").mkdir(parents=True)
    (home_trash / "info").mkdir(parents=True)

    # Unrelated file with the same name in the user's home trash
    unrelated_home_cr3 = home_trash / "files" / "IMG_0001.CR3"
    unrelated_home_info = home_trash / "info" / "IMG_0001.CR3.trashinfo"
    unrelated_home_cr3.write_bytes(b"unrelated-home-trash-cr3")
    unrelated_home_info.write_text(
        "[Trash Info]\nPath=/home/user/Pictures/IMG_0001.CR3\nDeletionDate=2026-01-01T00:00:00\n",
        encoding="utf-8",
    )

    # Mounted SD card with DCIM/100EOS/IMG_0001.{CR3,JPG,CR3.xmp}
    card_mount = tmp_path / "EOS_DIGITAL"
    dcim_dir = card_mount / "DCIM" / "100EOS"
    dcim_dir.mkdir(parents=True)
    cr3 = dcim_dir / "IMG_0001.CR3"
    jpg = dcim_dir / "IMG_0001.JPG"
    xmp = dcim_dir / "IMG_0001.CR3.xmp"
    cr3.write_bytes(b"card-cr3-bytes")
    jpg.write_bytes(b"card-jpg-bytes")
    xmp.write_bytes(b"card-xmp-bytes")

    candidate = CandidatePhoto(primary_path=cr3, companion_paths=[jpg, xmp])
    mgr = _make_card_mount_manager(card_mount, tmp_path, uid=1000)

    record = mgr.trash_candidate(candidate)

    card_trash = card_mount / ".Trash-1000"
    assert not cr3.exists() and not jpg.exists() and not xmp.exists()
    assert (card_trash / "files" / "IMG_0001.CR3").read_bytes() == b"card-cr3-bytes"
    assert (card_trash / "files" / "IMG_0001.JPG").read_bytes() == b"card-jpg-bytes"
    assert (card_trash / "files" / "IMG_0001.CR3.xmp").read_bytes() == b"card-xmp-bytes"

    cr3_info_text = (card_trash / "info" / "IMG_0001.CR3.trashinfo").read_text(encoding="utf-8")
    assert "Path=DCIM/100EOS/IMG_0001.CR3\n" in cr3_info_text

    # _find_trashed_entry resolves relative Path= against the card mount root, not home trash
    found_entry = mgr._find_trashed_entry(cr3)
    assert found_entry == (
        (card_trash / "files" / "IMG_0001.CR3").resolve(),
        (card_trash / "info" / "IMG_0001.CR3.trashinfo").resolve(),
    )

    # Undo restores all 3 files from card trash and leaves the unrelated home-trash file untouched
    assert mgr.undo_culling(record, candidate) is True
    assert cr3.exists() and cr3.read_bytes() == b"card-cr3-bytes"
    assert jpg.exists() and jpg.read_bytes() == b"card-jpg-bytes"
    assert xmp.exists() and xmp.read_bytes() == b"card-xmp-bytes"
    assert not (card_trash / "files" / "IMG_0001.CR3").exists()
    assert not (card_trash / "info" / "IMG_0001.CR3.trashinfo").exists()
    assert unrelated_home_cr3.exists() and unrelated_home_cr3.read_bytes() == b"unrelated-home-trash-cr3"
    assert unrelated_home_info.exists()


def test_trash_undo_missing_recorded_item_raises_and_does_not_restore_unrelated_same_named_file(
    tmp_path: Path,
):
    """If the recorded trashed item was removed before undo, undo raises FileCullingError and never guesses by name."""
    trash_dir = tmp_path / "Trash"
    (trash_dir / "files").mkdir(parents=True)
    (trash_dir / "info").mkdir(parents=True)

    # Pre-existing unrelated file named IMG_0001.CR3 in Trash/files (Forces candidate into IMG_0001.2.CR3)
    unrelated_in_trash = trash_dir / "files" / "IMG_0001.CR3"
    unrelated_info = trash_dir / "info" / "IMG_0001.CR3.trashinfo"
    unrelated_in_trash.write_bytes(b"unrelated-older-shoot-cr3")
    unrelated_info.write_text(
        "[Trash Info]\nPath=/other/folder/IMG_0001.CR3\nDeletionDate=2026-01-01T00:00:00\n",
        encoding="utf-8",
    )

    cr3 = tmp_path / "IMG_0001.CR3"
    cr3.write_bytes(b"current-shoot-cr3")
    candidate = CandidatePhoto(primary_path=cr3, companion_paths=[])
    mgr = FileCullingManager(trash_dir=trash_dir)

    record = mgr.trash_candidate(candidate)
    recorded_dst = dict(record.affected_paths)[cr3]
    assert recorded_dst.name == "IMG_0001.2.CR3"

    # Simulate external trash cleanup deleting the recorded trashed item before undo
    recorded_dst.unlink()
    record.trash_info_paths[cr3].unlink()

    with pytest.raises(FileCullingError) as exc_info:
        mgr.undo_culling(record, candidate)

    assert "IMG_0001.CR3" in str(exc_info.value)
    # Invariant: unrelated files/IMG_0001.CR3 must NEVER be restored to cr3!
    assert not cr3.exists()
    assert unrelated_in_trash.exists() and unrelated_in_trash.read_bytes() == b"unrelated-older-shoot-cr3"


def test_card_mount_trash_undo_when_original_occupied_restores_with_suffix(
    tmp_path: Path, monkeypatch
):
    """When original path on a mounted card is occupied before undo, card trash undo restores bundle with -2 suffix."""
    monkeypatch.setenv("XDG_DATA_HOME", str(tmp_path / "xdg_data"))
    card_mount = tmp_path / "EOS_DIGITAL"
    dcim_dir = card_mount / "DCIM" / "100EOS"
    dcim_dir.mkdir(parents=True)

    cr3 = dcim_dir / "IMG_0001.CR3"
    jpg = dcim_dir / "IMG_0001.JPG"
    cr3.write_bytes(b"trashed-card-cr3")
    jpg.write_bytes(b"trashed-card-jpg")

    candidate = CandidatePhoto(primary_path=cr3, companion_paths=[jpg])
    mgr = _make_card_mount_manager(card_mount, tmp_path, uid=1000)

    record = mgr.trash_candidate(candidate)

    # Occupy the original primary path on the card before undo
    cr3.write_bytes(b"new-card-shot-do-not-overwrite")

    assert mgr.undo_culling(record, candidate) is True
    assert cr3.read_bytes() == b"new-card-shot-do-not-overwrite"
    assert (dcim_dir / "IMG_0001-2.CR3").read_bytes() == b"trashed-card-cr3"
    assert (dcim_dir / "IMG_0001-2.JPG").read_bytes() == b"trashed-card-jpg"
    assert candidate.primary_path == dcim_dir / "IMG_0001-2.CR3"
    assert candidate.companion_paths == [dcim_dir / "IMG_0001-2.JPG"]


def test_bundle_trash_failure_rolls_back_first_member_and_retains_partial_record_if_rollback_fails(
    tmp_path: Path,
):
    """Half-failed bundle trash rolls back the first member; if rollback fails, retains partial_record for undo."""
    from photo_selector_linux.viewmodels.culling_workspace import CullingWorkspaceViewModel

    trash_dir = tmp_path / "Trash"
    (trash_dir / "files").mkdir(parents=True)
    (trash_dir / "info").mkdir(parents=True)

    # Put an unrelated IMG_0001.CR3 in Trash so our trashed primary lands at IMG_0001.2.CR3
    unrelated = trash_dir / "files" / "IMG_0001.CR3"
    unrelated.write_bytes(b"unrelated-trash-payload")
    (trash_dir / "info" / "IMG_0001.CR3.trashinfo").write_text(
        "[Trash Info]\nPath=/elsewhere/IMG_0001.CR3\nDeletionDate=2026-01-01T00:00:00\n",
        encoding="utf-8",
    )

    cr3 = tmp_path / "IMG_0001.CR3"
    jpg = tmp_path / "IMG_0001.JPG"
    cr3.write_bytes(b"precious-cr3")
    jpg.write_bytes(b"precious-jpg")

    candidate = CandidatePhoto(primary_path=cr3, companion_paths=[jpg])
    mgr = FileCullingManager(trash_dir=trash_dir)

    orig_trash_fn = mgr._trash_file_freedesktop
    call_count = [0]

    def fail_on_second_companion(src: Path, name=None):
        call_count[0] += 1
        if call_count[0] == 2:
            raise PermissionError("Simulated companion trash failure")
        return orig_trash_fn(src, name)

    # Case A: Second member fails, rollback succeeds and restores IMG_0001.2.CR3 -> IMG_0001.CR3
    with patch.object(mgr, "_trash_file_freedesktop", side_effect=fail_on_second_companion):
        with pytest.raises(FileCullingError) as exc_info:
            mgr.trash_candidate(candidate)
        assert exc_info.value.partial_record is None

    assert cr3.exists() and cr3.read_bytes() == b"precious-cr3"
    assert jpg.exists() and jpg.read_bytes() == b"precious-jpg"
    assert unrelated.exists() and unrelated.read_bytes() == b"unrelated-trash-payload"
    assert not (trash_dir / "files" / "IMG_0001.2.CR3").exists()

    # Case B: Second member fails AND rollback of first member also fails -> error names IMG_0001.CR3
    # and carries partial_record so CullingWorkspaceViewModel preserves it on undo_stack
    call_count[0] = 0
    vm = CullingWorkspaceViewModel(file_manager=mgr)
    vm.candidates = [candidate]
    vm.current_folder = tmp_path
    notifications = []
    vm.connect("notification", lambda _vm, msg: notifications.append(msg))

    with patch.object(mgr, "_trash_file_freedesktop", side_effect=fail_on_second_companion), \
         patch.object(mgr, "_restore_from_trash", side_effect=OSError("Simulated rollback I/O error")):
        vm.cull_trash()

    assert notifications and "IMG_0001.CR3" in notifications[-1]
    assert len(vm.undo_stack) == 1
    partial_rec, recorded_photo, _ = vm.undo_stack[0]
    assert recorded_photo is candidate
    assert partial_rec.affected_paths == [(cr3, (trash_dir / "files" / "IMG_0001.2.CR3").resolve())]

    # Now that the transient I/O error has cleared, user can Undo to recover IMG_0001.CR3 from Trash!
    assert vm.undo() is True
    assert cr3.exists() and cr3.read_bytes() == b"precious-cr3"
    assert jpg.exists() and jpg.read_bytes() == b"precious-jpg"
    assert unrelated.exists() and unrelated.read_bytes() == b"unrelated-trash-payload"


# ── B-1: partial cross-mount move (D-1) and capacity pre-flight (D-7) ───────────


class _NoNames:
    def names_in(self, directory):
        return {}


def test_cross_mount_move_with_unremovable_source_returns_partial_record(tmp_path: Path):
    """D-1: the verified copy stays in Selection/, the source stays on the card, and the failure
    carries a record (marked partial) so the caller can offer undo instead of retrying into IMG-2."""
    card = tmp_path / "card"
    card.mkdir()
    (card / "IMG_1.JPG").write_bytes(b"x" * 100)
    mgr = FileCullingManager(
        name_resolver=_NoNames(),
        trash_dir=tmp_path / "Trash",
        dev_of=lambda p: 2 if "Selection" in str(p) else 1,
        mount_root_for=lambda p: None,
    )
    real_unlink = Path.unlink

    def failing_unlink(self, *args, **kwargs):
        if self.parent == card:
            raise PermissionError("read-only card")
        return real_unlink(self, *args, **kwargs)

    candidate = CandidatePhoto(primary_path=card / "IMG_1.JPG")
    with patch.object(Path, "unlink", failing_unlink):
        with pytest.raises(FileCullingError) as exc_info:
            mgr.move_candidate(candidate, card)

    record = exc_info.value.partial_record
    assert record is not None
    assert record.action_type == CullingActionType.MOVE
    assert [dst.name for _, dst in record.affected_paths] == ["IMG_1.JPG"]
    assert record.unremoved_sources == [card / "IMG_1.JPG"]
    assert "original could not be removed" in str(exc_info.value)
    assert (card / "Selection" / "IMG_1.JPG").read_bytes() == b"x" * 100
    assert (card / "IMG_1.JPG").exists()


def test_same_filesystem_move_skips_the_capacity_preflight(tmp_path: Path):
    """D-7: a rename needs no free space, so a full disk must not refuse it."""
    (tmp_path / "IMG_2.JPG").write_bytes(b"y" * 1000)
    mgr = FileCullingManager(name_resolver=_NoNames(), trash_dir=tmp_path / "Trash")

    class FullDisk:
        f_bavail = 0
        f_frsize = 4096

    with patch("os.statvfs", lambda p: FullDisk()):
        record = mgr.move_candidate(CandidatePhoto(primary_path=tmp_path / "IMG_2.JPG"), tmp_path)

    assert (tmp_path / "Selection" / "IMG_2.JPG").exists()
    assert not (tmp_path / "IMG_2.JPG").exists()
    assert record.unremoved_sources == []


# ── B-2: FreeDesktop trash details (D-2, D-11) ─────────────────────────────────


def _card_manager(tmp_path: Path):
    card = tmp_path / "card"
    card.mkdir()
    home = tmp_path / "home"
    home.mkdir()
    return card, _make_card_mount_manager(card, home)


def _trash_one(mgr: FileCullingManager, card: Path):
    photo = card / "IMG_9.JPG"
    photo.write_bytes(b"photo")
    record = mgr.trash_candidate(CandidatePhoto(primary_path=photo))
    return record.affected_paths[0][1]


def test_top_dir_trash_uses_shared_dot_trash_only_when_sticky_and_not_a_symlink(tmp_path: Path):
    """D-11: $topdir/.Trash/$uid is allowed only if .Trash is a real directory with the sticky bit."""
    card, mgr = _card_manager(tmp_path)
    shared = card / ".Trash"
    shared.mkdir()
    os.chmod(shared, 0o1777)

    trashed = _trash_one(mgr, card)

    assert trashed.parent.parent == (card / ".Trash" / "1000").resolve()


def test_top_dir_trash_falls_back_to_per_user_dir_when_dot_trash_lacks_sticky_bit(tmp_path: Path):
    card, mgr = _card_manager(tmp_path)
    shared = card / ".Trash"
    shared.mkdir()
    os.chmod(shared, 0o777)

    trashed = _trash_one(mgr, card)

    assert trashed.parent.parent == (card / ".Trash-1000").resolve()


def test_top_dir_trash_ignores_a_dot_trash_symlink(tmp_path: Path):
    card, mgr = _card_manager(tmp_path)
    elsewhere = tmp_path / "elsewhere"
    elsewhere.mkdir()
    os.chmod(elsewhere, 0o1777)
    (card / ".Trash").symlink_to(elsewhere)

    trashed = _trash_one(mgr, card)

    assert trashed.parent.parent == (card / ".Trash-1000").resolve()
    assert list(elsewhere.iterdir()) == []


def test_trashinfo_is_created_exclusively_and_the_next_name_is_used_on_a_race(tmp_path: Path):
    """D-11: the .trashinfo reserves its name with O_EXCL; losing the race picks the next name."""
    import errno

    trash = tmp_path / "Trash"
    mgr = FileCullingManager(trash_dir=trash)
    photo = tmp_path / "IMG_5.JPG"
    photo.write_bytes(b"data")

    real_open = os.open
    raced = []

    def racing_open(path, flags, *args, **kwargs):
        if str(path).endswith("IMG_5.JPG.trashinfo") and not raced:
            raced.append(path)
            Path(path).write_text("[Trash Info]\nPath=/someone/else\n")  # the other process won
            raise FileExistsError(errno.EEXIST, "File exists", str(path))
        return real_open(path, flags, *args, **kwargs)

    with patch("os.open", racing_open):
        files_path, info_path = mgr._trash_file_freedesktop(photo)

    assert raced, "the exclusive create was never attempted"
    assert files_path.name == "IMG_5.2.JPG"
    assert info_path.name == "IMG_5.2.JPG.trashinfo"
    # The other process's entry is untouched.
    assert "someone/else" in (trash / "info" / "IMG_5.JPG.trashinfo").read_text()


class _FakeGioFile:
    def __init__(self, on_trash=None, children=None, move_ok=True, path=None):
        self.on_trash = on_trash
        self.children = children or []
        self.move_ok = move_ok
        self.path = path
        self.moved_to = None

    def trash(self, cancellable):
        if self.on_trash:
            self.on_trash()

    def enumerate_children(self, attributes, flags, cancellable):
        return self

    def next_file(self, cancellable):
        return self.children.pop(0) if self.children else None

    def get_child(self, name):
        return _FakeGioFile(path=name)

    def move(self, target, flags, *args):
        if not self.move_ok:
            raise OSError("cannot restore")
        self.moved_to = target


class _FakeGioItem:
    def __init__(self, name, orig_path):
        self.name = name
        self.orig_path = orig_path

    def get_name(self):
        return self.name

    def get_attribute_byte_string(self, attr):
        return self.orig_path


def _fake_gio(trashed_file, trash_root):
    class FakeGio:
        class FileCopyFlags:
            NONE = 0

        class FileQueryInfoFlags:
            NONE = 0

        class File:
            @staticmethod
            def new_for_path(path):
                return trashed_file

            @staticmethod
            def new_for_uri(uri):
                return trash_root

    return FakeGio


def test_gio_trash_whose_entry_cannot_be_matched_is_located_through_the_trash_listing(tmp_path: Path):
    """D-2: GIO trashed the file but the .trashinfo Path= does not match; the pair must still be
    recorded (found through GIO's own trash:/// listing) instead of raising with no record."""
    from photo_selector_linux.core import file_ops

    trash = tmp_path / "Trash"
    (trash / "files").mkdir(parents=True)
    (trash / "info").mkdir(parents=True)
    photo = tmp_path / "IMG_7.JPG"
    photo.write_bytes(b"data")

    def gio_moves_file():
        (trash / "files" / "IMG_7.JPG").write_bytes(b"data")
        (trash / "info" / "IMG_7.JPG.trashinfo").write_text("[Trash Info]\nPath=/odd/encoding\n")
        photo.unlink()

    trashed = _FakeGioFile(on_trash=gio_moves_file)
    root = _FakeGioFile(children=[_FakeGioItem("IMG_7.JPG", str(photo))])
    mgr = FileCullingManager(trash_dir=trash)

    with patch.object(FileCullingManager, "_use_gio", return_value=True), \
         patch.object(file_ops, "Gio", _fake_gio(trashed, root), create=True):
        record = mgr.trash_candidate(CandidatePhoto(primary_path=photo))

    assert record.affected_paths == [(photo, (trash / "files" / "IMG_7.JPG").resolve())]
    assert record.trash_info_paths[photo] == (trash / "info" / "IMG_7.JPG.trashinfo").resolve()


def test_gio_trash_that_cannot_be_located_at_all_is_restored_before_raising(tmp_path: Path):
    from photo_selector_linux.core import file_ops

    trash = tmp_path / "Trash"
    photo = tmp_path / "IMG_8.JPG"
    photo.write_bytes(b"data")

    def gio_moves_file():
        photo.unlink()

    trashed = _FakeGioFile(on_trash=gio_moves_file)
    restored_item = _FakeGioFile()
    root = _FakeGioFile(children=[_FakeGioItem("IMG_8.JPG", str(photo))])
    root.get_child = lambda name: restored_item  # type: ignore[method-assign]
    mgr = FileCullingManager(trash_dir=trash)

    with patch.object(FileCullingManager, "_use_gio", return_value=True), \
         patch.object(file_ops, "Gio", _fake_gio(trashed, root), create=True):
        with pytest.raises(FileCullingError) as exc_info:
            mgr.trash_candidate(CandidatePhoto(primary_path=photo))

    assert restored_item.moved_to is not None, "the file was left in the Trash"
    assert "restored" in str(exc_info.value).lower()


def test_gio_trash_that_cannot_be_located_or_restored_tells_the_user_where_the_file_is(tmp_path: Path):
    from photo_selector_linux.core import file_ops

    trash = tmp_path / "Trash"
    photo = tmp_path / "IMG_10.JPG"
    photo.write_bytes(b"data")
    trashed = _FakeGioFile(on_trash=photo.unlink)
    root = _FakeGioFile(children=[])
    mgr = FileCullingManager(trash_dir=trash)

    with patch.object(FileCullingManager, "_use_gio", return_value=True), \
         patch.object(file_ops, "Gio", _fake_gio(trashed, root), create=True):
        with pytest.raises(FileCullingError) as exc_info:
            mgr.trash_candidate(CandidatePhoto(primary_path=photo))

    assert "IMG_10.JPG" in str(exc_info.value)
    assert "Trash" in str(exc_info.value)
# ── B-3: undo of a partial move (D-3) ───────────────────────────────────────────


def _selection_files(card: Path):
    """Files in Selection/ (the folder's hidden hand-over marker is not a photo)."""
    return [p for p in (card / "Selection").iterdir() if not p.name.startswith(".photoselector")]


def _partial_move(tmp_path: Path, names, undeletable):
    """Cross-mount move of ``names`` from a fake card where the sources in ``undeletable`` stay."""
    card = tmp_path / "card"
    card.mkdir()
    for n in names:
        (card / n).write_bytes(n.encode() * 20)
    mgr = FileCullingManager(
        name_resolver=_NoNames(),
        trash_dir=tmp_path / "Trash",
        dev_of=lambda p: 2 if "Selection" in str(p) else 1,
        mount_root_for=lambda p: None,
    )
    real_unlink = Path.unlink

    def failing_unlink(self, *args, **kwargs):
        if self.parent == card and self.name in undeletable:
            raise PermissionError("read-only card")
        return real_unlink(self, *args, **kwargs)

    candidate = CandidatePhoto(
        primary_path=card / names[0], companion_paths=[card / n for n in names[1:]]
    )
    with patch.object(Path, "unlink", failing_unlink):
        with pytest.raises(FileCullingError) as exc_info:
            mgr.move_candidate(candidate, card)
    return mgr, card, candidate, exc_info.value.partial_record


def test_undo_of_a_fully_unremoved_partial_move_deletes_the_copy_and_creates_no_duplicate(tmp_path: Path):
    """D-3: the originals never left, so undo must delete the copies it created instead of
    restoring them next to the originals as IMG_1-2.JPG."""
    mgr, card, candidate, record = _partial_move(tmp_path, ["IMG_1.JPG"], {"IMG_1.JPG"})

    assert mgr.undo_culling(record, candidate) is True

    assert sorted(p.name for p in card.iterdir() if p.is_file()) == ["IMG_1.JPG"]
    assert (card / "IMG_1.JPG").read_bytes() == b"IMG_1.JPG" * 20
    assert _selection_files(card) == []


def test_undo_of_a_mixed_partial_move_restores_removed_members_and_deletes_only_surviving_copies(tmp_path: Path):
    mgr, card, candidate, record = _partial_move(
        tmp_path, ["IMG_2.CR3", "IMG_2.JPG"], {"IMG_2.JPG"}
    )
    assert not (card / "IMG_2.CR3").exists()      # moved
    assert (card / "IMG_2.JPG").exists()          # stayed behind

    assert mgr.undo_culling(record, candidate) is True

    # The member that was really moved is back under its own name; the one that never left
    # keeps its original and loses only the copy this operation made.
    assert (card / "IMG_2.CR3").read_bytes() == b"IMG_2.CR3" * 20
    assert (card / "IMG_2.JPG").read_bytes() == b"IMG_2.JPG" * 20
    assert sorted(p.name for p in card.iterdir() if p.is_file()) == ["IMG_2.CR3", "IMG_2.JPG"]
    assert _selection_files(card) == []


def test_undo_of_a_partial_move_never_deletes_a_copy_whose_original_is_gone(tmp_path: Path):
    """Only copies whose original still exists are deleted; if the original has meanwhile been
    removed by the user, the copy is the only one left and is restored, not deleted."""
    mgr, card, candidate, record = _partial_move(tmp_path, ["IMG_3.JPG"], {"IMG_3.JPG"})
    (card / "IMG_3.JPG").unlink()   # the user deleted the stuck original later

    assert mgr.undo_culling(record, candidate) is True

    assert (card / "IMG_3.JPG").read_bytes() == b"IMG_3.JPG" * 20
    assert _selection_files(card) == []


# ── D-12: a failed rollback is reported, never swallowed ────────────────────────


def _bundle(tmp_path: Path, stem: str):
    raw = tmp_path / f"{stem}.ARW"
    jpg = tmp_path / f"{stem}.JPG"
    raw.write_bytes(b"raw-" + stem.encode())
    jpg.write_bytes(b"jpg-" + stem.encode())
    return raw, jpg, CandidatePhoto(primary_path=raw, companion_paths=[jpg])


def test_same_filesystem_move_whose_rollback_fails_returns_a_partial_record(tmp_path: Path):
    """The companion move fails, then putting the primary back fails too: the primary stays in
    Selection/, and the error must say so and carry a record that undo can restore it from."""
    raw, jpg, candidate = _bundle(tmp_path, "DSC_D12A")
    mgr = FileCullingManager()
    calls = [0]

    def move(src, dst):
        calls[0] += 1
        if calls[0] == 2:
            raise PermissionError("companion refused")
        if calls[0] == 3:
            raise OSError("rollback refused")
        return _move_no_overwrite(src, dst)

    with patch("photo_selector_linux.core.file_ops._move_no_overwrite", side_effect=move):
        with pytest.raises(FileCullingError) as exc_info:
            mgr.move_candidate(candidate, tmp_path)

    err = exc_info.value
    assert "rolled back" not in str(err)
    assert "DSC_D12A.ARW" in str(err) and "could not be put back" in str(err)
    record = err.partial_record
    assert record is not None
    assert record.action_type == CullingActionType.MOVE
    assert record.affected_paths == [(raw, tmp_path / "Selection" / "DSC_D12A.ARW")]
    assert record.stranded == [tmp_path / "Selection" / "DSC_D12A.ARW"]
    assert record.unremoved_sources == []
    assert not raw.exists() and jpg.exists()

    # The transient error has cleared: undo restores the stranded member.
    assert mgr.undo_culling(record, candidate) is True
    assert raw.read_bytes() == b"raw-DSC_D12A"
    assert not (tmp_path / "Selection" / "DSC_D12A.ARW").exists()


def test_same_filesystem_move_with_successful_rollback_still_says_rolled_back(tmp_path: Path):
    raw, jpg, candidate = _bundle(tmp_path, "DSC_D12B")
    calls = [0]

    def move(src, dst):
        calls[0] += 1
        if calls[0] == 2:
            raise PermissionError("companion refused")
        return _move_no_overwrite(src, dst)

    with patch("photo_selector_linux.core.file_ops._move_no_overwrite", side_effect=move):
        with pytest.raises(FileCullingError) as exc_info:
            FileCullingManager().move_candidate(candidate, tmp_path)

    assert exc_info.value.partial_record is None
    assert "rolled back" in str(exc_info.value)
    assert raw.exists() and jpg.exists()


def _cross_mount_manager(tmp_path: Path) -> FileCullingManager:
    return FileCullingManager(
        name_resolver=_NoNames(),
        trash_dir=tmp_path / "Trash",
        dev_of=lambda p: 2 if "Selection" in str(p) else 1,
        mount_root_for=lambda p: None,
    )


def _fail_second_copy_and_every_selection_unlink(tmp_path: Path):
    """Patches: the second `_copy_file` raises, and nothing in Selection/ can be unlinked."""
    real_copy = _copy_file
    real_unlink = Path.unlink
    calls = [0]

    def copy(src, dst, fsync=False):
        calls[0] += 1
        if calls[0] == 2:
            raise OSError("No space left on device")
        return real_copy(src, dst, fsync=fsync)

    def unlink(self, *args, **kwargs):
        if self.parent.name == "Selection":
            raise PermissionError("cleanup refused")
        return real_unlink(self, *args, **kwargs)

    return (
        patch("photo_selector_linux.core.file_ops._copy_file", side_effect=copy),
        patch.object(Path, "unlink", unlink),
    )


def test_cross_mount_staging_whose_cleanup_fails_returns_a_partial_record(tmp_path: Path):
    """The replica staged before the failure cannot be removed: it stays in Selection/ next to an
    untouched original. The error must not claim 'aborted safely'; undo deletes the stray copy."""
    raw, jpg, candidate = _bundle(tmp_path, "DSC_D12C")
    mgr = _cross_mount_manager(tmp_path)
    patch_copy, patch_unlink = _fail_second_copy_and_every_selection_unlink(tmp_path)

    with patch_copy, patch_unlink:
        with pytest.raises(FileCullingError) as exc_info:
            mgr.move_candidate(candidate, tmp_path)

    err = exc_info.value
    assert "aborted safely" not in str(err)
    assert "DSC_D12C.ARW" in str(err) and "could not be removed" in str(err)
    record = err.partial_record
    assert record is not None
    assert record.action_type == CullingActionType.MOVE
    assert record.affected_paths == [(raw, tmp_path / "Selection" / "DSC_D12C.ARW")]
    assert record.stranded == [tmp_path / "Selection" / "DSC_D12C.ARW"]
    assert record.unremoved_sources == [raw]
    assert raw.exists() and jpg.exists()

    assert mgr.undo_culling(record, candidate) is True
    assert raw.read_bytes() == b"raw-DSC_D12C" and jpg.exists()
    assert not (tmp_path / "Selection" / "DSC_D12C.ARW").exists()
    assert "-2" not in " ".join(p.name for p in tmp_path.iterdir())


def test_cross_mount_staging_with_successful_cleanup_still_says_aborted_safely(tmp_path: Path):
    raw, jpg, candidate = _bundle(tmp_path, "DSC_D12D")
    real_copy = _copy_file
    calls = [0]

    def copy(src, dst, fsync=False):
        calls[0] += 1
        if calls[0] == 2:
            raise OSError("No space left on device")
        return real_copy(src, dst, fsync=fsync)

    with patch("photo_selector_linux.core.file_ops._copy_file", side_effect=copy):
        with pytest.raises(FileCullingError) as exc_info:
            _cross_mount_manager(tmp_path).move_candidate(candidate, tmp_path)

    assert exc_info.value.partial_record is None
    assert "aborted safely" in str(exc_info.value)
    assert not (tmp_path / "Selection" / "DSC_D12D.ARW").exists()


def test_copy_whose_cleanup_fails_returns_a_partial_record(tmp_path: Path):
    raw, jpg, candidate = _bundle(tmp_path, "DSC_D12E")
    mgr = FileCullingManager()
    patch_copy, patch_unlink = _fail_second_copy_and_every_selection_unlink(tmp_path)

    with patch_copy, patch_unlink:
        with pytest.raises(FileCullingError) as exc_info:
            mgr.copy_candidate(candidate, tmp_path)

    err = exc_info.value
    assert "rolled back" not in str(err)
    assert "DSC_D12E.ARW" in str(err) and "could not be removed" in str(err)
    record = err.partial_record
    assert record is not None
    assert record.action_type == CullingActionType.COPY
    assert record.affected_paths == [(raw, tmp_path / "Selection" / "DSC_D12E.ARW")]
    assert record.stranded == [tmp_path / "Selection" / "DSC_D12E.ARW"]
    assert raw.exists() and jpg.exists()

    assert mgr.undo_culling(record, candidate) is True
    assert not (tmp_path / "Selection" / "DSC_D12E.ARW").exists()
    assert raw.exists()


def test_copy_with_successful_cleanup_still_says_rolled_back(tmp_path: Path):
    raw, jpg, candidate = _bundle(tmp_path, "DSC_D12F")
    real_copy = _copy_file
    calls = [0]

    def copy(src, dst, fsync=False):
        calls[0] += 1
        if calls[0] == 2:
            raise OSError("No space left on device")
        return real_copy(src, dst, fsync=fsync)

    with patch("photo_selector_linux.core.file_ops._copy_file", side_effect=copy):
        with pytest.raises(FileCullingError) as exc_info:
            FileCullingManager().copy_candidate(candidate, tmp_path)

    assert exc_info.value.partial_record is None
    assert "rolled back" in str(exc_info.value)


def test_copy_never_deletes_a_file_that_appeared_at_the_destination_name(tmp_path: Path):
    """The exclusive create fails because someone else owns the name: that file is not ours to clean up."""
    raw, _jpg, candidate = _bundle(tmp_path, "DSC_D12G")
    (tmp_path / "Selection").mkdir()
    occupant = tmp_path / "Selection" / "DSC_D12G.ARW"
    occupant.write_bytes(b"someone else's photo")
    mgr = FileCullingManager()

    with patch.object(mgr, "_resolve_bundle_destinations", return_value=[(raw, occupant)]):
        with pytest.raises(FileCullingError):
            mgr.copy_candidate(candidate, tmp_path)

    assert occupant.read_bytes() == b"someone else's photo"
    assert raw.exists()


def test_cross_mount_staging_never_deletes_a_file_that_appeared_at_the_destination_name(tmp_path: Path):
    raw, _jpg, candidate = _bundle(tmp_path, "DSC_D12H")
    (tmp_path / "Selection").mkdir()
    occupant = tmp_path / "Selection" / "DSC_D12H.ARW"
    occupant.write_bytes(b"someone else's photo")
    mgr = _cross_mount_manager(tmp_path)

    with patch.object(mgr, "_resolve_bundle_destinations", return_value=[(raw, occupant)]):
        with pytest.raises(FileCullingError):
            mgr.move_candidate(candidate, tmp_path)

    assert occupant.read_bytes() == b"someone else's photo"
    assert raw.exists()


def test_move_undo_names_the_members_that_could_not_be_put_back(tmp_path: Path):
    """OPEN-undo-swallow: a failing rollback re-move is named in the error, not swallowed."""
    members = [tmp_path / n for n in ("DSC_700.ARW", "DSC_700.JPG", "DSC_700.xmp")]
    for m in members:
        m.write_bytes(m.name.encode())
    candidate = CandidatePhoto(primary_path=members[0], companion_paths=members[1:])
    mgr = FileCullingManager()
    record = mgr.move_candidate(candidate, tmp_path)

    orig_move = _move_no_overwrite
    state = {"restores": 0, "failed": False}

    def flaky(src, dst):
        # Selection -> original is a restore; original -> Selection is a rollback re-move.
        if Path(src).parent.name == "Selection":
            state["restores"] += 1
            if state["restores"] == 3:
                raise PermissionError("third restore fails")
        elif not state["failed"] and Path(src).name == "DSC_700.JPG":
            state["failed"] = True
            raise PermissionError("rollback of the 2nd member fails")
        return orig_move(src, dst)

    with patch("photo_selector_linux.core.file_ops._move_no_overwrite", side_effect=flaky):
        with pytest.raises(FileCullingError) as exc:
            mgr.undo_culling(record, candidate)

    assert "DSC_700.JPG" in str(exc.value)
    assert "DSC_700.ARW" not in str(exc.value).split("could not be put back")[1]


def test_trash_undo_can_be_retried_after_the_second_restore_failed(tmp_path: Path):
    """D-TRASH-UNDO: the member restored by the failed attempt counts as restored on retry."""
    trash_dir = tmp_path / "Trash"
    raw = tmp_path / "DSC_800.ARW"
    jpg = tmp_path / "DSC_800.JPG"
    raw.write_bytes(b"raw")
    jpg.write_bytes(b"jpg")
    candidate = CandidatePhoto(primary_path=raw, companion_paths=[jpg])
    mgr = FileCullingManager(trash_dir=trash_dir)
    record = mgr.trash_candidate(candidate)

    orig = mgr._restore_from_trash
    calls = {"n": 0}

    def flaky(*args, **kwargs):
        calls["n"] += 1
        if calls["n"] == 2:
            raise OSError("disk hiccup")
        return orig(*args, **kwargs)

    with patch.object(mgr, "_restore_from_trash", side_effect=flaky):
        with pytest.raises(FileCullingError):
            mgr.undo_culling(record, candidate)
    assert raw.exists() and not jpg.exists()

    assert mgr.undo_culling(record, candidate) is True
    assert raw.read_bytes() == b"raw" and jpg.read_bytes() == b"jpg"
    assert not (tmp_path / "DSC_800-2.ARW").exists()
    assert candidate.status == PhotoStatus.CANDIDATE
