package com.photoselectortoolbox.domain.learn

import com.photoselectortoolbox.R
import com.photoselectortoolbox.domain.text.UiText
import com.photoselectortoolbox.domain.text.text

/** A finding of the Photo Coach or of an Explore card whose advice names a camera control. */
enum class CoachFinding {
    /** Slower than the hand-holding guideline, and soft. */
    SLOW_AND_SOFT,
    /** Slower than the hand-holding guideline, not scanned for sharpness. */
    SLOW_UNSCORED,
    /** Soft at a fast enough shutter: focus, motion or depth of field. */
    SOFT_AT_FAST_SHUTTER,
    HIGH_ISO_NOISE,
    /** Highlights and shadows both clipped. */
    SCENE_CONTRAST,
    HIGHLIGHTS_CLIPPED,
    SHADOWS_CLIPPED,
    BUSY_BACKGROUND,
    /** A deliberate high-key or low-key tonal range. */
    TONAL_KEY,
    /** Exposure biased on purpose. */
    EXPOSURE_BIAS,
    /** Shot at blue hour: dim light, long exposures. */
    BLUE_HOUR,
    /** A tilted horizon. */
    TILTED_HORIZON,
    /** A fast exposure froze motion. */
    ACTION_FREEZE,
    /** A deliberate long exposure. */
    LONG_EXPOSURE,
}

/**
 * Advice that fits the camera (shoot debrief SD-2b, C2): no line names a control the camera class
 * lacks — no aperture advice to a phone, drone or action cam, no hand-holding rule to a drone, no
 * camera control at all to an unknown camera, which gets image-based remarks only. The debrief's
 * "next time" lines are the photo researcher's catalog (`SD2B_RESEARCH.md` §2, 2026-11-13, at most
 * 12 words each); the Photo Coach's and the Explore cards' recommendations follow the same rule.
 * Which words name which control is pinned by `AdviceCatalogTest`.
 */
object AdviceCatalog {

    /** At least this share of the slow frames soft (or unscored) before the hand-holding advice. */
    const val HAND_HOLD_ADVICE_SHARE = 0.25

    private val CONTROL_CLASSES = setOf(CameraClass.INTERCHANGEABLE, CameraClass.COMPACT)

    /**
     * The "Next time" lines for a debrief group's [focus] (one, sometimes a second), or none.
     * [mostlyRaw]: most of the group is RAW, whose highlights a RAW developer can recover — the
     * camera-control highlight line is skipped then. [slowSoftShare]: of the frames slower than
     * the hand-holding guideline, the share that came out soft or unscored.
     */
    fun focus(
        focus: DebriefMetric,
        cameraClass: CameraClass,
        decidedBy: DecidedBy = DecidedBy.UNKNOWN,
        mostlyRaw: Boolean = false,
        slowSoftShare: Double? = null,
    ): List<UiText> = when (focus) {
        DebriefMetric.HIGHLIGHTS_KEPT -> when (cameraClass) {
            CameraClass.INTERCHANGEABLE, CameraClass.COMPACT ->
                if (mostlyRaw) emptyList() else listOf(text(R.string.learn_advice_001))
            CameraClass.PHONE -> listOf(text(R.string.learn_advice_002))
            CameraClass.DRONE -> listOf(text(R.string.learn_advice_003))
            CameraClass.ACTION -> listOf(text(R.string.learn_advice_004))
            CameraClass.UNKNOWN -> listOf(text(R.string.learn_advice_005))
        }
        DebriefMetric.LEVEL_HORIZON -> listOf(
            when (cameraClass) {
                CameraClass.INTERCHANGEABLE, CameraClass.COMPACT -> text(R.string.learn_advice_006)
                CameraClass.PHONE -> text(R.string.learn_advice_007)
                CameraClass.DRONE -> text(R.string.learn_advice_008)
                CameraClass.ACTION -> text(R.string.learn_advice_009)
                CameraClass.UNKNOWN -> text(R.string.learn_advice_010)
            },
        )
        DebriefMetric.SUBJECT_OFF_EDGE -> listOfNotNull(
            text(R.string.learn_advice_011),
            when (cameraClass) {
                CameraClass.DRONE -> text(R.string.learn_advice_012)
                CameraClass.ACTION -> text(R.string.learn_advice_013)
                else -> null
            },
        )
        DebriefMetric.HAND_HOLD -> when {
            cameraClass !in CONTROL_CLASSES -> emptyList()
            slowSoftShare != null && slowSoftShare < HAND_HOLD_ADVICE_SHARE ->
                listOf(text(R.string.learn_advice_014))
            else -> listOf(
                when (decidedBy) {
                    DecidedBy.PHOTOGRAPHER -> text(R.string.learn_advice_015)
                    DecidedBy.CAMERA -> text(R.string.learn_advice_016)
                    DecidedBy.UNKNOWN -> text(R.string.learn_advice_017)
                },
                text(R.string.learn_advice_018),
            )
        }
        DebriefMetric.SHARP_FRAMES -> listOf(
            when (cameraClass) {
                CameraClass.INTERCHANGEABLE, CameraClass.COMPACT -> text(R.string.learn_advice_019)
                CameraClass.PHONE -> text(R.string.learn_advice_020)
                CameraClass.DRONE -> text(R.string.learn_advice_021)
                CameraClass.ACTION -> text(R.string.learn_advice_022)
                CameraClass.UNKNOWN -> text(R.string.learn_advice_023)
            },
        )
    }

    /**
     * The Photo Coach's or an Explore card's recommendation for [finding] on a camera of
     * [profile], or null where it has none to give (a drone or an action cam is not hand-held).
     * [safeShutter] is the hand-holding speed as written ("1/250s"); [ev] the compensation to
     * suggest, signed ("-0.7").
     */
    fun coach(finding: CoachFinding, profile: CameraProfile, safeShutter: String? = null, ev: String? = null): UiText? {
        val c = profile.cameraClass
        val full = c in CONTROL_CLASSES
        return when (finding) {
            CoachFinding.SLOW_AND_SOFT -> when (c) {
                CameraClass.INTERCHANGEABLE, CameraClass.COMPACT -> when (profile.decidedBy) {
                    DecidedBy.PHOTOGRAPHER -> text(R.string.learn_advice_024, safeShutter ?: text(R.string.learn_advice_the_guideline))
                    DecidedBy.CAMERA -> text(R.string.learn_advice_025, safeShutter ?: text(R.string.learn_advice_a_faster_one))
                    DecidedBy.UNKNOWN -> text(R.string.learn_advice_026, safeShutter ?: text(R.string.learn_advice_the_guideline))
                }
                CameraClass.PHONE -> text(R.string.learn_advice_027)
                CameraClass.UNKNOWN -> text(R.string.learn_advice_028)
                CameraClass.DRONE, CameraClass.ACTION -> null
            }
            CoachFinding.SLOW_UNSCORED -> when (c) {
                CameraClass.INTERCHANGEABLE, CameraClass.COMPACT -> text(R.string.learn_advice_029, safeShutter ?: text(R.string.learn_advice_the_guideline))
                CameraClass.PHONE -> text(R.string.learn_advice_030)
                CameraClass.UNKNOWN -> text(R.string.learn_advice_031)
                CameraClass.DRONE, CameraClass.ACTION -> null
            }
            CoachFinding.SOFT_AT_FAST_SHUTTER -> when (c) {
                CameraClass.INTERCHANGEABLE, CameraClass.COMPACT -> text(R.string.learn_advice_032)
                CameraClass.PHONE -> text(R.string.learn_advice_033)
                CameraClass.DRONE -> text(R.string.learn_advice_034)
                CameraClass.ACTION -> text(R.string.learn_advice_035)
                CameraClass.UNKNOWN -> text(R.string.learn_advice_036)
            }
            CoachFinding.HIGH_ISO_NOISE -> when (c) {
                CameraClass.INTERCHANGEABLE, CameraClass.COMPACT -> text(R.string.learn_advice_037)
                CameraClass.PHONE -> text(R.string.learn_advice_038)
                CameraClass.DRONE -> text(R.string.learn_advice_039)
                CameraClass.ACTION -> text(R.string.learn_advice_040)
                CameraClass.UNKNOWN -> text(R.string.learn_advice_041)
            }
            CoachFinding.SCENE_CONTRAST -> when (c) {
                CameraClass.INTERCHANGEABLE -> text(R.string.learn_advice_042)
                CameraClass.COMPACT -> text(R.string.learn_advice_043)
                CameraClass.PHONE -> text(R.string.learn_advice_044)
                CameraClass.DRONE -> text(R.string.learn_advice_045)
                CameraClass.ACTION -> text(R.string.learn_advice_046)
                CameraClass.UNKNOWN -> text(R.string.learn_advice_047)
            }
            CoachFinding.HIGHLIGHTS_CLIPPED -> when (c) {
                CameraClass.INTERCHANGEABLE, CameraClass.COMPACT -> text(R.string.learn_advice_048, ev ?: "-0.7")
                else -> focus(DebriefMetric.HIGHLIGHTS_KEPT, c).first()
            }
            CoachFinding.SHADOWS_CLIPPED -> when (c) {
                CameraClass.INTERCHANGEABLE, CameraClass.COMPACT -> text(R.string.learn_advice_049, ev ?: "+0.7")
                CameraClass.PHONE -> text(R.string.learn_advice_050)
                CameraClass.DRONE -> text(R.string.learn_advice_051)
                CameraClass.ACTION, CameraClass.UNKNOWN -> text(R.string.learn_advice_052)
            }
            CoachFinding.BUSY_BACKGROUND ->
                if (full) {
                    text(R.string.learn_advice_053)
                } else {
                    text(R.string.learn_advice_054)
                }
            CoachFinding.TONAL_KEY -> when (c) {
                CameraClass.INTERCHANGEABLE, CameraClass.COMPACT, CameraClass.DRONE ->
                    text(R.string.learn_advice_055)
                CameraClass.PHONE -> text(R.string.learn_advice_056)
                CameraClass.ACTION, CameraClass.UNKNOWN -> text(R.string.learn_advice_057)
            }
            CoachFinding.BLUE_HOUR ->
                if (Control.ISO in profile.controls) {
                    text(R.string.learn_advice_058)
                } else {
                    text(R.string.learn_advice_059)
                }
            CoachFinding.TILTED_HORIZON -> focus(DebriefMetric.LEVEL_HORIZON, c).first()
            CoachFinding.ACTION_FREEZE -> when (c) {
                CameraClass.INTERCHANGEABLE, CameraClass.COMPACT ->
                    text(R.string.learn_advice_060)
                CameraClass.PHONE -> text(R.string.learn_advice_061)
                else -> text(R.string.learn_advice_062)
            }
            CoachFinding.LONG_EXPOSURE -> when (c) {
                CameraClass.INTERCHANGEABLE, CameraClass.COMPACT ->
                    text(R.string.learn_advice_063)
                CameraClass.PHONE -> text(R.string.learn_advice_064)
                CameraClass.DRONE -> text(R.string.learn_advice_065)
                CameraClass.ACTION, CameraClass.UNKNOWN -> text(R.string.learn_advice_066)
            }
            CoachFinding.EXPOSURE_BIAS ->
                if (Control.EXPOSURE_COMPENSATION in profile.controls) {
                    text(R.string.learn_advice_067)
                } else {
                    text(R.string.learn_advice_068)
                }
        }
    }
}
