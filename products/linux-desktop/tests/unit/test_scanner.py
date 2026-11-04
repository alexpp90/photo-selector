"""Unit tests for DirectoryScanner, companion binding, and dynamic exclusion.

Adheres to:
- R-LINUX-META-01: Supported file formats
- R-LINUX-META-04: Companion RAW+JPEG, sidecar & edit binding
- R-LINUX-META-05: Dynamic Selection Subfolder Exclusion Invariant
- R-LINUX-CULL-01: Progressive asynchronous streaming ingestion
- R-LINUX-CULL-02: Natural alphanumeric sorting
"""

import json
from pathlib import Path
import pytest

from photo_selector_linux.core.scanner import (
    DEFAULT_EXCLUDED_FOLDERS,
    DirectoryScanner,
    SUPPORTED_EXTENSIONS,
    natural_sort_key,
)


def test_supported_extensions():
    """Bitmaps plus required and recognized RAW formats (R-LINUX-META-01).

    The authoritative list is testdata/formats/manifest.json; test_format_conformance.py checks
    these sets against it.
    """
    expected = {
        "cr2", "cr3", "nef", "nrw", "arw", "dng", "raf", "rw2", "orf", "pef", "srw",
        "3fr", "fff", "iiq", "rwl", "raw", "sr2", "srf", "mrw", "x3f", "erf", "mef", "mos",
        "jpg", "jpeg", "png", "heic", "heif", "tif", "tiff", "webp",
    }
    assert expected == SUPPORTED_EXTENSIONS


def test_companion_raw_jpeg_pairing(tmp_path: Path):
    """Verify RAW+JPEG pair designates RAW as primary and JPEG as companion (R-LINUX-META-04)."""
    (tmp_path / "DSC0001.ARW").write_bytes(b"raw-bytes")
    (tmp_path / "DSC0001.JPG").write_bytes(b"jpeg-bytes")

    scanner = DirectoryScanner()
    candidates = scanner.scan_directory(tmp_path)

    assert len(candidates) == 1
    assert candidates[0].primary_path.name == "DSC0001.ARW"
    assert len(candidates[0].companion_paths) == 1
    assert candidates[0].companion_paths[0].name == "DSC0001.JPG"


def test_companion_xmp_sidecar_binding(tmp_path: Path):
    """Verify binding of both <file>.xmp and <stem>.xmp sidecars (R-LINUX-META-04)."""
    (tmp_path / "IMG_0001.CR3").write_bytes(b"raw")
    (tmp_path / "IMG_0001.CR3.xmp").write_bytes(b"xmp-1")
    (tmp_path / "IMG_0002.NEF").write_bytes(b"raw-2")
    (tmp_path / "IMG_0002.xmp").write_bytes(b"xmp-2")

    scanner = DirectoryScanner()
    candidates = scanner.scan_directory(tmp_path)

    assert len(candidates) == 2

    c1 = next(c for c in candidates if c.primary_path.name == "IMG_0001.CR3")
    assert any(p.name == "IMG_0001.CR3.xmp" for p in c1.companion_paths)

    c2 = next(c for c in candidates if c.primary_path.name == "IMG_0002.NEF")
    assert any(p.name == "IMG_0002.xmp" for p in c2.companion_paths)


def test_companion_lightroom_edit_binding(tmp_path: Path):
    """Verify binding of Lightroom -Edit, _Edit, and -Enhanced-NR derivatives (R-LINUX-META-04)."""
    (tmp_path / "DSC0050.ARW").write_bytes(b"base")
    (tmp_path / "DSC0050-Edit.tif").write_bytes(b"edit-1")
    (tmp_path / "DSC0050_Edit.jpg").write_bytes(b"edit-2")
    (tmp_path / "DSC0050-Enhanced-NR.dng").write_bytes(b"nr")

    scanner = DirectoryScanner()
    candidates = scanner.scan_directory(tmp_path)

    assert len(candidates) == 1
    names = {p.name for p in candidates[0].all_paths}
    assert names == {
        "DSC0050.ARW",
        "DSC0050-Edit.tif",
        "DSC0050_Edit.jpg",
        "DSC0050-Enhanced-NR.dng",
    }


def test_claimed_companion_deduplication(tmp_path: Path):
    """Verify companion files are not enumerated as duplicate primary candidates."""
    (tmp_path / "PHOTO.RAW").write_bytes(b"raw")
    (tmp_path / "PHOTO.JPG").write_bytes(b"jpeg")

    scanner = DirectoryScanner()
    candidates = scanner.scan_directory(tmp_path)

    assert len(candidates) == 1
    assert candidates[0].primary_path.name == "PHOTO.RAW"


def test_dynamic_selection_exclusion_defaults(tmp_path: Path):
    """Verify exclusion of default selection subdirectories (R-LINUX-META-05)."""
    (tmp_path / "root_photo.jpg").write_bytes(b"ok")

    for folder in ("Selection", "Selected", "PhotoTok_Selection", "PhotoTok_LeftSwipe"):
        sub = tmp_path / folder
        sub.mkdir()
        (sub / "culled.jpg").write_bytes(b"culled")

    scanner = DirectoryScanner()
    candidates = scanner.scan_directory(tmp_path)

    assert len(candidates) == 1
    assert candidates[0].primary_path.name == "root_photo.jpg"


def test_dynamic_selection_exclusion_case_insensitivity(tmp_path: Path):
    """Verify case-insensitive exclusion of selection directories (R-LINUX-META-05)."""
    (tmp_path / "keep.jpg").write_bytes(b"keep")
    sub1 = tmp_path / "SELECTION"
    sub1.mkdir(exist_ok=True)
    (sub1 / "ignore1.jpg").write_bytes(b"ignore")

    scanner = DirectoryScanner()
    candidates = scanner.scan_directory(tmp_path)

    assert len(candidates) == 1
    assert candidates[0].primary_path.name == "keep.jpg"
    assert scanner.is_path_excluded(Path("selection"), Path(".")) is True
    assert scanner.is_path_excluded(Path("SELECTION"), Path(".")) is True
    assert scanner.is_path_excluded(Path("Selection"), Path(".")) is True
    assert scanner.is_path_excluded(Path("sElEcTiOn"), Path(".")) is True


def test_dynamic_selection_exclusion_custom_folder(tmp_path: Path):
    """Verify exclusion of user-configured custom selection directory (R-LINUX-META-05)."""
    (tmp_path / "img1.jpg").write_bytes(b"ok")
    custom = tmp_path / "MyCustomPicks"
    custom.mkdir()
    (custom / "picked.jpg").write_bytes(b"ignore")

    scanner = DirectoryScanner(custom_selection_folder="MyCustomPicks")
    candidates = scanner.scan_directory(tmp_path)

    assert len(candidates) == 1
    assert candidates[0].primary_path.name == "img1.jpg"


def test_dynamic_selection_exclusion_exact_component_matching(tmp_path: Path):
    """Verify substrings like Trip_Selection_Final/ are NOT false-positively excluded (R-LINUX-META-05)."""
    sub = tmp_path / "Trip_Selection_Final"
    sub.mkdir()
    (sub / "trip_photo.jpg").write_bytes(b"keep")

    scanner = DirectoryScanner()
    candidates = scanner.scan_directory(tmp_path)

    assert len(candidates) == 1
    assert candidates[0].primary_path.name == "trip_photo.jpg"


def test_direct_scan_of_selection_root(tmp_path: Path):
    """Verify root scan exception: explicitly opening Selection folder scans its contents (R-LINUX-META-05)."""
    sel_dir = tmp_path / "Selection"
    sel_dir.mkdir()
    (sel_dir / "review_me.jpg").write_bytes(b"review")

    scanner = DirectoryScanner()
    candidates = scanner.scan_directory(sel_dir)

    assert len(candidates) == 1
    assert candidates[0].primary_path.name == "review_me.jpg"


def test_progressive_streaming_batch_sizes(tmp_path: Path):
    """Verify scan_stream yields progressive batches adhering to batch_size (R-LINUX-CULL-01)."""
    for i in range(25):
        (tmp_path / f"DSC_{i:04d}.JPG").write_bytes(b"data")

    scanner = DirectoryScanner()
    batches = list(scanner.scan_stream(tmp_path, batch_size=10))

    assert len(batches) == 3
    assert len(batches[0]) == 10
    assert len(batches[1]) == 10
    assert len(batches[2]) == 5


def test_natural_alphanumeric_sorting():
    """Verify natural sorting places DSC_0002 before DSC_0010 (R-LINUX-CULL-02)."""
    names = ["DSC_0010.JPG", "DSC_0002.JPG", "DSC_0001.JPG", "DSC_0100.JPG"]
    sorted_names = sorted(names, key=natural_sort_key)
    assert sorted_names == ["DSC_0001.JPG", "DSC_0002.JPG", "DSC_0010.JPG", "DSC_0100.JPG"]


@pytest.mark.parametrize("custom_folder_input, expected_excluded_name", [
    ("  MyPicks/  ", "mypicks"),
    ("MyPicks/", "mypicks"),
    ("  MyPicks  ", "mypicks"),
    ("/var/media/Picks/", "picks"),
    ("sub/folder/Picks/  ", "picks"),
])
def test_dynamic_selection_exclusion_custom_folder_whitespace_and_slashes(
    tmp_path: Path,
    custom_folder_input: str,
    expected_excluded_name: str,
):
    """Verify custom folder name is robustly sanitized against whitespace and trailing slashes (R-LINUX-META-05)."""
    (tmp_path / "keep_photo.jpg").write_bytes(b"keep")
    sub = tmp_path / "MyPicks" if "mypicks" in expected_excluded_name else tmp_path / "Picks"
    sub.mkdir(parents=True, exist_ok=True)
    (sub / "ignore_photo.jpg").write_bytes(b"ignore")

    scanner = DirectoryScanner(custom_selection_folder=custom_folder_input)
    assert expected_excluded_name in scanner.excluded_names

    candidates = scanner.scan_directory(tmp_path)
    assert len(candidates) == 1
    assert candidates[0].primary_path.name == "keep_photo.jpg"


@pytest.mark.parametrize("empty_input", ["", "   ", "/", "///", "  /  "])
def test_dynamic_selection_exclusion_custom_folder_empty_or_slashes_only(empty_input: str):
    """Verify empty or slash-only custom selection folder input does not pollute exclusion list."""
    scanner = DirectoryScanner(custom_selection_folder=empty_input)
    assert "" not in scanner.excluded_names
    assert "/" not in scanner.excluded_names
    assert len(scanner.excluded_names) == len(DEFAULT_EXCLUDED_FOLDERS)


def test_candidate_file_size_prepopulated_during_scan(tmp_path: Path):
    """Verify CandidatePhoto._file_size is pre-populated during scan and survives file removal."""
    test_file = tmp_path / "img1.jpg"
    test_file.write_bytes(b"1234567890")
    scanner = DirectoryScanner()
    candidates = scanner.scan_directory(tmp_path)
    assert len(candidates) == 1
    assert candidates[0]._file_size == 10
    assert candidates[0].file_size == 10

    # Simulate move/removal of primary file
    test_file.unlink()
    # file_size property should still return cached 10 bytes because _file_size was pre-populated
    assert candidates[0].file_size == 10


# ── Shared companion-grouping table (testdata/formats/companions.json, finding D-6) ─────────────


def _companion_cases():
    here = Path(__file__).resolve()
    for parent in here.parents:
        table = parent / "testdata" / "formats" / "companions.json"
        if table.is_file():
            return json.loads(table.read_text())["cases"]
    raise AssertionError("testdata/formats/companions.json not found")


@pytest.mark.parametrize("case", _companion_cases(), ids=lambda c: c["name"])
def test_companion_grouping_follows_the_shared_table(tmp_path: Path, case):
    for name in case["files"]:
        (tmp_path / name).write_bytes(b"x")

    candidates = DirectoryScanner().scan_directory(tmp_path, recursive=False)
    groups = sorted(sorted(p.name.lower() for p in c.all_paths) for c in candidates)
    expected = sorted(sorted(n.lower() for n in bundle) for bundle in case["bundles"])

    assert groups == expected


# ── File-specific sidecars follow every member of the shot, not only the first in name order ─────
# (BL-D-1). The shared table above uses CR3, which sorts before JPG, so it cannot see this.
# Candidate additions to testdata/formats/companions.json once the other products pass them.
SIDECAR_ORDER_CASES = [
    {
        "name": "NEF sorts after JPG: its darktable sidecar still travels",
        "files": ["DSC_0001.NEF", "DSC_0001.JPG", "DSC_0001.NEF.xmp"],
        "bundles": [["DSC_0001.NEF", "DSC_0001.JPG", "DSC_0001.NEF.xmp"]],
    },
    {
        "name": "RAF with a JPG, both sidecars",
        "files": ["DSCF0002.RAF", "DSCF0002.JPG", "DSCF0002.RAF.xmp", "DSCF0002.JPG.xmp"],
        "bundles": [["DSCF0002.RAF", "DSCF0002.JPG", "DSCF0002.RAF.xmp", "DSCF0002.JPG.xmp"]],
    },
    {
        "name": "the JPG's own sidecar next to an ARW",
        "files": ["IMG_3.ARW", "IMG_3.JPG", "IMG_3.JPG.xmp"],
        "bundles": [["IMG_3.ARW", "IMG_3.JPG", "IMG_3.JPG.xmp"]],
    },
    {
        "name": "an edit derivative's sidecar follows the shot",
        "files": ["IMG_4.NEF", "IMG_4-Edit.tif", "IMG_4-Edit.tif.xmp", "IMG_5.NEF", "IMG_5.NEF.xmp"],
        "bundles": [["IMG_4.NEF", "IMG_4-Edit.tif", "IMG_4-Edit.tif.xmp"], ["IMG_5.NEF", "IMG_5.NEF.xmp"]],
    },
]


@pytest.mark.parametrize("case", SIDECAR_ORDER_CASES, ids=lambda c: c["name"])
def test_file_specific_sidecar_follows_whichever_member_carries_it(tmp_path: Path, case):
    for name in case["files"]:
        (tmp_path / name).write_bytes(b"x")

    candidates = DirectoryScanner().scan_directory(tmp_path, recursive=False)
    groups = sorted(sorted(p.name.lower() for p in c.all_paths) for c in candidates)
    expected = sorted(sorted(n.lower() for n in bundle) for bundle in case["bundles"])

    assert groups == expected
