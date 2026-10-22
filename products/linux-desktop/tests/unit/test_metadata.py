"""Unit tests for optical metadata extraction and APEX conversions.

Adheres to:
- R-LINUX-META-02: ExposureMetadata parsing
- R-LINUX-META-03: Zero-Placeholder Invariant
- R-LINUX-META-06: APEX exposure math fallbacks
"""

import math
from pathlib import Path
from unittest.mock import MagicMock, patch
from photo_selector_linux.core.metadata import (
    _safe_float,
    read_metadata,
)
from photo_selector_linux.core.models import ExposureMetadata


def test_safe_float_edge_cases():
    """Verify safe float conversion handles fractions, strings, and guards against zero division."""
    assert _safe_float(4) == 4.0
    assert _safe_float(2.8) == 2.8
    assert _safe_float("1/250") == 0.004
    assert _safe_float("0.5") == 0.5

    # Zero denominator / division by zero
    assert _safe_float("1/0") is None

    # Non-positive or non-finite values
    assert _safe_float(0) is None
    assert _safe_float(-2.5) is None
    assert _safe_float(float("nan")) is None
    assert _safe_float(float("inf")) is None
    assert _safe_float("garbage") is None
    assert _safe_float(None) is None


def test_apex_tv_conversion():
    """Verify APEX Tv conversion: t = 2^(-Tv) when ExposureTime is absent (R-LINUX-META-06)."""
    # For Tv = 8.0: t = 2^(-8) = 1/256 ≈ 0.00390625
    with patch("photo_selector_linux.core.metadata._HAVE_GEXIV2", False), \
         patch("photo_selector_linux.core.metadata.Image.open") as mock_open:

        mock_img = MagicMock()
        mock_exif = MagicMock()
        # 0x9201 is ShutterSpeedValue (Tv)
        mock_exif.items.return_value = [(0x9201, 8.0)]
        mock_exif.get_ifd.return_value = {}
        mock_img.getexif.return_value = mock_exif
        mock_open.return_value.__enter__.return_value = mock_img

        meta = read_metadata(Path("/tmp/test.jpg"))
        assert meta.is_fallback is True
        assert meta.shutter_speed is not None
        assert math.isclose(meta.shutter_speed, 2.0 ** -8.0, rel_tol=1e-4)
        assert meta.formatted_shutter_speed == "1/256s"


def test_apex_av_conversion():
    """Verify APEX Av conversion: f = 2^(Av / 2) when FNumber is absent (R-LINUX-META-06)."""
    # For Av = 2.0: f = 2^(2 / 2) = 2.0 (f/2.0)
    # For Av = 5.0: f = 2^(2.5) ≈ 5.6568 (f/5.7)
    with patch("photo_selector_linux.core.metadata._HAVE_GEXIV2", False), \
         patch("photo_selector_linux.core.metadata.Image.open") as mock_open:

        mock_img = MagicMock()
        mock_exif = MagicMock()
        # 0x9202 is ApertureValue (Av)
        mock_exif.items.return_value = [(0x9202, 5.0)]
        mock_exif.get_ifd.return_value = {}
        mock_img.getexif.return_value = mock_exif
        mock_open.return_value.__enter__.return_value = mock_img

        meta = read_metadata(Path("/tmp/test.jpg"))
        assert meta.is_fallback is True
        assert meta.aperture is not None
        assert math.isclose(meta.aperture, 2.0 ** 2.5, rel_tol=1e-3)
        assert meta.formatted_aperture == "f/5.7"


def test_direct_exif_tags_priority():
    """Verify direct EXIF tags take precedence over APEX tags (is_fallback=False)."""
    with patch("photo_selector_linux.core.metadata._HAVE_GEXIV2", False), \
         patch("photo_selector_linux.core.metadata.Image.open") as mock_open:

        mock_img = MagicMock()
        mock_exif = MagicMock()
        mock_exif.items.return_value = [
            (0x829A, 0.002),   # ExposureTime (1/500s)
            (0x9201, 8.0),     # Tv (1/256s) - should be ignored
            (0x829D, 2.8),     # FNumber (f/2.8)
            (0x9202, 5.0),     # Av (f/5.7) - should be ignored
            (0x8827, 400),     # ISO
            (0x920A, 50.0),    # FocalLength
        ]
        mock_exif.get_ifd.return_value = {}
        mock_img.getexif.return_value = mock_exif
        mock_open.return_value.__enter__.return_value = mock_img

        meta = read_metadata(Path("/tmp/test.jpg"))
        assert meta.is_fallback is False
        assert meta.shutter_speed == 0.002
        assert meta.formatted_shutter_speed == "1/500s"
        assert meta.aperture == 2.8
        assert meta.formatted_aperture == "f/2.8"
        assert meta.iso == 400
        assert meta.formatted_iso == "ISO 400"
        assert meta.focal_length == 50.0
        assert meta.formatted_focal_length == "50mm"
        assert meta.formatted_summary == "1/500s · f/2.8 · ISO 400 · 50mm"


def test_corrupt_exif_graceful_handling(tmp_path: Path):
    """Verify corrupted or truncated metadata fails gracefully without uncaught exceptions."""
    corrupt_file = tmp_path / "corrupt.jpg"
    corrupt_file.write_bytes(b"\xFF\xD8\xFF\xE1" + b"\x00" * 20)  # Invalid JPEG header

    meta = read_metadata(corrupt_file)
    assert isinstance(meta, ExposureMetadata)
    assert meta.shutter_speed is None
    assert meta.aperture is None


class _FakeGExiv2:
    """Dict-backed stand-in for an opened ``GExiv2.Metadata``."""

    def __init__(self, tags, broken=()):
        self.tags, self.broken = tags, set(broken)

    def has_tag(self, tag):
        return tag in self.tags or tag in self.broken

    def get_tag_string(self, tag):
        if tag in self.broken:
            raise RuntimeError("unreadable tag")
        return self.tags[tag]


def test_gexiv2_mapping_reads_cr3_iso_from_recommended_exposure_index():
    """Canon CR3 and Sony fill ISOSpeed/RecommendedExposureIndex, not ISOSpeedRatings."""
    from photo_selector_linux.core.metadata import metadata_from_gexiv2

    meta = metadata_from_gexiv2(_FakeGExiv2({
        "Exif.Photo.ISOSpeedRatings": "65535",  # "too high to fit" sentinel
        "Exif.Photo.RecommendedExposureIndex": "640",
        "Exif.Photo.ExposureTime": "1/60",
        "Exif.Photo.FNumber": "4/1",
        "Exif.Photo.FocalLength": "22/1",
    }))
    assert meta.iso == 640
    assert math.isclose(meta.shutter_speed, 1 / 60)
    assert meta.aperture == 4.0
    assert meta.focal_length == 22.0
    assert meta.is_fallback is False


def test_gexiv2_mapping_trims_padding_and_survives_broken_tags():
    """Pentax and Olympus pad make/model with spaces; unreadable tags are skipped."""
    from photo_selector_linux.core.metadata import metadata_from_gexiv2

    meta = metadata_from_gexiv2(_FakeGExiv2(
        {
            "Exif.Image.Make": "RICOH IMAGING COMPANY, LTD.  ",
            "Exif.Image.Model": "PENTAX K-70        \x00",
            "Exif.Canon.LensModel": "  ",
            "Exif.NikonLd3.LensIDNumber": "Unknown",
            "Exif.Photo.ISOSpeedRatings": "400 400",
        },
        broken={"Exif.Photo.LensModel", "Exif.Photo.ExposureTime"},
    ))
    assert meta.camera_make == "RICOH IMAGING COMPANY, LTD."
    assert meta.camera_model == "PENTAX K-70"
    assert meta.lens is None
    assert meta.iso == 400
    assert meta.shutter_speed is None


def test_gexiv2_mapping_reads_ifd0_exposure_of_tiff_ep_files():
    from photo_selector_linux.core.metadata import metadata_from_gexiv2

    meta = metadata_from_gexiv2(_FakeGExiv2({
        "Exif.Image.ExposureTime": "1/250",
        "Exif.Image.FNumber": "17/10",
        "Exif.Image.FocalLength": "438/100",
        "Exif.Image.ISOSpeedRatings": "55",
    }))
    assert meta.shutter_speed == 0.004
    assert meta.aperture == 1.7
    assert meta.focal_length == 4.38
    assert meta.iso == 55


def test_gexiv2_mapping_apex_fallbacks():
    from photo_selector_linux.core.metadata import metadata_from_gexiv2

    meta = metadata_from_gexiv2(_FakeGExiv2({
        "Exif.Photo.ShutterSpeedValue": "8/1",
        "Exif.Photo.ApertureValue": "5/1",
    }))
    assert meta.is_fallback is True
    assert meta.formatted_shutter_speed == "1/256s"
    assert meta.formatted_aperture == "f/5.7"


def test_pillow_reader_survives_an_ifd_enum_without_the_old_makernote_name(tmp_path):
    """B-LIN-3: newer Pillow renamed IFD.Makernote to IFD.MakerNote; exposure must still be read."""
    import types
    from PIL import Image
    from photo_selector_linux.core import metadata

    img = Image.new("RGB", (4, 4))
    exif = Image.Exif()
    exif.get_ifd(0x8769)  # create the Exif IFD
    exif[0x8769] = {0x829A: 0.01, 0x829D: 2.8, 0x8827: 400}
    path = tmp_path / "x.jpg"
    img.save(path, exif=exif)

    fake_ifd = types.SimpleNamespace(Exif=0x8769, MakerNote=0x927C)  # no ``Makernote`` attribute
    fake_tags = types.SimpleNamespace(IFD=fake_ifd)
    with patch.object(metadata, "ExifTags", fake_tags):
        meta = metadata.read_metadata_pil(path)

    assert meta is not None
    assert meta.iso == 400
    assert meta.aperture == 2.8
    assert meta.shutter_speed == 0.01
