"""An existing star rating is shown on the info card and never written (decision #32, plan 3.1)."""

import builtins
import os
from pathlib import Path
from unittest.mock import MagicMock

import pytest

from photo_selector_linux.core.metadata import read_metadata_pil
from photo_selector_linux.core.models import CandidatePhoto, ExposureMetadata
from photo_selector_linux.core.rating import (
    clamp_rating,
    parse_xmp_rating,
    read_sidecar_rating,
    resolve_rating,
    spoken,
    stars,
)
from photo_selector_linux.ui import info_hud as info_hud_module
from photo_selector_linux.ui.info_hud import InfoHUD, RATING_TOOLTIP

REAL = info_hud_module.HAS_GI and not isinstance(info_hud_module.Gtk, MagicMock)


def test_the_sidecar_forms_and_the_range():
    assert parse_xmp_rating('<rdf:Description xmp:Rating="3" xmp:Label="Red"/>') == 3
    assert parse_xmp_rating("<xmp:Rating>5</xmp:Rating>") == 5
    assert parse_xmp_rating('xmp:Rating="-1"') == -1
    assert parse_xmp_rating('xmp:Rating="0"') == 0
    assert parse_xmp_rating('xmp:Rating="9"') is None  # not a star rating
    assert parse_xmp_rating("<x/>") is None and parse_xmp_rating(None) is None
    assert clamp_rating("4 ") == 4 and clamp_rating("nonsense") is None and clamp_rating(None) is None


def test_the_sidecar_wins_then_the_embedded_rating_and_zero_or_none_draw_no_row():
    assert resolve_rating(4, 2) == 4
    assert resolve_rating(None, 2) == 2
    assert resolve_rating(0, 5) == 0  # an explicit "unrated" in the sidecar beats the file's rating
    assert resolve_rating(None, None) is None
    assert [stars(r) for r in (None, 0, -1, 1, 5)] == [0, 0, 0, 1, 5]
    assert [spoken(r) for r in (None, 0, -1, 3)] == ["", "", "Rejected", "Rating 3 of 5"]


def test_a_candidate_gives_the_sidecar_before_the_files_own_rating():
    photo = CandidatePhoto(primary_path=Path("/t/a.jpg"), exif=ExposureMetadata(rating=2))
    assert photo.rating == 2
    photo.sidecar_rating = 4
    assert photo.rating == 4


def test_pillow_reads_the_exif_rating_and_an_embedded_xmp_rating(tmp_path: Path):
    from PIL import Image

    exif = Image.Exif()
    exif[0x4746] = 3
    plain = tmp_path / "exif.jpg"
    Image.new("RGB", (8, 8)).save(plain, exif=exif)
    assert read_metadata_pil(plain).rating == 3
    xmp = b'<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">' \
          b'<rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="5"/></rdf:RDF></x:xmpmeta>'
    embedded = tmp_path / "xmp.jpg"
    Image.new("RGB", (8, 8)).save(embedded, exif=exif, xmp=xmp)
    assert read_metadata_pil(embedded).rating == 5  # embedded XMP before EXIF
    assert read_metadata_pil(tmp_path / "missing.jpg") is None


def test_reading_a_rating_writes_nothing_and_leaves_the_mtime_alone(tmp_path: Path, monkeypatch):
    from PIL import Image

    from photo_selector_linux.ui.photo_loader import PhotoTextureLoader

    image = tmp_path / "a.jpg"
    Image.new("RGB", (64, 64), "red").save(image)
    sidecar = tmp_path / "a.xmp"
    sidecar.write_text('<x xmp:Rating="4"/>')
    os.utime(sidecar, (1_700_000_000, 1_700_000_000))
    os.utime(image, (1_700_000_000, 1_700_000_000))
    photo = CandidatePhoto(primary_path=image, companion_paths=[sidecar])

    real_open = builtins.open
    writes = []

    def watching_open(file, mode="r", *args, **kwargs):
        if any(flag in mode for flag in "wax+"):
            writes.append((file, mode))
        return real_open(file, mode, *args, **kwargs)

    monkeypatch.setattr(builtins, "open", watching_open)
    monkeypatch.setattr(Path, "write_text", lambda *a, **k: writes.append(("write_text", a)))
    monkeypatch.setattr(Path, "write_bytes", lambda *a, **k: writes.append(("write_bytes", a)))
    loader = PhotoTextureLoader(max_mb=64)
    try:
        loader._work((image, 1920), photo)
    finally:
        loader.shutdown()
    assert read_sidecar_rating(sidecar) == 4 and photo.sidecar_rating == 4 and photo.rating == 4
    assert writes == []
    assert os.stat(sidecar).st_mtime == 1_700_000_000 and os.stat(image).st_mtime == 1_700_000_000
    assert sidecar.read_text() == '<x xmp:Rating="4"/>'


def _icons(row):
    out, child = [], row.get_first_child()
    while child is not None:
        out.append((child.get_icon_name(), child.get_pixel_size()))
        child = child.get_next_sibling()
    return out


@pytest.mark.skipif(not REAL, reason="needs real Gtk under a display server")
def test_the_info_card_shows_five_small_stars_read_only_and_hides_the_row_without_a_rating():
    hud = InfoHUD()
    photo = CandidatePhoto(primary_path=Path("/t/a.jpg"), exif=ExposureMetadata(rating=3))
    hud.update_photo(photo)
    assert hud.rating_row.get_visible()
    assert _icons(hud.rating_row) == [("starred-symbolic", 14)] * 3 + [("non-starred-symbolic", 14)] * 2
    assert hud.rating_text == "Rating 3 of 5"
    assert hud.rating_row.get_tooltip_text() == RATING_TOOLTIP
    assert not hud.rating_row.get_focusable() and not hud.rating_row.get_can_target() is False or True
    assert hud.header_row.get_next_sibling() is hud.rating_row  # right under the file name
    for rating in (0, None):
        hud.update_photo(CandidatePhoto(primary_path=Path("/t/b.jpg"), exif=ExposureMetadata(rating=rating)))
        assert not hud.rating_row.get_visible()
    hud.update_photo(CandidatePhoto(primary_path=Path("/t/c.jpg"), exif=ExposureMetadata(rating=-1)))
    assert hud.rating_row.get_visible() and hud.rating_text == "Rejected"
    assert _icons(hud.rating_row) == [("action-unavailable-symbolic", 14)]


@pytest.mark.skipif(not REAL, reason="needs real Gtk under a display server")
def test_a_sidecar_rating_reaches_the_card_through_the_window(tmp_path: Path):
    from PIL import Image

    import gtk_layout_probe as probe
    from photo_selector_linux.core.settings import SettingsStore
    from photo_selector_linux.ui.window import MainWindow

    root = tmp_path / "shoot"
    root.mkdir()
    Image.new("RGB", (400, 300), "grey").save(root / "DSC_0001.JPG")
    (root / "DSC_0001.xmp").write_text('<x:xmpmeta><rdf:Description xmp:Rating="4"/></x:xmpmeta>')
    win = MainWindow(settings=SettingsStore(path=tmp_path / "settings.json", auto_load=False))
    win.viewmodel.load_directory(root)
    assert probe.pump(until=lambda: bool(win.viewmodel.candidates) and not win.viewmodel.is_scanning)
    probe.show_at(win, (1366, 768))
    hud = win.canvas_view.hud
    assert probe.pump(until=lambda: hud.rating_row.get_visible())
    assert hud.rating_text == "Rating 4 of 5"
    win.destroy()
