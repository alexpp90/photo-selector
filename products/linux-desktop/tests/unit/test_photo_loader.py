"""Unit tests for scoring decoded photos in the texture loader.

Adheres to:
- R-LINUX-SCORE-01: Vectorized Laplacian Focus Convolution (scored on a 1080p-class proxy)
- R-LINUX-SCORE-05: Metric Omission Rule (scores appear once computed)
"""

from __future__ import annotations

import io
from pathlib import Path

import numpy as np
import pytest
from PIL import Image, ImageDraw

from photo_selector_linux.core.models import CandidatePhoto
from photo_selector_linux.ui import photo_loader

needs_gi = pytest.mark.skipif(not photo_loader.HAS_GI, reason="GdkPixbuf typelib not available")


def _pixbuf(rgb: np.ndarray, has_alpha: bool = False):
    """A pixbuf with rowstride padding, as GdkPixbuf allocates for odd widths."""
    GdkPixbuf = photo_loader.GdkPixbuf
    h, w, _ = rgb.shape
    pixbuf = GdkPixbuf.Pixbuf.new(GdkPixbuf.Colorspace.RGB, has_alpha, 8, w, h)
    pixbuf.fill(0)
    channels, stride = pixbuf.get_n_channels(), pixbuf.get_rowstride()
    buf = bytearray(stride * h)
    for y in range(h):
        row = np.zeros((w, channels), dtype=np.uint8)
        row[:, :3] = rgb[y]
        buf[y * stride:y * stride + w * channels] = row.tobytes()
    return GdkPixbuf.Pixbuf.new_from_bytes(
        photo_loader.GLib.Bytes.new(bytes(buf)), GdkPixbuf.Colorspace.RGB, has_alpha, 8, w, h, stride
    )


@needs_gi
@pytest.mark.parametrize("has_alpha", [False, True])
def test_pixbuf_to_rgb_array_drops_rowstride_padding_and_alpha(has_alpha):
    rng = np.random.default_rng(7)
    rgb = rng.integers(0, 256, (5, 7, 3), dtype=np.uint8)  # odd width → padded rows
    assert np.array_equal(photo_loader.pixbuf_to_rgb_array(_pixbuf(rgb, has_alpha)), rgb)


@needs_gi
def test_sharp_photo_scores_higher_than_its_blurred_copy():
    img = Image.new("RGB", (2400, 1600), "white")
    draw = ImageDraw.Draw(img)
    for x in range(0, 2400, 12):
        draw.line([(x, 0), (x, 1600)], fill="black", width=3)
    sharp = photo_loader.score_pixbuf(_pixbuf(np.asarray(img)))
    from PIL import ImageFilter
    soft = photo_loader.score_pixbuf(_pixbuf(np.asarray(img.filter(ImageFilter.GaussianBlur(6)))))
    assert sharp is not None and soft is not None
    assert sharp.sharpness > soft.sharpness


@needs_gi
def test_decode_worker_fills_in_scores_and_exif(tmp_path: Path, monkeypatch):
    """Regression: nothing called the scoring engine, so score badges and the HUD meter never showed."""
    photo = tmp_path / "DSC0001.JPG"
    buf = io.BytesIO()
    Image.effect_noise((640, 480), 64).convert("RGB").save(buf, "JPEG")
    photo.write_bytes(buf.getvalue())
    candidate = CandidatePhoto(primary_path=photo)
    delivered = []
    monkeypatch.setattr(photo_loader.GLib, "idle_add", lambda fn, *args, **kw: delivered.append(args))
    loader = photo_loader.PhotoTextureLoader(workers=1)

    loader._work((photo, photo_loader.FIT_MAX_EDGE), candidate)
    loader.shutdown()

    assert candidate.scores is not None
    assert 0.0 <= candidate.scores.sharpness <= 100.0
    assert candidate.exif is not None
    assert delivered and delivered[0][1] is not None  # the decoded pixbuf
