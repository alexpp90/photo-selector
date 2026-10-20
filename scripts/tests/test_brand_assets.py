"""Brand sources and exports (assets/brand/): exports current, palette checks, safe zones."""
from __future__ import annotations

import importlib.util
import io
import math
import re
import unittest
import xml.etree.ElementTree as ET
from contextlib import redirect_stdout
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BRAND = ROOT / "assets" / "brand"


def _load_generator():
    spec = importlib.util.spec_from_file_location("brand_generate", BRAND / "generate.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


G = _load_generator()


class BrandExportsTest(unittest.TestCase):
    def test_text_exports_match_the_sources(self):
        out = io.StringIO()
        with redirect_stdout(out):
            status = G.check()
        self.assertEqual(status, 0, out.getvalue())

    def test_every_app_has_a_master_glyph_and_a_name(self):
        for app, p in G.PALETTE.items():
            self.assertTrue((BRAND / "glyphs" / f"{app}.svg").is_file(), app)
            self.assertRegex(p["name"], r"^[A-Z][a-z]+$")

    def test_master_colours_follow_the_palette(self):
        """The masters show the brand colours; palette.json stays the single source."""
        masters = {f"{a}.svg": a for a in G.PALETTE} | {"blitzlens-small.svg": "blitzlens"}
        for name, app in masters.items():
            cols = G.colours(app)
            for el in ET.parse(BRAND / "glyphs" / name).getroot().iter():
                role = el.get("data-role")
                if not role:
                    continue
                paint = el.get("stroke") if el.get("fill") == "none" else el.get("fill")
                self.assertEqual(paint.upper(), cols[role].upper(), f"{name} {role}")


class PaletteTest(unittest.TestCase):
    def test_contrast_and_colour_vision_thresholds(self):
        for row in G.palette_rows():
            if "contrast" in row:
                self.assertGreaterEqual(row["contrast"], row["min"], row)
            else:
                self.assertGreaterEqual(min(row["deutan"], row["protan"]), G.CVD_MIN_DELTA_E, row)

    def test_contrast_matches_wcag_reference_values(self):
        self.assertAlmostEqual(G.contrast("#000000", "#FFFFFF"), 21.0, places=2)
        self.assertAlmostEqual(G.contrast("#777777", "#FFFFFF"), 4.48, places=2)

    def test_cvd_simulation_collapses_red_green(self):
        # Pure red and green differ hugely for normal vision and far less for a deuteranope.
        self.assertGreater(G.delta_e("#FF0000", "#00FF00"), 150)
        self.assertLess(G.delta_e("#FF0000", "#00FF00", "deutan"),
                        G.delta_e("#FF0000", "#00FF00") / 2)


class AndroidSafeZoneTest(unittest.TestCase):
    """Adaptive-icon foregrounds keep their glyph inside the 66 dp safe circle of 108 dp."""

    def _vertices(self, d):
        """Vertices of a path made of absolute M/L/H/V/Z (all the Android glyphs use)."""
        self.assertNotRegex(d, r"[a-zAC-GI-KN-UW-Y]", f"unsupported path command in {d!r}")
        x = y = 0.0
        out = []
        for cmd, args in re.findall(r"([MLHVZ])([^MLHVZ]*)", d):
            nums = [float(n) for n in re.findall(r"-?\d+(?:\.\d+)?", args)]
            if cmd in "ML":
                for i in range(0, len(nums), 2):
                    x, y = nums[i], nums[i + 1]
                    out.append((x, y))
            elif cmd == "H":
                x = nums[-1]
                out.append((x, y))
            elif cmd == "V":
                y = nums[-1]
                out.append((x, y))
        return out

    def _points(self, items, rot=None):
        pts = []
        for item in items:
            if isinstance(item, G.Group):
                pts += self._points(item.items, item.rotate)
                continue
            pad = float(item.stroke.get("stroke-width", 0)) / 2 * math.sqrt(2) if item.stroke else 0
            for x, y in self._vertices(item.d):
                if rot:
                    a, cx, cy = rot
                    t = math.radians(a)
                    x, y = (cx + (x - cx) * math.cos(t) - (y - cy) * math.sin(t),
                            cy + (x - cx) * math.sin(t) + (y - cy) * math.cos(t))
                pts.append((x, y, pad))
        return pts

    def test_glyph_inside_safe_zone(self):
        for app in G.ANDROID_RES:
            size, items = G.load_glyph(app)
            scale = 60 / size
            for x, y, pad in self._points(items):
                dist = (math.dist((x, y), (size / 2, size / 2)) + pad) * scale
                self.assertLessEqual(dist, 33, f"{app}: ({x:.1f}, {y:.1f}) at {dist:.1f} dp")

    def test_monochrome_layer_has_holes_not_tile_coloured_shapes(self):
        for app in G.ANDROID_RES:
            xml = G.android_layer(app, "monochrome")
            self.assertIn('android:fillType="evenOdd"', xml, app)
            self.assertNotIn(G.PALETTE[app]["tile"], xml, app)


if __name__ == "__main__":
    unittest.main()
