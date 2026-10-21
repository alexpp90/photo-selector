#!/usr/bin/env python3
"""Export every app icon, favicon and comparison render from the brand sources.

Sources (edit these, never an export):
  assets/brand/palette.json        colours, one entry per app
  assets/brand/glyphs/<app>.svg    master glyph, 48-unit grid; data-role M (mark),
                                   A (accent) or K (knockout, drawn in the tile colour)

Usage:
  python3 assets/brand/generate.py [TARGET ...]   write exports (default: brand)
  python3 assets/brand/generate.py --check        verify the text exports are current
  python3 assets/brand/generate.py --palette      print the contrast and CVD table
  python3 assets/brand/generate.py --root DIR T   write T's platform exports into DIR

Targets: brand (per-app SVG/PNG/favicons), renders (comparison images), jackpix,
frameduel (Android adaptive + legacy + Play icons), lensjet (AppIcon.icns),
blitzlens (hicolor + symbolic), all.

Rasterising needs one of: rsvg-convert (librsvg; `brew install librsvg` on the Mac),
PyGObject with Rsvg (Debian: run with /usr/bin/python3), or cairosvg. --check and
--palette need only the standard library. On macOS the .icns is built with iconutil when
available, otherwise by the built-in writer.
"""
from __future__ import annotations

import argparse
import json
import math
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

BRAND = Path(__file__).resolve().parent
ROOT = BRAND.parent.parent
SVG_NS = "http://www.w3.org/2000/svg"
ET.register_namespace("", SVG_NS)

PALETTE = json.loads((BRAND / "palette.json").read_text())["apps"]
APPS = list(PALETTE)

LINUX_ID = "app.alpatix.Blitzlens"


def set_root(root: Path) -> None:
    """Where each app's platform exports land (--root: another checkout or worktree)."""
    global ROOT, ANDROID_RES, MACOS_ICNS, LINUX_ICONS
    ROOT = root
    ANDROID_RES = {
        "jackpix": root / "products/android/phototok/res",
        "frameduel": root / "products/android/android-desktop/res",
    }
    MACOS_ICNS = root / "products/macos-desktop/Resources/AppIcon.icns"
    LINUX_ICONS = root / "products/linux-desktop/data/icons/hicolor"


set_root(ROOT)


# ── Glyph model ───────────────────────────────────────────────────────────────

class Shape:
    """One drawable element of a glyph: role, SVG path data and paint."""

    def __init__(self, role: str, d: str, stroke: dict | None):
        self.role = role
        self.d = d
        self.stroke = stroke  # None = filled; else stroke attributes

    def paint(self, colour: str) -> str:
        if self.stroke is None:
            return f'fill="{colour}"'
        attrs = " ".join(f'{k}="{v}"' for k, v in self.stroke.items())
        return f'fill="none" stroke="{colour}" {attrs}'


class Group:
    def __init__(self, rotate: tuple[float, float, float] | None, items: list):
        self.rotate = rotate
        self.items = items


def _num(v: str) -> str:
    f = float(v)
    return str(int(f)) if f.is_integer() else f"{f:g}"


def rect_path(x: float, y: float, w: float, h: float, r: float) -> str:
    if r <= 0:
        return f"M{_num(x)} {_num(y)}H{_num(x + w)}V{_num(y + h)}H{_num(x)}Z"
    r = min(r, w / 2, h / 2)
    n = _num
    return (f"M{n(x + r)} {n(y)}H{n(x + w - r)}A{n(r)} {n(r)} 0 0 1 {n(x + w)} {n(y + r)}"
            f"V{n(y + h - r)}A{n(r)} {n(r)} 0 0 1 {n(x + w - r)} {n(y + h)}"
            f"H{n(x + r)}A{n(r)} {n(r)} 0 0 1 {n(x)} {n(y + h - r)}"
            f"V{n(y + r)}A{n(r)} {n(r)} 0 0 1 {n(x + r)} {n(y)}Z")


STROKE_ATTRS = ("stroke-width", "stroke-linecap", "stroke-linejoin", "stroke-miterlimit")


def _parse(el: ET.Element) -> list:
    out = []
    for child in el:
        tag = child.tag.split("}")[-1]
        if tag == "g":
            m = re.fullmatch(r"\s*rotate\(\s*([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)\s*\)\s*",
                             child.get("transform", ""))
            if child.get("transform") and not m:
                raise ValueError(f"unsupported transform {child.get('transform')!r}")
            out.append(Group(tuple(map(float, m.groups())) if m else None, _parse(child)))
            continue
        if tag not in ("path", "rect"):
            continue
        role = child.get("data-role")
        if role not in ("M", "A", "K"):
            raise ValueError(f"<{tag}> without data-role M/A/K")
        if tag == "rect":
            g = lambda k, d=0.0: float(child.get(k, d))  # noqa: E731
            d = rect_path(g("x"), g("y"), g("width"), g("height"), g("rx"))
        else:
            d = child.get("d")
        stroke = None
        if child.get("stroke") not in (None, "none"):
            stroke = {k: child.get(k) for k in STROKE_ATTRS if child.get(k)}
        out.append(Shape(role, d, stroke))
    return out


def load_glyph(name: str) -> tuple[float, list]:
    root = ET.parse(BRAND / "glyphs" / f"{name}.svg").getroot()
    size = float(root.get("viewBox").split()[2])
    return size, _parse(root)


# ── SVG output ────────────────────────────────────────────────────────────────

def colours(app: str, variant: str = "default") -> dict:
    p = PALETTE[app]
    base = p.get(variant, {}) if variant != "default" else {}
    tile = base.get("tile", p["tile"])
    return {"M": base.get("mark", p["mark"]), "A": base.get("accent", p["accent"]), "K": tile,
            "tile": tile}


def glyph_svg(items: list, cols: dict | None, mono: str | None = None) -> str:
    """SVG elements for a glyph. mono: one colour, knockouts become holes (even-odd)."""
    parts = []
    for item in _merge_knockouts(items) if mono else items:
        if isinstance(item, Group):
            t = f' transform="rotate({_num(item.rotate[0])} {_num(item.rotate[1])} {_num(item.rotate[2])})"' if item.rotate else ""
            parts.append(f"<g{t}>{glyph_svg(item.items, cols, mono)}</g>")
        else:
            colour = mono or cols[item.role]
            rule = ' fill-rule="evenodd"' if getattr(item, "evenodd", False) else ""
            parts.append(f'<path d="{item.d}" {item.paint(colour)}{rule}/>')
    return "".join(parts)


def _merge_knockouts(items: list) -> list:
    """Fold each knockout into the filled shape before it, as an even-odd hole."""
    out: list = []
    for item in items:
        if isinstance(item, Group):
            out.append(Group(item.rotate, _merge_knockouts(item.items)))
        elif item.role == "K":
            host = next((s for s in reversed(out)
                         if isinstance(s, Shape) and s.stroke is None), None)
            if host is None:
                raise ValueError("knockout without a filled shape before it")
            merged = Shape(host.role, host.d + item.d, None)
            merged.evenodd = True
            out[out.index(host)] = merged
        else:
            out.append(item)
    return out


def svg_doc(w: float, h: float, body: str, view: str | None = None) -> str:
    view = view or f"0 0 {_num(w)} {_num(h)}"
    return (f'<svg xmlns="{SVG_NS}" width="{_num(w)}" height="{_num(h)}" viewBox="{view}">'
            f"{body}</svg>\n")


def place(glyph_size: float, box_x: float, box_y: float, box: float) -> str:
    s = box / glyph_size
    return f'transform="translate({_num(box_x)} {_num(box_y)}) scale({s:g})"'


def tile_icon(app: str, size: float = 512, variant: str = "default", small: bool = False) -> str:
    """Generic rounded-square icon (docs, README, favicons)."""
    cols = colours(app, variant)
    gsize, items = load_glyph(f"{app}-small" if small else app)
    r = size * 0.225
    pad = size * (0.10 if small else 0.14)
    body = (f'<rect width="{_num(size)}" height="{_num(size)}" rx="{r:g}" fill="{cols["tile"]}"/>'
            f'<g {place(gsize, pad, pad, size - 2 * pad)}>{glyph_svg(items, cols)}</g>')
    return svg_doc(size, size, body)


def macos_icon(app: str, variant: str = "default") -> str:
    """Apple's macOS 11+ grid: 824 pt body on a 1024 canvas, 185.4 pt corner radius."""
    cols = colours(app, variant)
    gsize, items = load_glyph(app)
    body = (f'<rect x="100" y="100" width="824" height="824" rx="185.4" fill="{cols["tile"]}"/>'
            f'<g {place(gsize, 212, 212, 600)}>{glyph_svg(items, cols)}</g>')
    return svg_doc(1024, 1024, body)


def gnome_icon(app: str) -> str:
    """GNOME HIG app icon: 128 grid, rounded body with a darker bottom edge for depth."""
    cols = colours(app)
    gsize, items = load_glyph(app)
    edge = _shade(cols["tile"], 0.82)
    body = (f'<rect x="12" y="16" width="104" height="100" rx="20" fill="{edge}"/>'
            f'<rect x="12" y="12" width="104" height="100" rx="20" fill="{cols["tile"]}"/>'
            f'<g {place(gsize, 24, 22, 80)}>{glyph_svg(items, cols)}</g>')
    return svg_doc(128, 128, body)


def gnome_small_icon(app: str, size: int) -> str:
    cols = colours(app)
    gsize, items = load_glyph(f"{app}-small")
    body = (f'<rect width="16" height="16" rx="3" fill="{cols["tile"]}"/>'
            f"{glyph_svg(items, cols)}")
    return svg_doc(size, size, body, "0 0 16 16")


def symbolic_icon(app: str) -> str:
    gsize, items = load_glyph(f"{app}-small")
    return svg_doc(16, 16, glyph_svg(items, None, mono="#2E3436"))


def _shade(hex_colour: str, f: float) -> str:
    r, g, b = (int(hex_colour[i:i + 2], 16) for i in (1, 3, 5))
    return "#" + "".join(f"{max(0, min(255, round(c * f))):02X}" for c in (r, g, b))


# ── Android vector drawables ──────────────────────────────────────────────────

VD_HEAD = ('<?xml version="1.0" encoding="utf-8"?>\n'
           "<!-- Generated by assets/brand/generate.py from assets/brand/glyphs/{src}.svg. "
           "Do not edit. -->\n"
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:width="108dp"\n    android:height="108dp"\n'
           '    android:viewportWidth="108"\n    android:viewportHeight="108">\n')

VD_STROKE = {"stroke-width": "strokeWidth", "stroke-linecap": "strokeLineCap",
             "stroke-linejoin": "strokeLineJoin", "stroke-miterlimit": "strokeMiterLimit"}


def _vd_items(items: list, cols: dict | None, mono: str | None, indent: str) -> str:
    out = []
    for item in items:
        if isinstance(item, Group):
            attrs = ""
            if item.rotate:
                a, cx, cy = item.rotate
                attrs = (f' android:rotation="{_num(a)}" android:pivotX="{_num(cx)}"'
                         f' android:pivotY="{_num(cy)}"')
            out.append(f"{indent}<group{attrs}>\n{_vd_items(item.items, cols, mono, indent + '    ')}"
                       f"{indent}</group>\n")
            continue
        colour = mono or cols[item.role]
        attrs = [f'android:pathData="{item.d}"']
        if item.stroke is None:
            attrs.append(f'android:fillColor="{colour}"')
            if getattr(item, "evenodd", False):
                attrs.append('android:fillType="evenOdd"')
        else:
            attrs.append(f'android:strokeColor="{colour}"')
            attrs += [f'android:{VD_STROKE[k]}="{v}"' for k, v in item.stroke.items()]
        sep = f"\n{indent}    "
        out.append(f"{indent}<path{sep}{sep.join(attrs)} />\n")
    return "".join(out)


def android_layer(app: str, layer: str) -> str:
    """Adaptive-icon layer, 108 dp viewport; the glyph sits in a 60 dp box (66 dp safe zone)."""
    cols = colours(app)
    gsize, items = load_glyph(app)
    head = VD_HEAD.format(src=app)
    if layer == "background":
        return (head + f'    <path\n        android:pathData="M0,0h108v108h-108z"\n'
                f'        android:fillColor="{cols["tile"]}" />\n</vector>\n')
    mono = PALETTE[app]["monochrome"] if layer == "monochrome" else None
    if mono:
        items = _merge_knockouts(items)
    s = 60 / gsize
    body = _vd_items(items, cols, mono, "        ")
    return (head + f'    <group android:translateX="24" android:translateY="24"'
            f' android:scaleX="{s:g}" android:scaleY="{s:g}">\n{body}    </group>\n</vector>\n')


def android_mark(app: str) -> str:
    """Glyph only, one colour with knockouts as holes: the in-app logo, tinted by the caller."""
    gsize, items = load_glyph(app)
    head = VD_HEAD.format(src=app).replace('"108dp"', '"48dp"').replace('"108"', f'"{_num(gsize)}"')
    return head + _vd_items(_merge_knockouts(items), None, "#FFFFFFFF", "    ") + "</vector>\n"


ADAPTIVE = ('<?xml version="1.0" encoding="utf-8"?>\n'
            "<!-- Generated by assets/brand/generate.py. Do not edit. -->\n"
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@drawable/ic_launcher_background" />\n'
            '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
            '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
            "</adaptive-icon>\n")

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def android_legacy(app: str, round_: bool) -> str:
    """Pre-API-26 launcher icon, 48 dp grid with 2 dp padding."""
    cols = colours(app)
    gsize, items = load_glyph(app)
    shape = ('<circle cx="24" cy="24" r="22"' if round_ else
             '<rect x="2" y="2" width="44" height="44" rx="8"') + f' fill="{cols["tile"]}"/>'
    box = 28 if round_ else 30
    off = (48 - box) / 2
    return svg_doc(48, 48, f'{shape}<g {place(gsize, off, off, box)}>{glyph_svg(items, cols)}</g>')


def play_icon(app: str) -> str:
    """Play Store icon: full-bleed 512 px square; Play applies the corner mask itself."""
    cols = colours(app)
    gsize, items = load_glyph(app)
    return svg_doc(512, 512, f'<rect width="512" height="512" fill="{cols["tile"]}"/>'
                             f'<g {place(gsize, 106, 106, 300)}>{glyph_svg(items, cols)}</g>')


# ── Rasterising ───────────────────────────────────────────────────────────────

def rasterise(svg: str, out: Path, w: int, h: int | None = None) -> None:
    h = h or w
    out.parent.mkdir(parents=True, exist_ok=True)
    data = svg.encode()
    if shutil.which("rsvg-convert"):
        subprocess.run(["rsvg-convert", "-w", str(w), "-h", str(h), "-o", str(out)],
                       input=data, check=True)
        return
    try:
        import gi  # type: ignore
        gi.require_version("Rsvg", "2.0")
        from gi.repository import Rsvg  # type: ignore
        import cairo  # type: ignore
        handle = Rsvg.Handle.new_from_data(data)
        surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, w, h)
        ctx = cairo.Context(surface)
        rect = Rsvg.Rectangle()
        rect.x, rect.y, rect.width, rect.height = 0, 0, w, h
        handle.render_document(ctx, rect)
        surface.write_to_png(str(out))
        return
    except (ImportError, ValueError):
        pass
    try:
        import cairosvg  # type: ignore
        cairosvg.svg2png(bytestring=data, write_to=str(out), output_width=w, output_height=h)
        return
    except ImportError:
        sys.exit("No SVG rasteriser: install librsvg (rsvg-convert), PyGObject+Rsvg or cairosvg.")


# PNG-capable types only, the set iconutil itself writes as PNG. The legacy 16/32/64 slots
# (icp4, icp5, icp6) are decoded by IconServices as old bitmap formats, so a PNG there shows
# as noise in Finder lists, Spotlight and other small sizes; macOS scales ic11/ic12 down.
ICNS_TYPES = [("ic11", 32), ("ic12", 64), ("ic07", 128), ("ic13", 256), ("ic08", 256),
              ("ic14", 512), ("ic09", 512), ("ic10", 1024)]
ICONSET = [("icon_16x16", 16), ("icon_16x16@2x", 32), ("icon_32x32", 32),
           ("icon_32x32@2x", 64), ("icon_128x128", 128), ("icon_128x128@2x", 256),
           ("icon_256x256", 256), ("icon_256x256@2x", 512), ("icon_512x512", 512),
           ("icon_512x512@2x", 1024)]


def icns_bytes(pngs: dict[int, bytes]) -> bytes:
    """The built-in .icns writer, used when iconutil is missing: PNG data by pixel size."""
    chunks = b""
    for kind, px in ICNS_TYPES:
        data = pngs[px]
        chunks += kind.encode() + struct.pack(">I", len(data) + 8) + data
    return b"icns" + struct.pack(">I", len(chunks) + 8) + chunks


def icns_types(data: bytes) -> list[str]:
    """Chunk types of an .icns file, in order."""
    types, i = [], 8
    while i + 8 <= len(data):
        types.append(data[i:i + 4].decode("latin-1"))
        i += struct.unpack(">I", data[i + 4:i + 8])[0]
    return types


def write_icns(svg: str, out: Path) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        pngs = {}
        for px in sorted({px for _, px in ICNS_TYPES} | {px for _, px in ICONSET}):
            p = tmp / f"{px}.png"
            rasterise(svg, p, px)
            pngs[px] = p
        out.parent.mkdir(parents=True, exist_ok=True)
        if shutil.which("iconutil"):
            iconset = tmp / "AppIcon.iconset"
            iconset.mkdir()
            for name, px in ICONSET:
                shutil.copy(pngs[px], iconset / f"{name}.png")
            subprocess.run(["iconutil", "-c", "icns", str(iconset), "-o", str(out)], check=True)
            return
        out.write_bytes(icns_bytes({px: p.read_bytes() for px, p in pngs.items()}))


# ── Targets ───────────────────────────────────────────────────────────────────

DOCS_PAGES = {"jackpix": "phototok", "frameduel": "android-desktop"}


def text_outputs() -> dict[Path, str]:
    """Every generated text file and its content; --check compares those present on disk."""
    files: dict[Path, str] = {}
    for app in APPS:
        files[BRAND / app / f"{app}.svg"] = tile_icon(app)
    files[BRAND / "lensjet" / "lensjet-macos.svg"] = macos_icon("lensjet")
    files[BRAND / "lensjet" / "lensjet-macos-dark.svg"] = macos_icon("lensjet", "dark")
    files[BRAND / "blitzlens" / "blitzlens-small.svg"] = tile_icon("blitzlens", 16, small=True)
    for app, res in ANDROID_RES.items():
        for layer in ("background", "foreground", "monochrome"):
            files[res / "drawable" / f"ic_launcher_{layer}.xml"] = android_layer(app, layer)
        files[res / "drawable" / "ic_brand_mark.xml"] = android_mark(app)
        files[res / "mipmap-anydpi-v26" / "ic_launcher.xml"] = ADAPTIVE
        files[res / "mipmap-anydpi-v26" / "ic_launcher_round.xml"] = ADAPTIVE
    for app, slug in DOCS_PAGES.items():
        files[ROOT / "docs/products" / slug / "favicon.svg"] = tile_icon(app)
    files[LINUX_ICONS / "scalable/apps" / f"{LINUX_ID}.svg"] = gnome_icon("blitzlens")
    files[LINUX_ICONS / "symbolic/apps" / f"{LINUX_ID}-symbolic.svg"] = symbolic_icon("blitzlens")
    return files


def build(target: str) -> list[Path]:
    written = []

    def text(path: Path, content: str):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        written.append(path)

    def png(svg: str, path: Path, w: int, h: int | None = None):
        rasterise(svg, path, w, h)
        written.append(path)

    texts = text_outputs()
    if target == "brand":
        for app in APPS:
            svg = tile_icon(app)
            text(BRAND / app / f"{app}.svg", svg)
            for px in (512, 180, 32):
                png(svg, BRAND / app / f"{app}-{px}.png", px)
        text(BRAND / "lensjet/lensjet-macos.svg", texts[BRAND / "lensjet/lensjet-macos.svg"])
        text(BRAND / "lensjet/lensjet-macos-dark.svg", texts[BRAND / "lensjet/lensjet-macos-dark.svg"])
        text(BRAND / "blitzlens/blitzlens-small.svg", texts[BRAND / "blitzlens/blitzlens-small.svg"])
        for app in ANDROID_RES:
            png(play_icon(app), BRAND / app / f"{app}-play-512.png", 512)
        # GitHub Pages serves only docs/: the legal pages' favicons live next to them.
        for app, slug in DOCS_PAGES.items():
            svg = tile_icon(app)
            text(ROOT / "docs/products" / slug / "favicon.svg", svg)
            png(svg, ROOT / "docs/products" / slug / "favicon-32.png", 32)
            png(svg, ROOT / "docs/products" / slug / "apple-touch-icon.png", 180)
    elif target in ANDROID_RES:
        res = ANDROID_RES[target]
        for path, content in texts.items():
            if res in path.parents:
                text(path, content)
        for dens, px in DENSITIES.items():
            png(android_legacy(target, False), res / f"mipmap-{dens}" / "ic_launcher.png", px)
            png(android_legacy(target, True), res / f"mipmap-{dens}" / "ic_launcher_round.png", px)
    elif target == "lensjet":
        write_icns(macos_icon("lensjet"), MACOS_ICNS)
        written.append(MACOS_ICNS)
    elif target == "blitzlens":
        for path, content in texts.items():
            if LINUX_ICONS in path.parents:
                text(path, content)
        png(gnome_icon("blitzlens"), LINUX_ICONS / "128x128/apps" / f"{LINUX_ID}.png", 128)
        for px in (16, 24):
            png(gnome_small_icon("blitzlens", px), LINUX_ICONS / f"{px}x{px}/apps" / f"{LINUX_ID}.png", px)
    elif target == "renders":
        import renders  # noqa: PLC0415 — sibling module, needs the rasteriser
        written += renders.build(sys.modules[__name__])
    else:
        sys.exit(f"unknown target {target!r}")
    return written


def check() -> int:
    """Text exports on disk must equal what the sources produce now."""
    stale = []
    required = [BRAND / app / f"{app}.svg" for app in APPS]
    for path, content in text_outputs().items():
        if not path.exists():
            if path in required:
                stale.append(f"missing {path}")
            continue
        current = path.read_text()
        if BRAND not in path.parents and "assets/brand/generate.py" not in content[:300]:
            pass  # product SVGs carry no marker; compare them as they are
        elif BRAND not in path.parents and "assets/brand/generate.py" not in current[:300]:
            continue  # a pre-rebrand file this generator has not replaced yet
        if current != content:
            stale.append(f"stale {path}")
    for line in stale:
        print(line)
    if stale:
        print("Run: python3 assets/brand/generate.py <target> (see --help)")
    return 1 if stale else 0


# ── Palette checks (contrast, colour-vision deficiency) ───────────────────────

def _rgb(h: str) -> list[float]:
    return [int(h[i:i + 2], 16) / 255 for i in (1, 3, 5)]


def _lin(c: float) -> float:
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def _delin(c: float) -> float:
    c = max(0.0, min(1.0, c))
    return 12.92 * c if c <= 0.0031308 else 1.055 * c ** (1 / 2.4) - 0.055


def luminance(h: str) -> float:
    r, g, b = (_lin(c) for c in _rgb(h))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a: str, b: str) -> float:
    hi, lo = sorted((luminance(a), luminance(b)), reverse=True)
    return (hi + 0.05) / (lo + 0.05)


# Machado, Oliveira & Fernandes (2009), severity 1.0, applied in linear RGB.
CVD = {
    "deutan": [[0.367322, 0.860646, -0.227968], [0.280085, 0.672501, 0.047413],
               [-0.011820, 0.042940, 0.968881]],
    "protan": [[0.152286, 1.052583, -0.204868], [0.114503, 0.786281, 0.099216],
               [-0.003882, -0.048116, 1.051998]],
}


def simulate(h: str, kind: str) -> str:
    v = [_lin(c) for c in _rgb(h)]
    m = CVD[kind]
    o = [sum(m[i][j] * v[j] for j in range(3)) for i in range(3)]
    return "#" + "".join(f"{round(_delin(c) * 255):02X}" for c in o)


def _lab(h: str) -> tuple[float, float, float]:
    r, g, b = (_lin(c) for c in _rgb(h))
    x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
    y = 0.2126 * r + 0.7152 * g + 0.0722 * b
    z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
    f = lambda t: t ** (1 / 3) if t > 0.008856 else 7.787 * t + 16 / 116  # noqa: E731
    return 116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z))


def delta_e(a: str, b: str, kind: str | None = None) -> float:
    if kind:
        a, b = simulate(a, kind), simulate(b, kind)
    return math.dist(_lab(a), _lab(b))


CVD_MIN_DELTA_E = 15.0  # CIE76 under simulated deuteranopia/protanopia


def palette_rows() -> list[dict]:
    rows = []
    for app, p in PALETTE.items():
        rows.append({"app": app, "pair": "icon mark / tile", "a": p["mark"], "b": p["tile"],
                     "contrast": contrast(p["mark"], p["tile"]), "min": 3.0})
        ia = p["in_app"]
        for key in ("accent", "brand"):
            col = ia.get(key)
            if not col or col == "system":
                continue
            rows.append({"app": app, "pair": f"in-app {key} / surface", "a": col, "b": ia["surface"],
                         "contrast": contrast(col, ia["surface"]), "min": 4.5})
            if key == "accent":
                rows.append({"app": app, "pair": "on-accent / accent", "a": ia["on_accent"], "b": col,
                             "contrast": contrast(ia["on_accent"], col), "min": 4.5})
                for name, sem in ia["semantic"].items():
                    rows.append({"app": app, "pair": f"accent vs {name}", "a": col, "b": sem,
                                 "de": delta_e(col, sem), "deutan": delta_e(col, sem, "deutan"),
                                 "protan": delta_e(col, sem, "protan")})
    return rows


def print_palette() -> int:
    bad = 0
    print(f"{'app':10} {'pair':28} {'colours':17} {'result':>34}")
    for r in palette_rows():
        cols = f"{r['a']} {r['b']}"
        if "contrast" in r:
            ok = r["contrast"] >= r["min"]
            res = f"{r['contrast']:5.2f}:1 (min {r['min']}:1) {'ok' if ok else 'FAIL'}"
        else:
            ok = min(r["deutan"], r["protan"]) >= CVD_MIN_DELTA_E
            res = (f"ΔE {r['de']:5.1f} deu {r['deutan']:5.1f} pro {r['protan']:5.1f} "
                   f"{'ok' if ok else 'FAIL'}")
        bad += not ok
        print(f"{r['app']:10} {r['pair']:28} {cols:17} {res:>34}")
    return 1 if bad else 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("targets", nargs="*", default=["brand"])
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--palette", action="store_true")
    ap.add_argument("--root", type=Path, help="write platform exports into this checkout")
    args = ap.parse_args(argv)
    if args.root:
        set_root(args.root.resolve())
    if args.check:
        return check()
    if args.palette:
        return print_palette()
    targets = args.targets
    if targets == ["all"]:
        targets = ["brand", *ANDROID_RES, "lensjet", "blitzlens", "renders"]
    sys.path.insert(0, str(BRAND))
    for t in targets:
        for path in build(t):
            print(path)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
