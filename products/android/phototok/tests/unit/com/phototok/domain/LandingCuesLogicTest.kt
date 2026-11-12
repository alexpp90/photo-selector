package com.phototok.domain

import androidx.datastore.preferences.core.emptyPreferences
import com.photoselector.core.storage.SourceKind
import com.phototok.data.model.SourceSettings
import com.phototok.data.repository.CullingStats
import com.phototok.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The landing's swipe-right cue per source and its stats line, as pure logic. */
class LandingCuesLogicTest {

    @Test
    fun `a fresh install stars on the phone and in the cloud and copies a card to the phone`() {
        val outcomes = SwipeRightOutcome.perKind(SettingsRepository.phoneSettingsOf(emptyPreferences()))
        assertEquals(SwipeRightOutcome.Kind.FAVOURITE, outcomes.getValue(SourceKind.PHONE_LIBRARY).kind)
        assertEquals(SwipeRightOutcome.Kind.FAVOURITE, outcomes.getValue(SourceKind.CLOUD_OTHER).kind)
        assertEquals(SwipeRightOutcome.Kind.COPY_TO_PHONE, outcomes.getValue(SourceKind.REMOVABLE).kind)
        assertEquals(SwipeRightOutcome.Kind.COPY_TO_PHONE, outcomes.getValue(SourceKind.CAMERA_MTP).kind)
        assertEquals(PhotoFolders.CASUAL_IMPORT, outcomes.getValue(SourceKind.REMOVABLE).folder)
    }

    @Test
    fun `in Pro a card copies or moves into its Selection folder, and cloud never moves`() {
        val card = SourceSettings(swipeRightVerb = SwipeRightVerb.MOVE, selectionSubfolder = "Keepers")
        assertEquals(SwipeRightOutcome(SwipeRightOutcome.Kind.MOVE_TO_FOLDER, "Keepers"), SwipeRightOutcome.of(card, SourceKind.REMOVABLE))
        assertEquals(SwipeRightOutcome.Kind.COPY_TO_FOLDER, SwipeRightOutcome.of(card, SourceKind.CLOUD_OTHER).kind)
        assertEquals(
            SwipeRightOutcome.Kind.COPY_TO_FOLDER,
            SwipeRightOutcome.of(card.copy(swipeRightVerb = SwipeRightVerb.COPY), SourceKind.REMOVABLE).kind,
        )
    }

    private val before = CullingStats(lifetimePhotosReviewedPhone = 90, lifetimeKeepersPhone = 9, sessionPhotosReviewed = 4, sessionKeepers = 1)
    private val after = before.copy(
        lifetimePhotosReviewedPhone = 96,
        lifetimeKeepersPhone = 11,
        lifetimeBytesFreedPhone = 2_000_000,
        sessionPhotosReviewed = 10,
        sessionKeepers = 3,
        sessionBytesFreedPhone = 2_000_000,
    )

    @Test
    fun `after the feed the landing shows this session's figures, else the lifetime ones`() {
        val session = LandingStats.line(milestonesEnabled = true, showSession = true, baseline = before, now = after)
        assertEquals(LandingStatsLine(isSession = true, figures = StatFigures(6, 2, 2_000_000)), session)

        val total = LandingStats.line(milestonesEnabled = true, showSession = false, baseline = before, now = after)
        assertEquals(LandingStatsLine(isSession = false, figures = StatFigures(96, 11, 2_000_000)), total)

        val nothingReviewed = LandingStats.line(milestonesEnabled = true, showSession = true, baseline = after, now = after)
        assertTrue("a session with nothing reviewed falls back to the totals", nothingReviewed?.isSession == false)
    }

    @Test
    fun `nothing is shown with Milestones off or on a fresh install`() {
        assertNull(LandingStats.line(milestonesEnabled = false, showSession = true, baseline = before, now = after))
        assertNull(LandingStats.line(milestonesEnabled = true, showSession = false, baseline = null, now = CullingStats()))
    }
}
