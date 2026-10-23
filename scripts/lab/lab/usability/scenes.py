"""Photo-like scene fixtures for the usability panel.

The technical lab fixtures are numbered triangles: ideal for oracles, useless for a reviewer who
is asked "would you keep this photo?". These are procedural landscapes with the flaws a real
camera roll has — a blurred shot, an underexposed one, a near-duplicate burst, a pocket shot — so
personas judge the app with content that asks for real keep/delete decisions.
"""

from __future__ import annotations

import os
import random
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path

try:
    from PIL import Image, ImageDraw, ImageEnhance, ImageFilter
except ImportError:
    Image = ImageDraw = ImageEnhance = ImageFilter = None  # type: ignore

from ..fixtures import _build_exif_bytes, _write_jpeg, _write_xmp
from ..paths import ensure_under


@dataclass(frozen=True)
class SceneSpec:
    name: str
    palette: str
    flaw: str | None = None
    burst_group: str | None = None
    burst_offset_s: int = 0
    blur_radius: float = 0.0
    iso: int = 400
    exposure_time: tuple[int, int] = (1, 250)
    f_number: tuple[int, int] = (28, 10)
    focal_length: tuple[int, int] = (50, 1)
    focal_length_35mm: int = 50
    lens_model: str = "PSTLab 50mm F2.8"
    rating: int = 0
    has_raw: bool = False
    raw_format: str = "dng"
    has_xmp: bool = False
    has_edit: bool = False
    orientation: int = 1


ROLL_SPECS: list[SceneSpec] = [
    # ── 1–3. Dawn Lake Burst: 3-Up comparison culling (UC-4, UC-5, UC-13) ──
    SceneSpec(
        name="lake-dawn",
        palette="dawn",
        burst_group="dawn-burst",
        burst_offset_s=0,
        blur_radius=0.0,  # Sharpest (keeper)
        iso=200,
        exposure_time=(1, 500),
        f_number=(28, 10),
        focal_length=(70, 1),
        focal_length_35mm=70,
        lens_model="PSTLab 24-70mm F2.8 GM",
        rating=3,
        has_raw=True,
        raw_format="dng",
        has_xmp=True,
    ),
    SceneSpec(
        name="lake-dawn-burst2",
        palette="dawn",
        flaw="shift",
        burst_group="dawn-burst",
        burst_offset_s=1,
        blur_radius=2.0,  # Soft focus
        iso=200,
        exposure_time=(1, 500),
        f_number=(28, 10),
        focal_length=(70, 1),
        focal_length_35mm=70,
        lens_model="PSTLab 24-70mm F2.8 GM",
    ),
    SceneSpec(
        name="lake-dawn-burst3",
        palette="dawn",
        flaw="shift",
        burst_group="dawn-burst",
        burst_offset_s=2,
        blur_radius=6.0,  # Missed focus
        iso=200,
        exposure_time=(1, 500),
        f_number=(28, 10),
        focal_length=(70, 1),
        focal_length_35mm=70,
        lens_model="PSTLab 24-70mm F2.8 GM",
    ),

    # ── 4–6. Noon Ridge & Exposure Flaws (UC-6 Photo Coach) ──
    SceneSpec(
        name="ridge-noon",
        palette="noon",
        iso=100,
        exposure_time=(1, 250),
        f_number=(80, 10),  # F8 sweet spot
        focal_length=(24, 1),
        focal_length_35mm=24,
        lens_model="PSTLab 24-70mm F2.8 GM",
    ),
    SceneSpec(
        name="ridge-noon-blown",
        palette="noon",
        flaw="blown",  # Overexposed (>5% highlight clipping)
        iso=100,
        exposure_time=(1, 30),
        f_number=(80, 10),
        focal_length=(24, 1),
        focal_length_35mm=24,
        lens_model="PSTLab 24-70mm F2.8 GM",
    ),
    SceneSpec(
        name="pocket",
        palette="noon",
        flaw="pocket",  # Accidental pocket shot
        iso=800,
        exposure_time=(1, 60),
        f_number=(28, 10),
        focal_length=(50, 1),
        focal_length_35mm=50,
        lens_model="PSTLab 50mm F1.4",
    ),

    # ── 7–9. Dusk Valley & Low-Light Camera Shake (UC-6, UC-14) ──
    SceneSpec(
        name="valley-dusk",
        palette="dusk",
        iso=800,
        exposure_time=(1, 125),
        f_number=(14, 10),  # F1.4 wide open
        focal_length=(50, 1),
        focal_length_35mm=50,
        lens_model="PSTLab 50mm F1.4",
    ),
    SceneSpec(
        name="valley-dusk-dark",
        palette="dusk",
        flaw="dark",  # Underexposed (>8% shadow clipping)
        iso=100,
        exposure_time=(1, 1000),
        f_number=(40, 10),
        focal_length=(50, 1),
        focal_length_35mm=50,
        lens_model="PSTLab 50mm F1.4",
    ),
    SceneSpec(
        name="valley-dusk-shake",
        palette="dusk",
        flaw="shake",  # Camera shake (1/15s on 100mm lens)
        iso=800,
        exposure_time=(1, 15),
        f_number=(40, 10),
        focal_length=(100, 1),
        focal_length_35mm=100,
        lens_model="PSTLab 70-200mm F4 G OSS",
    ),

    # ── 10–11. Snow Peaks & Tilted Horizon (UC-6) ──
    SceneSpec(
        name="snow-peaks",
        palette="snow",
        iso=100,
        exposure_time=(1, 500),
        f_number=(110, 10),
        focal_length=(35, 1),
        focal_length_35mm=35,
        lens_model="PSTLab 35mm F1.4 GM",
    ),
    SceneSpec(
        name="snow-peaks-tilted",
        palette="snow",
        flaw="tilt",  # 8° horizon tilt
        iso=100,
        exposure_time=(1, 500),
        f_number=(110, 10),
        focal_length=(35, 1),
        focal_length_35mm=35,
        lens_model="PSTLab 35mm F1.4 GM",
    ),

    # ── 12. Desert Noon & F22 Diffraction Warning (UC-6) ──
    SceneSpec(
        name="desert-noon",
        palette="desert",
        iso=100,
        exposure_time=(1, 60),
        f_number=(220, 10),  # F22 diffraction warning
        focal_length=(24, 1),
        focal_length_35mm=24,
        lens_model="PSTLab 24-70mm F2.8 GM",
    ),

    # ── 13. Night Stars & High ISO Sensor Noise (UC-6, UC-14) ──
    SceneSpec(
        name="night-stars",
        palette="night",
        iso=6400,  # High sensor noise
        exposure_time=(1, 30),
        f_number=(14, 10),
        focal_length=(24, 1),
        focal_length_35mm=24,
        lens_model="PSTLab 24-70mm F2.8 GM",
    ),

    # ── 14. Vertical Aspect Ratio (UC-1) ──
    SceneSpec(
        name="lake-dusk-vertical",
        palette="dusk",
        flaw="vertical",  # 1500x2000 vertical
        iso=100,
        exposure_time=(1, 1000),
        f_number=(14, 10),
        focal_length=(85, 1),
        focal_length_35mm=85,
        lens_model="PSTLab 85mm F1.4 GM",
    ),

    # ── 15–16. Forest Morning & Companion Pair + Blurred Reject (phototok.yaml sequence) ──
    SceneSpec(
        name="forest-morning",
        palette="forest",
        iso=200,
        exposure_time=(1, 125),
        f_number=(56, 10),
        focal_length=(50, 1),
        focal_length_35mm=50,
        lens_model="PSTLab 50mm F1.4",
        rating=4,
        has_raw=True,
        raw_format="cr3",
        has_xmp=True,
        has_edit=True,
    ),
    SceneSpec(
        name="forest-morning-blurred",
        palette="forest",
        flaw="blur",  # 14px heavy blur for phototok U-TOK-02 swipe left
        iso=200,
        exposure_time=(1, 1000),  # Fast shutter speed + blur = Missed Focus
        f_number=(56, 10),
        focal_length=(50, 1),
        focal_length_35mm=50,
        lens_model="PSTLab 50mm F1.4",
    ),
]

ROLL: list[tuple[str, str, str | None]] = [
    (s.name, s.palette, s.flaw) for s in ROLL_SPECS
]

PALETTES: dict[str, dict[str, tuple[int, int, int]]] = {
    "dawn": {"top": (40, 60, 120), "horizon": (250, 170, 120), "sun": (255, 230, 180), "far": (110, 90, 130),
             "near": (50, 45, 70), "water": (90, 90, 140)},
    "noon": {"top": (40, 110, 210), "horizon": (170, 210, 245), "sun": (255, 255, 235), "far": (120, 140, 160),
             "near": (60, 100, 60), "water": (50, 110, 170)},
    "dusk": {"top": (30, 20, 70), "horizon": (240, 110, 70), "sun": (255, 190, 90), "far": (90, 50, 80),
             "near": (35, 20, 40), "water": (80, 50, 90)},
    "snow": {"top": (60, 120, 200), "horizon": (210, 230, 250), "sun": (255, 255, 250), "far": (225, 230, 240),
             "near": (180, 190, 205), "water": (120, 160, 200)},
    "desert": {"top": (70, 140, 220), "horizon": (240, 220, 190), "sun": (255, 250, 220), "far": (200, 140, 90),
                "near": (170, 100, 60), "water": (200, 160, 110)},
    "night": {"top": (5, 8, 25), "horizon": (30, 40, 80), "sun": (235, 235, 220), "far": (20, 25, 45),
              "near": (8, 10, 18), "water": (15, 20, 40)},
    "forest": {"top": (120, 170, 210), "horizon": (220, 230, 210), "sun": (255, 250, 220), "far": (70, 110, 80),
                "near": (25, 60, 35), "water": (60, 100, 90)},
    "coast": {"top": (30, 120, 200), "horizon": (190, 225, 245), "sun": (255, 255, 240), "far": (150, 140, 120),
              "near": (90, 80, 60), "water": (20, 100, 160)},
}


def _lerp(a: tuple[int, int, int], b: tuple[int, int, int], t: float) -> tuple[int, int, int]:
    return (int(a[0] + (b[0] - a[0]) * t), int(a[1] + (b[1] - a[1]) * t), int(a[2] + (b[2] - a[2]) * t))


def _ridge(rng: random.Random, width: int, base: float, rough: float) -> list[float]:
    """Midpoint-displacement ridge line: heights (0..1 from top) for `width + 1` x positions."""
    n = 1
    pts = [base + rng.uniform(-rough, rough), base + rng.uniform(-rough, rough)]
    amp = rough
    while n < 256:
        nxt: list[float] = []
        for i in range(len(pts) - 1):
            nxt.extend([pts[i], (pts[i] + pts[i + 1]) / 2 + rng.uniform(-amp, amp)])
        nxt.append(pts[-1])
        pts, n, amp = nxt, n * 2, amp * 0.55
    return [pts[int(x / width * (len(pts) - 1))] for x in range(width + 1)]


def render_scene(palette: str, size: tuple[int, int], seed: int) -> Image.Image:
    """Render one landscape: sky gradient, sun or moon, two ridges, a reflecting lake."""
    if Image is None:
        raise RuntimeError("scene rendering needs the real Pillow")
    rng = random.Random(seed)
    p = PALETTES[palette]
    w, h = size
    img = Image.new("RGB", size)
    draw = ImageDraw.Draw(img)
    horizon = int(h * rng.uniform(0.55, 0.66))
    for y in range(horizon):
        draw.line([(0, y), (w, y)], fill=_lerp(p["top"], p["horizon"], (y / horizon) ** 1.6))
    if palette == "night":
        for _ in range(400):
            x, y, r = rng.randrange(w), rng.randrange(horizon), rng.choice((1, 1, 2))
            draw.ellipse((x, y, x + r, y + r), fill=(230, 230, 255))
    sx, sy, sr = int(w * rng.uniform(0.2, 0.8)), int(horizon * rng.uniform(0.35, 0.8)), int(min(w, h) * 0.05)
    glow = Image.new("L", size, 0)
    ImageDraw.Draw(glow).ellipse((sx - sr * 3, sy - sr * 3, sx + sr * 3, sy + sr * 3), fill=90)
    img = Image.composite(Image.new("RGB", size, p["sun"]), img, glow.filter(ImageFilter.GaussianBlur(sr * 2)))
    draw = ImageDraw.Draw(img)
    draw.ellipse((sx - sr, sy - sr, sx + sr, sy + sr), fill=p["sun"])
    for colour, base, rough in ((p["far"], 0.42, 0.12), (p["near"], 0.52, 0.08)):
        ridge = _ridge(rng, w, base, rough)
        poly = [(x, int(ridge[x] * h)) for x in range(0, w + 1, 4)] + [(w, horizon), (0, horizon)]
        draw.polygon(poly, fill=colour)
    sky = img.crop((0, 0, w, horizon)).transpose(Image.Transpose.FLIP_TOP_BOTTOM)
    water = Image.blend(sky.resize((w, h - horizon)), Image.new("RGB", (w, h - horizon), p["water"]), 0.45)
    img.paste(water.filter(ImageFilter.GaussianBlur(3)), (0, horizon))
    draw = ImageDraw.Draw(img)
    for _ in range(60):
        y = rng.randrange(horizon + 4, h)
        x = rng.randrange(w)
        draw.line([(x, y), (x + rng.randrange(20, 120), y)], fill=_lerp(p["water"], p["sun"], 0.35), width=2)
    grain = Image.effect_noise(size, 18).convert("RGB")
    return Image.blend(img, grain, 0.04)


def _apply_flaw(img: Image.Image, flaw: str | None, rng: random.Random) -> Image.Image:
    if flaw == "blur":
        return img.filter(ImageFilter.GaussianBlur(14))
    if flaw == "dark":
        dim = ImageEnhance.Brightness(img).enhance(0.12)
        lut = [0 if i < 18 else i for i in range(256)] * 3
        return dim.point(lut)
    if flaw == "blown":
        return ImageEnhance.Brightness(img).enhance(2.6)
    if flaw == "tilt":
        w, h = img.size
        rot = img.rotate(8, resample=Image.Resampling.BICUBIC)
        return rot.crop((w // 10, h // 10, w - w // 10, h - h // 10)).resize((w, h))
    if flaw == "shift":
        w, h = img.size
        dx, dy = rng.randint(10, 40), rng.randint(5, 20)
        return img.crop((dx, dy, w - 40 + dx, h - 20 + dy)).resize((w, h))
    if flaw == "shake":
        base = img.copy()
        for step in range(1, 9):
            shifted = img.transform(img.size, Image.Transform.AFFINE, (1, 0, step * 2, 0, 1, step))
            base = Image.blend(base, shifted, 0.22)
        return base
    if flaw == "pocket":
        base = Image.new("RGB", img.size, (60, 40, 30))
        blob = Image.effect_noise(img.size, 40).convert("RGB")
        return Image.blend(base, blob, 0.25).filter(ImageFilter.GaussianBlur(25))
    return img


def generate_roll(
    out_dir: Path | str,
    *,
    count: int = 16,
    start: datetime | None = None,
    seed: int = 7,
    include_bursts: bool = True,
    include_companions: bool = True,
) -> list[Path]:
    """Write the camera-roll scene set to `out_dir` deterministically."""
    if Image is None:
        raise RuntimeError("scene rendering needs the real Pillow")

    out = Path(out_dir).expanduser().resolve()
    out.mkdir(parents=True, exist_ok=True)
    t0 = start or datetime(2026, 8, 14, 6, 40)
    written: list[Path] = []
    scene_seed: dict[str, int] = {}

    specs = ROLL_SPECS[:count]
    base_dt = t0

    for idx, spec in enumerate(specs):
        rng = random.Random(seed * 1000 + idx)
        size = (1500, 2000) if spec.flaw == "vertical" else (2000, 1500)
        base_seed = (
            scene_seed.get(spec.palette, seed * 100 + idx)
            if spec.flaw not in (None, "vertical")
            else seed * 100 + idx
        )
        scene_seed[spec.palette] = base_seed

        # Render procedural landscape (ZERO PEOPLE invariant)
        img = render_scene(spec.palette, size, base_seed)
        if spec.blur_radius > 0:
            img = img.filter(ImageFilter.GaussianBlur(radius=spec.blur_radius))
        img = _apply_flaw(img, spec.flaw, rng)

        # Timestamp calculation: burst peers are separated by 1 second
        if spec.burst_group and include_bursts:
            dt = base_dt + timedelta(seconds=spec.burst_offset_s)
        else:
            base_dt += timedelta(hours=2, minutes=rng.randint(0, 45))
            dt = base_dt

        dest_jpg = ensure_under(out / f"IMG_{dt:%Y%m%d_%H%M%S}.jpg", out)
        _write_jpeg(
            dest_jpg,
            img,
            dt,
            orientation=spec.orientation,
            iso=spec.iso,
            exposure_time=spec.exposure_time,
            f_number=spec.f_number,
            focal_length=spec.focal_length,
            focal_length_35mm=spec.focal_length_35mm,
            lens_model=spec.lens_model,
        )
        written.append(dest_jpg)

        # Companion files (UC-2, UC-7, UC-10)
        if include_companions and spec.has_raw:
            raw_ext = spec.raw_format.upper()
            dest_raw = ensure_under(out / f"IMG_{dt:%Y%m%d_%H%M%S}.{raw_ext}", out)
            if spec.raw_format.lower() == "dng":
                exif_bytes = _build_exif_bytes(
                    dt,
                    spec.orientation,
                    iso=spec.iso,
                    exposure_time=spec.exposure_time,
                    f_number=spec.f_number,
                    focal_length=spec.focal_length,
                    focal_length_35mm=spec.focal_length_35mm,
                    lens_model=spec.lens_model,
                )
                img.save(dest_raw, format="TIFF", exif=exif_bytes)
            else:
                dest_raw.write_bytes(b"CR3\x00\x00\x00\x18ftypcrx " + rng.randbytes(1024))
            ts = dt.timestamp()
            os.utime(dest_raw, (ts, ts))
            written.append(dest_raw)

        if include_companions and spec.has_xmp:
            dest_xmp = ensure_under(out / f"IMG_{dt:%Y%m%d_%H%M%S}.xmp", out)
            _write_xmp(dest_xmp, rating=spec.rating, dt=dt)
            written.append(dest_xmp)

        if include_companions and spec.has_edit:
            dest_edit = ensure_under(out / f"IMG_{dt:%Y%m%d_%H%M%S}-Edit.jpg", out)
            edit_img = ImageEnhance.Color(img).enhance(1.3)
            _write_jpeg(
                dest_edit,
                edit_img,
                dt,
                orientation=spec.orientation,
                iso=spec.iso,
                exposure_time=spec.exposure_time,
                f_number=spec.f_number,
                focal_length=spec.focal_length,
                focal_length_35mm=spec.focal_length_35mm,
                lens_model=spec.lens_model,
            )
            written.append(dest_edit)

    return written
