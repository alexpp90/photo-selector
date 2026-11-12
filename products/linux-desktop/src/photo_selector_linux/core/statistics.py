"""Library statistics engine for optical EXIF and culling distributions.

Adheres to:
- R-LINUX-TOOLS-02: Single-pass O(N) Library Statistics Engine
- R-LINUX-META-03: Optical EXIF Summary & Histograms
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
import json
import os
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

CANONICAL_FORMAT_NAMES: Dict[str, str] = {
    "arw": "Sony ARW",
    "cr3": "Canon CR3",
    "cr2": "Canon CR2",
    "nef": "Nikon NEF",
    "nrw": "Nikon NEF",
    "raf": "Fujifilm RAF",
    "dng": "Adobe DNG",
    "rw2": "Panasonic RW2",
    "orf": "OM System / Olympus ORF",
    "jpg": "JPEG",
    "jpeg": "JPEG",
    "heic": "Apple HEIC",
    "heif": "Apple HEIC",
    "tif": "TIFF",
    "tiff": "TIFF",
    "png": "PNG",
    "webp": "WebP",
}


def normalize_extension(ext: str) -> str:
    """Normalize file extensions to canonical base forms."""
    cleaned = ext.lower().lstrip(".")
    if cleaned == "jpeg":
        return "jpg"
    if cleaned in ("heif",):
        return "heic"
    if cleaned in ("tiff",):
        return "tif"
    return cleaned


@dataclass
class FormatStatistic:
    """Breakdown for a single photographic file format."""
    canonical_name: str
    raw_extension: str
    count: int
    total_bytes: int
    percentage_of_total: float


@dataclass
class LibraryStatistics:
    """Aggregated library statistics across candidate photo collection."""
    total_photo_count: int = 0
    candidate_count: int = 0
    selected_count: int = 0
    copied_count: int = 0
    trashed_count: int = 0
    total_storage_bytes: int = 0
    selected_storage_bytes: int = 0
    trashed_storage_bytes: int = 0
    format_statistics: List[FormatStatistic] = field(default_factory=list)
    average_sharpness: Optional[float] = None
    focus_distribution: Dict[str, int] = field(
        default_factory=lambda: {"blurry": 0, "acceptable": 0, "sharp": 0}
    )
    top_sharpest_photos: List[Any] = field(default_factory=list)
    focal_length_histogram: Dict[str, int] = field(default_factory=dict)
    aperture_histogram: Dict[str, int] = field(default_factory=dict)
    iso_histogram: Dict[str, int] = field(default_factory=dict)
    shutter_speed_histogram: Dict[str, int] = field(default_factory=dict)
    sharpness_by_focal_length: Dict[str, float] = field(default_factory=dict)
    sharpness_by_aperture: Dict[str, float] = field(default_factory=dict)
    sharpness_by_shutter_speed: Dict[str, float] = field(default_factory=dict)


def calculate_library_statistics(photos: List[Any]) -> LibraryStatistics:
    """Aggregate library statistics in a single linear O(N) pass over candidate photos."""
    if not photos:
        return LibraryStatistics()

    total_photos = len(photos)
    cand_cnt = sel_cnt = copy_cnt = trash_cnt = 0
    total_bytes = sel_bytes = trash_bytes = 0

    format_counts: Dict[str, Tuple[int, int]] = {}  # ext -> (count, bytes)
    focus_counts = {"blurry": 0, "acceptable": 0, "sharp": 0}
    total_sharpness = 0.0
    sharpness_count = 0

    fl_hist = {"< 24mm": 0, "24-34mm": 0, "35-69mm": 0, "70-199mm": 0, ">= 200mm": 0}
    ap_hist = {
        "< f/2.0": 0,
        "f/2.0-f/2.8": 0,
        "f/2.8-f/4.0": 0,
        "f/4.0-f/5.6": 0,
        "f/5.6-f/8.0": 0,
        ">= f/8.0": 0,
    }
    iso_hist = {
        "ISO 50-100": 0,
        "ISO 101-400": 0,
        "ISO 401-1600": 0,
        "ISO 1601-6400": 0,
        "> ISO 6400": 0,
    }
    ss_hist = {
        "< 1/1000s": 0,
        "1/1000s-1/250s": 0,
        "1/250s-1/60s": 0,
        "1/60s-1/2s": 0,
        ">= 1/2s": 0,
    }

    fl_sharp_acc: Dict[str, Tuple[float, int]] = {}
    ap_sharp_acc: Dict[str, Tuple[float, int]] = {}
    ss_sharp_acc: Dict[str, Tuple[float, int]] = {}

    scored_photos = []

    for photo in photos:
        # 1. Status
        raw_status = getattr(photo, "status", "candidate")
        status_val = raw_status.value if hasattr(raw_status, "value") else str(raw_status)
        status_str = status_val.lower()

        if status_str == "candidate":
            cand_cnt += 1
        elif status_str == "selected":
            sel_cnt += 1
        elif status_str == "copied":
            copy_cnt += 1
        elif status_str == "trashed":
            trash_cnt += 1

        # 2. File size
        p_bytes = getattr(photo, "file_size", 0)
        if p_bytes == 0 and hasattr(photo, "primary_path"):
            try:
                p_bytes = photo.primary_path.stat().st_size
            except OSError:
                p_bytes = 0

        total_bytes += p_bytes
        if status_str in ("selected", "copied"):
            sel_bytes += p_bytes
        elif status_str == "trashed":
            trash_bytes += p_bytes

        # 3. Format
        ext = ""
        if hasattr(photo, "filename"):
            ext = Path(photo.filename).suffix.lower()
        elif hasattr(photo, "primary_path"):
            ext = photo.primary_path.suffix.lower()
        elif hasattr(photo, "extension"):
            ext = photo.extension.lower()
        norm_ext = normalize_extension(ext)
        c, b = format_counts.get(norm_ext, (0, 0))
        format_counts[norm_ext] = (c + 1, b + p_bytes)

        # 4. Scores
        scores = getattr(photo, "scores", None)
        if scores:
            total_sharpness += scores.sharpness
            sharpness_count += 1
            cat_raw = scores.focus_category
            cat = cat_raw.value if hasattr(cat_raw, "value") else str(cat_raw)
            cat_lower = cat.lower()
            if cat_lower in focus_counts:
                focus_counts[cat_lower] += 1
            scored_photos.append(photo)

        # 5. Optical EXIF
        exif = getattr(photo, "exif", None)
        if exif:
            # Focal Length
            fl = exif.focal_length
            if fl is not None and fl > 0:
                if fl < 24.0:
                    fl_bucket = "< 24mm"
                elif fl < 35.0:
                    fl_bucket = "24-34mm"
                elif fl < 70.0:
                    fl_bucket = "35-69mm"
                elif fl < 200.0:
                    fl_bucket = "70-199mm"
                else:
                    fl_bucket = ">= 200mm"
                fl_hist[fl_bucket] += 1
                if scores is not None:
                    s_sum, s_cnt = fl_sharp_acc.get(fl_bucket, (0.0, 0))
                    fl_sharp_acc[fl_bucket] = (s_sum + float(scores.sharpness), s_cnt + 1)

            # Aperture
            ap = exif.aperture
            if ap is not None and ap > 0:
                if ap < 2.0:
                    ap_bucket = "< f/2.0"
                elif ap < 2.8:
                    ap_bucket = "f/2.0-f/2.8"
                elif ap < 4.0:
                    ap_bucket = "f/2.8-f/4.0"
                elif ap < 5.6:
                    ap_bucket = "f/4.0-f/5.6"
                elif ap < 8.0:
                    ap_bucket = "f/5.6-f/8.0"
                else:
                    ap_bucket = ">= f/8.0"
                ap_hist[ap_bucket] += 1
                if scores is not None:
                    s_sum, s_cnt = ap_sharp_acc.get(ap_bucket, (0.0, 0))
                    ap_sharp_acc[ap_bucket] = (s_sum + float(scores.sharpness), s_cnt + 1)

            # ISO
            iso = exif.iso
            if iso is not None and iso > 0:
                if iso <= 100:
                    iso_hist["ISO 50-100"] += 1
                elif iso <= 400:
                    iso_hist["ISO 101-400"] += 1
                elif iso <= 1600:
                    iso_hist["ISO 401-1600"] += 1
                elif iso <= 6400:
                    iso_hist["ISO 1601-6400"] += 1
                else:
                    iso_hist["> ISO 6400"] += 1

            # Shutter Speed
            ss = exif.shutter_speed
            if ss is not None and ss > 0:
                if ss < 0.001:
                    ss_bucket = "< 1/1000s"
                elif ss < 0.004:
                    ss_bucket = "1/1000s-1/250s"
                elif ss < 0.0167:
                    ss_bucket = "1/250s-1/60s"
                elif ss < 0.5:
                    ss_bucket = "1/60s-1/2s"
                else:
                    ss_bucket = ">= 1/2s"
                ss_hist[ss_bucket] += 1
                if scores is not None:
                    s_sum, s_cnt = ss_sharp_acc.get(ss_bucket, (0.0, 0))
                    ss_sharp_acc[ss_bucket] = (s_sum + float(scores.sharpness), s_cnt + 1)

    # Format statistics list
    fmt_list = []
    for ext, (cnt, b) in format_counts.items():
        name = CANONICAL_FORMAT_NAMES.get(ext, ext.upper())
        pct = round((cnt / total_photos) * 100.0, 1) if total_photos > 0 else 0.0
        fmt_list.append(
            FormatStatistic(
                canonical_name=name,
                raw_extension=ext,
                count=cnt,
                total_bytes=b,
                percentage_of_total=pct,
            )
        )
    fmt_list.sort(key=lambda s: s.count, reverse=True)

    # Averages
    avg_sharpness = round(total_sharpness / sharpness_count, 1) if sharpness_count > 0 else None

    # Top 5 sharpest
    top_sharpest = sorted(
        scored_photos,
        key=lambda p: p.scores.sharpness,
        reverse=True,
    )[:5]

    sharpness_by_fl = {k: round(total / cnt, 1) for k, (total, cnt) in fl_sharp_acc.items() if cnt > 0}
    sharpness_by_ap = {k: round(total / cnt, 1) for k, (total, cnt) in ap_sharp_acc.items() if cnt > 0}
    sharpness_by_ss = {k: round(total / cnt, 1) for k, (total, cnt) in ss_sharp_acc.items() if cnt > 0}

    return LibraryStatistics(
        total_photo_count=total_photos,
        candidate_count=cand_cnt,
        selected_count=sel_cnt,
        copied_count=copy_cnt,
        trashed_count=trash_cnt,
        total_storage_bytes=total_bytes,
        selected_storage_bytes=sel_bytes,
        trashed_storage_bytes=trash_bytes,
        format_statistics=fmt_list,
        average_sharpness=avg_sharpness,
        focus_distribution=focus_counts,
        top_sharpest_photos=top_sharpest,
        focal_length_histogram=fl_hist,
        aperture_histogram=ap_hist,
        iso_histogram=iso_hist,
        shutter_speed_histogram=ss_hist,
        sharpness_by_focal_length=sharpness_by_fl,
        sharpness_by_aperture=sharpness_by_ap,
        sharpness_by_shutter_speed=sharpness_by_ss,
    )
