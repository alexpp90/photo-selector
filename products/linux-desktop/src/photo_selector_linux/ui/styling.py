"""Obsidian Darkroom & System Accent CSS styling and visual design tokens for Linux Desktop.

Adheres to:
- R-LINUX-UI-02: Studio Dark Scheme & High-Contrast Typography
- R-LINUX-UI-06: 2.5px solid active focus border in the system accent colour (@accent_bg_color)
- R-LINUX-UI-09: Translucent glassmorphic HUD with normalized optical goodness visualization
- R-LINUX-UI-10: Semantic tactile action bar and status counter pills
"""

from __future__ import annotations

import math
from pathlib import Path
from typing import Dict, List, Tuple

APP_BRAND_NAME: str = "Blitzlens"
APP_BRAND_TAGLINE: str = "Optical RAW & JPEG Triage"
APP_THEME_NAME: str = "Obsidian Darkroom & System Accent"
APP_ICON_NAME: str = "app.alpatix.Blitzlens"

# Normalized goodness bar floor (~6% minimum fill so worst-case blurry scores
# still read as a metric bar rather than missing data; see ai/memory/palette.md).
GOODNESS_BAR_FLOOR: float = 0.06

STUDIO_PALETTE: Dict[str, str] = {
    # Surface Hierarchy (Obsidian Darkroom)
    "canvas_abyss": "#0E0E0E",
    "surface_bg": "#141414",
    "header_chrome": "#181818",
    "card_bg": "#1C1C1C",
    "card_hover_bg": "#232323",
    "hud_glass_bg": "rgba(20, 20, 20, 0.88)",
    "border_stroke": "#3A3A3A",
    "border_subtle": "#2A2A2A",
    # High-Contrast Typography (Zero dark-on-dark text)
    "text_primary": "#FFFFFF",
    "text_secondary": "#D9D9D9",
    "text_muted": "#A6A6A6",
    # Selection / focus / active state is NOT a fixed colour: the CSS uses libadwaita's named
    # accent colours, which follow the GNOME accent setting. Nothing here overrides it.
    # Semantic colours (fixed meaning)
    "emerald_keeper": "#2EC27E",
    "amber_optical": "#E5A50A",
    "crimson_cull": "#E01B24",
}


def compute_sharpness_goodness(sharpness_score: float) -> float:
    """Normalize a 0..100 sharpness score to a [GOODNESS_BAR_FLOOR, 1.0] visual bar fraction."""
    score = float(sharpness_score)
    if not math.isfinite(score):
        return GOODNESS_BAR_FLOOR if math.isnan(score) or score < 0.0 else 1.0
    clamped = max(0.0, min(100.0, score))
    normalized = clamped / 100.0
    return round(GOODNESS_BAR_FLOOR + (1.0 - GOODNESS_BAR_FLOOR) * normalized, 4)


def get_slot_role_label(comparison_mode: int, slot_offset: int, is_active: bool = False) -> str:
    """Return the contextual role badge label for a viewport slot."""
    if comparison_mode == 3:
        if slot_offset < 0:
            return "PREVIOUS"
        if slot_offset == 0 or is_active:
            return "ACTIVE"
        return "NEXT"
    if comparison_mode == 2:
        return "CHAMPION" if (slot_offset == 0 or is_active) else "CHALLENGER"
    return "ACTIVE"


def format_companion_badges(
    has_raw_jpeg_pair: bool,
    has_xmp_sidecar: bool,
) -> List[Tuple[str, str]]:
    """Return (badge_text, css_class) tuples for bound companion media."""
    badges: List[Tuple[str, str]] = []
    if has_raw_jpeg_pair:
        badges.append(("RAW+JPG", "companion-badge-raw"))
    if has_xmp_sidecar:
        badges.append(("XMP", "companion-badge-xmp"))
    return badges


def get_candidate_companion_badges(candidate: object) -> List[Tuple[str, str]]:
    """Inspect a CandidatePhoto's bound paths and return accurate companion badges.

    Emits RAW+JPG only when the candidate bundle contains both a RAW file and a
    bitmap/JPEG file (preventing false positives on JPEG-only '-Edit' derivatives).
    """
    from photo_selector_linux.core.scanner import BITMAP_EXTENSIONS, RAW_EXTENSIONS

    primary = getattr(candidate, "primary_path", None)
    companions = getattr(candidate, "companion_paths", None) or []
    all_paths = ([primary] if primary is not None else []) + list(companions)

    name_of = getattr(candidate, "name_of", lambda p: p.name)
    exts = {Path(name_of(p)).suffix.lstrip(".").lower() for p in all_paths if p is not None}
    exts.discard("")
    has_raw_jpeg_pair = bool(exts & RAW_EXTENSIONS) and bool(exts & BITMAP_EXTENSIONS)
    has_xmp_sidecar = getattr(candidate, "sidecar_path", None) is not None or ("xmp" in exts)
    return format_companion_badges(
        has_raw_jpeg_pair=has_raw_jpeg_pair,
        has_xmp_sidecar=has_xmp_sidecar,
    )


STUDIO_DARK_CSS: str = """
/* Blitzlens — Obsidian Darkroom & System Accent scheme.
 * Large surfaces are neutral grey (equal R, G, B). Selection, focus and active state use the
 * libadwaita named accent colours (@accent_bg_color, @accent_fg_color), which follow the
 * system accent setting. Keeper / acceptable / cull colours are fixed semantic colours.
 * The app is dark-only: Adw.ColorScheme.FORCE_DARK in ui/application.py. */

window, .studio-dark-surface {
    background-color: #141414;
    color: #FFFFFF;
}

.studio-headerbar {
    background-color: #181818;
    border-bottom: 1px solid #2A2A2A;
    color: #FFFFFF;
}

.studio-actionbar {
    background-color: #181818;
    border-top: 1px solid #2A2A2A;
    padding: 4px 8px;
}

/* Card & Comparison Panes */
.card-pane {
    background-color: #1C1C1C;
    border: 1px solid #3A3A3A;
    border-radius: 10px;
    padding: 6px;
}

.card-pane-inactive {
    background-color: #181818;
    border: 1px solid #2E2E2E;
    border-radius: 10px;
    padding: 6px;
}

/* Center Active Focus Slot Border (2.5px solid system accent with a soft glow) */
.accent-border {
    border: 2.5px solid @accent_bg_color;
    border-radius: 10px;
    box-shadow: 0 0 14px alpha(@accent_bg_color, 0.28);
}

/* Boundary Slot Pane (First / Last Photograph) */
.boundary-slot-pane {
    background-color: #1C1C1C;
    border: 1px solid #3A3A3A;
    border-radius: 10px;
    color: #D9D9D9;
    padding: 16px;
}

.boundary-slot-title {
    color: #FFFFFF;
    font-size: 16px;
    font-weight: bold;
}

.boundary-slot-subtitle {
    color: #D9D9D9;
    font-size: 13px;
}

/* Slot Role & Active Pill Badges */
.active-pill {
    background-color: @accent_bg_color;
    color: @accent_fg_color;
    font-weight: bold;
    font-size: 11px;
    border-radius: 10px;
    padding: 2px 10px;
}

.slot-role-pill {
    background-color: #252525;
    border: 1px solid #3A3A3A;
    color: #D9D9D9;
    font-weight: 600;
    font-size: 11px;
    border-radius: 10px;
    padding: 2px 10px;
}

.zoom-indicator-pill {
    background-color: rgba(255, 255, 255, 0.10);
    border: 1px solid #6E6E6E;
    color: #FFFFFF;
    font-weight: bold;
    font-size: 11px;
    border-radius: 10px;
    padding: 2px 8px;
}

/* Companion Format Badges (RAW+JPG, XMP) */
.companion-badge-raw {
    background-color: rgba(255, 255, 255, 0.12);
    border: 1px solid #7A7A7A;
    color: #FFFFFF;
    font-weight: bold;
    font-size: 10px;
    border-radius: 6px;
    padding: 1px 6px;
}

.companion-badge-xmp {
    background-color: rgba(255, 255, 255, 0.06);
    border: 1px solid #6E6E6E;
    color: #FFFFFF;
    font-weight: bold;
    font-size: 10px;
    border-radius: 6px;
    padding: 1px 6px;
}

/* Photo Counter Pill */
.photo-counter-pill {
    background-color: #1C1C1C;
    border: 1px solid #3A3A3A;
    color: #D9D9D9;
    border-radius: 12px;
    padding: 4px 12px;
    font-weight: 600;
    font-size: 13px;
}

/* Floating Info HUD Overlay (Glassmorphic Darkroom Card) */
.floating-hud {
    background-color: rgba(20, 20, 20, 0.88);
    border: 1px solid #3A3A3A;
    border-radius: 8px;
    padding: 10px 14px;
    color: #D9D9D9;
    box-shadow: 0 6px 20px rgba(0, 0, 0, 0.45);
}

.optical-strip-primary {
    color: #FFFFFF;
    font-weight: bold;
    font-size: 14px;
}

.optical-strip-secondary {
    color: #D9D9D9;
    font-size: 12px;
}

/* Quality Score Badges */
.score-badge-sharp {
    background-color: #2EC27E;
    color: #FFFFFF;
    border-radius: 6px;
    padding: 2px 8px;
    font-weight: bold;
    font-size: 11px;
}

.score-badge-acceptable {
    background-color: #E5A50A;
    color: #141414;
    border-radius: 6px;
    padding: 2px 8px;
    font-weight: bold;
    font-size: 11px;
}

.score-badge-blurry {
    background-color: #E01B24;
    color: #FFFFFF;
    border-radius: 6px;
    padding: 2px 8px;
    font-weight: bold;
    font-size: 11px;
}

/* Optical Sharpness Goodness Meter */
.sharpness-meter-track,
.sharpness-meter-track trough {
    background-color: #262626;
    border: 1px solid #3A3A3A;
    border-radius: 4px;
    min-height: 6px;
}

.sharpness-meter-track progress {
    background-color: @accent_bg_color;
    border-radius: 3px;
    min-height: 6px;
}

.sharpness-meter-sharp progress {
    background-color: #2EC27E;
}

.sharpness-meter-acceptable progress {
    background-color: #E5A50A;
}

.sharpness-meter-blurry progress {
    background-color: #E01B24;
}

/* Bottom Action Bar Buttons */
.action-btn-move {
    background-color: #2EC27E;
    color: #FFFFFF;
    font-weight: bold;
    border-radius: 8px;
    padding: 6px 16px;
}

.action-btn-copy {
    background-color: @accent_bg_color;
    color: @accent_fg_color;
    font-weight: bold;
    border-radius: 8px;
    padding: 6px 16px;
}

.action-btn-trash {
    background-color: #E01B24;
    color: #FFFFFF;
    font-weight: bold;
    border-radius: 8px;
    padding: 6px 16px;
}

/* Semantic Status Counter Badges */
.status-counter-badge {
    background-color: #1C1C1C;
    border: 1px solid #3A3A3A;
    color: #D9D9D9;
    border-radius: 6px;
    padding: 4px 10px;
    font-size: 12px;
    font-weight: 500;
}

.status-counter-selected {
    background-color: rgba(46, 194, 126, 0.16);
    border: 1px solid #2EC27E;
    color: #FFFFFF;
    border-radius: 6px;
    padding: 4px 10px;
    font-size: 12px;
    font-weight: 600;
}

.status-counter-trashed {
    background-color: rgba(224, 27, 36, 0.16);
    border: 1px solid #E01B24;
    color: #FFFFFF;
    border-radius: 6px;
    padding: 4px 10px;
    font-size: 12px;
    font-weight: 600;
}

.status-counter-remaining {
    background-color: #1C1C1C;
    border: 1px solid #3A3A3A;
    color: #D9D9D9;
    border-radius: 6px;
    padding: 4px 10px;
    font-size: 12px;
    font-weight: 500;
}

/* Branded Empty-State Darkroom Hero */
.empty-state-hero {
    background-color: #1C1C1C;
    border: 1px solid #3A3A3A;
    border-radius: 14px;
    padding: 32px 40px;
}

.empty-state-title {
    color: #FFFFFF;
    font-size: 22px;
    font-weight: bold;
}

.empty-state-subtitle {
    color: #D9D9D9;
    font-size: 14px;
}

.hotkey-hint-pill {
    background-color: #141414;
    border: 1px solid #3A3A3A;
    color: #D9D9D9;
    border-radius: 6px;
    padding: 3px 8px;
    font-size: 11px;
    font-weight: 600;
}
"""
