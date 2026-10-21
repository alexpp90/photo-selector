package com.phototok.domain

import com.phototok.R

/**
 * Pure threshold and rate-limit rules for storage-freed and photos-reviewed milestone
 * celebrations (`U-19`).
 */
object Milestones {

    const val MB = 1024L * 1024L
    const val GB = 1024L * MB

    /** Storage-freed milestone thresholds in bytes (`50 MB`, `250 MB`, `1 GB`, `5 GB`). */
    val BYTE_THRESHOLDS: List<Long> = listOf(
        50L * MB,
        250L * MB,
        1L * GB,
        5L * GB,
    )

    /** Photos-reviewed milestone thresholds (`100`, `500`, `1_000`, `5_000`). */
    val SEEN_THRESHOLDS: List<Int> = listOf(
        100,
        500,
        1_000,
        5_000,
    )

    /** Minimum interval between milestone celebrations (2 minutes). */
    const val MIN_CELEBRATION_INTERVAL_MS = 2 * 60 * 1_000L

    sealed interface MilestoneEvent {
        val message: UiText

        data class BytesFreed(
            val thresholdBytes: Long,
            override val message: UiText,
        ) : MilestoneEvent

        data class PhotosReviewed(
            val thresholdCount: Int,
            override val message: UiText,
        ) : MilestoneEvent
    }

    /**
     * Evaluate whether a byte-freed transition (`previousBytes -> currentBytes`) or
     * seen-count transition (`previousSeen -> currentSeen`) crosses a new threshold.
     *
     * Never fires more than once per [MIN_CELEBRATION_INTERVAL_MS] (`2 minutes`), and
     * returns null immediately when [celebrationsEnabled] is false.
     */
    fun evaluate(
        previousBytes: Long,
        currentBytes: Long,
        previousSeen: Int,
        currentSeen: Int,
        lastCelebrationAtMs: Long,
        nowMs: Long,
        celebrationsEnabled: Boolean = true,
        alreadyFiredByteThresholds: Set<Long> = emptySet(),
        alreadyFiredSeenThresholds: Set<Int> = emptySet(),
    ): MilestoneEvent? {
        if (!celebrationsEnabled) return null
        if (lastCelebrationAtMs > 0L && (nowMs - lastCelebrationAtMs) < MIN_CELEBRATION_INTERVAL_MS) {
            return null
        }

        val crossedByte = BYTE_THRESHOLDS.lastOrNull { threshold ->
            previousBytes < threshold &&
                currentBytes >= threshold &&
                threshold !in alreadyFiredByteThresholds
        }
        if (crossedByte != null) {
            return MilestoneEvent.BytesFreed(
                thresholdBytes = crossedByte,
                message = UiText.of(R.string.snackbar_milestone_freed, formatBytes(crossedByte)),
            )
        }

        val crossedSeen = SEEN_THRESHOLDS.lastOrNull { threshold ->
            previousSeen < threshold &&
                currentSeen >= threshold &&
                threshold !in alreadyFiredSeenThresholds
        }
        if (crossedSeen != null) {
            return MilestoneEvent.PhotosReviewed(
                thresholdCount = crossedSeen,
                message = UiText.of(R.string.snackbar_milestone_reviewed, crossedSeen),
            )
        }

        return null
    }

    fun formatBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return when {
            safe >= GB -> {
                val gb = safe.toDouble() / GB.toDouble()
                if (gb % 1.0 == 0.0) "${gb.toLong()} GB"
                else String.format(java.util.Locale.US, "%.1f GB", gb)
            }
            safe >= MB -> "${safe / MB} MB"
            safe >= 1024L -> "${safe / 1024L} KB"
            else -> "$safe B"
        }
    }
}
