"""Photo-like scene fixtures for the usability panel.

The technical lab fixtures are numbered triangles: ideal for oracles, useless for a reviewer who
is asked "would you keep this photo?". These are procedural landscapes with the flaws a real
camera roll has — a blurred shot, an underexposed one, a near-duplicate burst, a pocket shot — so
personas judge the app with content that asks for real keep/delete decisions.
"""

from __future__ import annotations

import random
from datetime import datetime, timedelta
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter

from ..fixtures import _write_jpeg
from ..paths import ensure_under

# (name, palette, flaw). Order is the shooting order; the feed shows newest first.
ROLL: list[tuple[str, str, str | None]] = [
    ("lake-dawn", "dawn", None),
    ("lake-dawn-burst2", "dawn", "shift"),
    ("lake-dawn-burst3", "dawn", "shift"),
    ("ridge-noon", "noon", None),
    ("ridge-noon-blurred", "noon", "blur"),
    ("pocket", "noon", "pocket"),
    ("valley-dusk", "dusk", None),
    ("valley-dusk-dark", "dusk", "dark"),
    ("snow-peaks", "snow", None),
    ("snow-peaks-tilted", "snow", "tilt"),
    ("desert-noon", "desert", None),
    ("night-stars", "night", None),
    ("lake-dusk-portrait", "dusk", "portrait"),
    ("forest-morning", "forest", None),
    ("forest-morning-blurred", "forest", "blur"),
    ("coast-noon", "coast", None),
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
        return ImageEnhance.Brightness(img).enhance(0.22)
    if flaw == "tilt":
        w, h = img.size
        rot = img.rotate(9, resample=Image.Resampling.BICUBIC)
        return rot.crop((w // 10, h // 10, w - w // 10, h - h // 10)).resize((w, h))
    if flaw == "shift":
        w, h = img.size
        dx, dy = rng.randint(10, 40), rng.randint(5, 20)
        return img.crop((dx, dy, w - 40 + dx, h - 20 + dy)).resize((w, h))
    if flaw == "pocket":
        base = Image.new("RGB", img.size, (60, 40, 30))
        blob = Image.effect_noise(img.size, 40).convert("RGB")
        return Image.blend(base, blob, 0.25).filter(ImageFilter.GaussianBlur(25))
    return img


def generate_roll(out_dir: Path, *, start: datetime | None = None, seed: int = 7) -> list[Path]:
    """Write the camera-roll scene set to `out_dir` (`IMG_<date>_<time>.jpg`, EXIF dated)."""
    out = Path(out_dir)
    t0 = start or datetime(2026, 8, 14, 6, 40)
    written: list[Path] = []
    scene_seed: dict[str, int] = {}
    for idx, (name, palette, flaw) in enumerate(ROLL):
        rng = random.Random(seed * 1000 + idx)
        size = (1500, 2000) if flaw == "portrait" else (2000, 1500)
        # A flawed variant renders the same scene as its predecessor of the same palette.
        base_seed = scene_seed.get(palette, seed * 100 + idx) if flaw not in (None, "portrait") else seed * 100 + idx
        scene_seed[palette] = base_seed
        img = _apply_flaw(render_scene(palette, size, base_seed), flaw, rng)
        dt = t0 + timedelta(hours=idx * 3, minutes=rng.randint(0, 50))
        dest = ensure_under(out / f"IMG_{dt:%Y%m%d_%H%M%S}.jpg", out)
        _write_jpeg(dest, img, dt)
        written.append(dest)
    return written
