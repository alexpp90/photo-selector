package com.photoselectortoolbox.domain.learn

import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.domain.analysis.PhotoCoachEngineImpl
import com.photoselectortoolbox.domain.format.ExifFormatter
import java.util.Locale

enum class CardKind {
    DID_WELL,
    TRY_NEXT,
}

data class LearnCard(
    val id: String,
    val kind: CardKind,
    val title: String,
    val whatHappened: String,
    val whyItMatters: String,
    val tryNextTime: String,
    val lessonId: String? = null,
    val overlayType: String? = null,
    val geometryJson: String? = null,
    val priority: Double = 0.0,
)

data class GuessFirstChallenge(
    val prompt: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String,
)

data class CardGenerationResult(
    val didWellCards: List<LearnCard>,
    val tryNextCard: LearnCard?,
    val guessFirstChallenge: GuessFirstChallenge,
)

object CardGenerator {

    private const val MIN_CONFIDENCE = 0.55
    private const val MAX_DID_WELL_CARDS = 2

    fun generateFromEntities(
        entities: List<TechniqueFactEntity>,
        exif: ExifData? = null,
        scanResult: ScanResult? = null,
        isRawOrHasRawCompanion: Boolean = false,
        profile: CameraProfile = CameraProfiler.profile(exif),
    ): CardGenerationResult {
        val facts = entities.map {
            TechniqueFact(
                kind = it.kind,
                value = it.value,
                confidence = it.confidence,
                geometryJson = it.geometryJson,
            )
        }
        return generate(
            facts = facts,
            exif = exif,
            scanResult = scanResult,
            isRawOrHasRawCompanion = isRawOrHasRawCompanion,
            profile = profile,
            // The photo's URI: its "Guess first" options keep one order for it.
            seed = entities.firstOrNull()?.uri.orEmpty(),
        )
    }

    fun generate(
        facts: List<TechniqueFact>,
        exif: ExifData? = null,
        scanResult: ScanResult? = null,
        isRawOrHasRawCompanion: Boolean = false,
        /** The camera's profile: a "try next time" names only a control it has (SD-2b, C2). */
        profile: CameraProfile = CameraProfiler.profile(exif),
        /** Fixes the order of the "Guess first" options for one photo (its URI). */
        seed: String = "",
    ): CardGenerationResult {
        fun advice(finding: CoachFinding, safeShutter: String? = null, ev: String? = null) =
            AdviceCatalog.coach(finding, profile, safeShutter, ev).orEmpty()
        val eligibleFacts = facts.filter { it.confidence >= MIN_CONFIDENCE }
        val didWellCandidates = mutableListOf<LearnCard>()
        val tryNextCandidates = mutableListOf<LearnCard>()

        for (fact in eligibleFacts) {
            when (fact.kind) {
                "light.hour" -> when (fact.value) {
                    "golden_hour" -> didWellCandidates.add(
                        LearnCard(
                            id = "card_golden_hour",
                            kind = CardKind.DID_WELL,
                            title = "Captured in Golden Hour Light",
                            whatHappened = "You photographed while the sun was low near the horizon (0° to +6° elevation), giving warm, directional light.",
                            whyItMatters = "Low-angle sunlight creates soft, elongated shadows and warm highlights that add dimension without harsh contrast.",
                            tryNextTime = "Experiment with side-lighting or backlighting during the last 20 minutes before sunset for rim-lit subjects.",
                            lessonId = "light.golden_hour",
                            priority = fact.confidence * 1.35,
                        )
                    )
                    "blue_hour" -> didWellCandidates.add(
                        LearnCard(
                            id = "card_blue_hour",
                            kind = CardKind.DID_WELL,
                            title = "Atmospheric Blue Hour Timing",
                            whatHappened = "This frame was captured during civil twilight (-6° to -0.8° solar elevation).",
                            whyItMatters = "During blue hour, ambient sky luminance balances naturally with artificial city or interior lights.",
                            tryNextTime = advice(CoachFinding.BLUE_HOUR),
                            lessonId = "light.golden_hour",
                            priority = fact.confidence * 1.30,
                        )
                    )
                }

                "composition.horizon" -> when (fact.value) {
                    "level" -> didWellCandidates.add(
                        LearnCard(
                            id = "card_horizon_level",
                            kind = CardKind.DID_WELL,
                            title = "Crisp, Level Horizon",
                            whatHappened = "The detected horizon line is level within 0.5°.",
                            whyItMatters = "Even a 1° tilt on water or open landscapes immediately feels unstable to the viewer's eye.",
                            tryNextTime = "Try placing a level horizon near the upper or lower third rather than dead-center to emphasize sky or foreground.",
                            lessonId = "composition.horizon",
                            overlayType = "horizon",
                            geometryJson = fact.geometryJson,
                            priority = fact.confidence * 1.15,
                        )
                    )
                    "slight_tilt", "tilted" -> tryNextCandidates.add(
                        LearnCard(
                            id = "card_horizon_tilt",
                            kind = CardKind.TRY_NEXT,
                            title = "Straighten the Horizon Line",
                            whatHappened = "The detected horizon line tilts noticeably across the frame.",
                            whyItMatters = "Unintentional horizon tilt distracts from the subject and makes scenes feel off-balance.",
                            tryNextTime = advice(CoachFinding.TILTED_HORIZON),
                            lessonId = "composition.horizon",
                            overlayType = "horizon",
                            geometryJson = fact.geometryJson,
                            priority = fact.confidence * 1.45,
                        )
                    )
                }

                "composition.subject_placement" -> when (fact.value) {
                    "thirds_aligned" -> didWellCandidates.add(
                        LearnCard(
                            id = "card_subject_thirds",
                            kind = CardKind.DID_WELL,
                            title = "Balanced Thirds Placement",
                            whatHappened = "Your primary subject sits right near a Rule-of-Thirds intersection.",
                            whyItMatters = "Off-center placement gives the subject visual breathing room and lets the eye travel through the scene.",
                            tryNextTime = "Leave the open two-thirds of the frame in the direction your subject is looking or moving.",
                            lessonId = "composition.rule_of_thirds",
                            overlayType = "subject_thirds",
                            geometryJson = fact.geometryJson,
                            priority = fact.confidence * 1.30,
                        )
                    )
                    "edge_crowded" -> tryNextCandidates.add(
                        LearnCard(
                            id = "card_subject_edge",
                            kind = CardKind.TRY_NEXT,
                            title = "Give the Subject Edge Breathing Room",
                            whatHappened = "The main subject's bounding box presses closely against the edge of the frame.",
                            whyItMatters = "Subjects touching the frame border create visual tension and can feel accidentally clipped.",
                            tryNextTime = "Step back slightly or reframe to place the subject closer to a Rule-of-Thirds intersection.",
                            lessonId = "composition.rule_of_thirds",
                            overlayType = "subject_thirds",
                            geometryJson = fact.geometryJson,
                            priority = fact.confidence * 1.35,
                        )
                    )
                    "off_center" -> tryNextCandidates.add(
                        LearnCard(
                            id = "card_subject_reframe",
                            kind = CardKind.TRY_NEXT,
                            title = "Try a Rule-of-Thirds Crop",
                            whatHappened = "The main subject sits between the center and the thirds grid without strong bilateral symmetry.",
                            whyItMatters = "Deliberately anchoring the subject on a power point or dead-center makes the composition feel intentional.",
                            tryNextTime = "Use the 'What if?' crop preview to see how aligning the subject with a power point tightens the frame.",
                            lessonId = "composition.rule_of_thirds",
                            overlayType = "subject_thirds",
                            geometryJson = fact.geometryJson,
                            priority = fact.confidence * 1.15,
                        )
                    )
                }

                "composition.leading_lines" -> if (fact.value == "converging_to_subject") {
                    didWellCandidates.add(
                        LearnCard(
                            id = "card_leading_lines",
                            kind = CardKind.DID_WELL,
                            title = "Leading Lines Draw the Eye",
                            whatHappened = "Strong linear edges in the scene converge directly toward your subject.",
                            whyItMatters = "Converging lines create depth on a 2D screen and guide the viewer's gaze straight to the focal point.",
                            tryNextTime = "Lower your camera angle near roads, paths, or railings to exaggerate perspective convergence.",
                            lessonId = "composition.leading_lines",
                            overlayType = "leading_lines",
                            geometryJson = fact.geometryJson,
                            priority = fact.confidence * 1.25,
                        )
                    )
                }

                "depth.background_separation" -> when (fact.value) {
                    "strong_isolation" -> didWellCandidates.add(
                        LearnCard(
                            id = "card_bg_separation",
                            kind = CardKind.DID_WELL,
                            title = "Clean Subject-Background Separation",
                            whatHappened = "Micro-contrast on the subject is more than 2.2x sharper than the surrounding background.",
                            whyItMatters = "Softening background detail eliminates visual clutter so the subject stands out immediately.",
                            tryNextTime = "Increase the distance between your subject and the background to get even smoother separation.",
                            lessonId = "depth.aperture_story",
                            priority = fact.confidence * 1.28,
                        )
                    )
                }

                "composition.simplicity" -> when (fact.value) {
                    "clean" -> didWellCandidates.add(
                        LearnCard(
                            id = "card_simplicity_clean",
                            kind = CardKind.DID_WELL,
                            title = "Uncluttered Background Simplicity",
                            whatHappened = "The periphery of the frame has low competing edge density.",
                            whyItMatters = "Minimal background distractions keep attention locked on your subject.",
                            tryNextTime = "Keep scanning the four corners of the viewfinder before shooting to maintain clean edges.",
                            lessonId = "composition.simplicity",
                            priority = fact.confidence * 1.10,
                        )
                    )
                    "busy" -> tryNextCandidates.add(
                        LearnCard(
                            id = "card_simplicity_busy",
                            kind = CardKind.TRY_NEXT,
                            title = "Simplify the Background",
                            whatHappened = "High edge density around the edges competes with the center of interest.",
                            whyItMatters = "Busy backgrounds pull the viewer's eye away from the main subject.",
                            tryNextTime = advice(CoachFinding.BUSY_BACKGROUND),
                            lessonId = "composition.simplicity",
                            priority = fact.confidence * 1.20,
                        )
                    )
                }

                "composition.symmetry" -> if (fact.value == "vertical" || fact.value == "horizontal") {
                    didWellCandidates.add(
                        LearnCard(
                            id = "card_symmetry_${fact.value}",
                            kind = CardKind.DID_WELL,
                            title = "Intentional ${fact.value.replaceFirstChar { it.uppercase() }} Symmetry",
                            whatHappened = "Both halves of the frame mirror each other closely across the ${fact.value} axis.",
                            whyItMatters = "Strong bilateral symmetry creates a calm, graphic, architectural sense of order.",
                            tryNextTime = "Stand dead-center on the symmetry axis so perspective lines meet at the exact midpoint.",
                            lessonId = "composition.symmetry",
                            overlayType = "symmetry",
                            geometryJson = fact.geometryJson,
                            priority = fact.confidence * 1.22,
                        )
                    )
                }

                "exposure.tonal_key" -> when (fact.value) {
                    "silhouette" -> didWellCandidates.add(
                        LearnCard(
                            id = "card_tonal_silhouette",
                            kind = CardKind.DID_WELL,
                            title = "Dramatic Backlit Silhouette",
                            whatHappened = "Strong contrast between a dark foreground shape and bright background created a graphic silhouette.",
                            whyItMatters = "Silhouettes distill a subject down to its pure outline and gesture.",
                            tryNextTime = "Separate overlapping limbs or shapes against the bright sky so the silhouette outline stays unmistakable.",
                            lessonId = "light.direction",
                            priority = fact.confidence * 1.25,
                        )
                    )
                    "high_key", "low_key" -> didWellCandidates.add(
                        LearnCard(
                            id = "card_tonal_${fact.value}",
                            kind = CardKind.DID_WELL,
                            title = if (fact.value == "high_key") "Airy High-Key Tonal Range" else "Moody Low-Key Tonal Range",
                            whatHappened = if (fact.value == "high_key") {
                                "Bright midtones and open shadows create a light, high-key atmosphere without blowing out the subject."
                            } else {
                                "Deep, controlled shadows with selective highlights create a dramatic low-key mood."
                            },
                            whyItMatters = "Deliberate high-key or low-key exposure conveys mood far more effectively than an average middle-gray meter reading.",
                            tryNextTime = advice(CoachFinding.TONAL_KEY),
                            lessonId = "light.tonal_key",
                            priority = fact.confidence * 1.18,
                        )
                    )
                }

                "exposure.clipping" -> when (fact.value) {
                    "highlights_clipped", "both_clipped" -> tryNextCandidates.add(
                        LearnCard(
                            id = "card_exposure_clipping",
                            kind = CardKind.TRY_NEXT,
                            title = "Protect Non-Specular Highlights",
                            whatHappened = "Broad bright regions in the frame reached maximum luminance clipping.",
                            whyItMatters = if (isRawOrHasRawCompanion) {
                                "While 1–2 EV of highlight detail may still be recoverable in your RAW file, heavy clipping flattens bright textures."
                            } else {
                                "In 8-bit JPEGs, clipped highlight regions lose all texture and cannot be recovered in post-processing."
                            },
                            tryNextTime = advice(CoachFinding.HIGHLIGHTS_CLIPPED, ev = "-0.7"),
                            lessonId = "exposure.histogram_clipping",
                            priority = fact.confidence * 1.50,
                        )
                    )
                    "shadows_clipped" -> tryNextCandidates.add(
                        LearnCard(
                            id = "card_shadow_clipping",
                            kind = CardKind.TRY_NEXT,
                            title = "Lift Blocked Shadow Detail",
                            whatHappened = "Over 5% of the frame's shadow regions clipped to pure black.",
                            whyItMatters = "Crushed shadows lose texture and introduce color noise when lifted aggressively in post.",
                            tryNextTime = advice(CoachFinding.SHADOWS_CLIPPED, ev = "+0.7"),
                            lessonId = "exposure.histogram_clipping",
                            priority = fact.confidence * 1.25,
                        )
                    )
                }

                "intent.shutter" -> when (fact.value) {
                    "action_freeze" -> didWellCandidates.add(
                        LearnCard(
                            id = "card_action_freeze",
                            kind = CardKind.DID_WELL,
                            title = "Fast Action-Freezing Shutter",
                            whatHappened = "A fast shutter speed (${exif?.shutterSpeed?.let { ExifFormatter.shutterSpeed(it) } ?: "≤ 1/1000s"}) froze motion crisply.",
                            whyItMatters = "High shutter speeds capture split-second gestures, water droplets, or wildlife without motion blur.",
                            tryNextTime = advice(CoachFinding.ACTION_FREEZE),
                            lessonId = "motion.shutter_intent",
                            priority = fact.confidence * 1.15,
                        )
                    )
                    "long_exposure" -> didWellCandidates.add(
                        LearnCard(
                            id = "card_long_exposure",
                            kind = CardKind.DID_WELL,
                            title = "Creative Long Exposure",
                            whatHappened = "A slow shutter speed (${exif?.shutterSpeed?.let { ExifFormatter.shutterSpeed(it) } ?: "≥ 0.5s"}) recorded movement over time.",
                            whyItMatters = "Long exposures turn moving water, clouds, or light trails into smooth, painterly streaks.",
                            tryNextTime = advice(CoachFinding.LONG_EXPOSURE),
                            lessonId = "motion.shutter_intent",
                            priority = fact.confidence * 1.18,
                        )
                    )
                }

                "intent.exposure_bias" -> didWellCandidates.add(
                    LearnCard(
                        id = "card_ev_bias",
                        kind = CardKind.DID_WELL,
                        title = "Deliberate Exposure Control",
                        whatHappened = "You controlled exposure settings deliberately to preserve scene tones.",
                        whyItMatters = "Reflective meters aim for 18% middle gray; deliberate exposure control keeps snow white, shadows deep, and highlights safe.",
                        tryNextTime = advice(CoachFinding.EXPOSURE_BIAS),
                        lessonId = "exposure.ev_metering",
                        priority = fact.confidence * 1.12,
                    )
                )
            }
        }

        // Check hand-holding guideline from EXIF + sharpness if not already added
        val effectiveFocal = PhotoCoachEngineImpl.computeEffectiveFocalLength(exif)
        val shutter = exif?.shutterSpeed
        val sharpness = scanResult?.sharpnessScore
        // Drones fly on a gimbal and action cams stabilise electronically: no hand-holding card.
        val handHeld = profile.cameraClass != CameraClass.DRONE && profile.cameraClass != CameraClass.ACTION
        if (handHeld && effectiveFocal != null && shutter != null && shutter > 0.0) {
            val safeSpeed = 1.0 / effectiveFocal
            if (shutter > safeSpeed * 1.05 && sharpness != null && sharpness < 38.0) {
                tryNextCandidates.add(
                    LearnCard(
                        id = "card_handholding_guideline",
                        kind = CardKind.TRY_NEXT,
                        title = "Use Faster Shutter for ${ExifFormatter.focalLength(effectiveFocal)} (35mm-eq)",
                        whatHappened = "Shutter speed (${ExifFormatter.shutterSpeed(shutter)}) was slower than the 1/${effectiveFocal.toInt()}s hand-holding guideline and sharpness is low (${sharpness.toInt()}).",
                        whyItMatters = "Longer effective focal lengths magnify angular hand movement, requiring proportionally faster shutter speeds.",
                        tryNextTime = advice(CoachFinding.SLOW_AND_SOFT, safeShutter = ExifFormatter.shutterSpeed(safeSpeed)),
                        lessonId = "motion.reciprocal_rule",
                        priority = 1.55,
                    )
                )
            } else if (shutter > safeSpeed * 1.2 && sharpness != null && sharpness >= 45.0) {
                didWellCandidates.add(
                    LearnCard(
                        id = "card_steady_handholding",
                        kind = CardKind.DID_WELL,
                        title = "Sharp Below the Hand-Holding Guideline",
                        whatHappened = "You achieved crisp detail (${sharpness.toInt()}) at ${ExifFormatter.shutterSpeed(shutter)}, slower than the 1/${effectiveFocal.toInt()}s guideline.",
                        whyItMatters = "Steady bracing or optical stabilization let you use a slower shutter and keep ISO lower.",
                        tryNextTime = "Take a 2–3 frame burst when shooting slower than the hand-holding speed so at least one frame catches the steadiest moment.",
                        lessonId = "motion.reciprocal_rule",
                        priority = 1.24,
                    )
                )
            }
        }

        val selectedDidWell = didWellCandidates
            .sortedByDescending { it.priority }
            .distinctBy { it.id }
            .take(MAX_DID_WELL_CARDS)

        val selectedTryNext = tryNextCandidates
            .sortedByDescending { it.priority }
            .firstOrNull()

        val guessFirst = buildGuessFirstChallenge(selectedDidWell, selectedTryNext).shuffled(seed)
        return CardGenerationResult(
            didWellCards = selectedDidWell,
            tryNextCard = selectedTryNext,
            guessFirstChallenge = guessFirst,
        )
    }

    /**
     * [this] with its options in an order fixed by [seed] and the right answer: it was always the
     * first option. The same photo keeps the same order, so a recomposition never reshuffles it.
     */
    private fun GuessFirstChallenge.shuffled(seed: String): GuessFirstChallenge {
        val order = options.indices.shuffled(kotlin.random.Random("$seed#${options[correctIndex]}".hashCode()))
        return copy(options = order.map { options[it] }, correctIndex = order.indexOf(correctIndex))
    }

    private fun buildGuessFirstChallenge(
        didWell: List<LearnCard>,
        tryNext: LearnCard?,
    ): GuessFirstChallenge {
        val primaryCard = tryNext ?: didWell.firstOrNull()
        if (primaryCard == null) {
            return GuessFirstChallenge(
                prompt = "Before revealing the analysis: what stands out most in this photograph's technique?",
                options = listOf(
                    "Balanced exposure and steady shutter speed",
                    "Severe highlight clipping across the sky",
                    "Extreme telephoto motion blur",
                ),
                correctIndex = 0,
                explanation = "This frame has balanced technical metrics without severe clipping or motion blur.",
            )
        }

        val DistractorPool = listOf(
            "The subject is placed on a Rule-of-Thirds intersection",
            "The horizon line has a noticeable tilt",
            "Warm golden-hour light shapes the scene",
            "Broad non-specular highlights are clipped",
            "Shutter speed was slower than the hand-holding speed for this lens",
            "Strong bilateral symmetry anchors the frame",
        )
        val correctOption = primaryCard.title
        val distractors = DistractorPool
            .filter { !it.equals(correctOption, ignoreCase = true) && !correctOption.contains(it.take(12), ignoreCase = true) }
            .take(2)
        val options = listOf(correctOption) + distractors
        return GuessFirstChallenge(
            prompt = "Look closely at the photo first — which observation best matches this frame?",
            options = options,
            correctIndex = 0,
            explanation = "${primaryCard.whatHappened} ${primaryCard.whyItMatters}",
        )
    }
}
