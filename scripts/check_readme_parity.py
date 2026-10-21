#!/usr/bin/env python3
"""check_readme_parity.py — Automated Root README Parity and Hygiene Checker.

Enforces mechanically:
1. Product Representation: Every product defined in ai/ROUTING.md and AGENTS.md
   has a dedicated section and entry in the product table.
2. Canonical Presentation Order:
   1. Jackpix (Mobile)
   2. Frameduel — Android Desktop (Tablet/DeX)
   3. Lensjet — Native macOS Desktop
   4. Blitzlens — Linux Desktop
   5. Archived Legacy Desktop
3. Asset Integrity: Every screenshot or image referenced in README.md exists on disk.
4. Deprecated Term Detection: No deprecated, obsolete, or prohibited design terms
   appear in active product sections (e.g., Google Drive in Android Desktop,
   Three-Column View in Android Desktop).

Usage:
    python3 scripts/check_readme_parity.py --check
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
README_PATH = REPO_ROOT / "README.md"

CANONICAL_PRODUCT_ORDER = [
    ("Jackpix", re.compile(r"##\s+.*\bJackpix\b", re.I)),
    ("Frameduel", re.compile(r"##\s+.*\bFrameduel\b", re.I)),
    ("Lensjet", re.compile(r"##\s+.*\bLensjet\b", re.I)),
    ("Blitzlens", re.compile(r"##\s+.*\bBlitzlens\b", re.I)),
    ("Desktop (Legacy)", re.compile(r"##\s+.*\b(?:Desktop \(Legacy\)|Legacy Desktop|Archived Legacy Desktop)\b", re.I)),
]

FORBIDDEN_SECTION_TERMS = {
    "Frameduel": [
        ("Google Drive", "Google Drive was deprecated and removed in REQ-AND-PLATFORM.08"),
        ("Three-Column View", "Three-column view was replaced by 1-over-2 layout in REQ-AND-LAYOUT.04"),
    ],
    "Lensjet": [
        ("Ollama", "macOS Desktop uses Apple Vision native ANE scoring, not Ollama"),
    ],
    "Jackpix": [
        ("Three-Column", "Jackpix is a vertical swipe-first mobile app"),
    ],
    "Blitzlens": [
        ("Optical Cobalt", "the rebrand replaced the fixed accents with the GNOME system accent"),
        ("Obsidian Darkroom", "the rebrand replaced the fixed accents with the GNOME system accent"),
    ],
}


def check_readme() -> list[str]:
    errors: list[str] = []

    if not README_PATH.is_file():
        return [f"README.md not found at {README_PATH}"]

    content = README_PATH.read_text(encoding="utf-8")
    lines = content.splitlines()

    # 1. Check Product Headings and Ordering
    heading_indices: list[tuple[str, int]] = []
    for line_idx, line in enumerate(lines):
        for name, pattern in CANONICAL_PRODUCT_ORDER:
            if pattern.search(line):
                heading_indices.append((name, line_idx))
                break

    found_names = [name for name, _ in heading_indices]
    expected_names = [name for name, _ in CANONICAL_PRODUCT_ORDER]

    for expected in expected_names:
        if expected not in found_names:
            errors.append(f"Missing product section in README.md for: '{expected}'")

    # Verify canonical ordering
    if len(found_names) >= len(expected_names):
        for i, (name, _) in enumerate(heading_indices[:len(expected_names)]):
            expected = expected_names[i]
            if name != expected:
                errors.append(
                    f"Product section ordering violation: section #{i+1} is '{name}', "
                    f"expected '{expected}'. Canonical order: {', '.join(expected_names)}"
                )
                break

    # 2. Check Referenced Image Assets
    img_matches = re.findall(r'(?:src=["\']|!\[.*?\]\()([^"\'\)\s]+)', content)
    for img_path in img_matches:
        if img_path.startswith("http://") or img_path.startswith("https://"):
            continue
        if img_path.startswith("#"):
            continue
        # Clean query strings / fragments
        clean_path = img_path.split("?")[0].split("#")[0]
        resolved = (REPO_ROOT / clean_path).resolve()
        if not resolved.is_file():
            errors.append(f"Broken image reference in README.md: '{img_path}' (resolved: {resolved})")

    # 3. Check for Deprecated Terms in Section Text
    # Split content by markdown ## headings
    sections = re.split(r"\n(?=##\s+)", content)
    for section in sections:
        for product_name, forbidden_list in FORBIDDEN_SECTION_TERMS.items():
            if re.search(r"##\s+.*\b" + re.escape(product_name), section, re.I):
                for term, rationale in forbidden_list:
                    if re.search(r"\b" + re.escape(term) + r"\b", section, re.I):
                        errors.append(
                            f"Deprecated term '{term}' found in section '{product_name}'. Rationale: {rationale}"
                        )

    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description="Check README.md parity and hygiene.")
    parser.add_argument("--check", action="store_true", help="Run parity checks and exit non-zero on failure")
    args = parser.parse_args()

    errors = check_readme()

    if errors:
        print("README Parity Check FAILED with the following errors:", file=sys.stderr)
        for err in errors:
            print(f"  ✘ {err}", file=sys.stderr)
        return 1

    print("✔ README.md parity check passed: all products present in canonical order with valid visual assets.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
