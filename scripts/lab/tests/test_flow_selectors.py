"""Every app selector in the Android lab flows names something the product's source defines.

The flows select app controls by test tag and dialog buttons by their text (dialog windows do
not export test tags), found in the Kotlin sources or the string resources. Both are owned by the product, which can change them without
running the lab: on 2026-10-18 a copy pass renamed PhotoTok's "Start with Newest" and "Link
actions across both" buttons, and every journey's `_ensure_folder.yaml` would have broken on the
next device run. This check fails in the CI mirror instead.
"""

from __future__ import annotations

import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lab.paths import repo_root  # noqa: E402

ROOT = repo_root()
PRODUCTS = ("android-desktop", "phototok")
SELECTOR_RE = re.compile(r'^\s*-?\s*(id|text):\s*"([^"]+)"', re.M)
# Strings the lab owns: fixture folder names, and the ISO every fixture writes into its EXIF
# (`lab/fixtures.py`). System UI is selected by its resource id and skipped.
LAB_OWNED_TEXT = {"PSTLab", "work", "ISO 400"}
# Tags a flow already uses, guarded by `when: visible`, that an open product PR adds. Each entry
# fails the test as soon as the tag is in the source, so the exception ends with its reason.
AWAITED_TAGS: dict[str, str] = {}


def alternatives(selector: str) -> list[str]:
    """Names a selector accepts; Maestro selectors are regexes.

    `delete_button_(expanded|compact)` -> both names; `Show new|Start with Newest` -> both texts
    (a flow that accepts an old and a new label while branches catch up).
    """
    m = re.fullmatch(r"(.*)\(([^()]+)\)(.*)", selector)
    if m:
        return [m.group(1) + alt + m.group(3) for alt in m.group(2).split("|")]
    return selector.split("|")


def product_strings(product: str) -> str:
    """Kotlin sources plus every string resource (EN and translations), each as a `"…"` literal.

    PhotoTok's visible text lives in `res/values*/strings.xml` (docs/shared/UI_TEXT.md), so a
    dialog button's label is a resource, not a Kotlin literal.
    """
    import xml.etree.ElementTree as ET

    base = ROOT / "products" / "android" / product
    parts = [p.read_text(encoding="utf-8", errors="replace") for p in (base / "src").rglob("*.kt")]
    for xml in sorted((base / "res").glob("values*/strings.xml")):
        for el in ET.parse(xml).getroot().iter():
            if el.tag in ("string", "item") and el.text:
                parts.append(f'"{el.text.strip()}"'.replace("\\'", "'"))
    return "\n".join(parts)


class TestFlowSelectors(unittest.TestCase):
    def test_every_app_selector_exists_in_the_product_source(self) -> None:
        missing: list[str] = []
        for product in PRODUCTS:
            source = product_strings(product)
            for flow in sorted((ROOT / "products" / "android" / product / "tests" / "e2e").rglob("*.yaml")):
                for kind, value in SELECTOR_RE.findall(flow.read_text(encoding="utf-8")):
                    if ":id/" in value or (kind == "text" and value in LAB_OWNED_TEXT):
                        continue
                    if kind == "id" and value in AWAITED_TAGS:
                        continue
                    if not any(f'"{alt}"' in source for alt in alternatives(value)):
                        missing.append(f"{flow.relative_to(ROOT)}: {kind} {value!r}")
        self.assertEqual(missing, [], "flow selectors the product no longer defines")

    def test_awaited_tags_are_still_missing(self) -> None:
        source = "\n".join(product_strings(p) for p in PRODUCTS)
        landed = [f"{tag} ({pr})" for tag, pr in AWAITED_TAGS.items() if f'"{tag}"' in source]
        self.assertEqual(landed, [], "these tags landed: drop them from AWAITED_TAGS")

    def test_alternatives_expand_one_group(self) -> None:
        self.assertEqual(alternatives("delete_button_(expanded|compact)"), ["delete_button_expanded", "delete_button_compact"])
        self.assertEqual(alternatives("viewer.photo"), ["viewer.photo"])
        self.assertEqual(alternatives("Show new|Start with Newest"), ["Show new", "Start with Newest"])


if __name__ == "__main__":
    unittest.main()
