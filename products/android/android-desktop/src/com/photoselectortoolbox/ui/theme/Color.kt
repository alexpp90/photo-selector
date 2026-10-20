package com.photoselectortoolbox.ui.theme

import androidx.compose.ui.graphics.Color

// Neutral greys (backgrounds / text). Equal R, G, B at the former Zinc lightness:
// a tinted surround shifts the perceived colour of the photo being judged.
val Zinc50 = Color(0xFFFAFAFA)
val Zinc100 = Color(0xFFF4F4F4)
val Zinc200 = Color(0xFFE4E4E4)
val Zinc300 = Color(0xFFD4D4D4)
val Zinc400 = Color(0xFFA1A1A1)
val Zinc500 = Color(0xFF717171)
val Zinc600 = Color(0xFF525252)
val Zinc700 = Color(0xFF3F3F3F)
val Zinc800 = Color(0xFF272727)
val Zinc900 = Color(0xFF181818)
val Zinc950 = Color(0xFF090909)

// ── Frameduel palette (dark-only) ─────────────────────────────────────────
//
// Calibrated for judging photographs:
// - Studio surfaces are neutral grey (R == G == B) so the surround adds no
//   colour cast to skin tones or shadows; they sit a few percent above black.
// - The lavender brand accent is used sparingly: primary action, selection and
//   focus. The icon's orange-red accent is deliberately not used in the UI; it
//   would collide with the reject rose and the keeper amber.
// - Amber is reserved for keeper selection and star ratings; the score colours
//   and error/success colours keep their meaning.

/** Neutral canvas pedestal. */
val StudioCanvas = Color(0xFF0D0D0D)

/** Panel & readout shell background (navigation sidebar, details pane). */
val StudioPanel = Color(0xFF151515)

/** Hardware control block card fill & telemetry tiles. */
val StudioCard = Color(0xFF1E1E1E)

/** Structural hairline border divider (1dp). */
val StudioBorder = Color(0xFF313131)

/** Active border / highlighted hairline divider (1dp). */
val StudioBorderActive = Color(0xFF484848)

// ── Brand accent (lavender) ───────────────────────────────────────────────
/** Primary action, active frame border, selection and focus. */
val BrandAccent = Color(0xFFC4B8FF)

/** Content colour on [BrandAccent] fills (7.2:1). */
val BrandOnAccent = Color(0xFF2D2570)

/** Peaking telemetry, glowing reticles and readout highlights. */
val BrandAccentGlow = Color(0xFFDAD3FF)

/** Tonal accent fill for filled-tonal buttons at rest. */
val BrandAccentTonal = Color(0x29C4B8FF)

/** Tonal accent fill behind active navigation destinations and rail indicators. */
val BrandAccentNav = Color(0x1FC4B8FF)

/** Text and icon colour on tonal accent fills. */
val BrandAccentText = Color(0xFFE4DFFF)

/** Deep indigo for chosen/active chip fills that carry white content. */
val BrandAccentDeep = Color(0xFF4A3FA6)

/** Deeper indigo container for the tertiary role. */
val BrandAccentDeeper = Color(0xFF362D80)

// ── Curatorial Amber (Keeper Triage & Ratings) ───────────────────────────
/** Keeper selection, star ratings, and curatorial actions. */
val CurationAmber = Color(0xFFF59E0B)

/** Tonal amber fill behind curatorial actions. */
val CurationAmberTonal = Color(0x24F59E0B)

// ── High-Luminance Micro-Telemetry ────────────────────────────────────────
/** Good / passing value on a 2dp score bar or 6dp dot (Emerald-400). */
val ScoreGood = Color(0xFF34D399)

/** Middling value on a 2dp score bar or 6dp dot (Amber-400). */
val ScoreWarn = Color(0xFFFBBF24)

/** Poor value on a 2dp score bar or 6dp dot; reject action tint (Rose-400). */
val ScoreBad = Color(0xFFF87171)

// Standard semantic fallbacks
val ErrorRed = Color(0xFFEF4444)
val SuccessGreen = Color(0xFF22C55E)
val WarningAmber = Color(0xFFF59E0B)
