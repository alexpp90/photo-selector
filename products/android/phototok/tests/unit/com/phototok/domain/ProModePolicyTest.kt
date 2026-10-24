package com.phototok.domain

import com.photoselector.core.storage.SourceKind
import com.phototok.R
import com.phototok.data.model.SourceSettings
import com.phototok.de
import com.phototok.en
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProModePolicyTest {

    private val allKinds = SourceKind.entries

    // A stored value that differs from the Casual one in every Pro-only field.
    private fun nonCasual(kind: SourceKind): SourceSettings = SourceSettings.defaultFor(kind).copy(
        swipeRightVerb = SwipeRightVerb.MOVE,
        swipeLeftVerb = SwipeAction.COPY,
        feedOrderMode = FeedOrderMode.SHUFFLE,
        fileTypeFilter = FileTypeFilter.RAW,
        moveRelatedFiles = false,
        sortingEnabled = !ProModePolicy.casualSourceSettings(kind).sortingEnabled,
        collectionUri = "content://tree/collection",
        leftSwipeUri = "content://tree/left",
    )

    private fun stored(
        perKind: Map<SourceKind, SourceSettings> = allKinds.associateWith { SourceSettings.defaultFor(it) },
        cloudInheritsPhone: Boolean = true,
        selectionFolderName: String = "Selection",
        sortingEnabled: Boolean = false,
        showExifOverlay: Boolean = false,
        recentPathsEnabled: Boolean = true,
        recentPathsCount: Int = 3,
        hasCardOrCameraRecent: Boolean = false,
    ) = ProModePolicy.StoredProSettings(
        perKind, cloudInheritsPhone, selectionFolderName, sortingEnabled, showExifOverlay,
        recentPathsEnabled, recentPathsCount, hasCardOrCameraRecent,
    )

    // ── Casual and Pro default sets ───────────────────────────────────────

    @Test
    fun `casual phone and cloud are favourite, delete, newest first, pairs merged, linked, flat`() {
        for (kind in listOf(SourceKind.PHONE_LIBRARY, SourceKind.CLOUD_OTHER)) {
            val casual = ProModePolicy.casualSourceSettings(kind)
            assertEquals(SwipeRightVerb.FAVOURITE, casual.swipeRightVerb)
            assertEquals(SwipeAction.DELETE, casual.swipeLeftVerb)
            assertEquals(FeedOrderMode.NEWEST_FIRST, casual.feedOrderMode)
            assertEquals(FileTypeFilter.ONE_PER_SHOT, casual.fileTypeFilter)
            assertTrue(casual.moveRelatedFiles)
            assertFalse(casual.sortingEnabled)
            assertNull(casual.collectionUri)
            assertNull(casual.leftSwipeUri)
            assertEquals(PhotoFolders.SELECTION, casual.selectionSubfolder)
            assertFalse(casual.importToPhoneCamera)
        }
    }

    @Test
    fun `casual SD card and camera copy into the phone camera folder under Jackpix, in shooting order`() {
        for (kind in listOf(SourceKind.REMOVABLE, SourceKind.CAMERA_MTP)) {
            val casual = ProModePolicy.casualSourceSettings(kind)
            assertEquals(SwipeRightVerb.COPY, casual.swipeRightVerb)
            assertEquals(SwipeAction.DELETE, casual.swipeLeftVerb)
            assertEquals(FeedOrderMode.SHOOTING, casual.feedOrderMode)
            assertEquals(FileTypeFilter.ONE_PER_SHOT, casual.fileTypeFilter)
            assertTrue(casual.moveRelatedFiles)
            assertFalse(casual.sortingEnabled)
            assertTrue(casual.importToPhoneCamera)
            assertEquals("Jackpix", casual.selectionSubfolder)
            assertNull(casual.collectionUri)
        }
    }

    @Test
    fun `the on-disk import folder name is never translated and the target is the camera folder`() {
        assertEquals("Jackpix", PhotoFolders.CASUAL_IMPORT)
        assertEquals("primary:DCIM/Camera", ProModePolicy.CAMERA_IMPORT_DOCUMENT_ID)
        assertEquals("com.android.externalstorage.documents", ProModePolicy.CAMERA_IMPORT_AUTHORITY)
    }

    @Test
    fun `casual shared values`() {
        assertFalse(ProModePolicy.DEFAULT_PRO_MODE)
        assertEquals("Selection", ProModePolicy.effectiveSelectionFolderName("Keepers", proMode = false))
        assertFalse(ProModePolicy.effectiveSortingEnabled(true, proMode = false))
        assertFalse(ProModePolicy.effectiveShowExifOverlay(true, proMode = false))
        assertTrue(ProModePolicy.effectiveCloudInheritsPhone(false, proMode = false))
        assertTrue(ProModePolicy.effectiveRecentPathsEnabled(false, proMode = false))
        assertEquals(3, ProModePolicy.effectiveRecentPathsCount(9, proMode = false))
    }

    @Test
    fun `pro values are the stored values, falling back to the factory defaults`() {
        assertEquals("Keepers", ProModePolicy.effectiveSelectionFolderName("Keepers", proMode = true))
        assertTrue(ProModePolicy.effectiveSortingEnabled(true, proMode = true))
        assertTrue(ProModePolicy.effectiveShowExifOverlay(true, proMode = true))
        assertFalse(ProModePolicy.effectiveCloudInheritsPhone(false, proMode = true))
        assertFalse(ProModePolicy.effectiveRecentPathsEnabled(false, proMode = true))
        assertEquals(9, ProModePolicy.effectiveRecentPathsCount(9, proMode = true))
        for (kind in allKinds) {
            val factory = SourceSettings.defaultFor(kind)
            assertEquals(factory, ProModePolicy.effective(factory, kind, proMode = true))
        }
        // Pro SD keeps `<card>/Selection`: no import, default subfolder.
        val sd = ProModePolicy.effective(SourceSettings.defaultFor(SourceKind.REMOVABLE), SourceKind.REMOVABLE, true)
        assertFalse(sd.importToPhoneCamera)
        assertEquals(PhotoFolders.SELECTION, sd.selectionSubfolder)
    }

    @Test
    fun `effective gates every pro-only field in casual and never erases the stored value`() {
        for (kind in allKinds) {
            val storedValue = nonCasual(kind).copy(directDeleteConfirmEnabled = false)
            val casual = ProModePolicy.effective(storedValue, kind, proMode = false)
            assertEquals(
                "$kind",
                ProModePolicy.casualSourceSettings(kind).copy(directDeleteConfirmEnabled = false),
                casual,
            )
            assertEquals(storedValue, ProModePolicy.effective(storedValue, kind, proMode = true))
        }
    }

    @Test
    fun `confirm delete belongs to both modes`() {
        for (proMode in listOf(false, true)) {
            val off = SourceSettings.defaultFor(SourceKind.PHONE_LIBRARY).copy(directDeleteConfirmEnabled = false)
            assertFalse(ProModePolicy.effective(off, SourceKind.PHONE_LIBRARY, proMode).directDeleteConfirmEnabled)
            assertTrue(
                ProModePolicy.effective(SourceSettings(), SourceKind.PHONE_LIBRARY, proMode).directDeleteConfirmEnabled,
            )
        }
    }

    // ── Transitions ───────────────────────────────────────────────────────

    @Test
    fun `pro to casual writes nothing but the flag and says settings paused`() {
        for (ever in listOf(false, true)) {
            val t = ProModePolicy.transition(currentlyPro = true, targetPro = false, everEnabled = ever)
            assertFalse(t.proMode)
            assertEquals(emptyList<ProModePolicy.ProModeWrite>(), t.writes)
            assertEquals(UiText.of(R.string.snackbar_pro_paused), t.message)
        }
    }

    @Test
    fun `first ever casual to pro turns camera info on, shows five recents and says camera info on`() {
        val t = ProModePolicy.transition(currentlyPro = false, targetPro = true, everEnabled = false)
        assertTrue(t.proMode)
        assertEquals(
            listOf(
                ProModePolicy.ProModeWrite.ShowExifOverlay(true),
                ProModePolicy.ProModeWrite.RecentPathsCount(5),
                ProModePolicy.ProModeWrite.EverEnabled,
            ),
            t.writes,
        )
        assertEquals(UiText.of(R.string.snackbar_pro_camera_info_on), t.message)
    }

    @Test
    fun `later casual to pro restores the stored values without writing`() {
        val t = ProModePolicy.transition(currentlyPro = false, targetPro = true, everEnabled = true)
        assertTrue(t.proMode)
        assertEquals(emptyList<ProModePolicy.ProModeWrite>(), t.writes)
        assertEquals(UiText.of(R.string.snackbar_pro_restored), t.message)
    }

    @Test
    fun `no change means no writes and no message`() {
        for (mode in listOf(false, true)) {
            val t = ProModePolicy.transition(currentlyPro = mode, targetPro = mode, everEnabled = true)
            assertEquals(mode, t.proMode)
            assertTrue(t.writes.isEmpty())
            assertNull(t.message)
        }
    }

    @Test
    fun `transition messages read in english and german`() {
        fun text(current: Boolean, target: Boolean, ever: Boolean) =
            ProModePolicy.transition(current, target, ever).message!!
        assertEquals("Pro settings paused", text(true, false, true).en())
        assertEquals("Profi-Einstellungen pausiert", text(true, false, true).de())
        assertEquals("Camera info on", text(false, true, false).en())
        assertEquals("Kamera-Info an", text(false, true, false).de())
        assertEquals("Pro settings restored", text(false, true, true).en())
        assertEquals("Profi-Einstellungen zurück", text(false, true, true).de())
        assertEquals("Not copied", UiText.of(R.string.snackbar_not_copied).en())
        assertEquals("Nicht kopiert", UiText.of(R.string.snackbar_not_copied).de())
    }

    // ── Migration decision ────────────────────────────────────────────────

    @Test
    fun `an untouched install starts in casual`() {
        assertFalse(ProModePolicy.shouldStartInPro(stored()))
    }

    @Test
    fun `each pro-only value that differs from casual starts the user in pro`() {
        val phone = SourceSettings.defaultFor(SourceKind.PHONE_LIBRARY)
        val variants = mapOf(
            "swipe right" to phone.copy(swipeRightVerb = SwipeRightVerb.COPY),
            "swipe left" to phone.copy(swipeLeftVerb = SwipeAction.MOVE),
            "feed order" to phone.copy(feedOrderMode = FeedOrderMode.SHUFFLE),
            "format filter" to phone.copy(fileTypeFilter = FileTypeFilter.ALL),
            "link" to phone.copy(moveRelatedFiles = false),
            "collection uri" to phone.copy(collectionUri = "content://tree/c"),
            "left folder" to phone.copy(leftSwipeUri = "content://tree/l"),
        )
        for ((label, value) in variants) {
            val perKind = allKinds.associateWith { SourceSettings.defaultFor(it) } + (SourceKind.PHONE_LIBRARY to value)
            assertTrue(label, ProModePolicy.shouldStartInPro(stored(perKind = perKind)))
        }
        assertTrue(ProModePolicy.shouldStartInPro(stored(selectionFolderName = "Keepers")))
        assertTrue(ProModePolicy.shouldStartInPro(stored(sortingEnabled = true)))
        assertTrue(ProModePolicy.shouldStartInPro(stored(showExifOverlay = true)))
        assertTrue(ProModePolicy.shouldStartInPro(stored(recentPathsEnabled = false)))
        assertTrue(ProModePolicy.shouldStartInPro(stored(recentPathsCount = 5)))
        assertTrue(ProModePolicy.shouldStartInPro(stored(cloudInheritsPhone = false)))
    }

    @Test
    fun `values are compared per kind, SD and camera included`() {
        for (kind in listOf(SourceKind.REMOVABLE, SourceKind.CAMERA_MTP)) {
            val perKind = allKinds.associateWith { SourceSettings.defaultFor(it) } +
                (kind to SourceSettings.defaultFor(kind).copy(swipeRightVerb = SwipeRightVerb.MOVE))
            assertTrue("$kind", ProModePolicy.shouldStartInPro(stored(perKind = perKind)))
        }
    }

    @Test
    fun `the factory SD defaults alone do not start pro, only the stored per-source sorting default differs`() {
        // SourceSettings.defaultFor(REMOVABLE) has sortingEnabled = true, but transfers read only the
        // global flag; comparing the per-source field would push every install into Pro.
        assertFalse(ProModePolicy.shouldStartInPro(stored()))
    }

    @Test
    fun `cloud values count only when cloud does not inherit the phone`() {
        val cloudChanged = allKinds.associateWith { SourceSettings.defaultFor(it) } +
            (SourceKind.CLOUD_OTHER to nonCasual(SourceKind.CLOUD_OTHER))
        assertFalse(ProModePolicy.shouldStartInPro(stored(perKind = cloudChanged, cloudInheritsPhone = true)))
        assertTrue(ProModePolicy.shouldStartInPro(stored(perKind = cloudChanged, cloudInheritsPhone = false)))
    }

    @Test
    fun `an SD card or camera in the recent folders starts pro`() {
        assertTrue(ProModePolicy.shouldStartInPro(stored(hasCardOrCameraRecent = true)))
    }

    @Test
    fun `confirm delete never decides the mode`() {
        val perKind = allKinds.associateWith { SourceSettings.defaultFor(it).copy(directDeleteConfirmEnabled = false) }
        assertFalse(ProModePolicy.shouldStartInPro(stored(perKind = perKind)))
    }
}
