"""Optical EXIF metadata reader utilizing GExiv2 with Pillow fallback.

Adheres to:
- R-LINUX-META-02: Standardized ExposureMetadata Data Model
- R-LINUX-META-03: Formatted Optical Exposure Strip & Zero-Placeholder Invariant
- R-LINUX-META-06: APEX Exposure Math Fallbacks
"""

from __future__ import annotations

import io
import math
from pathlib import Path
from typing import Any, Iterable, Optional

from photo_selector_linux.core.models import ExposureMetadata

_HAVE_GEXIV2 = False
try:
    import gi
    # Debian 13 ships gexiv2 0.14 as namespace 0.10; gexiv2 >= 0.15 exposes namespace 0.16.
    for _gexiv2_ns in ("0.10", "0.16"):
        try:
            gi.require_version("GExiv2", _gexiv2_ns)
            break
        except ValueError:
            continue
    else:
        raise ImportError("no GExiv2 typelib")
    from gi.repository import GExiv2, GLib
    # Must run once before any read: with exiv2 0.27.x it also enables ISO-BMFF parsing, without
    # which CR3 and HEIC files have no metadata (exiv2 >= 0.28 enables BMFF unconditionally).
    GExiv2.initialize()
    _HAVE_GEXIV2 = True
except Exception:
    _HAVE_GEXIV2 = False

_HAVE_PIL = False
try:
    from PIL import Image, ExifTags
    _HAVE_PIL = True
except Exception:
    _HAVE_PIL = False


def _safe_float(val: Any) -> Optional[float]:
    """Safely cast numeric value or fraction to float, rejecting non-finite/negative values."""
    if val is None:
        return None
    try:
        if isinstance(val, (int, float)):
            f = float(val)
        elif hasattr(val, "numerator") and hasattr(val, "denominator"):
            denom = float(val.denominator)
            if denom == 0.0:
                return None
            f = float(val.numerator) / denom
        elif isinstance(val, str):
            val_s = val.strip()
            if "/" in val_s:
                num_s, den_s = val_s.split("/", 1)
                den = float(den_s)
                if den == 0.0:
                    return None
                f = float(num_s) / den
            else:
                f = float(val_s)
        else:
            return None

        if not math.isfinite(f) or f <= 0.0:
            return None
        return f
    except (ValueError, TypeError, ZeroDivisionError):
        return None


# ISO lives in different tags per camera generation: classic ISOSpeedRatings (exiv2 >= 0.28 calls
# it PhotographicSensitivity), while Canon CR3 and Sony bodies only fill ISOSpeed /
# RecommendedExposureIndex. Phone DNGs may carry it in IFD0 (TIFF/EP).
_ISO_TAGS = (
    "Exif.Photo.ISOSpeedRatings",
    "Exif.Photo.PhotographicSensitivity",
    "Exif.Photo.ISOSpeed",
    "Exif.Photo.RecommendedExposureIndex",
    "Exif.Image.ISOSpeedRatings",
)
_EXPOSURE_TIME_TAGS = ("Exif.Photo.ExposureTime", "Exif.Image.ExposureTime")
_FNUMBER_TAGS = ("Exif.Photo.FNumber", "Exif.Image.FNumber")
_FOCAL_LENGTH_TAGS = ("Exif.Photo.FocalLength", "Exif.Image.FocalLength")
_LENS_TAGS = ("Exif.Photo.LensModel", "Exif.Canon.LensModel", "Exif.NikonLd3.LensIDNumber")


def _tag_string(meta: Any, tag: str) -> Optional[str]:
    """Raw tag value as a trimmed string, or None (missing, unreadable or blank).

    Uses only ``has_tag``/``get_tag_string``, which behave the same in every gexiv2 release;
    the typed getters (get_exposure_time, ...) return different Python types across releases.
    """
    try:
        if not meta.has_tag(tag):
            return None
        val = meta.get_tag_string(tag)
    except Exception:
        return None
    if val is None:
        return None
    val = str(val).replace("\x00", "").strip()
    return val or None


def _first_float(meta: Any, tags: Iterable[str]) -> Optional[float]:
    for tag in tags:
        f = _safe_float(_tag_string(meta, tag))
        if f is not None:
            return f
    return None


def _iso_from(meta: Any) -> Optional[int]:
    for tag in _ISO_TAGS:
        raw = _tag_string(meta, tag)
        if raw is None:
            continue
        try:
            # Multi-valued tags read as "100 100"; take the first value.
            val = int(float(raw.split()[0]))
        except (ValueError, IndexError):
            continue
        # 65535 is the "ISO too high to fit" sentinel; the next tag holds the real value.
        if 0 < val < 65535:
            return val
    return None


def _text_from(meta: Any, tags: Iterable[str]) -> Optional[str]:
    for tag in tags:
        val = _tag_string(meta, tag)
        if val and val.lower() != "unknown":
            return val
    return None


def read_metadata_gexiv2(path: Path, data: Optional[bytes] = None) -> Optional[ExposureMetadata]:
    """Read EXIF using native GExiv2 C++ library (R-LINUX-META-02).

    ``data`` is the file's content when already in memory (parsed instead of reopening ``path``).
    """
    if not _HAVE_GEXIV2:
        return None

    try:
        meta = GExiv2.Metadata()
        if data is not None:
            meta.open_buf(data)
        else:
            meta.open_path(str(path))
    except (GLib.Error, Exception):
        return None
    return metadata_from_gexiv2(meta)


def metadata_from_gexiv2(meta: Any) -> ExposureMetadata:
    """Map an opened ``GExiv2.Metadata`` (or any object with ``has_tag``/``get_tag_string``)."""
    is_fallback = False

    # 1. Shutter Speed (Exposure Time)
    shutter_speed = _first_float(meta, _EXPOSURE_TIME_TAGS)
    if shutter_speed is None:
        # APEX Tv conversion: t = 2^(-Tv) (R-LINUX-META-06)
        tv = _safe_float(_tag_string(meta, "Exif.Photo.ShutterSpeedValue"))
        if tv is not None:
            shutter_speed = 2.0 ** (-tv)
            is_fallback = True

    # 2. Aperture (FNumber)
    aperture = _first_float(meta, _FNUMBER_TAGS)
    if aperture is None:
        # APEX Av conversion: f = 2^(Av / 2) (R-LINUX-META-06)
        av = _safe_float(_tag_string(meta, "Exif.Photo.ApertureValue"))
        if av is not None:
            aperture = 2.0 ** (av / 2.0)
            is_fallback = True

    return ExposureMetadata(
        shutter_speed=shutter_speed,
        aperture=aperture,
        iso=_iso_from(meta),
        focal_length=_first_float(meta, _FOCAL_LENGTH_TAGS),
        lens=_text_from(meta, _LENS_TAGS),
        camera_make=_text_from(meta, ("Exif.Image.Make",)),
        camera_model=_text_from(meta, ("Exif.Image.Model",)),
        is_fallback=is_fallback,
    )


def read_metadata_pil(path: Path, data: Optional[bytes] = None) -> Optional[ExposureMetadata]:
    """Fallback EXIF reader using Pillow for headless/unit testing environments."""
    if not _HAVE_PIL:
        return None

    try:
        with Image.open(io.BytesIO(data) if data is not None else path) as img:
            exif_raw = img.getexif()
            if not exif_raw:
                return ExposureMetadata()

            # Merge IFD sub-dictionaries
            exif_dict = dict(exif_raw.items())
            # Pillow renamed IFD.Makernote to IFD.MakerNote; accept both spellings. The Exif IFD
            # is read on its own so a missing/failing MakerNote never costs the exposure fields.
            ifd_enum = ExifTags.IFD
            maker_note = getattr(ifd_enum, "MakerNote", None) or getattr(ifd_enum, "Makernote", None)
            for ifd_id in (getattr(ifd_enum, "Exif", None), maker_note):
                if ifd_id is None:
                    continue
                try:
                    ifd = exif_raw.get_ifd(ifd_id)
                    if ifd:
                        exif_dict.update(ifd.items())
                except Exception:
                    pass
    except Exception:
        return None

    shutter_speed: Optional[float] = None
    aperture: Optional[float] = None
    iso: Optional[int] = None
    focal_length: Optional[float] = None
    lens: Optional[str] = None
    camera_make: Optional[str] = None
    camera_model: Optional[str] = None
    is_fallback: bool = False

    # 1. Shutter speed
    exp_time_val = exif_dict.get(0x829A)  # ExposureTime
    if exp_time_val is not None:
        shutter_speed = _safe_float(exp_time_val)
    elif 0x9201 in exif_dict:  # ShutterSpeedValue (APEX Tv)
        tv = _safe_float(exif_dict.get(0x9201))
        if tv is not None:
            shutter_speed = 2.0 ** (-tv)
            is_fallback = True

    # 2. Aperture
    fnumber_val = exif_dict.get(0x829D)  # FNumber
    if fnumber_val is not None:
        aperture = _safe_float(fnumber_val)
    elif 0x9202 in exif_dict:  # ApertureValue (APEX Av)
        av = _safe_float(exif_dict.get(0x9202))
        if av is not None:
            aperture = 2.0 ** (av / 2.0)
            is_fallback = True

    # 3. ISO
    # ISOSpeedRatings, then ISOSpeed / RecommendedExposureIndex (CR3 and Sony fill only those)
    iso_val = exif_dict.get(0x8827) or exif_dict.get(0x8833) or exif_dict.get(0x8832)
    if iso_val is not None:
        if isinstance(iso_val, (tuple, list)) and len(iso_val) > 0:
            iso_candidate = iso_val[0]
        else:
            iso_candidate = iso_val
        try:
            val_int = int(iso_candidate)
            if val_int > 0:
                iso = val_int
        except Exception:
            pass

    # 4. Focal Length
    fl_val = exif_dict.get(0x920A)  # FocalLength
    if fl_val is not None:
        focal_length = _safe_float(fl_val)

    # 5. Lens Model
    lens_val = exif_dict.get(0xA434)  # LensModel
    if lens_val and isinstance(lens_val, str) and lens_val.strip().lower() != "unknown":
        lens = lens_val.strip()

    # 6. Make & Model
    make_val = exif_dict.get(0x010F)  # Make
    if make_val and isinstance(make_val, str) and make_val.strip().lower() != "unknown":
        camera_make = make_val.strip()
    model_val = exif_dict.get(0x0110)  # Model
    if model_val and isinstance(model_val, str) and model_val.strip().lower() != "unknown":
        camera_model = model_val.strip()

    return ExposureMetadata(
        shutter_speed=shutter_speed,
        aperture=aperture,
        iso=iso,
        focal_length=focal_length,
        lens=lens,
        camera_make=camera_make,
        camera_model=camera_model,
        is_fallback=is_fallback,
    )


def read_metadata(path: Path, data: Optional[bytes] = None) -> ExposureMetadata:
    """Read EXIF metadata from file using GExiv2 with Pillow fallback.

    Pass ``data`` when the file's bytes are already in memory, e.g. downloaded once from a
    GVFS cloud mount where every seek of a path-based parser is a network round trip.
    """
    if _HAVE_GEXIV2:
        meta = read_metadata_gexiv2(path, data)
        if meta is not None:
            return meta

    if _HAVE_PIL:
        meta = read_metadata_pil(path, data)
        if meta is not None:
            return meta

    return ExposureMetadata()
