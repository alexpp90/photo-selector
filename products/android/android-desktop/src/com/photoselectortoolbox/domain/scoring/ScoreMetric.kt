package com.photoselectortoolbox.domain.scoring

import com.photoselectortoolbox.data.model.ScanResult
import java.util.Locale

/**
 * The quality metrics a scan produces, together with everything needed to
 * present them so a user can tell what a number means.
 *
 * Before this existed the UI showed a bare icon and a number ("512", "3.2"),
 * which is unreadable without knowing the codebase. Each metric now carries a
 * short on-chip label, a full name, what it measures, and — crucially — which
 * direction is good.
 *
 * Kept free of Compose/Android types so the wording and formatting are
 * unit-testable.
 */
enum class ScoreMetric(
    /** Two-to-five character label rendered on the chip itself. */
    val shortLabel: String,
    /** Full name used in the legend and in accessibility descriptions. */
    val displayName: String,
    /** What the number measures, in plain language. */
    val description: String,
    /**
     * The glyph that stands for this metric.
     *
     * Load-bearing, not decorative. On the Previous and Next frames the value
     * overlay has no room for names, so the glyph is the *only* thing
     * identifying which metric a number belongs to. Declaring it here — beside
     * the label, the format and the direction — is what makes "one metric, one
     * glyph, everywhere" true by construction: adding an entry without one does
     * not compile, and the exhaustive mapping in the UI layer does not compile
     * either until the new glyph is chosen.
     */
    val icon: ScoreMetricIcon,
    /** How to read the value. */
    val direction: ScoreDirection,
    private val format: String,
    /** Raw value that maps to goodness 0 for this metric. */
    private val worstValue: Double,
    /** Raw value that maps to goodness 1 for this metric. */
    private val bestValue: Double,
) {
    SHARPNESS(
        shortLabel = "Sharp",
        displayName = "Sharpness",
        description = "Edge contrast in the sharpest part of the frame. Low values usually mean " +
            "motion blur or a missed focus.",
        icon = ScoreMetricIcon.FOCUS,
        direction = ScoreDirection.HIGHER_IS_BETTER,
        format = "%.1f",
        worstValue = 5.0,
        bestValue = 100.0,
    ),
    NOISE(
        shortLabel = "Noise",
        displayName = "Noise",
        description = "Estimated sensor noise in flat areas of the image. Rises with high ISO.",
        icon = ScoreMetricIcon.GRAIN,
        direction = ScoreDirection.LOWER_IS_BETTER,
        format = "%.1f",
        worstValue = 7.0,
        bestValue = 0.2,
    ),
    HIGHLIGHT_CLIPPING(
        shortLabel = "Highl",
        displayName = "Highlight clipping",
        description = "Share of pixels blown out to pure white — detail that cannot be recovered.",
        icon = ScoreMetricIcon.HIGHLIGHT,
        direction = ScoreDirection.LOWER_IS_BETTER,
        format = "%.1f%%",
        worstValue = 10.0,
        bestValue = 0.0,
    ),
    SHADOW_CLIPPING(
        shortLabel = "Shad",
        displayName = "Shadow clipping",
        description = "Share of pixels crushed to pure black — detail that cannot be recovered.",
        icon = ScoreMetricIcon.SHADOW,
        direction = ScoreDirection.LOWER_IS_BETTER,
        format = "%.1f%%",
        worstValue = 14.0,
        bestValue = 0.0,
    );

    /** Format a raw value for display, e.g. `512.3` or `1.4%`. */
    fun format(value: Double): String = String.format(Locale.US, format, value)

    /**
     * Map a raw value onto 0..1 where 1 is always "good", regardless of which
     * direction the underlying number runs.
     *
     * This is what lets a single bar be read the same way for every metric: a
     * long green bar means a good frame whether the metric is sharpness (high
     * is good) or noise (low is good). Comparing bar lengths across metrics is
     * the point — the raw numbers live on wildly different scales and cannot be
     * compared by eye.
     */
    fun goodness(value: Double): Double {
        val span = bestValue - worstValue
        if (span == 0.0) return 1.0
        return ((value - worstValue) / span).coerceIn(0.0, 1.0)
    }

    /**
     * Which of [values] is the best frame for this metric, as an index, or null
     * when fewer than two frames carry a value (a "best of one" badge is noise).
     * Ties resolve to the first frame so the marker never flickers between
     * identical neighbours.
     */
    fun bestIndexOf(values: List<Double?>): Int? {
        val scored = values.withIndex().filter { it.value != null }
        if (scored.size < 2) return null
        return scored.maxByOrNull { goodness(it.value!!) }?.index
    }

    /** Pull this metric's value out of a scan result, or null if not computed. */
    fun valueOf(result: ScanResult): Double? = when (this) {
        SHARPNESS -> result.sharpnessScore
        NOISE -> result.noiseLevel
        HIGHLIGHT_CLIPPING -> result.highlightClipping
        SHADOW_CLIPPING -> result.shadowClipping
    }

    /**
     * Screen-reader / tooltip text: name, value, how to read it, and whether
     * this is the best of the frames currently on screen.
     *
     * The direction hint is not optional decoration — a number with no stated
     * direction is not interpretable, and a sighted user gets the direction
     * from the bar, which a screen-reader user does not.
     */
    fun accessibilityLabel(value: Double, isBestOfVisible: Boolean = false): String {
        val base = "$displayName ${format(value)}, ${direction.hint}"
        return if (isBestOfVisible) "$base, best of the three visible frames" else base
    }

    companion object {
        /** Metrics that actually have a value in [result], in display order. */
        fun present(result: ScanResult?): List<Pair<ScoreMetric, Double>> {
            if (result == null) return emptyList()
            return entries.mapNotNull { metric ->
                metric.valueOf(result)?.let { metric to it }
            }
        }
    }
}

/** Which way is "good" for a metric. */
enum class ScoreDirection(val hint: String) {
    HIGHER_IS_BETTER("higher is better"),
    LOWER_IS_BETTER("lower is better"),
}

/**
 * The glyph vocabulary, as a closed set rather than as Compose `ImageVector`s.
 *
 * `ScoreMetric` stays free of Android types — that is what lets the wording,
 * the formatting and the normalisation be unit-tested on the JVM — so the
 * choice of glyph is named here and resolved to an actual vector in the UI
 * layer by an exhaustive `when`. The indirection buys the compile-time
 * guarantee: a metric added without a glyph fails at the enum, and a glyph
 * added without a vector fails at the mapping.
 */
enum class ScoreMetricIcon {
    /** Focus reticle — sharpness. */
    FOCUS,

    /** Film grain — noise. */
    GRAIN,

    /** Sun — highlight clipping. */
    HIGHLIGHT,

    /** Moon — shadow clipping. */
    SHADOW,
}
