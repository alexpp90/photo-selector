"""Every app selector in the Android lab flows names something the product's source defines.

The flows select app controls by test tag and dialog buttons by their text (dialog windows do
not export test tags). Both are strings owned by the product, which can change them without
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
# Strings the lab owns (fixture folder names), and system UI selected by its resource id.
LAB_OWNED_TEXT = {"PSTLab", "work"}


def alternatives(selector: str) -> list[str]:
    """`delete_button_(expanded|compact)` -> both names; Maestro selectors are regexes."""
    m = re.fullmatch(r"(.*)\(([^()]+)\)(.*)", selector)
    if not m:
        return [selector]
    return [m.group(1) + alt + m.group(3) for alt in m.group(2).split("|")]


def product_strings(product: str) -> str:
    src = ROOT / "products" / "android" / product / "src"
    return "\n".join(p.read_text(encoding="utf-8", errors="replace") for p in src.rglob("*.kt"))


class TestFlowSelectors(unittest.TestCase):
    def test_every_app_selector_exists_in_the_product_source(self) -> None:
        missing: list[str] = []
        for product in PRODUCTS:
            source = product_strings(product)
            for flow in sorted((ROOT / "products" / "android" / product / "tests" / "e2e").rglob("*.yaml")):
                for kind, value in SELECTOR_RE.findall(flow.read_text(encoding="utf-8")):
                    if ":id/" in value or (kind == "text" and value in LAB_OWNED_TEXT):
                        continue
                    if not any(f'"{alt}"' in source for alt in alternatives(value)):
                        missing.append(f"{flow.relative_to(ROOT)}: {kind} {value!r}")
        self.assertEqual(missing, [], "flow selectors the product no longer defines")

    def test_alternatives_expand_one_group(self) -> None:
        self.assertEqual(alternatives("delete_button_(expanded|compact)"), ["delete_button_expanded", "delete_button_compact"])
        self.assertEqual(alternatives("viewer.photo"), ["viewer.photo"])


if __name__ == "__main__":
    unittest.main()
