package com.phototok.domain

import com.phototok.data.repository.CullingStats

/** Photos reviewed, keepers selected and bytes freed, for the landing (REQ-TOK-FEATURE.19). */
data class StatFigures(val reviewed: Int, val selected: Int, val freedBytes: Long) {
    val isZero: Boolean get() = reviewed <= 0 && selected <= 0 && freedBytes <= 0L
}

/** The landing's stats line: this session's figures on return from the feed, else the lifetime ones. */
data class LandingStatsLine(val isSession: Boolean, val figures: StatFigures)

/**
 * What the landing shows between the app name and the sources (`USE_CASES` T1.4): after the feed
 * was left with photos reviewed, this session's figures (since the folder was opened); otherwise
 * the lifetime figures. Nothing when Milestones is off or everything is zero. "Freed" is what the
 * deletes released: they are permanent once committed, and the queued ones are committed when the
 * feed is left.
 */
object LandingStats {

    fun lifetime(stats: CullingStats): StatFigures = StatFigures(
        reviewed = stats.totalLifetimePhotosReviewed,
        selected = stats.lifetimeKeepersPhone + stats.lifetimeKeepersCard,
        freedBytes = stats.totalLifetimeBytesFreed,
    )

    fun session(baseline: CullingStats, now: CullingStats): StatFigures = StatFigures(
        reviewed = (now.sessionPhotosReviewed - baseline.sessionPhotosReviewed).coerceAtLeast(0),
        selected = (now.sessionKeepers - baseline.sessionKeepers).coerceAtLeast(0),
        freedBytes = (now.totalSessionBytesFreed - baseline.totalSessionBytesFreed).coerceAtLeast(0L),
    )

    fun line(
        milestonesEnabled: Boolean,
        showSession: Boolean,
        baseline: CullingStats?,
        now: CullingStats,
    ): LandingStatsLine? {
        if (!milestonesEnabled) return null
        if (showSession && baseline != null) {
            val session = session(baseline, now)
            if (session.reviewed > 0) return LandingStatsLine(isSession = true, figures = session)
        }
        val total = lifetime(now)
        return if (total.isZero) null else LandingStatsLine(isSession = false, figures = total)
    }
}
