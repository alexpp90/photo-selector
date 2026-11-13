package com.photoselectortoolbox.domain.learn

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
    ): List<String> = when (focus) {
        DebriefMetric.HIGHLIGHTS_KEPT -> when (cameraClass) {
            CameraClass.INTERCHANGEABLE, CameraClass.COMPACT ->
                if (mostlyRaw) emptyList() else listOf("Dial exposure compensation down when bright areas turn pure white.")
            CameraClass.PHONE -> listOf("Tap the bright area, then slide brightness down.")
            CameraClass.DRONE -> listOf("Lower the exposure in the app when skies turn white.")
            CameraClass.ACTION -> listOf("Shoot in softer light; bright skies often turn pure white.")
            CameraClass.UNKNOWN -> listOf("Check bright areas on screen; they may be losing detail.")
        }
        DebriefMetric.LEVEL_HORIZON -> listOf(
            when (cameraClass) {
                CameraClass.INTERCHANGEABLE, CameraClass.COMPACT -> "Turn on the grid or electronic level."
                CameraClass.PHONE -> "Turn on the camera grid and follow a straight line."
                CameraClass.DRONE -> "Check the horizon on screen, or straighten it when editing."
                CameraClass.ACTION -> "Mount the camera level, or straighten it when editing."
                CameraClass.UNKNOWN -> "Straighten tilted horizons when editing."
            },
        )
        DebriefMetric.SUBJECT_OFF_EDGE -> listOfNotNull(
            "Leave a little space between your subject and the frame edge.",
            when (cameraClass) {
                CameraClass.DRONE -> "Fly a little farther back so the subject has room."
                CameraClass.ACTION -> "Check framing; wide lenses push subjects toward the edges."
                else -> null
            },
        )
        DebriefMetric.HAND_HOLD -> when {
            cameraClass !in CONTROL_CLASSES -> emptyList()
            slowSoftShare != null && slowSoftShare < HAND_HOLD_ADVICE_SHARE ->
                listOf("Most slow frames stayed sharp; keep bracing like that.")
            else -> listOf(
                when (decidedBy) {
                    DecidedBy.PHOTOGRAPHER -> "You chose a slow shutter speed; go faster or brace yourself."
                    DecidedBy.CAMERA -> "The camera chose slow shutters; raise ISO or open the aperture."
                    DecidedBy.UNKNOWN -> "Use a faster shutter speed, or brace yourself."
                },
                "Brace your elbows against your body, or lean on something.",
            )
        }
        DebriefMetric.SHARP_FRAMES -> listOf(
            when (cameraClass) {
                CameraClass.INTERCHANGEABLE, CameraClass.COMPACT -> "Use a faster shutter and check focus lands on the subject."
                CameraClass.PHONE -> "Hold still, brace your elbows, and tap to focus."
                CameraClass.DRONE -> "Let the drone settle before pressing the shutter."
                CameraClass.ACTION -> "Shoot in brighter light and keep the camera still."
                CameraClass.UNKNOWN -> "Check focus and hold the camera steadier."
            },
        )
    }

    /**
     * The Photo Coach's or an Explore card's recommendation for [finding] on a camera of
     * [profile], or null where it has none to give (a drone or an action cam is not hand-held).
     * [safeShutter] is the hand-holding speed as written ("1/250s"); [ev] the compensation to
     * suggest, signed ("-0.7").
     */
    fun coach(finding: CoachFinding, profile: CameraProfile, safeShutter: String? = null, ev: String? = null): String? {
        val c = profile.cameraClass
        val full = c in CONTROL_CLASSES
        return when (finding) {
            CoachFinding.SLOW_AND_SOFT -> when (c) {
                CameraClass.INTERCHANGEABLE, CameraClass.COMPACT -> when (profile.decidedBy) {
                    DecidedBy.PHOTOGRAPHER -> "You chose a shutter slower than ${safeShutter ?: "the guideline"}: go faster or brace yourself."
                    DecidedBy.CAMERA -> "The camera chose a slow shutter: raise ISO or open the aperture to get ${safeShutter ?: "a faster one"}."
                    DecidedBy.UNKNOWN -> "Use a faster shutter speed (at least ${safeShutter ?: "the guideline"}), open the aperture, or raise ISO to reduce camera motion blur."
                }
                CameraClass.PHONE -> "Hold the phone steadier: brace your elbows, use night mode, or rest it on something."
                CameraClass.UNKNOWN -> "Hold the camera steadier, or rest it on something."
                CameraClass.DRONE, CameraClass.ACTION -> null
            }
            CoachFinding.SLOW_UNSCORED -> when (c) {
                CameraClass.INTERCHANGEABLE, CameraClass.COMPACT -> "Increase shutter speed to at least ${safeShutter ?: "the guideline"} or ensure optical stabilization is active."
                CameraClass.PHONE -> "Hold the phone still, or rest it on something."
                CameraClass.UNKNOWN -> "Hold the camera still, or rest it on something."
                CameraClass.DRONE, CameraClass.ACTION -> null
            }
            CoachFinding.SOFT_AT_FAST_SHUTTER -> when (c) {
                CameraClass.INTERCHANGEABLE, CameraClass.COMPACT -> "Verify autofocus point placement or stop down the aperture for greater depth of field."
                CameraClass.PHONE -> "Tap the subject to focus before you take the photo."
                CameraClass.DRONE -> "Let the drone settle, and check focus on screen."
                CameraClass.ACTION -> "Keep the camera still, and shoot in good light."
                CameraClass.UNKNOWN -> "Check where the focus landed."
            }
            CoachFinding.HIGH_ISO_NOISE -> when (c) {
                CameraClass.INTERCHANGEABLE, CameraClass.COMPACT -> "Widen the aperture or use a slower shutter (if stabilized) to capture more light and lower ISO."
                CameraClass.PHONE -> "Add light, or hold the phone still so night mode can work."
                CameraClass.DRONE -> "Use a lower ISO and a slower shutter while the drone hovers."
                CameraClass.ACTION -> "Shoot in brighter light; small sensors turn grainy in dim light."
                CameraClass.UNKNOWN -> "Shoot in brighter light to keep the grain down."
            }
            CoachFinding.SCENE_CONTRAST -> when (c) {
                CameraClass.INTERCHANGEABLE -> "Recommend bracketed HDR exposure (+/- 2 EV) or graduated neutral density (GND) filter."
                CameraClass.COMPACT -> "Use the camera's bracketing or HDR mode."
                CameraClass.PHONE -> "Turn on HDR in the camera app."
                CameraClass.DRONE -> "Use the drone's bracketing or HDR mode."
                CameraClass.ACTION -> "Use HDR mode if the camera has one, or softer light."
                CameraClass.UNKNOWN -> "Shoot in softer light; the scene was too contrasty for one frame."
            }
            CoachFinding.HIGHLIGHTS_CLIPPED -> when (c) {
                CameraClass.INTERCHANGEABLE, CameraClass.COMPACT -> "Apply negative EV compensation (${ev ?: "-0.7"} EV) or meter for the brightest important subject to protect highlight details."
                else -> focus(DebriefMetric.HIGHLIGHTS_KEPT, c).first()
            }
            CoachFinding.SHADOWS_CLIPPED -> when (c) {
                CameraClass.INTERCHANGEABLE, CameraClass.COMPACT -> "Apply positive EV compensation (${ev ?: "+0.7"} EV) or use fill light/reflector to lift shadow details."
                CameraClass.PHONE -> "Tap the dark area, then slide brightness up."
                CameraClass.DRONE -> "Raise the exposure in the app when shadows turn black."
                CameraClass.ACTION, CameraClass.UNKNOWN -> "Shoot with the light behind you, or add fill light."
            }
            CoachFinding.BUSY_BACKGROUND ->
                if (full) {
                    "Change your camera angle, move closer, or open the aperture to simplify competing background elements."
                } else {
                    "Change your angle or move closer to simplify competing background elements."
                }
            CoachFinding.TONAL_KEY -> when (c) {
                CameraClass.INTERCHANGEABLE, CameraClass.COMPACT, CameraClass.DRONE ->
                    "Use exposure compensation (+/- EV) so the camera's meter doesn't pull intentional tones back to middle gray."
                CameraClass.PHONE -> "Slide brightness after tapping, so the phone keeps the mood you see."
                CameraClass.ACTION, CameraClass.UNKNOWN -> "Check the screen: keep bright scenes bright and dark scenes dark."
            }
            CoachFinding.BLUE_HOUR ->
                if (Control.ISO in profile.controls) {
                    "Brace the camera or use a tripod at base ISO during blue hour to keep twilight gradients noise-free."
                } else {
                    "Brace the camera or rest it on something during blue hour to keep twilight smooth."
                }
            CoachFinding.TILTED_HORIZON -> focus(DebriefMetric.LEVEL_HORIZON, c).first()
            CoachFinding.ACTION_FREEZE -> when (c) {
                CameraClass.INTERCHANGEABLE, CameraClass.COMPACT ->
                    "Pair fast shutter speeds with continuous autofocus (AF-C) when tracking moving subjects."
                CameraClass.PHONE -> "Tap the moving subject so focus follows it."
                else -> "Keep the moving subject well inside the frame."
            }
            CoachFinding.LONG_EXPOSURE -> when (c) {
                CameraClass.INTERCHANGEABLE, CameraClass.COMPACT ->
                    "Use a 2-second self-timer or remote release on a tripod so pressing the shutter doesn't vibrate the camera."
                CameraClass.PHONE -> "Rest the phone on something steady and use the self-timer."
                CameraClass.DRONE -> "Let the drone hover still before a long exposure."
                CameraClass.ACTION, CameraClass.UNKNOWN -> "Rest the camera on something steady and use the self-timer."
            }
            CoachFinding.EXPOSURE_BIAS ->
                if (Control.EXPOSURE_COMPENSATION in profile.controls) {
                    "Check the live histogram in your viewfinder when dialing in EV compensation."
                } else {
                    "Check the screen's brightness against the scene before you shoot."
                }
        }
    }
}
