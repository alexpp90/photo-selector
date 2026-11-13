package com.photoselectortoolbox.domain.learn

import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.R
import com.photoselectortoolbox.domain.text.UiText
import com.photoselectortoolbox.domain.text.raw
import com.photoselectortoolbox.domain.text.text

enum class LessonTrack(val title: UiText) {
    LIGHT(text(R.string.label_track_light)),
    COMPOSITION(text(R.string.label_track_composition)),
    MOTION_AND_DEPTH(text(R.string.learn_lesson_001)),
    EXPOSURE_AND_COLOR(text(R.string.learn_lesson_002)),
}

data class MicroLesson(
    val id: String,
    val track: LessonTrack,
    val title: UiText,
    val conceptSummary: UiText,
    val diagramType: String,
    val factKind: String,
    val positiveFactValues: Set<String>,
    val negativeFactValues: Set<String>,
    val tryThisPrompt: UiText,
    val fallbackQuestionPrompt: UiText,
    val fallbackOptions: List<UiText>,
    val fallbackCorrectIndex: Int,
)

data class SpotItQuestion(
    val prompt: UiText,
    val options: List<UiText>,
    val correctIndex: Int,
    val examplePhotoUri: String? = null,
    val contrastPhotoUri: String? = null,
    val explanation: UiText,
)

/**
 * Built-in catalog of 14 micro-lessons across 4 tracks (`U-26`).
 * Matches lessons against the user's own scanned photos via `technique_facts`
 * and builds a 2-question "Spot it" quiz per lesson.
 */
object LessonCatalog {

    val ALL_LESSONS: List<MicroLesson> = listOf(
        // Track 1 — Light (3 lessons)
        MicroLesson(
            id = "light.golden_hour",
            track = LessonTrack.LIGHT,
            title = text(R.string.learn_lesson_light_golden_hour_title),
            conceptSummary = text(R.string.learn_lesson_light_golden_hour_concept_summary),
            diagramType = "solar_elevation_arc",
            factKind = "light.hour",
            positiveFactValues = setOf("golden_hour", "blue_hour"),
            negativeFactValues = setOf("day", "night"),
            tryThisPrompt = text(R.string.learn_lesson_light_golden_hour_try_this_prompt),
            fallbackQuestionPrompt = text(R.string.learn_lesson_light_golden_hour_fallback_question_prompt),
            fallbackOptions = listOf(text(R.string.learn_lesson_light_golden_hour_003), text(R.string.learn_lesson_light_golden_hour_004), text(R.string.learn_lesson_light_golden_hour_005)),
            fallbackCorrectIndex = 0,
        ),
        MicroLesson(
            id = "light.direction",
            track = LessonTrack.LIGHT,
            title = text(R.string.learn_lesson_light_direction_title),
            conceptSummary = text(R.string.learn_lesson_light_direction_concept_summary),
            diagramType = "light_direction_angles",
            factKind = "exposure.tonal_key",
            positiveFactValues = setOf("silhouette"),
            negativeFactValues = setOf("normal"),
            tryThisPrompt = text(R.string.learn_lesson_light_direction_try_this_prompt),
            fallbackQuestionPrompt = text(R.string.learn_lesson_light_direction_fallback_question_prompt),
            fallbackOptions = listOf(text(R.string.learn_lesson_light_direction_006), text(R.string.learn_lesson_light_direction_007), text(R.string.learn_lesson_light_direction_008)),
            fallbackCorrectIndex = 0,
        ),
        MicroLesson(
            id = "light.tonal_key",
            track = LessonTrack.LIGHT,
            title = text(R.string.learn_lesson_light_tonal_key_title),
            conceptSummary = text(R.string.learn_lesson_light_tonal_key_concept_summary),
            diagramType = "tonal_key_histograms",
            factKind = "exposure.tonal_key",
            positiveFactValues = setOf("high_key", "low_key"),
            negativeFactValues = setOf("normal"),
            tryNextTimeFallback = text(R.string.learn_lesson_light_tonal_key_try_next_time_fallback),
        ),

        // Track 2 — Composition (5 lessons)
        MicroLesson(
            id = "composition.rule_of_thirds",
            track = LessonTrack.COMPOSITION,
            title = text(R.string.learn_lesson_composition_rule_of_thirds_title),
            conceptSummary = text(R.string.learn_lesson_composition_rule_of_thirds_concept_summary),
            diagramType = "thirds_grid_power_points",
            factKind = "composition.subject_placement",
            positiveFactValues = setOf("thirds_aligned"),
            negativeFactValues = setOf("edge_crowded", "off_center"),
            tryThisPrompt = text(R.string.learn_lesson_composition_rule_of_thirds_try_this_prompt),
            fallbackQuestionPrompt = text(R.string.learn_lesson_composition_rule_of_thirds_fallback_question_prompt),
            fallbackOptions = listOf(text(R.string.learn_lesson_composition_rule_of_thirds_009), text(R.string.learn_lesson_composition_rule_of_thirds_010), text(R.string.learn_lesson_composition_rule_of_thirds_011)),
            fallbackCorrectIndex = 0,
        ),
        MicroLesson(
            id = "composition.leading_lines",
            track = LessonTrack.COMPOSITION,
            title = text(R.string.learn_lesson_composition_leading_lines_title),
            conceptSummary = text(R.string.learn_lesson_composition_leading_lines_concept_summary),
            diagramType = "converging_perspective_lines",
            factKind = "composition.leading_lines",
            positiveFactValues = setOf("converging_to_subject"),
            negativeFactValues = emptySet(),
            tryThisPrompt = text(R.string.learn_lesson_composition_leading_lines_try_this_prompt),
            fallbackQuestionPrompt = text(R.string.learn_lesson_composition_leading_lines_fallback_question_prompt),
            fallbackOptions = listOf(text(R.string.learn_lesson_composition_leading_lines_012), text(R.string.learn_lesson_composition_leading_lines_013), text(R.string.learn_lesson_composition_leading_lines_014)),
            fallbackCorrectIndex = 0,
        ),
        MicroLesson(
            id = "composition.horizon",
            track = LessonTrack.COMPOSITION,
            title = text(R.string.learn_lesson_composition_horizon_title),
            conceptSummary = text(R.string.learn_lesson_composition_horizon_concept_summary),
            diagramType = "horizon_level_and_thirds",
            factKind = "composition.horizon",
            positiveFactValues = setOf("level"),
            negativeFactValues = setOf("slight_tilt", "tilted"),
            tryThisPrompt = text(R.string.learn_lesson_composition_horizon_try_this_prompt),
            fallbackQuestionPrompt = text(R.string.learn_lesson_composition_horizon_fallback_question_prompt),
            fallbackOptions = listOf(text(R.string.learn_lesson_composition_horizon_015), text(R.string.learn_lesson_composition_horizon_016), text(R.string.learn_lesson_composition_horizon_017)),
            fallbackCorrectIndex = 0,
        ),
        MicroLesson(
            id = "composition.simplicity",
            track = LessonTrack.COMPOSITION,
            title = text(R.string.learn_lesson_composition_simplicity_title),
            conceptSummary = text(R.string.learn_lesson_composition_simplicity_concept_summary),
            diagramType = "figure_ground_simplicity",
            factKind = "composition.simplicity",
            positiveFactValues = setOf("clean"),
            negativeFactValues = setOf("busy"),
            tryThisPrompt = text(R.string.learn_lesson_composition_simplicity_try_this_prompt),
            fallbackQuestionPrompt = text(R.string.learn_lesson_composition_simplicity_fallback_question_prompt),
            fallbackOptions = listOf(text(R.string.learn_lesson_composition_simplicity_018), text(R.string.learn_lesson_composition_simplicity_019), text(R.string.learn_lesson_composition_simplicity_020)),
            fallbackCorrectIndex = 0,
        ),
        MicroLesson(
            id = "composition.symmetry",
            track = LessonTrack.COMPOSITION,
            title = text(R.string.learn_lesson_composition_symmetry_title),
            conceptSummary = text(R.string.learn_lesson_composition_symmetry_concept_summary),
            diagramType = "bilateral_symmetry_axis",
            factKind = "composition.symmetry",
            positiveFactValues = setOf("vertical", "horizontal"),
            negativeFactValues = emptySet(),
            tryThisPrompt = text(R.string.learn_lesson_composition_symmetry_try_this_prompt),
            fallbackQuestionPrompt = text(R.string.learn_lesson_composition_symmetry_fallback_question_prompt),
            fallbackOptions = listOf(text(R.string.learn_lesson_composition_symmetry_021), text(R.string.learn_lesson_composition_symmetry_022), text(R.string.learn_lesson_composition_symmetry_023)),
            fallbackCorrectIndex = 0,
        ),

        // Track 3 — Motion & Depth (4 lessons)
        MicroLesson(
            id = "motion.reciprocal_rule",
            track = LessonTrack.MOTION_AND_DEPTH,
            title = text(R.string.learn_lesson_motion_reciprocal_rule_title),
            conceptSummary = text(R.string.learn_lesson_motion_reciprocal_rule_concept_summary),
            diagramType = "reciprocal_rule_cone",
            factKind = "optics.effective_focal_length",
            positiveFactValues = emptySet(),
            negativeFactValues = emptySet(),
            tryThisPrompt = text(R.string.learn_lesson_motion_reciprocal_rule_try_this_prompt),
            fallbackQuestionPrompt = text(R.string.learn_lesson_motion_reciprocal_rule_fallback_question_prompt),
            fallbackOptions = listOf(text(R.string.learn_lesson_motion_reciprocal_rule_024), raw("1/30s"), raw("1/15s")),
            fallbackCorrectIndex = 0,
        ),
        MicroLesson(
            id = "motion.shutter_intent",
            track = LessonTrack.MOTION_AND_DEPTH,
            title = text(R.string.learn_lesson_motion_shutter_intent_title),
            conceptSummary = text(R.string.learn_lesson_motion_shutter_intent_concept_summary),
            diagramType = "shutter_time_scale",
            factKind = "intent.shutter",
            positiveFactValues = setOf("action_freeze", "long_exposure"),
            negativeFactValues = emptySet(),
            tryThisPrompt = text(R.string.learn_lesson_motion_shutter_intent_try_this_prompt),
            fallbackQuestionPrompt = text(R.string.learn_lesson_motion_shutter_intent_fallback_question_prompt),
            fallbackOptions = listOf(text(R.string.learn_lesson_motion_shutter_intent_025), text(R.string.learn_lesson_motion_shutter_intent_026), text(R.string.learn_lesson_motion_shutter_intent_027)),
            fallbackCorrectIndex = 0,
        ),
        MicroLesson(
            id = "depth.aperture_story",
            track = LessonTrack.MOTION_AND_DEPTH,
            title = text(R.string.learn_lesson_depth_aperture_story_title),
            conceptSummary = text(R.string.learn_lesson_depth_aperture_story_concept_summary),
            diagramType = "dof_aperture_cone",
            factKind = "depth.background_separation",
            positiveFactValues = setOf("strong_isolation", "moderate_separation"),
            negativeFactValues = setOf("deep_dof"),
            tryThisPrompt = text(R.string.learn_lesson_depth_aperture_story_try_this_prompt),
            fallbackQuestionPrompt = text(R.string.learn_lesson_depth_aperture_story_fallback_question_prompt),
            fallbackOptions = listOf(text(R.string.learn_lesson_depth_aperture_story_028), text(R.string.learn_lesson_depth_aperture_story_029), text(R.string.learn_lesson_depth_aperture_story_030)),
            fallbackCorrectIndex = 0,
        ),
        MicroLesson(
            id = "depth.diffraction",
            track = LessonTrack.MOTION_AND_DEPTH,
            title = text(R.string.learn_lesson_depth_diffraction_title),
            conceptSummary = text(R.string.learn_lesson_depth_diffraction_concept_summary),
            diagramType = "diffraction_airy_disk",
            factKind = "optics.crop_factor",
            positiveFactValues = emptySet(),
            negativeFactValues = emptySet(),
            tryThisPrompt = text(R.string.learn_lesson_depth_diffraction_try_this_prompt),
            fallbackQuestionPrompt = text(R.string.learn_lesson_depth_diffraction_fallback_question_prompt),
            fallbackOptions = listOf(text(R.string.learn_lesson_depth_diffraction_031), text(R.string.learn_lesson_depth_diffraction_032), text(R.string.learn_lesson_depth_diffraction_033)),
            fallbackCorrectIndex = 0,
        ),

        // Track 4 — Exposure & Color (2 lessons)
        MicroLesson(
            id = "exposure.histogram_clipping",
            track = LessonTrack.EXPOSURE_AND_COLOR,
            title = text(R.string.learn_lesson_exposure_histogram_clipping_title),
            conceptSummary = text(R.string.learn_lesson_exposure_histogram_clipping_concept_summary),
            diagramType = "histogram_clipping_walls",
            factKind = "exposure.clipping",
            positiveFactValues = setOf("well_exposed"),
            negativeFactValues = setOf("highlights_clipped", "both_clipped", "shadows_clipped"),
            tryThisPrompt = text(R.string.learn_lesson_exposure_histogram_clipping_try_this_prompt),
            fallbackQuestionPrompt = text(R.string.learn_lesson_exposure_histogram_clipping_fallback_question_prompt),
            fallbackOptions = listOf(text(R.string.learn_lesson_exposure_histogram_clipping_034), text(R.string.learn_lesson_exposure_histogram_clipping_035), text(R.string.learn_lesson_exposure_histogram_clipping_036)),
            fallbackCorrectIndex = 0,
        ),
        MicroLesson(
            id = "exposure.ev_metering",
            track = LessonTrack.EXPOSURE_AND_COLOR,
            title = text(R.string.learn_lesson_exposure_ev_metering_title),
            conceptSummary = text(R.string.learn_lesson_exposure_ev_metering_concept_summary),
            diagramType = "metering_modes_ev_scale",
            factKind = "intent.exposure_bias",
            positiveFactValues = setOf("positive_ev", "negative_ev"),
            negativeFactValues = emptySet(),
            tryThisPrompt = text(R.string.learn_lesson_exposure_ev_metering_try_this_prompt),
            fallbackQuestionPrompt = text(R.string.learn_lesson_exposure_ev_metering_fallback_question_prompt),
            fallbackOptions = listOf(text(R.string.learn_lesson_exposure_ev_metering_037), text(R.string.learn_lesson_exposure_ev_metering_038), text(R.string.learn_lesson_exposure_ev_metering_039)),
            fallbackCorrectIndex = 0,
        ),
    )

    private fun MicroLesson(
        id: String,
        track: LessonTrack,
        title: UiText,
        conceptSummary: UiText,
        diagramType: String,
        factKind: String,
        positiveFactValues: Set<String>,
        negativeFactValues: Set<String>,
        tryNextTimeFallback: UiText,
    ) = MicroLesson(
        id = id,
        track = track,
        title = title,
        conceptSummary = conceptSummary,
        diagramType = diagramType,
        factKind = factKind,
        positiveFactValues = positiveFactValues,
        negativeFactValues = negativeFactValues,
        tryThisPrompt = tryNextTimeFallback,
        fallbackQuestionPrompt = text(R.string.learn_lesson_exposure_ev_metering_fallback_question_prompt_2),
        fallbackOptions = listOf(
            text(R.string.learn_lesson_exposure_ev_metering_040),
            text(R.string.learn_lesson_exposure_ev_metering_041),
            text(R.string.learn_lesson_exposure_ev_metering_042),
        ),
        fallbackCorrectIndex = 0,
    )

    fun getById(lessonId: String): MicroLesson? = ALL_LESSONS.find { it.id == lessonId }

    fun lessonsByTrack(): Map<LessonTrack, List<MicroLesson>> =
        ALL_LESSONS.groupBy { it.track }

    /**
     * Builds the 2-question "Spot it" quiz (`U-26`) for a lesson, drawing examples and contrasts
     * from the user's own scanned photos when available and falling back to built-in conceptual questions.
     */
    fun buildSpotItQuiz(
        lesson: MicroLesson,
        factsByUri: Map<String, List<TechniqueFactEntity>> = emptyMap(),
    ): List<SpotItQuestion> {
        val positiveMatches = mutableListOf<Pair<String, TechniqueFactEntity>>()
        val negativeMatches = mutableListOf<Pair<String, TechniqueFactEntity>>()

        for ((uri, facts) in factsByUri) {
            for (fact in facts) {
                if (fact.kind == lesson.factKind && fact.confidence >= 0.55) {
                    if (fact.value in lesson.positiveFactValues) {
                        positiveMatches.add(uri to fact)
                    } else if (fact.value in lesson.negativeFactValues) {
                        negativeMatches.add(uri to fact)
                    }
                }
            }
        }

        val q1 = SpotItQuestion(
            prompt = lesson.fallbackQuestionPrompt,
            options = lesson.fallbackOptions,
            correctIndex = lesson.fallbackCorrectIndex,
            examplePhotoUri = positiveMatches.firstOrNull()?.first,
            contrastPhotoUri = negativeMatches.firstOrNull()?.first,
            explanation = lesson.conceptSummary,
        )

        val q2 = if (positiveMatches.isNotEmpty() && negativeMatches.isNotEmpty()) {
            val pos = positiveMatches.first()
            val neg = negativeMatches.first()
            SpotItQuestion(
                prompt = text(R.string.learn_lesson_exposure_ev_metering_prompt, lesson.title),
                options = listOf(
                    text(R.string.learn_lesson_exposure_ev_metering_043, pos.second.value.replace('_', ' '), neg.second.value.replace('_', ' ')),
                    text(R.string.learn_lesson_exposure_ev_metering_044),
                    text(R.string.learn_lesson_exposure_ev_metering_045),
                ),
                correctIndex = 0,
                examplePhotoUri = pos.first,
                contrastPhotoUri = neg.first,
                explanation = text(R.string.learn_lesson_exposure_ev_metering_explanation),
            )
        } else {
            SpotItQuestion(
                prompt = text(R.string.learn_lesson_exposure_ev_metering_prompt_2, lesson.title),
                options = listOf(
                    lesson.tryThisPrompt,
                    text(R.string.learn_lesson_exposure_ev_metering_046),
                    text(R.string.learn_lesson_exposure_ev_metering_047),
                ),
                correctIndex = 0,
                examplePhotoUri = positiveMatches.firstOrNull()?.first,
                explanation = lesson.tryThisPrompt,
            )
        }

        return listOf(q1.shuffled(lesson.id, 1), q2.shuffled(lesson.id, 2))
    }

    /**
     * [this] with its options in an order fixed by the lesson, the question and its example
     * photo (shoot debrief SD-1b, S3): the right answer was always the first option. The same
     * inputs give the same order, so a recomposition never reshuffles a question being answered.
     */
    internal fun SpotItQuestion.shuffled(lessonId: String, number: Int): SpotItQuestion {
        val seed = "$lessonId#$number#${examplePhotoUri.orEmpty()}".hashCode()
        val order = options.indices.shuffled(kotlin.random.Random(seed))
        return copy(options = order.map { options[it] }, correctIndex = order.indexOf(correctIndex))
    }
}
