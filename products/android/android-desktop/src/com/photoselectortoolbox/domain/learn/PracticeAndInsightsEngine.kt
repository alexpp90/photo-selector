package com.photoselectortoolbox.domain.learn

import com.photoselectortoolbox.data.cache.PracticeGoalEntity
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.domain.analysis.PhotoCoachEngineImpl
import java.util.Locale

data class PatternInsight(
    val id: String,
    val title: String,
    val observation: String,
    val sampleSize: Int,
    val recommendedLessonId: String,
)

data class PracticeGoalStatus(
    val goal: PracticeGoalEntity,
    val lessonTitle: String,
    val tryThisPrompt: String,
    val baselineRate: Double,
    val recentRate: Double?,
    val recentSampleSize: Int,
    val recentMatchingCount: Int,
    val summaryText: String,
)

/**
 * Computes Personal Pattern Insights (requiring n >= 20 sample size) and
 * tracks active Practice Goals (`U-28`).
 */
object PracticeAndInsightsEngine {

    const val MIN_SAMPLE_SIZE = 20

    fun computeInsights(
        images: List<ImageItem>,
        factsByUri: Map<String, List<TechniqueFactEntity>>,
        scoresByUri: Map<String, ScanResult> = emptyMap(),
    ): List<PatternInsight> {
        if (images.size < MIN_SAMPLE_SIZE) return emptyList()
        val insights = mutableListOf<PatternInsight>()

        // 1. Telephoto hand-holding rate (f_eff >= 85mm)
        val telephotoPhotos = images.filter { img ->
            val fEff = PhotoCoachEngineImpl.computeEffectiveFocalLength(
                img.exifData,
                img.imageWidth,
                img.imageHeight,
            )
            fEff != null && fEff >= 85.0 && img.exifData?.shutterSpeed != null
        }
        if (telephotoPhotos.size >= MIN_SAMPLE_SIZE) {
            val slowBelowGuideline = telephotoPhotos.count { img ->
                val fEff = PhotoCoachEngineImpl.computeEffectiveFocalLength(
                    img.exifData,
                    img.imageWidth,
                    img.imageHeight,
                ) ?: return@count false
                val shutter = img.exifData?.shutterSpeed ?: return@count false
                val sharpness = (scoresByUri[img.uri] ?: img.scanResult)?.sharpnessScore
                shutter > (1.0 / fEff) * 1.05 && (sharpness == null || sharpness < 40.0)
            }
            val pct = (slowBelowGuideline * 100) / telephotoPhotos.size
            if (pct >= 20) {
                insights.add(
                    PatternInsight(
                        id = "insight_telephoto_shutter",
                        title = "Telephoto Hand-Holding Pattern",
                        observation = "$pct% of your telephoto shots ($slowBelowGuideline of ${telephotoPhotos.size} at ≥85mm 35mm-eq) were taken slower than the hand-holding speed and came out less sharp.",
                        sampleSize = telephotoPhotos.size,
                        recommendedLessonId = "motion.reciprocal_rule",
                    )
                )
            }
        }

        // 2. Horizon tilt habit
        val horizonFacts = images.mapNotNull { img ->
            factsByUri[img.uri]?.find { it.kind == "composition.horizon" && it.confidence >= 0.55 }
        }
        if (horizonFacts.size >= MIN_SAMPLE_SIZE) {
            val tiltedCount = horizonFacts.count { it.value == "slight_tilt" || it.value == "tilted" }
            val pct = (tiltedCount * 100) / horizonFacts.size
            if (pct >= 20) {
                insights.add(
                    PatternInsight(
                        id = "insight_horizon_tilt",
                        title = "Horizon Leveling Habit",
                        observation = "$pct% of your outdoor horizon scenes ($tiltedCount of ${horizonFacts.size}) have a measurable horizon tilt (> 0.5°).",
                        sampleSize = horizonFacts.size,
                        recommendedLessonId = "composition.horizon",
                    )
                )
            } else {
                val levelCount = horizonFacts.count { it.value == "level" }
                val levelPct = (levelCount * 100) / horizonFacts.size
                if (levelPct >= 75) {
                    insights.add(
                        PatternInsight(
                            id = "insight_horizon_steady",
                            title = "Consistent Level Horizons",
                            observation = "$levelPct% of your horizon scenes ($levelCount of ${horizonFacts.size}) are level within 0.5°.",
                            sampleSize = horizonFacts.size,
                            recommendedLessonId = "composition.horizon",
                        )
                    )
                }
            }
        }

        // 3. Highlight clipping rate
        val clippingFacts = images.mapNotNull { img ->
            factsByUri[img.uri]?.find { it.kind == "exposure.clipping" && it.confidence >= 0.55 }
        }
        if (clippingFacts.size >= MIN_SAMPLE_SIZE) {
            val clippedCount = clippingFacts.count {
                it.value == "highlights_clipped" || it.value == "both_clipped"
            }
            val pct = (clippedCount * 100) / clippingFacts.size
            if (pct >= 15) {
                insights.add(
                    PatternInsight(
                        id = "insight_highlight_clipping",
                        title = "Highlight Headroom Pattern",
                        observation = "$pct% of your analyzed frames ($clippedCount of ${clippingFacts.size}) show non-specular highlight clipping.",
                        sampleSize = clippingFacts.size,
                        recommendedLessonId = "exposure.histogram_clipping",
                    )
                )
            }
        }

        // 4. Subject placement habit
        val placementFacts = images.mapNotNull { img ->
            factsByUri[img.uri]?.find { it.kind == "composition.subject_placement" && it.confidence >= 0.55 }
        }
        if (placementFacts.size >= MIN_SAMPLE_SIZE) {
            val thirdsCount = placementFacts.count { it.value == "thirds_aligned" }
            val pct = (thirdsCount * 100) / placementFacts.size
            insights.add(
                PatternInsight(
                    id = "insight_subject_placement",
                    title = "Rule-of-Thirds Alignment Rate",
                    observation = "$pct% of your subject-detected photos ($thirdsCount of ${placementFacts.size}) align the subject near a Rule-of-Thirds intersection.",
                    sampleSize = placementFacts.size,
                    recommendedLessonId = "composition.rule_of_thirds",
                )
            )
        }

        return insights
    }

    fun createPracticeGoal(
        lessonId: String,
        images: List<ImageItem>,
        factsByUri: Map<String, List<TechniqueFactEntity>>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): PracticeGoalEntity {
        val lesson = LessonCatalog.getById(lessonId)
        var matching = 0
        var total = 0
        if (lesson != null) {
            for (img in images) {
                val fact = factsByUri[img.uri]?.find { it.kind == lesson.factKind && it.confidence >= 0.55 }
                if (fact != null) {
                    total++
                    if (fact.value in lesson.positiveFactValues) {
                        matching++
                    }
                }
            }
        }
        val baselineRate = if (total > 0) matching.toDouble() / total.toDouble() else 0.0
        return PracticeGoalEntity(
            lessonId = lessonId,
            startedAtEpochMs = nowEpochMs,
            baselineRate = baselineRate,
            baselineSampleSize = total,
        )
    }

    fun evaluateGoalProgress(
        goal: PracticeGoalEntity,
        images: List<ImageItem>,
        factsByUri: Map<String, List<TechniqueFactEntity>>,
    ): PracticeGoalStatus {
        val lesson = LessonCatalog.getById(goal.lessonId)
        val title = lesson?.title ?: goal.lessonId
        val prompt = lesson?.tryThisPrompt ?: "Practice this technique on your next shoot."

        // When the photo was taken, by its stored capture time; the file date only for a photo
        // without one (a copy or an edit keeps the capture time and changes the file date; SD-1b).
        val recentImages = images.filter { img ->
            (ScanFacts.captureTimeOf(factsByUri[img.uri]) ?: img.lastModified) >= goal.startedAtEpochMs
        }
        var recentTotal = 0
        var recentMatching = 0
        if (lesson != null) {
            for (img in recentImages) {
                val fact = factsByUri[img.uri]?.find { it.kind == lesson.factKind && it.confidence >= 0.55 }
                if (fact != null) {
                    recentTotal++
                    if (fact.value in lesson.positiveFactValues) {
                        recentMatching++
                    }
                }
            }
        }
        val recentRate = if (recentTotal > 0) recentMatching.toDouble() / recentTotal.toDouble() else null
        val baselinePct = (goal.baselineRate * 100.0).toInt()
        val summary = if (recentRate != null) {
            val recentPct = (recentRate * 100.0).toInt()
            String.format(
                Locale.US,
                "Since setting this goal: %d of %d matching photos (%d%% vs %d%% baseline).",
                recentMatching,
                recentTotal,
                recentPct,
                baselinePct,
            )
        } else {
            String.format(
                Locale.US,
                "Baseline: %d%% (%d photos). Shoot or scan new photos to track progress.",
                baselinePct,
                goal.baselineSampleSize,
            )
        }

        return PracticeGoalStatus(
            goal = goal,
            lessonTitle = title,
            tryThisPrompt = prompt,
            baselineRate = goal.baselineRate,
            recentRate = recentRate,
            recentSampleSize = recentTotal,
            recentMatchingCount = recentMatching,
            summaryText = summary,
        )
    }
}
