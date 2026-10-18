"""Deterministic synthetic photo fixture generator (no people, geometric markers only)."""

from __future__ import annotations

import colorsys
import json
import os
import random
import shutil
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import yaml
from PIL import Image, ImageDraw, ImageFilter

from .paths import repo_root


SPEC_PATH = Path(__file__).resolve().parent.parent / "fixtures" / "spec.yaml"
BASE_TIME = datetime(2026, 1, 15, 10, 0, 0, tzinfo=timezone.utc)

# 7-segment bitmasks (top, top-left, top-right, mid, bot-left, bot-right, bottom)
_DIGITS_7SEG: dict[str, tuple[int, ...]] = {
    "0": (1, 1, 1, 0, 1, 1, 1),
    "1": (0, 0, 1, 0, 0, 1, 0),
    "2": (1, 0, 1, 1, 1, 0, 1),
    "3": (1, 0, 1, 1, 0, 1, 1),
    "4": (0, 1, 1, 1, 0, 1, 0),
    "5": (1, 1, 0, 1, 0, 1, 1),
    "6": (1, 1, 0, 1, 1, 1, 1),
    "7": (1, 0, 1, 0, 0, 1, 0),
    "8": (1, 1, 1, 1, 1, 1, 1),
    "9": (1, 1, 1, 1, 0, 1, 1),
}


class CorpusMissingError(RuntimeError):
    """Raised when a fixture set requires the external format corpus and it is not cached."""


def load_spec(path: Path | None = None) -> dict[str, Any]:
    """Load `scripts/lab/fixtures/spec.yaml`."""
    target = path or SPEC_PATH
    return yaml.safe_load(target.read_text(encoding="utf-8"))


def _hue_rgb(hue_deg: float, sat: float, val: float) -> tuple[int, int, int]:
    r, g, b = colorsys.hsv_to_rgb((hue_deg % 360.0) / 360.0, sat, val)
    return int(round(r * 255)), int(round(g * 255)), int(round(b * 255))


def _draw_7seg_number(draw: ImageDraw.ImageDraw, text: str, x: int, y: int, digit_w: int, digit_h: int) -> None:
    thick = max(4, digit_w // 6)
    gap = max(6, digit_w // 4)
    half_h = digit_h // 2
    for idx, ch in enumerate(text):
        segs = _DIGITS_7SEG.get(ch)
        if not segs:
            continue
        dx = x + idx * (digit_w + gap)
        rects = [
            (dx, y, dx + digit_w, y + thick),                                  # top
            (dx, y, dx + thick, y + half_h),                                   # top-left
            (dx + digit_w - thick, y, dx + digit_w, y + half_h),               # top-right
            (dx, y + half_h - thick // 2, dx + digit_w, y + half_h + thick // 2),  # mid
            (dx, y + half_h, dx + thick, y + digit_h),                         # bot-left
            (dx + digit_w - thick, y + half_h, dx + digit_w, y + digit_h),     # bot-right
            (dx, y + digit_h - thick, dx + digit_w, y + digit_h),              # bottom
        ]
        for on, box in zip(segs, rects):
            if on:
                draw.rectangle(box, fill=(255, 240, 60))


def _render_synthetic_image(
    index: int,
    size: tuple[int, int],
    blur_radius: float = 0.0,
    border_color: tuple[int, int, int] | None = None,
) -> Image.Image:
    w, h = size
    hue = (index * 37) % 360
    c_top = _hue_rgb(hue, 0.55, 0.42)
    c_bot = _hue_rgb(hue + 40, 0.65, 0.22)

    # Fast deterministic gradient via a 64x64 grid resized with bilinear interpolation
    grid = Image.new("RGB", (64, 64))
    g_pixels = []
    for gy in range(64):
        t_y = gy / 63.0
        for gx in range(64):
            t_x = gx / 63.0
            r = int(c_top[0] * (1.0 - t_y) + c_bot[0] * t_y + 18.0 * t_x)
            g = int(c_top[1] * (1.0 - t_y) + c_bot[1] * t_y + 12.0 * (1.0 - t_x))
            b = int(c_top[2] * (1.0 - t_y) + c_bot[2] * t_y)
            g_pixels.append((min(255, max(0, r)), min(255, max(0, g)), min(255, max(0, b))))
    grid.putdata(g_pixels)
    img = grid.resize((w, h), resample=Image.Resampling.BILINEAR)
    draw = ImageDraw.Draw(img)

    # Asymmetric marker: bright white upward-pointing triangle in the top-left quarter
    tri = [
        (int(w * 0.12), int(h * 0.05)),
        (int(w * 0.05), int(h * 0.21)),
        (int(w * 0.19), int(h * 0.21)),
    ]
    draw.polygon(tri, fill=(255, 255, 255), outline=(10, 10, 10))

    # Centre checkerboard patch for focus/Laplacian contrast
    cb_w = int(w * 0.24)
    cb_h = int(h * 0.24)
    cb_x0 = (w - cb_w) // 2
    cb_y0 = (h - cb_h) // 2
    cells = 8
    cell_w = max(1, cb_w // cells)
    cell_h = max(1, cb_h // cells)
    for cy in range(cells):
        for cx in range(cells):
            fill = (245, 245, 245) if (cx + cy) % 2 == 0 else (20, 20, 20)
            x0 = cb_x0 + cx * cell_w
            y0 = cb_y0 + cy * cell_h
            draw.rectangle((x0, y0, x0 + cell_w, y0 + cell_h), fill=fill)

    # Large frame number top-right
    num_text = f"{index:04d}"
    digit_w = max(24, w // 32)
    digit_h = max(44, h // 14)
    total_w = len(num_text) * digit_w + (len(num_text) - 1) * max(6, digit_w // 4)
    _draw_7seg_number(draw, num_text, w - total_w - int(w * 0.04), int(h * 0.05), digit_w, digit_h)

    if blur_radius > 0:
        img = img.filter(ImageFilter.GaussianBlur(radius=blur_radius))

    if border_color is not None:
        bw = max(8, min(w, h) // 40)
        draw = ImageDraw.Draw(img)
        draw.rectangle((0, 0, w - 1, h - 1), outline=border_color, width=bw)

    return img


def _build_exif_bytes(dt: datetime, orientation: int = 1) -> bytes:
    exif = Image.Exif()
    dt_str = dt.strftime("%Y:%m:%d %H:%M:%S")
    exif[0x010F] = "PSTLab"
    exif[0x0110] = "Fixture"
    exif[0x0112] = int(orientation)
    exif[0x0132] = dt_str
    exif[0x9003] = dt_str
    # Exposure data in the Exif sub-IFD, like a real camera writes it: the apps' info overlays
    # and the shared EXIF contract read ISO, shutter, aperture, focal length and lens from here,
    # and PhotoTok shows no overlay at all for a photo without them.
    if hasattr(exif, "get_ifd"):
        from PIL.TiffImagePlugin import IFDRational

        sub = exif.get_ifd(0x8769)
        sub[0x8827] = 400  # ISOSpeedRatings
        sub[0x829A] = IFDRational(1, 250)  # ExposureTime
        sub[0x829D] = IFDRational(28, 10)  # FNumber
        sub[0x920A] = IFDRational(50, 1)  # FocalLength
        sub[0xA434] = "PSTLab 50mm F2.8"  # LensModel
        sub[0x9003] = dt_str  # DateTimeOriginal
    return exif.tobytes()


def _write_jpeg(
    path: Path,
    img: Image.Image,
    dt: datetime,
    orientation: int = 1,
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    exif_bytes = _build_exif_bytes(dt, orientation)
    img.save(path, format="JPEG", quality=88, subsampling=0, exif=exif_bytes)
    ts = dt.timestamp()
    os.utime(path, (ts, ts))


def _write_xmp(path: Path, rating: int, dt: datetime) -> None:
    content = (
        '<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>\n'
        '<x:xmpmeta xmlns:x="adobe:ns:meta/">\n'
        ' <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">\n'
        '  <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/"'
        f' xmp:Rating="{rating}"/>\n'
        " </rdf:RDF>\n"
        "</x:xmpmeta>\n"
        '<?xpacket end="w"?>\n'
    )
    path.write_text(content, encoding="utf-8")
    ts = dt.timestamp()
    os.utime(path, (ts, ts))


def _resolve_corpus_samples(formats_needed: list[str], root: Path | None = None) -> dict[str, Path]:
    r = root or repo_root()
    fetcher = r / "scripts" / "fetch_format_corpus.py"
    manifest_path = r / "testdata" / "formats" / "manifest.json"
    if not fetcher.is_file() or not manifest_path.is_file():
        print("[lab] ⊘ set needs the format corpus", file=sys.stderr)
        raise CorpusMissingError("format corpus scripts or manifest missing")

    chk = subprocess.run(
        [sys.executable, str(fetcher), "--check"],
        capture_output=True,
        text=True,
        check=False,
    )
    if chk.returncode != 0:
        print("[lab] ⊘ set needs the format corpus", file=sys.stderr)
        raise CorpusMissingError("format corpus is not populated")

    dir_out = subprocess.run(
        [sys.executable, str(fetcher), "--print-dir"],
        capture_output=True,
        text=True,
        check=True,
    )
    corpus_dir = Path(dir_out.stdout.strip())
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    by_format: dict[str, Path] = {}
    for sample in manifest.get("samples", []):
        fmt = str(sample.get("format", "")).lower()
        if fmt in formats_needed and fmt not in by_format:
            # "repo" samples are the small bitmaps committed next to the manifest; only the
            # downloaded RAW samples live in the corpus cache.
            base = manifest_path.parent if sample.get("source") == "repo" else corpus_dir
            candidate = base / str(sample["file"])
            if candidate.is_file():
                by_format[fmt] = candidate
    for fmt in formats_needed:
        if fmt.lower() not in by_format:
            print(f"[lab] ⊘ set needs the format corpus ({fmt})", file=sys.stderr)
            raise CorpusMissingError(f"missing format sample for {fmt}")
    return by_format


def generate_fixtures(
    set_name: str,
    out_dir: Path | str,
    seed: int = 42,
    spec_path: Path | None = None,
    root: Path | None = None,
) -> list[Path]:
    """Generate a named fixture set into `out_dir` deterministically; return written paths."""
    spec = load_spec(spec_path)
    sets = spec.get("sets", {})
    if set_name not in sets:
        raise ValueError(f"Unknown fixture set {set_name!r}; available: {sorted(sets)}")

    cfg = sets[set_name]
    out = Path(out_dir).expanduser().resolve()
    out.mkdir(parents=True, exist_ok=True)
    rng = random.Random(seed)
    size_list = cfg.get("size", [1200, 800])
    size = (int(size_list[0]), int(size_list[1]))

    # Determine corpus requirements up front before writing files
    needed_formats: list[str] = []
    if "raw_companion" in cfg:
        needed_formats.append(str(cfg["raw_companion"]).lower())
    for fmt in cfg.get("from_corpus", []):
        needed_formats.append(str(fmt).lower())
    corpus_map = _resolve_corpus_samples(needed_formats, root=root) if needed_formats else {}

    # Plan all items then emit in sorted path order
    planned: list[tuple[Path, str, dict[str, Any]]] = []

    if "bursts" in cfg:
        cur_dt = BASE_TIME
        for b_idx, burst in enumerate(cfg["bursts"]):
            if b_idx > 0:
                cur_dt += timedelta(seconds=60)
            start = int(burst["start"])
            count = int(burst["count"])
            sharpest = int(burst["sharpest"])
            for offset in range(count):
                frame_num = start + offset
                pos_1based = offset + 1
                blur = 0.0 if pos_1based == sharpest else float(2 + abs(pos_1based - sharpest))
                dt = cur_dt + timedelta(seconds=offset)
                jpg_path = out / f"DSC_{frame_num:04d}.JPG"
                planned.append(
                    (
                        jpg_path,
                        "jpeg",
                        {"index": frame_num, "size": size, "blur": blur, "dt": dt, "orientation": 1},
                    )
                )
                if "raw_companion" in cfg:
                    ext = str(cfg["raw_companion"]).upper()
                    raw_path = out / f"DSC_{frame_num:04d}.{ext}"
                    planned.append(
                        (
                            raw_path,
                            "copy",
                            {"src": corpus_map[str(cfg["raw_companion"]).lower()], "dt": dt},
                        )
                    )
                if frame_num in cfg.get("xmp", []):
                    xmp_path = out / f"DSC_{frame_num:04d}.xmp"
                    planned.append((xmp_path, "xmp", {"rating": 3, "dt": dt}))
                if frame_num in cfg.get("edits", []):
                    edit_path = out / f"DSC_{frame_num:04d}-Edit.JPG"
                    planned.append(
                        (
                            edit_path,
                            "jpeg",
                            {
                                "index": frame_num,
                                "size": size,
                                "blur": blur,
                                "dt": dt,
                                "orientation": 1,
                                "border": (255, 80, 80),
                            },
                        )
                    )
            cur_dt += timedelta(seconds=max(0, count - 1))
    else:
        n_photos = int(cfg.get("photos", cfg.get("plus_jpeg", 0)))
        orientations = cfg.get("exif_orientation", [])
        for i in range(1, n_photos + 1):
            dt = BASE_TIME + timedelta(seconds=i - 1)
            orient = int(orientations[i - 1]) if i - 1 < len(orientations) else 1
            jpg_path = out / f"DSC_{i:04d}.JPG"
            planned.append(
                (
                    jpg_path,
                    "jpeg",
                    {"index": i, "size": size, "blur": 0.0, "dt": dt, "orientation": orient},
                )
            )

        for idx, fmt in enumerate(cfg.get("from_corpus", []), start=1):
            fmt_l = str(fmt).lower()
            src = corpus_map[fmt_l]
            ext = src.suffix or f".{fmt_l}"
            dt = BASE_TIME + timedelta(seconds=100 + idx)
            dst = out / f"DSC_{100 + idx:04d}{ext}"
            planned.append((dst, "copy", {"src": src, "dt": dt}))

        for d_idx in range(1, int(cfg.get("selection_decoys", 0)) + 1):
            dt = BASE_TIME + timedelta(seconds=500 + d_idx)
            decoy_num = 9000 + d_idx
            decoy_path = out / "Selection" / f"DSC_{decoy_num:04d}.JPG"
            planned.append(
                (
                    decoy_path,
                    "jpeg",
                    {"index": decoy_num, "size": size, "blur": 0.0, "dt": dt, "orientation": 1},
                )
            )

        for c_idx in range(int(cfg.get("corrupt", 0))):
            corrupt_num = 999 - c_idx
            dt = BASE_TIME + timedelta(seconds=900 + c_idx)
            corrupt_path = out / f"DSC_{corrupt_num:04d}.JPG"
            payload = b"\xff\xd8" + rng.randbytes(2048)
            planned.append((corrupt_path, "bytes", {"data": payload, "dt": dt}))

    planned.sort(key=lambda item: item[0].relative_to(out).as_posix())

    # Cache rendered base images by (size, blur, border) when generating large sets
    written: list[Path] = []
    for target_path, kind, meta in planned:
        target_path.parent.mkdir(parents=True, exist_ok=True)
        dt = meta["dt"]
        if kind == "jpeg":
            img = _render_synthetic_image(
                index=int(meta["index"]),
                size=meta["size"],
                blur_radius=float(meta["blur"]),
                border_color=meta.get("border"),
            )
            _write_jpeg(target_path, img, dt=dt, orientation=int(meta["orientation"]))
        elif kind == "copy":
            shutil.copyfile(meta["src"], target_path)
            ts = dt.timestamp()
            os.utime(target_path, (ts, ts))
        elif kind == "xmp":
            _write_xmp(target_path, rating=int(meta["rating"]), dt=dt)
        elif kind == "bytes":
            target_path.write_bytes(meta["data"])
            ts = dt.timestamp()
            os.utime(target_path, (ts, ts))
        written.append(target_path)

    return written
