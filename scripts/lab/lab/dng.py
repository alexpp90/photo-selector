"""Write a Linear DNG from a rendered image, so a fixture's RAW companion decodes like a camera's.

A TIFF renamed to `.DNG`, or random bytes named `.CR3`, shows up in every app as a broken photo,
and a usability reviewer then reports the fixture as an app defect. This writes the smallest
file the DNG 1.4 spec and LibRaw accept: 16-bit linear RGB (`PhotometricInterpretation`
34892, LinearRaw) with an identity-sRGB colour matrix, an embedded JPEG preview in a SubIFD
(what Blitzlens and the Android apps show first), and an EXIF IFD with the shot's settings.
"""

from __future__ import annotations

import io
import os
import struct
from datetime import datetime
from pathlib import Path
from typing import Any

# XYZ (D65) -> linear sRGB: the ColorMatrix1 for camera data that already is linear sRGB.
_XYZ_TO_SRGB = (3.2406, -1.5372, -0.4986, -0.9689, 1.8758, 0.0415, 0.0557, -0.2040, 1.0570)

BYTE, ASCII, SHORT, LONG, RATIONAL, SRATIONAL = 1, 2, 3, 4, 5, 10


def _rational(value: tuple[int, int] | float) -> tuple[int, int]:
    return (int(value[0]), int(value[1])) if isinstance(value, tuple) else (int(round(value * 1000)), 1000)


def _ifd(entries: list[tuple[int, int, list[Any]]], at: int) -> bytes:
    """One little-endian IFD at file offset `at`, followed by its out-of-line values.

    Its length does not depend on `at`, so a first pass with `at=0` sizes the layout.
    """
    packers = {BYTE: "B", SHORT: "H", LONG: "I"}
    entries = sorted(entries)
    directory = struct.pack("<H", len(entries))
    values = b""
    values_at = at + 2 + 12 * len(entries) + 4
    for tag, typ, items in entries:
        if typ == ASCII:
            data = str(items[0]).encode("ascii") + b"\0"
            count = len(data)
        elif typ in (RATIONAL, SRATIONAL):
            code = "I" if typ == RATIONAL else "i"
            data = b"".join(struct.pack(f"<{code}{code}", n, d) for n, d in items)
            count = len(items)
        else:
            data = struct.pack(f"<{len(items)}{packers[typ]}", *items)
            count = len(items)
        if len(data) <= 4:
            field = data.ljust(4, b"\0")
        else:
            if len(values) % 2:
                values += b"\0"  # TIFF values start on a word boundary
            field = struct.pack("<I", values_at + len(values))
            values += data
        directory += struct.pack("<HHI", tag, typ, count) + field
    block = directory + struct.pack("<I", 0) + values
    return block + (b"\0" if len(block) % 2 else b"")


def write_linear_dng(
    path: Path,
    img: Any,
    dt: datetime,
    *,
    iso: int,
    exposure_time: tuple[int, int] | float,
    f_number: tuple[int, int] | float,
    focal_length: tuple[int, int] | float,
    focal_length_35mm: int | None,
    lens_model: str,
    make: str = "PSTLab",
    model: str = "Fixture",
    preview_px: int = 1024,
) -> None:
    """Write `img` (a Pillow image) as a Linear DNG at `path`, dated `dt` (file time and EXIF)."""
    rgb = img.convert("RGB")
    w, h = rgb.size
    # sRGB-encoded 8-bit -> linear 16-bit.
    lut = [
        round(65535 * (((v / 255 + 0.055) / 1.055) ** 2.4 if v / 255 > 0.04045 else v / 255 / 12.92))
        for v in range(256)
    ]
    r_band, g_band, b_band = (bytes(band.tobytes()) for band in rgb.split())
    raw = bytearray(w * h * 6)
    for i, (r, g, b) in enumerate(zip(r_band, g_band, b_band)):
        struct.pack_into("<HHH", raw, i * 6, lut[r], lut[g], lut[b])
    preview = rgb.copy()
    preview.thumbnail((preview_px, preview_px))
    buf = io.BytesIO()
    preview.save(buf, format="JPEG", quality=85)
    jpeg = buf.getvalue()

    stamp = dt.strftime("%Y:%m:%d %H:%M:%S")

    def tables(raw_at: int, preview_at: int, exif_at: int, jpeg_at: int) -> list[tuple[int, list[Any]]]:
        main = [
            (254, LONG, [0]), (256, LONG, [w]), (257, LONG, [h]), (258, SHORT, [16, 16, 16]),
            (259, SHORT, [1]), (262, SHORT, [34892]), (271, ASCII, [make]), (272, ASCII, [model]),
            (273, LONG, [raw_at]), (274, SHORT, [1]), (277, SHORT, [3]), (278, LONG, [h]),
            (279, LONG, [len(raw)]), (284, SHORT, [1]), (306, ASCII, [stamp]), (330, LONG, [preview_at]),
            (34665, LONG, [exif_at]), (50706, BYTE, [1, 4, 0, 0]), (50707, BYTE, [1, 1, 0, 0]),
            (50708, ASCII, [f"{make} {model}"]), (50717, LONG, [65535]),
            (50721, SRATIONAL, [(round(c * 10000), 10000) for c in _XYZ_TO_SRGB]),
            (50728, RATIONAL, [(1, 1), (1, 1), (1, 1)]), (50778, SHORT, [21]),
        ]
        thumb = [
            (254, LONG, [1]), (256, LONG, [preview.width]), (257, LONG, [preview.height]),
            (258, SHORT, [8, 8, 8]), (259, SHORT, [7]), (262, SHORT, [6]), (273, LONG, [jpeg_at]),
            (277, SHORT, [3]), (278, LONG, [preview.height]), (279, LONG, [len(jpeg)]), (284, SHORT, [1]),
        ]
        exif = [
            (33434, RATIONAL, [_rational(exposure_time)]), (33437, RATIONAL, [_rational(f_number)]),
            (34855, SHORT, [int(iso)]), (36867, ASCII, [stamp]), (36868, ASCII, [stamp]),
            (37386, RATIONAL, [_rational(focal_length)]), (42036, ASCII, [lens_model]),
        ]
        if focal_length_35mm is not None:
            exif.append((41989, SHORT, [int(focal_length_35mm)]))
        return [(8, main), (preview_at, thumb), (exif_at, exif)]

    # Pass 1 sizes the three IFDs; pass 2 writes them with the real offsets.
    sizes = [len(_ifd(entries, 0)) for _, entries in tables(0, 0, 0, 0)]
    preview_at = 8 + sizes[0]
    exif_at = preview_at + sizes[1]
    jpeg_at = exif_at + sizes[2]
    raw_at = jpeg_at + len(jpeg) + (len(jpeg) % 2)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as f:
        f.write(b"II*\0" + struct.pack("<I", 8))
        for at, entries in tables(raw_at, preview_at, exif_at, jpeg_at):
            if f.tell() != at:
                raise AssertionError(f"DNG layout: IFD expected at {at}, writing at {f.tell()}")
            f.write(_ifd(entries, at))
        f.write(jpeg + (b"\0" if len(jpeg) % 2 else b""))
        f.write(raw)
    ts = dt.timestamp()
    os.utime(path, (ts, ts))
