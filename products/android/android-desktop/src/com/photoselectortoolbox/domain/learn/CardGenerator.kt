package com.photoselectortoolbox.domain.learn

import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.domain.analysis.PhotoCoachEngineImpl
import com.photoselectortoolbox.domain.format.ExifFormatter
import java.util.Locale
import com.photoselectortoolbox.R
import com.photoselectortoolbox.domain.text.UiText
import com.photoselectortoolbox.domain.text.text

enum class CardKind {
    DID_WELL,
    TRY_NEXT,
}

data class LearnCard(
    val id: String,
    val kind: CardKind,
    val title: UiText,
    val whatHappened: UiText,
    val whyItMatters: UiText,
    val tryNextTime: UiText,
    val lessonId: String? = null,
    val overlayType: String? = null,
    val geometryJson: String? = null,
    val priority: Double = 0.0,
)

data class GuessFirstChallenge(
    val prompt: UiText,
    val options: List<UiText>,
    val correctIndex: Int,
    val explanation: UiText,
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
            AdviceCatalog.coach(finding, profile, safeShutter, ev) ?: UiText.Empty
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
                            title = text(R.string.learn_card_card_golden_hour_title),
                            whatHappened = text(R.string.learn_card_card_golden_hour_what_happened),
                            whyItMatters = text(R.string.learn_card_card_golden_hour_why_it_matters),
                            tryNextTime = text(R.string.learn_card_card_golden_hour_try_next_time),
                            lessonId = "light.golden_hour",
                            priority = fact.confidence * 1.35,
                        )
                    )
                    "blue_hour" -> didWellCandidates.add(
                        LearnCard(
                            id = "card_blue_hour",
                            kind = CardKind.DID_WELL,
                            title = text(R.string.learn_card_card_blue_hour_title),
                            whatHappened = text(R.string.learn_card_card_blue_hour_what_happened),
                            whyItMatters = text(R.string.learn_card_card_blue_hour_why_it_matters),
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
                            title = text(R.string.learn_card_card_horizon_level_title),
                            whatHappened = text(R.string.learn_card_card_horizon_level_what_happened),
                            whyItMatters = text(R.string.learn_card_card_horizon_level_why_it_matters),
                            tryNextTime = text(R.string.learn_card_card_horizon_level_try_next_time),
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
                            title = text(R.string.learn_card_card_horizon_tilt_title),
                            whatHappened = text(R.string.learn_card_card_horizon_tilt_what_happened),
                            whyItMatters = text(R.string.learn_card_card_horizon_tilt_why_it_matters),
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
                            title = text(R.string.learn_card_card_subject_thirds_title),
                            whatHappened = text(R.string.learn_card_card_subject_thirds_what_happened),
                            whyItMatters = text(R.string.learn_card_card_subject_thirds_why_it_matters),
                            tryNextTime = text(R.string.learn_card_card_subject_thirds_try_next_time),
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
                            title = text(R.string.learn_card_card_subject_edge_title),
                            whatHappened = text(R.string.learn_card_card_subject_edge_what_happened),
                            whyItMatters = text(R.string.learn_card_card_subject_edge_why_it_matters),
                            tryNextTime = text(R.string.learn_card_card_subject_edge_try_next_time),
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
                            title = text(R.string.learn_card_card_subject_reframe_title),
                            whatHappened = text(R.string.learn_card_card_subject_reframe_what_happened),
                            whyItMatters = text(R.string.learn_card_card_subject_reframe_why_it_matters),
                            tryNextTime = text(R.string.learn_card_card_subject_reframe_try_next_time),
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
                            title = text(R.string.learn_card_card_leading_lines_title),
                            whatHappened = text(R.string.learn_card_card_leading_lines_what_happened),
                            whyItMatters = text(R.string.learn_card_card_leading_lines_why_it_matters),
                            tryNextTime = text(R.string.learn_card_card_leading_lines_try_next_time),
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
                            title = text(R.string.learn_card_card_bg_separation_title),
                            whatHappened = text(R.string.learn_card_card_bg_separation_what_happened),
                            whyItMatters = text(R.string.learn_card_card_bg_separation_why_it_matters),
                            tryNextTime = text(R.string.learn_card_card_bg_separation_try_next_time),
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
                            title = text(R.string.learn_card_card_simplicity_clean_title),
                            whatHappened = text(R.string.learn_card_card_simplicity_clean_what_happened),
                            whyItMatters = text(R.string.learn_card_card_simplicity_clean_why_it_matters),
                            tryNextTime = text(R.string.learn_card_card_simplicity_clean_try_next_time),
                            lessonId = "composition.simplicity",
                            priority = fact.confidence * 1.10,
                        )
                    )
                    "busy" -> tryNextCandidates.add(
                        LearnCard(
                            id = "card_simplicity_busy",
                            kind = CardKind.TRY_NEXT,
                            title = text(R.string.learn_card_card_simplicity_busy_title),
                            whatHappened = text(R.string.learn_card_card_simplicity_busy_what_happened),
                            whyItMatters = text(R.string.learn_card_card_simplicity_busy_why_it_matters),
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
                            title = text(R.string.learn_card_card_symmetry_fact_value_title, fact.value.replaceFirstChar { it.uppercase() }),
                            whatHappened = text(R.string.learn_card_card_symmetry_fact_value_what_happened, fact.value),
                            whyItMatters = text(R.string.learn_card_card_symmetry_fact_value_why_it_matters),
                            tryNextTime = text(R.string.learn_card_card_symmetry_fact_value_try_next_time),
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
                            title = text(R.string.learn_card_card_tonal_silhouette_title),
                            whatHappened = text(R.string.learn_card_card_tonal_silhouette_what_happened),
                            whyItMatters = text(R.string.learn_card_card_tonal_silhouette_why_it_matters),
                            tryNextTime = text(R.string.learn_card_card_tonal_silhouette_try_next_time),
                            lessonId = "light.direction",
                            priority = fact.confidence * 1.25,
                        )
                    )
                    "high_key", "low_key" -> didWellCandidates.add(
                        LearnCard(
                            id = "card_tonal_${fact.value}",
                            kind = CardKind.DID_WELL,
                            title = if (fact.value == "high_key") text(R.string.learn_card_card_tonal_fact_value_001) else text(R.string.learn_card_card_tonal_fact_value_002),
                            whatHappened = if (fact.value == "high_key") {
                                text(R.string.learn_card_card_tonal_fact_value_003)
                            } else {
                                text(R.string.learn_card_card_tonal_fact_value_004)
                            },
                            whyItMatters = text(R.string.learn_card_card_tonal_fact_value_why_it_matters),
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
                            title = text(R.string.learn_card_card_exposure_clipping_title),
                            whatHappened = text(R.string.learn_card_card_exposure_clipping_what_happened),
                            whyItMatters = if (isRawOrHasRawCompanion) {
                                text(R.string.learn_card_card_exposure_clipping_005)
                            } else {
                                text(R.string.learn_card_card_exposure_clipping_006)
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
                            title = text(R.string.learn_card_card_shadow_clipping_title),
                            whatHappened = text(R.string.learn_card_card_shadow_clipping_what_happened),
                            whyItMatters = text(R.string.learn_card_card_shadow_clipping_why_it_matters),
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
                            title = text(R.string.learn_card_card_action_freeze_title),
                            whatHappened = text(R.string.learn_card_card_action_freeze_what_happened, exif?.shutterSpeed?.let { ExifFormatter.shutterSpeed(it) } ?: "≤ 1/1000s"),
                            whyItMatters = text(R.string.learn_card_card_action_freeze_why_it_matters),
                            tryNextTime = advice(CoachFinding.ACTION_FREEZE),
                            lessonId = "motion.shutter_intent",
                            priority = fact.confidence * 1.15,
                        )
                    )
                    "long_exposure" -> didWellCandidates.add(
                        LearnCard(
                            id = "card_long_exposure",
                            kind = CardKind.DID_WELL,
                            title = text(R.string.learn_card_card_long_exposure_title),
                            whatHappened = text(R.string.learn_card_card_long_exposure_what_happened, exif?.shutterSpeed?.let { ExifFormatter.shutterSpeed(it) } ?: "≥ 0.5s"),
                            whyItMatters = text(R.string.learn_card_card_long_exposure_why_it_matters),
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
                        title = text(R.string.learn_card_card_ev_bias_title),
                        whatHappened = text(R.string.learn_card_card_ev_bias_what_happened),
                        whyItMatters = text(R.string.learn_card_card_ev_bias_why_it_matters),
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
                        title = text(R.string.learn_card_card_handholding_guideline_title, ExifFormatter.focalLength(effectiveFocal)),
                        whatHappened = text(R.string.learn_card_card_handholding_guideline_what_happened, ExifFormatter.shutterSpeed(shutter), effectiveFocal.toInt(), sharpness.toInt()),
                        whyItMatters = text(R.string.learn_card_card_handholding_guideline_why_it_matters),
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
                        title = text(R.string.learn_card_card_steady_handholding_title),
                        whatHappened = text(R.string.learn_card_card_steady_handholding_what_happened, sharpness.toInt(), ExifFormatter.shutterSpeed(shutter), effectiveFocal.toInt()),
                        whyItMatters = text(R.string.learn_card_card_steady_handholding_why_it_matters),
                        tryNextTime = text(R.string.learn_card_card_steady_handholding_try_next_time),
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
                prompt = text(R.string.learn_card_card_steady_handholding_prompt),
                options = listOf(
                    text(R.string.learn_card_card_steady_handholding_007),
                    text(R.string.learn_card_card_steady_handholding_008),
                    text(R.string.learn_card_card_steady_handholding_009),
                ),
                correctIndex = 0,
                explanation = text(R.string.learn_card_card_steady_handholding_explanation),
            )
        }

        val DistractorPool = listOf(
            text(R.string.learn_card_card_steady_handholding_010),
            text(R.string.learn_card_card_steady_handholding_011),
            text(R.string.learn_card_card_steady_handholding_012),
            text(R.string.learn_card_card_steady_handholding_013),
            text(R.string.learn_card_card_steady_handholding_014),
            text(R.string.learn_card_card_steady_handholding_015),
        )
        val correctOption = primaryCard.title
        val distractors = DistractorPool
            // The pool and the card titles are resources now: a distractor is never the answer itself.
            .filter { it != correctOption }
            .take(2)
        val options = listOf(correctOption) + distractors
        return GuessFirstChallenge(
            prompt = text(R.string.learn_card_card_steady_handholding_prompt_2),
            options = options,
            correctIndex = 0,
            explanation = UiText.Joined(listOf(primaryCard.whatHappened, primaryCard.whyItMatters)),
        )
    }
}
