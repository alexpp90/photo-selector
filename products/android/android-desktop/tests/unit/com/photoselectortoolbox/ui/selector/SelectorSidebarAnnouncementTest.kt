package com.photoselectortoolbox.ui.selector

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselectortoolbox.domain.session.SelectorWork
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A request that has to wait (a scan while grouping runs, grouping while a scan runs) changes the
 * sidebar without moving focus, so a screen reader must hear it: the queued item is a polite live
 * region. The scan counter is not one, because it changes on every photo.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
class SelectorSidebarAnnouncementTest {

    @get:Rule
    val rule = createComposeRule()

    private fun host(isScanning: Boolean, isGrouping: Boolean, queued: SelectorWork?) {
        rule.setContent {
            PhotoSelectorToolboxTheme {
                SelectorSidebar(
                    currentRoute = null, groupingEnabled = true, hasImages = true,
                    isScanning = isScanning, scanStatusText = "Scanning 3 / 12",
                    onOpenFolder = {}, onScan = {}, onCancelScan = {}, onToggleGrouping = {},
                    onShowLegend = {}, onShowMenu = {}, onNavigate = {},
                    isGrouping = isGrouping, queuedWork = queued,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun liveRegion(tag: String) =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)

    @Test
    fun `a queued scan is announced`() {
        host(isScanning = false, isGrouping = true, queued = SelectorWork.SCAN)
        assertEquals(LiveRegionMode.Polite, liveRegion("cancel_queued_scan"))
    }

    @Test
    fun `queued grouping is announced, the running scan's counter is not`() {
        host(isScanning = true, isGrouping = false, queued = SelectorWork.GROUPING)
        assertEquals(LiveRegionMode.Polite, liveRegion("cancel_queued_grouping"))
        assertNull(liveRegion("cancel_scan_button"))
    }
}
