package com.phototok.domain

import com.phototok.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MilestonesTest {

    @Test
    fun `evaluate triggers BytesFreed when crossing 50 MB threshold`() {
        val event = Milestones.evaluate(
            previousBytes = 40L * Milestones.MB,
            currentBytes = 55L * Milestones.MB,
            previousSeen = 10,
            currentSeen = 10,
            lastCelebrationAtMs = 0L,
            nowMs = 500_000L,
            celebrationsEnabled = true,
        )

        assertTrue(event is Milestones.MilestoneEvent.BytesFreed)
        assertEquals(UiText.of(R.string.snackbar_milestone_freed, "50 MB"), event?.message)
    }

    @Test
    fun `evaluate triggers PhotosReviewed when crossing 100 seen threshold`() {
        val event = Milestones.evaluate(
            previousBytes = 0L,
            currentBytes = 0L,
            previousSeen = 99,
            currentSeen = 100,
            lastCelebrationAtMs = 0L,
            nowMs = 500_000L,
            celebrationsEnabled = true,
        )

        assertTrue(event is Milestones.MilestoneEvent.PhotosReviewed)
        assertEquals(UiText.of(R.string.snackbar_milestone_reviewed, 100), event?.message)
    }

    @Test
    fun `evaluate respects 2-minute rate limit and celebrationsEnabled toggle`() {
        val rateLimited = Milestones.evaluate(
            previousBytes = 0L,
            currentBytes = 60L * Milestones.MB,
            previousSeen = 0,
            currentSeen = 0,
            lastCelebrationAtMs = 450_000L,
            nowMs = 500_000L, // only 50s elapsed (< 120s)
            celebrationsEnabled = true,
        )
        assertNull(rateLimited)

        val disabled = Milestones.evaluate(
            previousBytes = 0L,
            currentBytes = 60L * Milestones.MB,
            previousSeen = 0,
            currentSeen = 0,
            lastCelebrationAtMs = 0L,
            nowMs = 500_000L,
            celebrationsEnabled = false,
        )
        assertNull(disabled)
    }
}
