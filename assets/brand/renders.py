"""Comparison renders for docs/shared/BRANDING.md: the four icons next to each other and
next to typical neighbours on each platform. Called by `generate.py renders`; needs a
rasteriser (see generate.py) and Pillow. The filler icons are anonymous placeholders in
the colours common on each platform, not any real product's icon.
"""
from __future__ import annotations

import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent / "renders"


def _font(size: int):
    for name in ("DejaVuSans.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                 "/System/Library/Fonts/Supplemental/Arial.ttf", "Arial.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


class Raster:
    def __init__(self, g):
        self.g = g
        self.tmp = Path(tempfile.mkdtemp())
        self.n = 0

    def img(self, svg: str, w: int, h: int | None = None) -> Image.Image:
        self.n += 1
        p = self.tmp / f"{self.n}.png"
        self.g.rasterise(svg, p, w, h)
        return Image.open(p).convert("RGBA")


# ── Icon builders for the scenes ──────────────────────────────────────────────

def adaptive(g, app: str, mono: tuple[str, str] | None = None) -> str:
    """The launcher's view of an adaptive icon: central 72 dp of the 108 dp layers, circle mask."""
    cols = g.colours(app)
    size, items = g.load_glyph(app)
    if mono:
        bg, fg = mono
        glyph = g.glyph_svg(items, None, mono=fg)
    else:
        bg, glyph = cols["tile"], g.glyph_svg(items, cols)
    body = (f'<clipPath id="c"><circle cx="54" cy="54" r="36"/></clipPath>'
            f'<g clip-path="url(#c)"><rect width="108" height="108" fill="{bg}"/>'
            f'<g {g.place(size, 24, 24, 60)}>{glyph}</g></g>')
    return g.svg_doc(72, 72, body, "18 18 72 72")


def filler_android(colour: str, glyph: str, shape: str = "circle") -> str:
    shapes = {
        "circle": f'<circle cx="36" cy="36" r="14" fill="{glyph}"/>',
        "square": f'<rect x="24" y="24" width="24" height="24" rx="4" fill="{glyph}"/>',
        "bars": f'<rect x="22" y="24" width="28" height="6" fill="{glyph}"/><rect x="22" y="36" width="20" height="6" fill="{glyph}"/>',
        "ring": f'<circle cx="36" cy="36" r="13" fill="none" stroke="{glyph}" stroke-width="6"/>',
        "tri": f'<path d="M22 48L36 22L50 48Z" fill="{glyph}"/>',
    }
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="72" height="72" viewBox="0 0 72 72">'
            f'<circle cx="36" cy="36" r="36" fill="{colour}"/>{shapes[shape]}</svg>')


def filler_macos(colour: str, glyph: str, shape: str) -> str:
    inner = filler_android("none", glyph, shape).split(">", 1)[1].rsplit("</svg>", 1)[0]
    inner = inner.replace('<circle cx="36" cy="36" r="36" fill="none"/>', "")
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">'
            f'<rect x="100" y="100" width="824" height="824" rx="185.4" fill="{colour}"/>'
            f'<g transform="translate(152 152) scale(10)">{inner}</g></svg>')


def filler_gnome(colour: str, glyph: str, shape: str, g) -> str:
    inner = filler_android("none", glyph, shape).split(">", 1)[1].rsplit("</svg>", 1)[0]
    inner = inner.replace('<circle cx="36" cy="36" r="36" fill="none"/>', "")
    edge = g._shade(colour, 0.82)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="128" height="128" viewBox="0 0 128 128">'
            f'<rect x="12" y="16" width="104" height="100" rx="20" fill="{edge}"/>'
            f'<rect x="12" y="12" width="104" height="100" rx="20" fill="{colour}"/>'
            f'<g transform="translate(19 17) scale(1.25)">{inner}</g></svg>')


def label(draw, xy, text, size=18, fill="#FFFFFF", anchor="mm"):
    draw.text(xy, text, font=_font(size), fill=fill, anchor=anchor)


# ── Scenes ────────────────────────────────────────────────────────────────────

def android_home(g, r: Raster) -> Path:
    apps = [("jackpix", "Jackpix"), ("frameduel", "Frameduel")]
    fillers = [("#FFFFFF", "#4285F4", "ring"), ("#2F2F33", "#FFFFFF", "square"),
               ("#1A73E8", "#FFFFFF", "tri"), ("#E8EAED", "#5F6368", "bars"),
               ("#0F9D58", "#FFFFFF", "circle"), ("#121212", "#E53935", "ring")]
    W, cell = 1240, 150
    img = Image.new("RGB", (W, 900), "#000000")
    d = ImageDraw.Draw(img)
    for row, (wall, text, title) in enumerate([("#1B1F2A", "#FFFFFF", "Dark wallpaper"),
                                                ("#E9E4DA", "#1A1A1A", "Light wallpaper")]):
        y0 = row * 300
        d.rectangle([0, y0, W, y0 + 300], fill=wall)
        label(d, (24, y0 + 22), title, 22, text, "lm")
        order = [("f", fillers[0]), ("a", apps[0]), ("f", fillers[1]), ("f", fillers[2]),
                 ("a", apps[1]), ("f", fillers[3]), ("f", fillers[4]), ("f", fillers[5])]
        for i, (kind, item) in enumerate(order):
            x = 40 + i * cell
            icon = r.img(adaptive(g, item[0]) if kind == "a" else filler_android(*item), 112)
            img.paste(icon, (x, y0 + 70), icon)
            if kind == "a":
                label(d, (x + 56, y0 + 210), item[1], 20, text)
    # Themed icons (Android 13+): monochrome layer tinted by the wallpaper colours.
    y0 = 600
    d.rectangle([0, y0, W, 900], fill="#E3E6EF")
    label(d, (24, y0 + 22), "Themed icons (monochrome layer)", 22, "#1A1A1A", "lm")
    for i, (bg, fg) in enumerate([("#D8E2FF", "#24345C"), ("#2B3445", "#C6D2F2")]):
        for j, (app, name) in enumerate(apps):
            x = 40 + (i * 2 + j) * cell * 1.2
            icon = r.img(adaptive(g, app, mono=(bg, fg)), 112)
            img.paste(icon, (int(x), y0 + 70), icon)
            label(d, (int(x) + 56, y0 + 210), name, 20, "#1A1A1A")
    out = OUT / "android-home.png"
    img.save(out)
    return out


def macos_dock(g, r: Raster) -> Path:
    fillers = [("#1C1C1E", "#FFFFFF", "square"), ("#2C2C2E", "#30D158", "bars"),
               ("#0B0B0F", "#FF9F0A", "ring"), ("#3A3A3C", "#FFFFFF", "tri"),
               ("#1E1E28", "#64D2FF", "circle"), ("#121212", "#FFFFFF", "ring"),
               ("#F2F2F7", "#1C1C1E", "square")]
    W = 1240
    img = Image.new("RGB", (W, 520), "#000000")
    d = ImageDraw.Draw(img)
    for row, (wall, dock, text, title, variant) in enumerate([
            ("#2A2D3A", "#4A4C55", "#FFFFFF", "Dock, dark mode, among dark icons", "default"),
            ("#C9D3E0", "#EEF0F4", "#1A1A1A", "Dock, light mode", "default")]):
        y0 = row * 260
        d.rectangle([0, y0, W, y0 + 260], fill=wall)
        label(d, (24, y0 + 24), title, 22, text, "lm")
        d.rounded_rectangle([60, y0 + 70, W - 60, y0 + 230], 36, fill=dock)
        icons = [r.img(filler_macos(*f), 128) for f in fillers[:3]]
        icons.append(r.img(g.macos_icon("lensjet", variant), 128))
        icons += [r.img(filler_macos(*f), 128) for f in fillers[3:]]
        for i, icon in enumerate(icons):
            img.paste(icon, (88 + i * 136, y0 + 86), icon)
        label(d, (88 + 3 * 136 + 64, y0 + 222), "Lensjet", 16, text)
    out = OUT / "macos-dock.png"
    img.save(out)
    return out


def gnome_grid(g, r: Raster) -> Path:
    fillers = [("#3584E4", "#FFFFFF", "square"), ("#9141AC", "#FFFFFF", "circle"),
               ("#26A269", "#FFFFFF", "bars"), ("#E66100", "#FFFFFF", "tri"),
               ("#F6F5F4", "#3D3846", "ring"), ("#241F31", "#F6F5F4", "square"),
               ("#C01C28", "#FFFFFF", "ring")]
    W = 1240
    img = Image.new("RGB", (W, 460), "#1E1E22")
    d = ImageDraw.Draw(img)
    label(d, (24, 28), "GNOME app grid", 22, "#FFFFFF", "lm")
    icons = [r.img(filler_gnome(*f, g), 128) for f in fillers[:4]]
    icons.append(r.img(g.gnome_icon("blitzlens"), 128))
    icons += [r.img(filler_gnome(*f, g), 128) for f in fillers[4:]]
    for i, icon in enumerate(icons):
        x, y = 40 + (i % 6) * 196, 70 + (i // 6) * 190
        img.paste(icon, (x + 30, y), icon)
        if i == 4:
            label(d, (x + 94, y + 150), "Blitzlens", 20, "#FFFFFF")
    out = OUT / "gnome-grid.png"
    img.save(out)
    return out


def sizes(g, r: Raster) -> Path:
    apps = list(g.PALETTE)
    zoom = 3
    W = 1240
    img = Image.new("RGB", (W, 620), "#FFFFFF")
    d = ImageDraw.Draw(img)
    label(d, (24, 24), "16 / 24 / 48 px, magnified 3×", 22, "#1A1A1A", "lm")
    for row, bg in enumerate(["#FFFFFF", "#1E1E1E"]):
        y0 = 50 + row * 280
        d.rectangle([0, y0, W, y0 + 280], fill=bg)
        for i, app in enumerate(apps):
            x = 30 + i * 300
            for j, px in enumerate((16, 24, 48)):
                small = app == "blitzlens" and px <= 24
                svg = g.gnome_small_icon(app, px) if small else g.tile_icon(app, px)
                icon = r.img(svg, px).resize((px * zoom, px * zoom), Image.NEAREST)
                img.paste(icon, (x + [0, 60, 144][j], y0 + 40 + (144 - px * zoom)), icon)
            label(d, (x + 144, y0 + 230), g.PALETTE[app]["name"], 20,
                  "#1A1A1A" if bg == "#FFFFFF" else "#FFFFFF")
    out = OUT / "sizes.png"
    img.save(out)
    return out


def cvd(g, r: Raster) -> Path:
    apps = list(g.PALETTE)
    W = 1240
    img = Image.new("RGB", (W, 520), "#F4F4F4")
    d = ImageDraw.Draw(img)
    tiles = [r.img(g.tile_icon(a), 128) for a in apps]
    for row, kind in enumerate([None, "deutan", "protan"]):
        y = 30 + row * 160
        label(d, (24, y + 64), kind or "normal vision", 20, "#1A1A1A", "lm")
        for i, t in enumerate(tiles):
            t2 = _simulate(g, t, kind) if kind else t
            img.paste(t2, (260 + i * 230, y), t2)
    out = OUT / "colour-vision.png"
    img.save(out)
    return out


def _simulate(g, im: Image.Image, kind: str) -> Image.Image:
    px = im.load()
    out = im.copy()
    po = out.load()
    cache: dict = {}
    for y in range(im.height):
        for x in range(im.width):
            r_, g_, b_, a = px[x, y]
            key = (r_, g_, b_)
            if key not in cache:
                s = g.simulate(f"#{r_:02X}{g_:02X}{b_:02X}", kind)
                cache[key] = tuple(int(s[i:i + 2], 16) for i in (1, 3, 5))
            po[x, y] = (*cache[key], a)
    return out


def palette_sheet(g, r: Raster) -> Path:
    W = 1240
    img = Image.new("RGB", (W, 640), "#0E0E10")
    d = ImageDraw.Draw(img)
    label(d, (24, 28), "In-app colours (dark-only, neutral grey surfaces)", 22, "#FFFFFF", "lm")
    for i, (app, p) in enumerate(g.PALETTE.items()):
        x = 24 + i * 304
        ia = p["in_app"]
        d.rounded_rectangle([x, 60, x + 290, 620], 16, fill=ia["surface"])
        icon = r.img(g.tile_icon(app), 96)
        img.paste(icon, (x + 16, 76), icon)
        label(d, (x + 128, 104), p["name"], 26, "#FFFFFF", "lm")
        label(d, (x + 128, 140), p["palette_id"], 15, "#B0B0B0", "lm")
        y = 196
        if ia["accent"] == "system":
            label(d, (x + 16, y + 20), "Selection/focus: system accent", 16, "#E0E0E0", "lm")
            d.rounded_rectangle([x + 16, y + 44, x + 274, y + 92], 12, fill=ia["brand"])
            label(d, (x + 145, y + 68), "brand surfaces only", 17, "#1A1A1A")
        else:
            d.rounded_rectangle([x + 16, y, x + 274, y + 48], 24, fill=ia["accent"])
            label(d, (x + 145, y + 24), "Primary action", 18, ia["on_accent"])
            c = g.contrast(ia["accent"], ia["surface"])
            label(d, (x + 16, y + 74), f"accent on surface {c:.1f}:1", 16, ia["accent"], "lm")
        y = 320
        label(d, (x + 16, y), "Semantic colours (unchanged)", 15, "#B0B0B0", "lm")
        for j, (name, col) in enumerate(ia["semantic"].items()):
            yy = y + 26 + j * 46
            d.rounded_rectangle([x + 16, yy, x + 56, yy + 34], 8, fill=col)
            label(d, (x + 68, yy + 17), name.replace("_", " "), 16, "#E0E0E0", "lm")
    out = OUT / "in-app-palettes.png"
    img.save(out)
    return out


def banner(g, r: Raster) -> Path:
    """README banner (assets/banner.png): the four apps, their platforms, no family name."""
    W, H = 1600, 560
    img = Image.new("RGB", (W, H), "#121212")
    d = ImageDraw.Draw(img)
    platforms = {"jackpix": "Android phones", "frameduel": "Android tablets & DeX",
                 "lensjet": "macOS", "blitzlens": "GNOME on Debian"}
    for i, (app, p) in enumerate(g.PALETTE.items()):
        cx = 200 + i * 400
        icon = r.img(g.tile_icon(app), 220)
        img.paste(icon, (cx - 110, 90), icon)
        label(d, (cx, 370), p["name"], 46, "#F2F2F2")
        label(d, (cx, 420), platforms[app], 24, "#A8A8A8")
        d.rounded_rectangle([cx - 36, 462, cx + 36, 468], 3, fill=p["tile"])
    out = OUT.parent.parent / "banner.png"
    img.save(out)
    return out


def build(g) -> list[Path]:
    OUT.mkdir(exist_ok=True)
    r = Raster(g)
    return [f(g, r) for f in (android_home, macos_dock, gnome_grid, sizes, cvd, palette_sheet,
                              banner)]
