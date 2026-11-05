package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.photoselector.core.Requirement
import com.phototok.R
import com.phototok.data.model.ImageItem
import com.phototok.ui.theme.PhotoTokTheme
import com.phototok.viewmodel.SelectionViewerTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * In landscape the 3-button navigation bar is on the side (right at rotation 90, left at 270). The
 * top bar (landing, loading, feed) and the Selection viewer's bar inset horizontally by it, so no
 * control lies under the bar (device lab 2026-11-05, Pixel 4a: the landing Settings button lay
 * 32 dp under the bar and two taps hit Recents). With gesture navigation (a bottom-only inset)
 * nothing moves. Landscape Pixel 4a geometry: 851 x 393 dp at 440 dpi; at rotation 1 the device
 * has the display cutout on the left (136 px) AND the navigation bar on the right (132 px) at
 * once, which the cutout cases dispatch as real `displayCutout` + `navigationBars` insets.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w851dp-h393dp-land-440dpi")
class LandscapeTopBarInsetsTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val navDp = 48

    private val photo = ImageItem(
        uri = "content://photos/a.jpg",
        fileName = "a.jpg",
        fileSize = 1,
        lastModified = 1,
        mimeType = "image/jpeg",
        imageWidth = 100,
        imageHeight = 50,
    )

    private val feedTopBarTags = listOf(
        "top_bar_logo", "done_with_card_button", "reload_folder_button", "help_button",
        "info_exif_button", "settings_button",
    )
    private val landingTopBarTags = listOf("top_bar_logo", "settings_button")
    private val selectionBarTags = listOf(
        "selection_back_button", "selection_tab_favourites", "selection_tab_selection",
        "selection_copy_favourites_button", "selection_share_all_button",
    )

    private val density get() = rule.density.density

    private fun bounds(tag: String): Rect {
        val nodes = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()
        assertFalse("no node tagged $tag", nodes.isEmpty())
        return rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot
    }

    private fun show(content: @Composable () -> Unit) {
        rule.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(rule.activity.window, false) }
        rule.setContent { PhotoTokTheme { Box(Modifier.fillMaxSize()) { content() } } }
        rule.waitForIdle()
    }

    /** A navigation-bar inset of [left]/[right]/[bottom] dp, as the system dispatches it. */
    private fun navigationBar(left: Int = 0, right: Int = 0, bottom: Int = 0) {
        rule.runOnUiThread {
            val px = rule.activity.resources.displayMetrics.density
            val insets = WindowInsetsCompat.Builder()
                .setInsets(
                    WindowInsetsCompat.Type.navigationBars(),
                    Insets.of((left * px).toInt(), 0, (right * px).toInt(), (bottom * px).toInt()),
                )
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                .build()
            ViewCompat.dispatchApplyWindowInsets(rule.activity.window.decorView, insets)
        }
        rule.waitForIdle()
    }

    private fun assertClearOfTheRightBar(tags: List<String>) {
        val rootWidth = rule.onRoot().fetchSemanticsNode().boundsInRoot.width
        val barStart = rootWidth - navDp * density
        for (tag in tags) {
            val b = bounds(tag)
            assertTrue(
                "$tag ends at ${b.right / density} dp, the navigation bar starts at ${barStart / density} dp",
                b.right <= barStart + 0.5f,
            )
        }
    }

    /**
     * The icon buttons are pinned to 48 dp by their own tag. The two tabs are M3 FilterChips: 32 dp
     * drawn, with M3's own minimum touch target around them, so their tag measures 32 dp and they
     * are only checked for position here.
     */
    private fun assertAtLeast48dp(tags: List<String>) {
        for (tag in tags) {
            val b = bounds(tag)
            assertTrue("$tag is ${b.width / density} x ${b.height / density} dp", b.width / density >= 48f - 0.5f)
            assertTrue("$tag is ${b.width / density} x ${b.height / density} dp", b.height / density >= 48f - 0.5f)
        }
    }

    private val selectionBarButtonTags = listOf(
        "selection_back_button", "selection_copy_favourites_button", "selection_share_all_button",
    )

    private fun assertClearOfTheLeftBar(tags: List<String>) {
        val barEnd = navDp * density
        for (tag in tags) {
            val b = bounds(tag)
            assertTrue(
                "$tag starts at ${b.left / density} dp, the navigation bar ends at $navDp dp",
                b.left >= barEnd - 0.5f,
            )
        }
    }

    private fun assertNoOverlap(tags: List<String>) {
        val all = tags.map { it to bounds(it) }
        for (i in all.indices) for (j in i + 1 until all.size) {
            val (a, ra) = all[i]
            val (b, rb) = all[j]
            val overlaps = ra.left < rb.right - 0.5f && rb.left < ra.right - 0.5f &&
                ra.top < rb.bottom - 0.5f && rb.top < ra.bottom - 0.5f
            assertFalse("$a $ra overlaps $b $rb", overlaps)
        }
    }

    @Composable
    private fun TopBar(showControls: Boolean) {
        Box(Modifier.fillMaxSize()) {
            PhoneModeTopBar(
                showControls = showControls,
                showDoneWithCard = showControls,
                guideOpen = false,
                exifOverlayOn = false,
                onToggleExif = {},
                onDoneWithCard = {},
                onReload = {},
                onHelp = {},
                onSettings = {},
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }

    @Composable
    private fun SelectionViewer() {
        SelectionFolderViewer(
            images = listOf(photo),
            currentIndex = 0,
            folderName = "Selection",
            activeTab = SelectionViewerTab.FAVOURITES,
            showTabs = true,
            favouriteCount = 1,
            selectionCount = 1,
            showExifOverlay = false,
            onSelectTab = {},
            onShareBatch = {},
            onCopyFavouritesToFolder = {},
            onNavigate = {},
            onClose = {},
        )
    }

    // ── A: 3-button navigation on the right (rotation 90) ───────────────────

    @Test
    @Requirement("REQ-TOK-UX.02")
    fun feedTopBar_rightNavigationBar_noControlUnderIt() {
        show { TopBar(showControls = true) }
        navigationBar(right = navDp)
        assertClearOfTheRightBar(feedTopBarTags)
        assertAtLeast48dp(feedTopBarTags)
    }

    @Test
    @Requirement("REQ-TOK-UX.02")
    fun landingTopBar_rightNavigationBar_settingsClearOfIt() {
        show { TopBar(showControls = false) }
        navigationBar(right = navDp)
        assertClearOfTheRightBar(landingTopBarTags)
    }

    @Test
    @Requirement("REQ-TOK-UX.02")
    fun selectionViewerBar_rightNavigationBar_noControlUnderIt() {
        show { SelectionViewer() }
        navigationBar(right = navDp)
        assertClearOfTheRightBar(selectionBarTags)
        assertAtLeast48dp(selectionBarButtonTags)
    }

    // ── B: 3-button navigation on the left (rotation 270) ───────────────────

    @Test
    @Requirement("REQ-TOK-UX.02")
    fun feedTopBar_leftNavigationBar_noControlUnderIt() {
        show { TopBar(showControls = true) }
        navigationBar(left = navDp)
        assertClearOfTheLeftBar(feedTopBarTags)
    }

    @Test
    @Requirement("REQ-TOK-UX.02")
    fun selectionViewerBar_leftNavigationBar_noControlUnderIt() {
        show { SelectionViewer() }
        navigationBar(left = navDp)
        assertClearOfTheLeftBar(selectionBarTags)
    }

    /** Left edge (dp) of the RAW+JPG badge text: the badges row's tag sits outside its padding. */
    private fun rawJpgBadgeLeftDp(): Float {
        val label = rule.activity.getString(R.string.chip_raw_jpg)
        return rule.onAllNodes(hasText(label), useUnmergedTree = true).onFirst()
            .fetchSemanticsNode().boundsInRoot.left / density
    }

    @Composable
    private fun ViewerWithBadges(readOnly: Boolean) {
        PhoneModeViewer(
            images = listOf(photo),
            currentIndex = 0,
            portraitSectionStart = -1,
            onNavigate = {},
            onAddToCollection = {},
            onRequestDelete = {},
            rawCompanionUris = setOf(photo.uri),
            readOnly = readOnly,
        )
    }

    @Test
    fun readOnlyBadges_leftNavigationBar_clearOfIt() {
        show { ViewerWithBadges(readOnly = true) }
        navigationBar(left = navDp)
        val left = rawJpgBadgeLeftDp()
        assertTrue("the badge starts at $left dp, the navigation bar ends at $navDp dp", left >= navDp - 0.5f)
    }

    @Test
    fun feedBadges_notInsetByTheViewer() {
        // The feed's landscape viewer sits between the side panels, which already inset: the
        // viewer itself must not add the navigation bar a second time.
        show { ViewerWithBadges(readOnly = false) }
        navigationBar(left = navDp)
        val left = rawJpgBadgeLeftDp()
        assertTrue("the feed badge starts at $left dp", left < navDp)
    }

    // ── Pixel 4a at rotation 1 (dumpsys window, 2026-11-05): BOTH sides at once ──
    // Display cutout on the left, 136 px (49.5 dp at 440 dpi); 3-button navigation bar on the
    // right, 132 px (48 dp). The cutout is dispatched as a real displayCutout inset.

    private val cutoutPx = 136
    private val navPx = 132

    private fun pixel4aRotation1Insets() {
        rule.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(cutoutPx, 0, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, navPx, 0))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                .build()
            ViewCompat.dispatchApplyWindowInsets(rule.activity.window.decorView, insets)
        }
        rule.waitForIdle()
    }

    private fun assertClearOfCutoutAndBar(tags: List<String>) {
        val rootWidth = rule.onRoot().fetchSemanticsNode().boundsInRoot.width
        for (tag in tags) {
            val b = bounds(tag)
            assertTrue(
                "$tag starts at ${b.left / density} dp, the cutout ends at ${cutoutPx / density} dp",
                b.left >= cutoutPx - 0.5f,
            )
            assertTrue(
                "$tag ends at ${b.right / density} dp, the navigation bar starts at ${(rootWidth - navPx) / density} dp",
                b.right <= rootWidth - navPx + 0.5f,
            )
        }
    }

    @Test
    @Requirement("REQ-TOK-UX.02")
    fun feedTopBar_leftCutoutAndRightNavigationBar_clearOfBoth() {
        show { TopBar(showControls = true) }
        pixel4aRotation1Insets()
        assertClearOfCutoutAndBar(feedTopBarTags)
        assertAtLeast48dp(feedTopBarTags)
    }

    @Test
    @Requirement("REQ-TOK-UX.02")
    fun landingTopBar_leftCutoutAndRightNavigationBar_clearOfBoth() {
        show { TopBar(showControls = false) }
        pixel4aRotation1Insets()
        assertClearOfCutoutAndBar(landingTopBarTags)
    }

    @Test
    @Requirement("REQ-TOK-UX.02")
    fun selectionViewerBar_leftCutoutAndRightNavigationBar_clearOfBoth() {
        show { SelectionViewer() }
        pixel4aRotation1Insets()
        assertClearOfCutoutAndBar(selectionBarTags)
        assertAtLeast48dp(selectionBarButtonTags)
    }

    @Test
    fun landingColumn_leftCutout_insetLikeTheTopBar() {
        show {
            Box(Modifier.fillMaxSize()) {
                PhoneModeLanding(
                    sourceFolderUri = "content://tree/PSTLab",
                    sourceFolderName = "PSTLab",
                    collectionFolderName = "Selection",
                    isLoading = false,
                    onSelectSource = {},
                    onSelectCollection = {},
                    onStart = {},
                )
                TopBar(showControls = false)
            }
        }
        val before = bounds("landing_logo")
        pixel4aRotation1Insets()
        val after = bounds("landing_logo")
        // The column is centred: a left cutout of c and a right bar of n shift its centre by (c - n) / 2.
        // Without the column's safe-drawing inset it moved by -n / 2 (the cutout was ignored).
        val expectedShiftDp = (cutoutPx - navPx) / 2f / density
        assertEquals(
            "landing logo centre shift (dp)",
            expectedShiftDp,
            (after.center.x - before.center.x) / density,
            0.5f,
        )
        assertClearOfCutoutAndBar(landingTopBarTags)
    }

    // ── C: gesture navigation (bottom-only inset): nothing moves ────────────

    @Test
    fun feedTopBar_bottomOnlyInset_boundsUnchanged() {
        show { TopBar(showControls = true) }
        val before = feedTopBarTags.associateWith { bounds(it) }
        navigationBar(bottom = 24)
        for (tag in feedTopBarTags) assertEquals("$tag moved", before.getValue(tag), bounds(tag))
    }

    @Test
    fun selectionBar_bottomOnlyInset_boundsUnchanged() {
        show { SelectionViewer() }
        val before = selectionBarTags.associateWith { bounds(it) }
        navigationBar(bottom = 24)
        for (tag in selectionBarTags) assertEquals("$tag moved", before.getValue(tag), bounds(tag))
    }

    // ── German at font scale 2.0: the tabs wrap, no control is clipped or overlapped ──

    @Test
    @Config(qualifiers = "de-w851dp-h393dp-land-440dpi")
    @Requirement("REQ-TOK-UX.05")
    fun selectionViewerBar_germanFontScale2_rightNavigationBar_fitsWithoutOverlap() {
        show {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                SelectionViewer()
            }
        }
        navigationBar(right = navDp)
        assertClearOfTheRightBar(selectionBarTags)
        assertNoOverlap(selectionBarTags)
        assertAtLeast48dp(selectionBarButtonTags)
    }

    @Test
    @Config(qualifiers = "de-w851dp-h393dp-land-440dpi")
    @Requirement("REQ-TOK-UX.05")
    fun feedTopBar_germanFontScale2_leftNavigationBar_fitsWithoutOverlap() {
        show {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                TopBar(showControls = true)
            }
        }
        navigationBar(left = navDp)
        assertClearOfTheLeftBar(feedTopBarTags)
        assertNoOverlap(feedTopBarTags)
        assertAtLeast48dp(feedTopBarTags)
    }
}
