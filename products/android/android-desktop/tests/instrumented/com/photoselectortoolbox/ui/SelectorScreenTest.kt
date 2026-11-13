package com.photoselectortoolbox.ui

// Explicitly imported, never `androidx.compose.ui.test.*`. The wildcard is what
// let a non-existent matcher (`hasTag`, when the real one is `hasTestTag`) pass
// review and fail only inside the emulator job — see `docs/build/CI_PARITY.md`
// § Compose instrumented-test conventions.
// `assertExists`, `assertDoesNotExist`, `onNode` and `onAllNodes` are members of
// `SemanticsNodeInteraction`/`SemanticsNodeInteractionsProvider`, not extensions,
// so they carry no import. Everything below is a genuine extension function.
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.MainActivity
import com.photoselectortoolbox.data.cache.ScoreDao
import com.photoselectortoolbox.data.cache.ScoreEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.guidance.SidebarAction
import com.photoselectortoolbox.data.repository.FakeImageRepository
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.domain.guidance.SelectorHint
import com.photoselectortoolbox.domain.session.SelectorSortOrder
import com.photoselectortoolbox.ui.selector.FrameGeometry
import com.photoselectortoolbox.ui.selector.SidebarWidth
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertTrue
import kotlin.math.roundToInt
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import com.photoselectortoolbox.str
import com.photoselectortoolbox.R

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SelectorScreenTest {

    // Outermost: the activity must start in the geometry the test asks for.
    @get:Rule(order = 0)
    val geometryRule = WindowGeometryRule()

    @get:Rule(order = 1)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    // Innermost: tells a hang in the Compose rule's teardown from one in the test (M-9, 2026-11-09).
    @get:Rule(order = 3)
    val bodyProbe: TestRule = geometryRule.bodyProbe

    @Inject
    lateinit var repository: ImageRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var scoreDao: ScoreDao

    private val fakeRepo: FakeImageRepository
        get() = repository as FakeImageRepository

    private val mockImages = listOf(
        ImageItem(
            uri = "content://test/test_folder/image1.jpg",
            fileName = "image1.jpg",
            fileSize = 1024L,
            lastModified = 1000L,
            mimeType = "image/jpeg",
            imageWidth = 1920,
            imageHeight = 1080,
            exifData = ExifData(
                shutterSpeed = 0.005, // 1/200s
                aperture = 2.8,
                iso = 100,
                focalLength = 50.0,
                focalLength35mm = 50.0,
                lens = "FE 50mm F1.2 GM",
                isFallback = false
            )
        ),
        ImageItem(
            uri = "content://test/test_folder/image2.jpg",
            fileName = "image2.jpg",
            fileSize = 2048L,
            lastModified = 2000L,
            mimeType = "image/jpeg",
            imageWidth = 1920,
            imageHeight = 1080,
            exifData = ExifData(
                shutterSpeed = 0.008, // 1/125s
                aperture = 4.0,
                iso = 200,
                focalLength = 85.0,
                focalLength35mm = 85.0,
                lens = "FE 85mm F1.4 GM",
                isFallback = false
            )
        )
    )

    @Before
    fun setup() {
        hiltRule.inject()
        composeRule.enableAccessibilityChecks()
        // A settled folder is the default: enumeration finished, so the
        // position readout carries no `+`.
        fakeRepo.completeAfterFirstBatch = true
        fakeRepo.canTrashResult = false
        fakeRepo.folderName = "Test Folder"
        runBlocking {
            scoreDao.deleteAll()
            settingsRepository.setLastFolderUri(null)
            settingsRepository.setSortingEnabled(true)
            settingsRepository.setGroupingEnabled(false)
            // The default order is newest first. These tests count frames in the
            // order the fixtures are listed (image1, image2, image3), which is
            // file-name order; the new order has its own tests below.
            settingsRepository.setSelectorSortOrder(SelectorSortOrder.FILE_NAME)
            // The one-time coach affordances are overlays. Left un-dismissed
            // they sit on top of the frames these tests are asserting about,
            // and whether they appear depends on leftover DataStore state — so
            // pin them off and cover them in their own test instead.
            settingsRepository.setHasSeenNavHint(true)
            settingsRepository.setHasSeenFullscreenGestureHint(true)
            // Same reasoning for the coach-mark guide and the one-time action
            // explanations: the guide opens itself on a first launch and would
            // sit over every assertion below. Pinned off here, exercised in
            // their own tests.
            SelectorHint.entries.forEach { settingsRepository.markHintSeen(it) }
            settingsRepository.setFilmstripVisible(true)
            settingsRepository.setDetailsVisible(true)
            settingsRepository.setPendingDeleteJournal(emptySet())
        }
    }

    @After
    fun teardown() {
        fakeRepo.folderName = "Test Folder"
        runBlocking {
            scoreDao.deleteAll()
            settingsRepository.setLastFolderUri(null)
            settingsRepository.setPendingDeleteJournal(emptySet())
        }
    }

    @Test
    fun initialEmptyState_isDisplayed() {
        // App starts with no folder selected, should show the empty state card.
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText(str(R.string.empty_select_folder), ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onAllNodesWithText(str(R.string.empty_select_folder), ignoreCase = true).onFirst()
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            "Choose a shoot folder to start comparing and culling frames.",
            substring = true,
            ignoreCase = true,
        ).assertIsDisplayed()
    }

    @Test
    fun revokedGrant_showsReselectFolderPrompt() {
        fakeRepo.folderName = null
        runBlocking {
            settingsRepository.setLastFolderUri("content://test/revoked_folder")
        }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithTag("missing_grant_prompt_card")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("missing_grant_prompt_card").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.empty_folder_access_needed), ignoreCase = true).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.action_reselect_folder), ignoreCase = true).assertIsDisplayed()
    }

    @Test
    fun folderLoaded_reviewUiAndExifShown() {
        // Prepare mock images
        fakeRepo.imagesFlow.value = mockImages

        // Simulate folder loading by setting preferred folder URI
        runBlocking {
            settingsRepository.setLastFolderUri("content://test/test_folder")
        }

        // Wait until empty state disappears and review UI appears
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Dismiss gesture tutorial overlay if shown
        dismissGestureTutorialIfShown()

        // Verify active photo and EXIF details are shown in UI. The details
        // panel presents ISO as a labelled row ("ISO" / "100") rather than the
        // run-together "ISO 100" of the one-line summary, so assert on the
        // value itself — it is present in either presentation.
        composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true).onFirst()
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("1/200s", substring = true).onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("f/2.8", substring = true).onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("100", substring = true).onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("50mm", substring = true).onFirst().assertIsDisplayed()
    }

    @ExpandedWindow
    @Test
    fun threeUpLayout_allThreeFramesAreExactlyTheSameSize() {
        // The core promise of the layout: the active frame is marked only by a
        // border and by being centred, never by being bigger. A neighbour at a
        // different size cannot be judged for sharpness against the current
        // frame, which is the entire task.
        //
        // This is asserted rather than eyeballed because the failure mode is
        // invisible to review: `aspectRatio` resolves the width constraint
        // first by default, so a wide frame quietly derives a different height
        // from a narrow one. See the 2026-07-27 entry in ai/memory/palette.md.
        fakeRepo.imagesFlow.value = mockImages
        runBlocking { settingsRepository.setLastFolderUri("content://test/test_folder") }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()

        requireExpandedLayout()
        assertTrue("the next frame is on screen", composeRule.onAllNodesWithTag("column_next").fetchSemanticsNodes().isNotEmpty())

        val current = composeRule.onNodeWithTag("column_current").getUnclippedBoundsInRoot()
        val previous = composeRule.onNodeWithTag("column_previous").getUnclippedBoundsInRoot()
        val next = composeRule.onNodeWithTag("column_next").getUnclippedBoundsInRoot()

        listOf("previous" to previous, "next" to next).forEach { (name, neighbour) ->
            val heightDelta = kotlin.math.abs(
                (current.bottom - current.top).value - (neighbour.bottom - neighbour.top).value
            )
            val widthDelta = kotlin.math.abs(
                (current.right - current.left).value - (neighbour.right - neighbour.left).value
            )
            // 2dp of slack: the active tile's border is 2dp where a resting
            // neighbour's is 1dp.
            assert(heightDelta <= 2f) {
                "current frame is ${(current.bottom - current.top).value}dp tall but " +
                    "$name is ${(neighbour.bottom - neighbour.top).value}dp"
            }
            assert(widthDelta <= 2f) {
                "current frame is ${(current.right - current.left).value}dp wide but " +
                    "$name is ${(neighbour.right - neighbour.left).value}dp"
            }
        }
    }

    @ExpandedWindow
    @Test
    fun hidingTheDetails_keepsTheControlsAndLeavesTheFrameCentred() {
        // The reported bug, in one test. Switching the readouts off collapsed
        // the flank to 0 dp, which took the control block with it — including
        // the details toggle that is the only way back — and un-centred the
        // current frame, because it is centred by flank | frame | flank rather
        // than by an Alignment.
        fakeRepo.imagesFlow.value = mockImages
        runBlocking { settingsRepository.setLastFolderUri("content://test/test_folder") }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()

        requireExpandedLayout()
        assertTrue("the details toggle is on screen", composeRule.onAllNodesWithTag("details_toggle").fetchSemanticsNodes().isNotEmpty())

        composeRule.onNodeWithTag("details_toggle").performClick()
        composeRule.waitForIdle()

        // The way back is still on screen.
        composeRule.onNodeWithTag("control_block").assertExists()
        composeRule.onNodeWithTag("details_toggle").assertExists()

        val region = composeRule.onNodeWithTag("image_region").getUnclippedBoundsInRoot()
        val current = composeRule.onNodeWithTag("column_current").getUnclippedBoundsInRoot()
        val leftGap = (current.left - region.left).value
        val rightGap = (region.right - current.right).value

        assert(kotlin.math.abs(leftGap - rightGap) <= 2f) {
            "current frame is off centre with the readouts hidden: " +
                "${leftGap}dp to its left, ${rightGap}dp to its right"
        }
    }

    @ExpandedWindow
    @Test
    fun threeUpLayout_framesUseTheHeightTheDisplayAllows() {
        // A size floor, not a style preference. Both previous revisions of this
        // screen shipped with frames far smaller than the display allowed — the
        // first arranged them in a row (width-bound, 40% of the height unused),
        // the second stacked an app bar, an action row and a filmstrip into the
        // one axis that was scarce. Neither was caught by a test, because there
        // wasn't one.
        //
        // The assertion is a comparison against the *window*, not a fixed
        // percentage. `FrameGeometry.imageRegion` states the entire permitted
        // chrome budget — sidebar and outer padding, nothing in the vertical
        // stack — so re-deriving the frame from the measured window and
        // comparing gives an assertion that scales to any window and to any
        // photograph. A fixed 45 % did neither: at 1480x924 a 16:9 frame is
        // width-bound at 684x385 (two abreast want 1464 dp of a 1376 dp
        // region), so 41 % was the display's ceiling being reported as a
        // defect, on both this window and a 1280x800 one.
        //
        // The fixture is 3:2 — the standard camera ratio, and the one the
        // 450 dp figure in REQUIREMENTS §2 is quoted for.
        fakeRepo.imagesFlow.value = mockImages.map {
            it.copy(imageWidth = 3000, imageHeight = 2000)
        }
        runBlocking { settingsRepository.setLastFolderUri("content://test/test_folder") }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()

        requireExpandedLayout()
        assertTrue("the current frame is on screen", composeRule.onAllNodesWithTag("column_current").fetchSemanticsNodes().isNotEmpty())

        val rootBounds = composeRule.onRoot().getUnclippedBoundsInRoot()
        val windowHeight = rootBounds.bottom - rootBounds.top
        val frame = composeRule.onNodeWithTag("column_current").getUnclippedBoundsInRoot()
        val frameHeight = frame.bottom - frame.top

        val imageRegionBounds = composeRule.onNodeWithTag("image_region").getUnclippedBoundsInRoot()
        val statusBarHeight = (imageRegionBounds.top - rootBounds.top).coerceAtLeast(0.dp)
        // A display cutout (a punch-hole phone driven at the reference size, 86 dp on a Pixel 4a)
        // takes width from the window before the screen draws anything; what the solver is
        // offered is the window less that inset.
        val windowWidth = offeredWindowWidth()

        val region = FrameGeometry.imageRegion(windowWidth, windowHeight, SidebarWidth, statusBarHeight)
        val expected = FrameGeometry.threeUpLayout(
            regionWidth = region.width,
            regionHeight = region.height,
            aspect = 1.5f,
            detailsVisible = true,
            filmstripVisible = true,
        ).frame

        // 2 dp of slack for the active tile's 2 dp border against a 1 dp one.
        assert(frameHeight.value >= expected.height.value - 2f) {
            "frame is ${frameHeight.value}dp tall in a ${windowWidth.value}x" +
                "${windowHeight.value}dp window, but the sidebar and the outer padding are " +
                "the only chrome allowed and they leave room for ${expected.height.value}dp — " +
                "something is consuming the height budget " +
                "(image region ${imageRegionBounds}, status bar ${statusBarHeight.value}dp, root ${rootBounds})"
        }

        // The absolute floor, only where the window is the size the product is
        // designed for. A 1280x800 CI emulator is a smaller display than a Tab
        // S11 Ultra, not a regression.
        if (windowHeight >= FrameGeometry.ReferenceWindowHeight && windowWidth >= FrameGeometry.ReferenceWindowWidth) {
            assert(frameHeight >= FrameGeometry.MinimumReferenceFrameHeight) {
                "frame is only ${frameHeight.value}dp on a reference-class " +
                    "${windowHeight.value}dp window; the floor is " +
                    "${FrameGeometry.MinimumReferenceFrameHeight.value}dp"
            }
        }
    }

    @ExpandedWindow
    @Test
    fun filmstrip_defaultsToOffAndTheFramesKeepTheirFullHeightEitherWay() {
        // Two facts, and they are the same fact: the strip starts hidden
        // (REQUIREMENTS §2), and turning it on costs the frames nothing,
        // because it is a vertical column in the flank's horizontal slack
        // rather than a bar across the bottom. Asserting only the default
        // would let the strip quietly become expensive again for everyone who
        // switches it on; asserting only the cost would let the default drift.
        fakeRepo.imagesFlow.value = mockImages.map {
            it.copy(imageWidth = 3000, imageHeight = 2000)
        }
        runBlocking {
            settingsRepository.setFilmstripVisible(
                SettingsRepository.DEFAULT_FILMSTRIP_VISIBLE,
            )
            settingsRepository.setLastFolderUri("content://test/test_folder")
        }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()

        requireExpandedLayout()
        assertTrue("the current frame is on screen", composeRule.onAllNodesWithTag("column_current").fetchSemanticsNodes().isNotEmpty())

        composeRule.onAllNodesWithTag("filmstrip").assertCountEquals(0)
        val hidden = composeRule.onNodeWithTag("column_current").getUnclippedBoundsInRoot()

        runBlocking { settingsRepository.setFilmstripVisible(true) }
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithTag("filmstrip").fetchSemanticsNodes().isNotEmpty()
        }
        val shown = composeRule.onNodeWithTag("column_current").getUnclippedBoundsInRoot()
        val hiddenHeight = (hidden.bottom - hidden.top).value
        val shownHeight = (shown.bottom - shown.top).value

        // The strip is free on a window as wide as the reference one; on a narrower one (a display
        // cutout takes 86 dp from a Pixel 4a driven at the reference size) the solver itself says
        // what it costs, so each state is held to the solver's answer for the width offered.
        val rootBounds = composeRule.onRoot().getUnclippedBoundsInRoot()
        val imageRegionBounds = composeRule.onNodeWithTag("image_region").getUnclippedBoundsInRoot()
        val region = FrameGeometry.imageRegion(
            offeredWindowWidth(),
            rootBounds.bottom - rootBounds.top,
            SidebarWidth,
            (imageRegionBounds.top - rootBounds.top).coerceAtLeast(0.dp),
        )
        listOf(false to hiddenHeight, true to shownHeight).forEach { (strip, actual) ->
            val expected = FrameGeometry.threeUpLayout(
                regionWidth = region.width,
                regionHeight = region.height,
                aspect = 1.5f,
                detailsVisible = true,
                filmstripVisible = strip,
            ).frame.height.value
            assert(kotlin.math.abs(actual - expected) < 2f) {
                "with the filmstrip ${if (strip) "on" else "off"} the frames are ${actual}dp tall, " +
                    "the solver offers ${expected}dp"
            }
        }
        if (offeredWindowWidth() >= FrameGeometry.ReferenceWindowWidth) {
            assert(kotlin.math.abs(shownHeight - hiddenHeight) < 1f) {
                "turning the filmstrip on moved the frames from ${hiddenHeight}dp to " +
                    "${shownHeight}dp — it is back in the height budget"
            }
        }
    }

    @ExpandedWindow
    @Test
    fun filmstrip_isAVerticalColumnBesideTheFramesAndNeverOverThem() {
        // The filmstrip is the one piece of chrome that keeps trying to get back
        // into the vertical stack. Measured on the reference device, 76 dp of
        // full-width strip took the frames from 675x450 to 618x412 — 38 dp off
        // every frame, ~16 % of the area, for an affordance the culling loop
        // does not use. It belongs in the horizontal slack, and this is what
        // says so in a way a build can check.
        fakeRepo.imagesFlow.value = mockImages.map {
            it.copy(imageWidth = 3000, imageHeight = 2000)
        }
        runBlocking { settingsRepository.setLastFolderUri("content://test/test_folder") }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()

        requireExpandedLayout()
        val strips = composeRule.onAllNodesWithTag("filmstrip").fetchSemanticsNodes()
        assertTrue("the filmstrip is on screen", strips.isNotEmpty())

        val frames = listOf("column_current", "column_previous", "column_next")
            .flatMap { tag ->
                composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().map { tag to it.boundsInRoot }
            }

        strips.forEach { strip ->
            val s = strip.boundsInRoot
            frames.forEach { (name, f) ->
                val intersects = s.left < f.right && f.left < s.right &&
                    s.top < f.bottom && f.top < s.bottom
                assert(!intersects) { "the filmstrip at $s overlaps $name at $f" }
            }
            // Beside, not stacked: the strip's vertical band has to meet some
            // frame's. A strip across the bottom of the window meets none, and
            // that is exactly the arrangement that costs 38 dp a frame.
            val besideAFrame = frames.any { (_, f) -> s.top < f.bottom && f.top < s.bottom }
            assert(besideAFrame) {
                "the filmstrip at $s is in the vertical stack — no frame is beside it, so it " +
                    "is taking height from all three"
            }
            // Vertical, not horizontal. The orientation is the whole economy of
            // this screen: width is surplus and height is scarce, so a strip
            // that is wider than it is tall has been rotated back into the
            // expensive axis.
            assert(s.height > s.width) {
                "the filmstrip at $s is wider than it is tall — it is a bar again, not a column"
            }
            // Outer edge of the row it shares, which is the top one: the
            // sidebar owns the far left, so the strip takes the far right.
            // Deliberately measured against the current frame and not against
            // the widest frame on screen — Previous and Next are centred in the
            // *whole* region and reach further right than any flank does, which
            // is not an overlap because they are a row below.
            val current = frames.firstOrNull { (name, _) -> name == "column_current" }?.second
            if (current != null) {
                assert(s.left >= current.right) {
                    "the filmstrip at $s is not outboard of the current frame at $current"
                }
            }
        }
    }

    @ExpandedWindow
    @Test
    fun neighbourFrame_maximiseBadgeDoesNotOverlapItsValueOverlay() {
        // Both sit inside the same tile bounds, so their placement is a rule
        // (badge bottom-left on neighbours, values right edge) rather than an
        // accident. Overlap between two controls in different composables is
        // invisible in review — this is the same class of bug as the layout
        // toggle that once rendered on top of the fullscreen button.
        fakeRepo.imagesFlow.value = mockImages
        runBlocking { settingsRepository.setLastFolderUri("content://test/test_folder") }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()

        requireExpandedLayout()
        val overlays = composeRule.onAllNodesWithTag("neighbour_overlay").fetchSemanticsNodes()
        val badges = composeRule.onAllNodesWithTag("maximise_badge").fetchSemanticsNodes()
        assertTrue("a neighbour value overlay is on screen", overlays.isNotEmpty())
        assertTrue("a maximise badge is on screen", badges.isNotEmpty())

        overlays.forEach { overlay ->
            badges.forEach { badge ->
                val a = overlay.boundsInRoot
                val b = badge.boundsInRoot
                val intersects = a.left < b.right && b.left < a.right &&
                    a.top < b.bottom && b.top < a.bottom
                assert(!intersects) {
                    "a value overlay at $a intersects a maximise badge at $b"
                }
            }
        }
    }

    // ── AD-11: the first physical arrow key must act ─────────────────────

    private fun loadTwoImages() {
        fakeRepo.imagesFlow.value = mockImages
        runBlocking { settingsRepository.setLastFolderUri("content://test/test_folder") }
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()
        composeRule.waitForIdle()
    }

    private fun positionCounterShows(text: String): Boolean =
        composeRule.onAllNodes(
            hasTestTag("position_counter") and hasText(text),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().isNotEmpty()

    @ExpandedWindow
    @Test
    fun firstRightArrowAfterLaunch_movesToTheSecondFrame() {
        loadTwoImages()
        // Expanded-only (the compact layout has its own keys); WindowGeometryRule makes the window expanded.
        requireExpandedLayout()

        // The app's own focus claim must have landed before the key (see awaitKeyFocusOnTheSelector).
        awaitKeyFocusOnTheSelector()

        // One key, through the window, like a keyboard: `sendKeyDownUpSync`
        // leaves touch mode. On a real Tab S11 Ultra the first press was spent on
        // Android's initial-focus pass and only the second one navigated.
        BoundedKeys.send(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)

        composeRule.waitUntil(timeoutMillis = 15000) { positionCounterShows("2 / 2") }
    }

    @ExpandedWindow
    @Test
    fun firstRightArrowAfterClosingTheGuide_movesToTheSecondFrame() {
        loadTwoImages()
        // Expanded-only (the compact layout has its own keys); WindowGeometryRule makes the window expanded.
        requireExpandedLayout()

        composeRule.onAllNodesWithTag("shortcuts_button", useUnmergedTree = true)
            .onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithTag("selector_coach_overlay", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()
        composeRule.waitForIdle()
        awaitKeyFocusOnTheSelector()

        BoundedKeys.send(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)

        composeRule.waitUntil(timeoutMillis = 15000) { positionCounterShows("2 / 2") }
    }

    private fun activityWindowHasFocus(): Boolean {
        var focused = false
        composeRule.activityRule.scenario.onActivity { focused = it.window.decorView.hasWindowFocus() }
        return focused
    }

    /**
     * Waits until the activity's window holds window focus and `selector_root` holds key focus,
     * the state a person's first key press meets. A key sent earlier reaches a window nobody has
     * focus in and is dropped: on an Xperia XA2 (API 37, 2026-11-09, M-8) SystemUI had restarted
     * 5 s before the test and the window had not taken focus back when the key went out.
     *
     * This waits only; it never requests focus. The claim is the app's own (`SelectorScreen`,
     * keyed on window focus), so a selector that cannot take focus (AD-11: no `focusable()` on the
     * root, or a failed `requestFocus`) never reaches this state and the test fails here.
     */
    private fun awaitKeyFocusOnTheSelector() {
        try {
            composeRule.waitUntil(timeoutMillis = 15000) { activityWindowHasFocus() && selectorRootHasKeyFocus() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError(
                "the selector is not ready for a key after 15 s: activity window focus=${activityWindowHasFocus()}, " +
                    "selector_root key focus=${selectorRootHasKeyFocus()}",
                e,
            )
        }
    }

    // ── System Back does what Escape does (device lab 2026-11-06) ─────────

    private fun guideShown(): Boolean =
        composeRule.onAllNodesWithTag("selector_coach_overlay", useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    private fun activityFinishing(): Boolean {
        var finishing = false
        composeRule.activityRule.scenario.onActivity { finishing = it.isFinishing }
        return finishing
    }

    private fun backClaimedByTheApp(): Boolean {
        var claimed = false
        composeRule.activityRule.scenario.onActivity { claimed = it.onBackPressedDispatcher.hasEnabledCallbacks() }
        return claimed
    }

    @ExpandedWindow
    @Test
    fun systemBack_closesTheGuideNotTheApp() {
        loadTwoImages()
        requireExpandedLayout()
        composeRule.onAllNodesWithTag("shortcuts_button", useUnmergedTree = true)
            .onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 15000) { guideShown() }

        // The real BACK key through the window, as `adb shell input keyevent KEYCODE_BACK` sends it.
        BoundedKeys.send(android.view.KeyEvent.KEYCODE_BACK)

        composeRule.waitUntil(timeoutMillis = 15000) { !guideShown() }
        assertTrue("one Back closes the guide; it must not close the app", !activityFinishing())
        // Nothing is open any more, so Back is the system's again.
        assertTrue("with nothing open Back must fall through", !backClaimedByTheApp())
    }

    @ExpandedWindow
    @Test
    fun systemBack_leavesAMaximisedFrameThenFallsThrough() {
        loadTwoImages()
        requireExpandedLayout()
        BoundedKeys.send(android.view.KeyEvent.KEYCODE_2)
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithTag("maximised_frame", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        BoundedKeys.send(android.view.KeyEvent.KEYCODE_BACK)

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithTag("maximised_frame", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
        assertTrue("one Back leaves the maximised frame; it must not close the app", !activityFinishing())
        assertTrue("three-up is back", positionCounterShows("1 / 2"))
        assertTrue("at the bare selector Back is the system's", !backClaimedByTheApp())
    }

    @ExpandedWindow
    @Test
    fun systemBack_fromLearnReturnsToTheSelector() {
        loadTwoImages()
        requireExpandedLayout()
        // Learn and Dupes sit behind Tools ([USER 2026-11-12] decision #18).
        composeRule.onAllNodesWithTag("nav_tools", useUnmergedTree = true).onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithTag("nav_inspect", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag("nav_inspect", useUnmergedTree = true).onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithTag("inspect_screen", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        BoundedKeys.send(android.view.KeyEvent.KEYCODE_BACK)

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithTag("inspect_screen", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
        assertTrue("Back from Learn returns to Cull, not to the launcher", !activityFinishing())
        assertTrue("the selector is on screen", positionCounterShows("1 / 2"))
    }

    // ── Go to photo ───────────────────────────────────────────────────────

    private fun goToPhotoDialogShown(): Boolean =
        composeRule.onAllNodes(hasTestTag("go_to_photo_field"), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    @ExpandedWindow
    @Test
    fun goToPhoto_keyG_opensTheDialog_aTypedOneDoesNotMaximise_escCloses_andFocusReturns() {
        loadTwoImages()
        // Expanded-only (the compact layout has no selector key handler); WindowGeometryRule makes the window expanded.
        requireExpandedLayout()

        pressKey(android.view.KeyEvent.KEYCODE_G)
        composeRule.waitUntil(timeoutMillis = 5000) { goToPhotoDialogShown() }

        // A real `1` key into the focused field: it is text, not "maximise the left frame".
        pressKey(android.view.KeyEvent.KEYCODE_1)
        composeRule.waitForIdle()
        assertTrue("the field took the 1", composeRule.onAllNodes(
            hasTestTag("go_to_photo_field") and hasText("1"),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().isNotEmpty())
        // A maximised frame hides the control block (and its counter) entirely.
        assertTrue("no frame was maximised behind the dialog", positionCounterShows("1 / 2"))

        // One Esc closes it even with a soft keyboard up (this AVD has no `hw.keyboard`):
        // without the pre-IME intercept the IME took this Esc to hide itself.
        pressKey(android.view.KeyEvent.KEYCODE_ESCAPE)
        composeRule.waitUntil(timeoutMillis = 5000) { !goToPhotoDialogShown() }

        // Focus is back on the selector root: the very next arrow key navigates. The dialog's node
        // leaves the tree before its window is gone: the activity's window takes focus a frame or
        // two later and the selector's reclaim runs after that (`SelectorScreen`, `windowFocused`),
        // so an arrow sent on the dialog's disappearance reaches a window nobody has focus in and
        // is dropped (Pixel Tablet, API 37, 2026-11-05: dialog gone at 43.435, activity window back
        // at 43.565, about 1 run in 5). A person cannot press a key that fast; the test waits for
        // the state the key needs, then sends it once.
        composeRule.waitUntil(timeoutMillis = 5000) { selectorRootHasKeyFocus() }
        pressKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        composeRule.waitUntil(timeoutMillis = 5000) { positionCounterShows("2 / 2") }
    }

    private fun selectorRootHasKeyFocus(): Boolean =
        composeRule.onAllNodes(hasTestTag("selector_root") and isFocused(), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    @ExpandedWindow
    @Test
    fun goToPhoto_tappingTheCounter_opensTheDialog_andGoJumps() {
        loadTwoImages()
        requireExpandedLayout()

        composeRule.onAllNodesWithTag("position_counter", useUnmergedTree = true)
            .onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 5000) { goToPhotoDialogShown() }

        composeRule.onNodeWithTag("go_to_photo_field").performTextInput("2")
        composeRule.onNodeWithTag("go_to_photo_go").performClick()

        composeRule.waitUntil(timeoutMillis = 5000) { !goToPhotoDialogShown() }
        composeRule.waitUntil(timeoutMillis = 5000) { positionCounterShows("2 / 2") }
    }

    @ExpandedWindow
    @Test
    fun duplicates_opensTheFolderTheSelectorHasOpen() {
        loadTwoImages()
        requireExpandedLayout()

        // Learn and Dupes sit behind Tools ([USER 2026-11-12] decision #18).
        composeRule.onAllNodesWithTag("nav_tools", useUnmergedTree = true).onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithTag("nav_duplicates", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag("nav_duplicates", useUnmergedTree = true).onFirst().performClick()

        // Not "No Folder Selected": the folder open in Cull is open here (FD-UX-3).
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText(str(R.string.action_start_scan), ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText(str(R.string.empty_no_folder_selected), ignoreCase = true).assertCountEquals(0)
    }

    /**
     * The confirmation card's line for [verb]: "Moved IMG_0001 · Selection" ([USER 2026-11-12]
     * decision #33 (1)). Scoped to the card, because the one-time filing hint card still says
     * "Moved to Selection".
     */
    private fun cardLine(verb: String) =
        hasText("$verb ", substring = true, ignoreCase = true) and
            hasText("· Selection", substring = true, ignoreCase = true) and
            hasAnyAncestor(hasTestTag("selector_snackbar"))

    private fun toolsMenuShown(): Boolean =
        composeRule.onAllNodesWithTag("tools_menu", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /**
     * The Tools menu by keyboard ([USER 2026-11-12] decision #18): arrows move between Learn and
     * Dupes, Enter opens the focused row, and `Esc` closes the menu with the selector still
     * taking the keys (its root reclaims focus when the window gets it back, AD-11).
     */
    @ExpandedWindow
    @Test
    fun toolsMenu_arrowsAndEnterOpenDupes_escClosesAndTheSelectorKeepsItsKeys() {
        loadTwoImages()
        requireExpandedLayout()

        composeRule.onAllNodesWithTag("nav_tools", useUnmergedTree = true).onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 5000) { toolsMenuShown() }
        pressKey(android.view.KeyEvent.KEYCODE_ESCAPE)
        composeRule.waitUntil(timeoutMillis = 5000) { !toolsMenuShown() }
        composeRule.waitUntil(timeoutMillis = 5000) { selectorRootHasKeyFocus() }
        pressKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        composeRule.waitUntil(timeoutMillis = 5000) { positionCounterShows("2 / 2") }

        composeRule.onAllNodesWithTag("nav_tools", useUnmergedTree = true).onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 5000) { toolsMenuShown() }
        pressKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        pressKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodes(hasTestTag("nav_duplicates") and isFocused(), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        pressKey(android.view.KeyEvent.KEYCODE_ENTER)
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText(str(R.string.action_start_scan), ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * On a screen with the rail, a keyboard user who opened Tools with Enter is back on Tools
     * after `Esc` ([USER 2026-11-12] decision #18, UX notes F-18).
     */
    @ExpandedWindow
    @Test
    fun toolsMenu_onTheRail_escReturnsFocusToTools() {
        loadTwoImages()
        requireExpandedLayout()
        composeRule.onAllNodesWithTag("nav_statistics", useUnmergedTree = true).onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithTag("selector_root", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }

        // A keyboard user is out of touch mode, and only then is a rail item focusable. A real Tab
        // gets there as a keyboard does (the window consumes the first navigation key to leave
        // touch mode). `Instrumentation.setInTouchMode(false)` did not: on API 30 the window learns
        // the new mode only with its next input event, so Compose stayed in touch mode and the
        // request below was refused (CI, PR #933).
        pressKey(android.view.KeyEvent.KEYCODE_TAB)
        composeRule.onAllNodesWithTag("nav_tools", useUnmergedTree = true).onFirst().requestFocus()
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodes(hasTestTag("nav_tools") and isFocused(), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        pressKey(android.view.KeyEvent.KEYCODE_ENTER)
        composeRule.waitUntil(timeoutMillis = 5000) { toolsMenuShown() }
        pressKey(android.view.KeyEvent.KEYCODE_ESCAPE)
        composeRule.waitUntil(timeoutMillis = 5000) { !toolsMenuShown() }
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodes(hasTestTag("nav_tools") and isFocused(), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @ExpandedWindow
    @Test
    fun sortOrder_newestFirst_isTheDefaultOrder_andTheMenuSwitchesIt() {
        runBlocking { settingsRepository.setSelectorSortOrder(SelectorSortOrder.NEWEST_FIRST) }
        loadTwoImagesNewestFirst()
        requireExpandedLayout()

        // Newest first: image2 (2000) is frame 1, so the counter reads 1 / 2 on image2.
        composeRule.waitUntil(timeoutMillis = 15000) { positionCounterShows("1 / 2") }
        assertTrue(
            composeRule.onAllNodesWithText("image2.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )

        composeRule.onAllNodesWithText(str(SidebarAction.MORE.label), useUnmergedTree = true)
            .onFirst().performClick()
        composeRule.onAllNodesWithTag("sort_oldest_first", useUnmergedTree = true)
            .onFirst().performClick()

        // The frame on screen stays on screen: image2 is now the second of two.
        composeRule.waitUntil(timeoutMillis = 5000) { positionCounterShows("2 / 2") }
    }

    @Test
    fun sortOrder_keyS_opensTheMenu_andPickingChangesTheOrder() {
        runBlocking { settingsRepository.setSelectorSortOrder(SelectorSortOrder.NEWEST_FIRST) }
        loadTwoImagesNewestFirst()
        // CI's phone-class emulator runs the compact layout, which has no selector key handler.
        if (isCompactLayout()) return
        composeRule.waitUntil(timeoutMillis = 15000) { positionCounterShows("1 / 2") }

        // One real `S` through the window: it opens the menu and does not re-order anything.
        pressKey(android.view.KeyEvent.KEYCODE_S)
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithTag("sort_oldest_first", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("S opens, it does not cycle", positionCounterShows("1 / 2"))

        composeRule.onAllNodesWithTag("sort_oldest_first", useUnmergedTree = true).onFirst().performClick()

        // The frame on screen stays on screen: image2 is now the second of two.
        composeRule.waitUntil(timeoutMillis = 5000) { positionCounterShows("2 / 2") }
    }

    private fun loadTwoImagesNewestFirst() {
        fakeRepo.imagesFlow.value = mockImages
        runBlocking { settingsRepository.setLastFolderUri("content://test/test_folder") }
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image2.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()
        composeRule.waitForIdle()
    }

    // ── Fullscreen: the arrow keys, the chrome and the EXIF ───────────────

    /** Three frames, so a move leaves two and there is somewhere to page to. */
    private fun loadThreeImages() {
        fakeRepo.imagesFlow.value = mockImages + mockImages[0].copy(
            uri = "content://test/test_folder/image3.jpg",
            fileName = "image3.jpg",
            exifData = mockImages[0].exifData!!.copy(iso = 3200),
        )
        runBlocking { settingsRepository.setLastFolderUri("content://test/test_folder") }
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()
        composeRule.waitForIdle()
    }

    private fun openFullscreen() {
        composeRule.onAllNodesWithTag("fullscreen_button", useUnmergedTree = true)
            .onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 15000) { fullscreenShows("1 / 3") }
        // The viewer is a window of its own and takes key focus a moment after it shows. A key sent
        // before that goes to the window behind it, which is how the first arrow of a class run on a
        // Pixel 4a was lost (2026-11-04); waiting for the claim keeps this a test of the viewer's
        // key handling, and a viewer that never claims focus still fails here.
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodes(
                hasTestTag("fullscreen_root") and
                    androidx.compose.ui.test.SemanticsMatcher.expectValue(
                        androidx.compose.ui.semantics.SemanticsProperties.Focused, true,
                    ),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun fullscreenShows(position: String): Boolean =
        composeRule.onAllNodes(
            hasTestTag("fullscreen_position") and hasText(position),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().isNotEmpty()

    private fun fullscreenChromeShown(): Boolean =
        composeRule.onAllNodes(hasTestTag("fullscreen_position"), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    private fun pressKey(keyCode: Int) {
        // One key through the window, like a keyboard: `sendKeyDownUpSync` leaves
        // touch mode, and `performKeyInput` would request focus first and so hide
        // exactly the failure under test.
        BoundedKeys.send(keyCode)
    }

    @ExpandedWindow
    @Test
    fun fullscreen_firstRightArrow_pagesOnTheFirstPress() {
        loadThreeImages()
        // Expanded-only (the compact layout has no fullscreen button); WindowGeometryRule makes the window expanded.
        requireExpandedLayout()
        openFullscreen()

        pressKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)

        composeRule.waitUntil(timeoutMillis = 5000) { fullscreenShows("2 / 3") }
    }

    @ExpandedWindow
    @Test
    fun fullscreen_arrowAfterTheChromeFaded_stillPages_andShowsTheNewFramesExif() {
        loadThreeImages()
        // Expanded-only (the compact layout has no fullscreen button); WindowGeometryRule makes the window expanded.
        requireExpandedLayout()
        openFullscreen()
        // The chrome fades after 3 s, and the button that held focus went with it.
        composeRule.waitUntil(timeoutMillis = 10000) { !fullscreenChromeShown() }

        pressKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)

        composeRule.waitUntil(timeoutMillis = 5000) { fullscreenShows("2 / 3") }
        // The key brought the info panel back, and the EXIF on it is the second frame's.
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodes(
                hasTestTag("exposure_token") and hasText("ISO 200", substring = true) and
                    hasAnyAncestor(hasTestTag("fullscreen_exif")),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @ExpandedWindow
    @Test
    fun fullscreen_arrowAfterAMoveInsideFullscreen_stillPages() {
        loadThreeImages()
        // Expanded-only (the compact layout has no fullscreen button); WindowGeometryRule makes the window expanded.
        requireExpandedLayout()
        openFullscreen()

        pressKey(android.view.KeyEvent.KEYCODE_M)
        composeRule.waitUntil(timeoutMillis = 10000) { fullscreenShows("1 / 2") }

        pressKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)

        composeRule.waitUntil(timeoutMillis = 5000) { fullscreenShows("2 / 2") }
    }

    @ExpandedWindow
    @Test
    fun fullscreen_moveShowsAConfirmationWithoutACountdown() {
        loadThreeImages()
        // Expanded-only (the compact layout has no fullscreen button); WindowGeometryRule makes the window expanded.
        requireExpandedLayout()
        openFullscreen()

        pressKey(android.view.KeyEvent.KEYCODE_M)

        composeRule.waitUntil(timeoutMillis = 10000) {
            composeRule.onAllNodes(
                cardLine(str(R.string.label_moved)),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        // A move's undo does not expire, so the card draws no deadline.
        composeRule.onAllNodesWithTag("snackbar_countdown", useUnmergedTree = true)
            .assertCountEquals(0)
    }

    /**
     * The window width the screen can actually use: the root's width less the horizontal inset
     * the screen applies (a display cutout; none on a tablet). The sidebar is the first thing
     * drawn at the inset's edge.
     */
    private fun offeredWindowWidth(): androidx.compose.ui.unit.Dp {
        val root = composeRule.onRoot().getUnclippedBoundsInRoot()
        val sidebar = composeRule.onAllNodesWithTag("selector_sidebar").onFirst().getUnclippedBoundsInRoot()
        return (root.right - root.left) - (sidebar.left - root.left).coerceAtLeast(0.dp)
    }

    /** True when the window is narrow enough to be running the phone layout. */
    private fun isCompactLayout(): Boolean =
        composeRule.onAllNodesWithTag("copy_button_compact").fetchSemanticsNodes().isNotEmpty()

    /**
     * The expanded-layout tests carry [ExpandedWindow], so [WindowGeometryRule] has made the window
     * expanded; this fails, instead of returning, when it is not. The 28 tests that used to start
     * with `if (isCompactLayout()) return` passed on CI's phone-class emulator without asserting
     * anything (FD-T-1).
     */
    private fun requireExpandedLayout() {
        val widthDp = composeRule.activity.resources.configuration.screenWidthDp
        assertTrue("the window must be expanded (>= 840 dp) but it is $widthDp dp wide", widthDp >= 840)
        assertTrue("the expanded layout is on screen", !isCompactLayout())
    }

    @Test
    fun navigateBetweenImages_updatesActiveExif() {
        fakeRepo.imagesFlow.value = mockImages
        runBlocking {
            settingsRepository.setLastFolderUri("content://test/test_folder")
        }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()

        // Determine layout: check for compact culling button vs expanded layout
        val isCompact = composeRule.onAllNodesWithTag("copy_button_compact")
            .fetchSemanticsNodes().isNotEmpty()

        if (isCompact) {
            // Horizontal swipe browses, on every layout in this product.
            composeRule.onAllNodesWithContentDescription("image1.jpg").onFirst().performTouchInput {
                swipeLeft()
            }
        } else {
            // In expanded layout, horizontal swipe across current frame also browses (REQ-AND-INPUT.02)
            composeRule.onNodeWithTag("column_current").performTouchInput {
                swipeLeft()
            }
        }

        composeRule.waitForIdle()

        // Verify the second image details are now shown
        composeRule.onAllNodesWithText("image2.jpg", ignoreCase = true).onFirst()
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("1/125s", substring = true).onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("f/4.0", substring = true).onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("200", substring = true).onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("85mm", substring = true).onFirst().assertIsDisplayed()
    }

    @Test
    fun cullingAction_CopyAndMove_showSnackbar() {
        fakeRepo.imagesFlow.value = mockImages
        runBlocking {
            settingsRepository.setLastFolderUri("content://test/test_folder")
        }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()

        val isCompact = composeRule.onAllNodesWithTag("copy_button_compact")
            .fetchSemanticsNodes().isNotEmpty()

        // Click Copy Button
        if (isCompact) {
            composeRule.onNodeWithTag("copy_button_compact").performClick()
        } else {
            composeRule.onNodeWithTag("copy_button_expanded").performClick()
        }
        composeRule.waitForIdle()

        try {
            composeRule.waitUntil(timeoutMillis = 15000) {
                composeRule.onAllNodes(cardLine(str(R.string.label_copied)), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Exception) {
            try { composeRule.onRoot().printToLog("SelectorScreenTest_Copy") } catch (_: Exception) {}
            throw e
        }
        composeRule.onAllNodes(cardLine(str(R.string.label_copied)), useUnmergedTree = true).onFirst().assertIsDisplayed()

        // Move straight away, as a photographer would, rather than waiting for the
        // copy card to leave (3 s now; it was the whole 30 s undo window when this
        // made the test the slowest on every device, device matrix 2026-10-19). It
        // cannot block the button: the card has no pointer input, and the next
        // confirmation replaces it. What can be late after the copy is the compact action bar
        // recomposing, so wait for the button itself (code_health 2026-10-10).
        val moveTag = if (isCompact) "move_button_compact" else "move_button_expanded"
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithTag(moveTag).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(moveTag).performClick()
        composeRule.waitForIdle()

        try {
            composeRule.waitUntil(timeoutMillis = 15000) {
                composeRule.onAllNodes(cardLine(str(R.string.label_moved)), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Exception) {
            try { composeRule.onRoot().printToLog("SelectorScreenTest_Move") } catch (_: Exception) {}
            throw e
        }
        composeRule.onAllNodes(cardLine(str(R.string.label_moved)), useUnmergedTree = true).onFirst().assertIsDisplayed()
        // One confirmation at a time: the move's replaced the copy's. Scoped to the
        // snackbar, because the one-time filing hint card repeats the sentence.
        assertTrue(
            "the copy snackbar is still showing beside the move's",
            composeRule.onAllNodes(cardLine(str(R.string.label_copied)), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun cullingAction_DeleteImage_removesImageAfterConfirmation() {
        fakeRepo.imagesFlow.value = mockImages
        runBlocking {
            settingsRepository.setLastFolderUri("content://test/test_folder")
        }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()

        val isCompact = composeRule.onAllNodesWithTag("copy_button_compact")
            .fetchSemanticsNodes().isNotEmpty()

        // Print tree before clicking delete to debug compact layout clicks
        try { composeRule.onRoot().printToLog("SelectorScreenTest_BeforeDelete") } catch (_: Exception) {}

        // Click Delete Button
        if (isCompact) {
            composeRule.onNodeWithTag("delete_button_compact").performClick()
        } else {
            composeRule.onNodeWithTag("delete_button_expanded").performClick()
        }

        // Verify Delete Confirmation Dialog is shown
        try {
            composeRule.waitUntil(timeoutMillis = 15000) {
                composeRule.onAllNodesWithText(str(R.string.dialog_title_delete_image), ignoreCase = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Exception) {
            try { composeRule.onRoot().printToLog("SelectorScreenTest_DeleteDialogTimeout") } catch (_: Exception) {}
            throw e
        }
        composeRule.onNodeWithText(str(R.string.dialog_title_delete_image), ignoreCase = true).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.action_cancel), ignoreCase = true).assertIsDisplayed()

        // Click Confirm Delete inside the Delete Image dialog
        composeRule.onNodeWithTag("dialog_confirm_delete").performClick()

        // Verify image1 is removed and image2 becomes active
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image2.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("image2.jpg", ignoreCase = true).onFirst()
            .assertIsDisplayed()
        composeRule.onNodeWithText("image1.jpg", ignoreCase = true).assertDoesNotExist()
    }

    @ExpandedWindow
    @Test
    fun scanImages_computesMetricsAndDisplaysScores() {
        fakeRepo.imagesFlow.value = mockImages

        // Pre-populate the cache for the mock image to avoid actual decoding failure
        runBlocking {
            scoreDao.insertOrUpdate(
                ScoreEntity(
                    filePath = "content://test/test_folder/image1.jpg",
                    fileSize = 1024L,
                    lastModified = 1000L,
                    sharpnessScore = 78.5,
                    noiseLevel = 1.2,
                    highlightClipping = 2.4,
                    shadowClipping = 0.5
                )
            )
            settingsRepository.setLastFolderUri("content://test/test_folder")
        }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()

        requireExpandedLayout()

        // Tap Scan button on sidebar/chrome
        composeRule.onAllNodesWithTag("scan_button", useUnmergedTree = true).onFirst().performClick()

        // Verify Scan Configuration sheet is shown and start scan
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText(str(R.string.title_scan_configuration), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText(str(R.string.title_scan_configuration), useUnmergedTree = true).onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText(str(R.string.action_start_scan), useUnmergedTree = true).onFirst().performClick()

        // Wait until metrics update and display in the UI
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("78.5", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Verify metrics are visible
        composeRule.onAllNodesWithText("78.5", substring = true).onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("1.2", substring = true).onFirst().assertIsDisplayed()
    }

    @ExpandedWindow
    @Test
    fun noTwoSelectorControlsShareBounds() {
        // Generalised from the regression it replaces: the layout toggle used
        // to be pinned to the same top-right corner as the fullscreen button,
        // in a different composable, so the two were invisible to each other in
        // review. The layout toggle is gone with the second layout, but the
        // class of bug is not, so this now checks every control in the block.
        fakeRepo.imagesFlow.value = mockImages
        runBlocking { settingsRepository.setLastFolderUri("content://test/test_folder") }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()

        requireExpandedLayout()

        val tags = listOf(
            "copy_button_expanded",
            "move_button_expanded",
            "delete_button_expanded",
            "fullscreen_button",
            "rail_previous",
            "rail_next",
            "details_toggle",
            "filmstrip_toggle",
            "overlay_values_toggle",
            "shortcuts_button",
            // The strip shares the control flank now, so it is in this check
            // rather than only in its own test: a column of thumbnails drawn
            // over the view toggles is exactly the overlap this test exists for.
            "filmstrip",
        )
        val bounds = tags.mapNotNull { tag ->
            val nodes = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes()
            if (nodes.isEmpty()) null else tag to nodes.first().boundsInRoot
        }

        bounds.forEachIndexed { i, (nameA, a) ->
            bounds.drop(i + 1).forEach { (nameB, b) ->
                val intersects = a.left < b.right && b.left < a.right &&
                    a.top < b.bottom && b.top < a.bottom
                assert(!intersects) { "$nameA at $a overlaps $nameB at $b" }
            }
        }
    }

    @ExpandedWindow
    @Test
    fun filingButtonsAreWordedAndNeverSayKeep() {
        // The label is derived from the Filing Action setting, so it says what
        // happens to the file. "Keep" describes a feeling, and under a move
        // configuration it is simply false — the file leaves the folder.
        fakeRepo.imagesFlow.value = mockImages
        runBlocking { settingsRepository.setLastFolderUri("content://test/test_folder") }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()

        requireExpandedLayout()

        composeRule.onAllNodesWithText(str(R.string.action_keep), substring = true, ignoreCase = true)
            .assertCountEquals(0)
        composeRule.onAllNodesWithText(str(R.string.action_copy)).fetchSemanticsNodes().let {
            assert(it.isNotEmpty()) { "no worded Copy control on screen" }
        }
        composeRule.onAllNodesWithText(str(R.string.action_move)).fetchSemanticsNodes().let {
            assert(it.isNotEmpty()) { "no worded Move control on screen" }
        }
    }

    @Test
    fun firstRun_navigationHintShowsOnceAndStaysDismissed() {
        fakeRepo.imagesFlow.value = mockImages
        runBlocking {
            settingsRepository.setHasSeenNavHint(false)
            settingsRepository.setLastFolderUri("content://test/test_folder")
        }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Compact layout uses the gesture tutorial instead of this hint.
        if (composeRule.onAllNodesWithTag("copy_button_compact").fetchSemanticsNodes().isNotEmpty()) {
            return
        }
        if (composeRule.onAllNodesWithTag("first_run_hint", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        ) {
            return
        }

        composeRule.onAllNodes(hasText(str(R.string.action_got_it), ignoreCase = true), useUnmergedTree = true)
            .onFirst().performClick()

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithTag("first_run_hint", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.onAllNodesWithTag("first_run_hint", useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun scoreChip_carriesDirectionAndBestOfThreeInItsDescription() {
        // A bare number with no stated direction is not interpretable, and a
        // sighted user gets the direction from the bar that a screen-reader
        // user does not.
        val scanned = mockImages.mapIndexed { idx, item ->
            item.copy(
                scanResult = com.photoselectortoolbox.data.model.ScanResult(
                    filePath = item.uri,
                    sharpnessScore = if (idx == 0) 88.3 else 22.4,
                )
            )
        }
        fakeRepo.imagesFlow.value = scanned
        runBlocking { settingsRepository.setLastFolderUri("content://test/test_folder") }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithContentDescription(str(R.string.label_higher_is_better), substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithContentDescription(str(R.string.label_higher_is_better), substring = true)
            .onFirst().assertExists()
    }

    @ExpandedWindow
    @Test
    fun legend_namesTheScoresAndTheSidebarIconsInPlace() {
        fakeRepo.imagesFlow.value = mockImages
        runBlocking {
            settingsRepository.setLastFolderUri("content://test/test_folder")
        }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()

        requireExpandedLayout()

        // No scan has run. The legend is still offered, because it now explains
        // the rail as well as the scores — which is when a new photographer
        // needs it most.
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithTag("score_legend_button", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag("score_legend_button", useUnmergedTree = true)
            .onFirst().performClick()

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithTag("selector_coach_overlay", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val inOverlay = hasAnyAncestor(hasTestTag("selector_coach_overlay"))

        // The scores, with the direction spelled out.
        composeRule.onAllNodes(hasText(str(R.string.label_metric_sharpness)) and inOverlay, useUnmergedTree = true)
            .onFirst().assertExists()
        composeRule.onAllNodes(hasText(str(R.string.label_metric_noise)) and inOverlay, useUnmergedTree = true)
            .onFirst().assertExists()
        composeRule.onAllNodes(
            hasText(str(R.string.label_higher_is_better), substring = true) and inOverlay,
            useUnmergedTree = true,
        ).onFirst().assertExists()
        composeRule.onAllNodes(
            hasText(str(R.string.label_lower_is_better), substring = true) and inOverlay,
            useUnmergedTree = true,
        ).onFirst().assertExists()

        // ...and the icons down the left, which the sheet this replaces never
        // mentioned at all.
        SidebarAction.entries.forEach { action ->
            composeRule.onAllNodes(hasText(str(action.label)) and inOverlay, useUnmergedTree = true)
                .onFirst().assertExists()
        }
    }

    @ExpandedWindow
    @Test
    fun positionReadout_marksAFolderThatIsStillEnumerating() {
        // Discovery streams, so for the first seconds of a large shoot the total
        // is a running total. `2 / 2` would state a folder size nobody counted.
        fakeRepo.completeAfterFirstBatch = false
        fakeRepo.imagesFlow.value = mockImages
        runBlocking { settingsRepository.setLastFolderUri("content://test/test_folder") }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()

        requireExpandedLayout()
        // An assertion, not an early return: a missing counter is the AD-1 defect
        // itself, and returning here is how it passed CI for as long as it did.
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithTag("position_counter", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Scoped to the counter node rather than matched as bare text: "1 / 2+"
        // is short enough to appear elsewhere by accident.
        composeRule.onAllNodes(
            hasTestTag("position_counter") and hasText("1 / 2+"),
            useUnmergedTree = true,
        ).onFirst().assertExists()
    }

    @ExpandedWindow
    @Test
    fun positionReadout_hasNoPlusOnceEnumerationHasSettled() {
        fakeRepo.imagesFlow.value = mockImages
        runBlocking { settingsRepository.setLastFolderUri("content://test/test_folder") }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()

        requireExpandedLayout()
        // An assertion, not an early return: a missing counter is the AD-1 defect
        // itself, and returning here is how it passed CI for as long as it did.
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithTag("position_counter", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodes(
                hasTestTag("position_counter") and hasText("1 / 2"),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodes(
            hasTestTag("position_counter") and hasText("1 / 2+"),
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }

    @ExpandedWindow
    @Test
    fun deleteAction_offersAnUndoThatBringsTheFrameBack() {
        // The affordance the snackbar has drawn a countdown next to since the
        // refresh, wired to null because nothing below the UI could reverse an
        // action (`ai/memory/code_health.md`, [OPEN] 2026-07-27). A deferred
        // delete is the strongest case: nothing has touched the disk, so the
        // undo is a list insertion and cannot fail.
        fakeRepo.imagesFlow.value = mockImages
        runBlocking { settingsRepository.setLastFolderUri("content://test/test_folder") }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()

        requireExpandedLayout()

        composeRule.onAllNodesWithTag("delete_button_expanded", useUnmergedTree = true)
            .onFirst().performClick()

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText(str(R.string.dialog_title_delete_image), ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("dialog_confirm_delete").performClick()

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithTag("snackbar_undo", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val inSnackbar = hasAnyAncestor(hasTestTag("selector_snackbar"))
        composeRule.onAllNodes(hasText(str(R.string.snackbar_deleted), substring = true) and inSnackbar, useUnmergedTree = true)
            .onFirst().assertExists()
        // UNDO restores files: the tagged node is the clickable and at least 48 dp both ways
        // (REQ-AND-INPUT.04), compared in whole pixels.
        val undo = composeRule.onAllNodesWithTag("snackbar_undo", useUnmergedTree = true)
            .onFirst().fetchSemanticsNode().boundsInRoot
        val minPx = (48 * composeRule.density.density).roundToInt()
        assertTrue("UNDO is ${undo.width} x ${undo.height} px, under $minPx", undo.width.roundToInt() >= minPx && undo.height.roundToInt() >= minPx)
        // A delete's undo lasts until the folder is left, so no card draws a deadline (FD-D-1).
        composeRule.onAllNodesWithTag("snackbar_countdown", useUnmergedTree = true)
            .assertCountEquals(0)

        composeRule.onAllNodesWithTag("snackbar_undo", useUnmergedTree = true)
            .onFirst().performClick()

        // The restored frame is a neighbour of the one on screen, so it is only on screen in the
        // expanded layout. If the window dropped to the compact one meanwhile (the display moving
        // under the test, Pixel 4a 2026-11-05), say so at once instead of timing out on a text
        // that cannot be there: the assertion below still demands the expanded layout.
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty() || isCompactLayout()
        }
        requireExpandedLayout()
        composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true).onFirst()
            .assertIsDisplayed()
    }

    @ExpandedWindow
    @Test
    fun copyAction_offersNoUndo_becauseTheOriginalNeverMoved() {
        // Undoing a copy could only mean deleting the file the photographer just
        // asked for. An UNDO that silently does nothing — or does the wrong
        // thing — is worse than none.
        fakeRepo.imagesFlow.value = mockImages
        runBlocking { settingsRepository.setLastFolderUri("content://test/test_folder") }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()

        requireExpandedLayout()

        composeRule.onAllNodesWithTag("copy_button_expanded", useUnmergedTree = true)
            .onFirst().performClick()

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodes(cardLine(str(R.string.label_copied)), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag("snackbar_undo", useUnmergedTree = true).assertCountEquals(0)
    }

    @ExpandedWindow
    @Test
    fun coachOverlay_calloutsNeverOverlapEachOther() {
        // The guide now carries the legend too — every score named, every icon
        // in the rail named — so the left flank holds three stacked blocks
        // where it once held one. Compose will happily draw them through each
        // other rather than complain, and unreadable overlapping text is
        // exactly the failure a legend cannot afford.
        fakeRepo.imagesFlow.value = mockImages
        runBlocking { settingsRepository.setLastFolderUri("content://test/test_folder") }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()

        requireExpandedLayout()
        if (composeRule.onAllNodesWithTag("shortcuts_button", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        ) return

        composeRule.onAllNodesWithTag("shortcuts_button", useUnmergedTree = true)
            .onFirst().performClick()

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithTag("coach_callout", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Paged (AD-3): every page is checked, and every page must explain
        // something — a page holding only the heading is a page to skip.
        forEachCoachPage {
            val callouts = composeRule.onAllNodesWithTag("coach_callout", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .map { it.boundsInRoot }
            assertTrue("a guide page carries no callout", callouts.isNotEmpty())

            for (i in callouts.indices) {
                for (j in i + 1 until callouts.size) {
                    val a = callouts[i]
                    val b = callouts[j]
                    val intersects = a.left < b.right && b.left < a.right &&
                        a.top < b.bottom && b.top < a.bottom
                    assert(!intersects) { "coach marks overlap: $a and $b" }
                }
            }
        }
    }

    @ExpandedWindow
    @Test
    fun coachOverlay_neverCoversAFrame() {
        // The whole reason this is a coach-mark overlay rather than a sheet is
        // that it labels the real chrome in place — which is only true if the
        // callouts land in the horizontal slack. An overlay drifting over the
        // photographs is invisible in review and obvious in use, so it is
        // asserted the same way the badge-versus-overlay rule is.
        fakeRepo.imagesFlow.value = mockImages
        runBlocking { settingsRepository.setLastFolderUri("content://test/test_folder") }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()

        requireExpandedLayout()
        assertTrue("the current frame is on screen", composeRule.onAllNodesWithTag("column_current").fetchSemanticsNodes().isNotEmpty())

        val frames = listOf("column_current", "column_previous", "column_next")
            .flatMap { tag ->
                composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().map { tag to it.boundsInRoot }
            }
        assertTrue("the frames are on screen", frames.isNotEmpty())

        composeRule.onAllNodesWithTag("shortcuts_button", useUnmergedTree = true)
            .onFirst().performClick()

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithTag("coach_callout", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        forEachCoachPage {
            val callouts = composeRule.onAllNodesWithTag("coach_callout", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .map { it.boundsInRoot }
            assertTrue("a guide page carries no callout", callouts.isNotEmpty())

            callouts.forEach { callout ->
                frames.forEach { (name, frame) ->
                    val intersects = callout.left < frame.right && frame.left < callout.right &&
                        callout.top < frame.bottom && frame.top < callout.bottom
                    assert(!intersects) { "a coach mark at $callout covers $name at $frame" }
                }
            }
        }
    }

    @ExpandedWindow
    @Test
    fun coachOverlay_opensFromTheControlBlockAndClosesOnGotIt() {
        fakeRepo.imagesFlow.value = mockImages
        runBlocking { settingsRepository.setLastFolderUri("content://test/test_folder") }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()

        requireExpandedLayout()
        if (composeRule.onAllNodesWithTag("shortcuts_button", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        ) {
            return
        }

        composeRule.onAllNodesWithTag("shortcuts_button", useUnmergedTree = true)
            .onFirst().performClick()

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithTag("selector_coach_overlay", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Scoped to the overlay: "Prev" and "Next" are on the control block
        // behind it as well, and a bare text match would find either.
        val inOverlay = hasAnyAncestor(hasTestTag("selector_coach_overlay"))
        // The guide is paged, so "The comparison" is on whichever page it packs into.
        var sawComparison = false
        forEachCoachPage {
            if (composeRule.onAllNodes(hasText(str(R.string.hint_title_tour_comparison)) and inOverlay, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            ) sawComparison = true
        }
        assertTrue("no page of the guide shows \"The comparison\"", sawComparison)

        composeRule.onAllNodesWithTag("coach_overlay_dismiss", useUnmergedTree = true)
            .onFirst().performClick()

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithTag("selector_coach_overlay", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.onAllNodesWithTag("selector_coach_overlay", useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @ExpandedWindow
    @Test
    fun firstFiling_explainsWhereThePhotographWentAndNeverSaysKeep() {
        // The one explanation that matters most: the frame left the centre of
        // the screen and nothing on it says where it went. The wording is built
        // from the user's own settings, so it names the configured verb — never
        // a euphemism, which under a move configuration would be false.
        fakeRepo.imagesFlow.value = mockImages
        runBlocking {
            settingsRepository.resetGuidance()
            settingsRepository.markHintSeen(SelectorHint.TOUR)
            settingsRepository.setHasSeenNavHint(true)
            settingsRepository.setLastFolderUri("content://test/test_folder")
        }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()

        requireExpandedLayout()
        if (composeRule.onAllNodesWithTag("copy_button_expanded").fetchSemanticsNodes().isEmpty()) {
            return
        }

        composeRule.onAllNodesWithTag("copy_button_expanded", useUnmergedTree = true)
            .onFirst().performClick()

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithTag("selector_hint_card", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        val inCard = hasAnyAncestor(hasTestTag("selector_hint_card"))
        composeRule.onAllNodes(hasText(str(R.string.snackbar_copied_to, "Selection")) and inCard, useUnmergedTree = true)
            .onFirst().assertExists()
        composeRule.onAllNodes(hasText(str(R.string.action_keep), substring = true, ignoreCase = true) and inCard,
            useUnmergedTree = true).assertCountEquals(0)

        // The card sits in the flank beside the frames, never over one.
        val cards = composeRule.onAllNodesWithTag("selector_hint_card", useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.boundsInRoot }
        val frames = listOf("column_current", "column_previous", "column_next")
            .flatMap { tag ->
                composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().map { tag to it.boundsInRoot }
            }
        cards.forEach { card ->
            frames.forEach { (name, frame) ->
                val intersects = card.left < frame.right && frame.left < card.right &&
                    card.top < frame.bottom && frame.top < card.bottom
                assert(!intersects) { "the explanation at $card covers $name at $frame" }
            }
        }

        composeRule.onAllNodesWithTag("selector_hint_dismiss", useUnmergedTree = true)
            .onFirst().performClick()

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithTag("selector_hint_card", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
    }

    /**
     * The guide's own "Got it" must be a real target, not just a node.
     *
     * It was a 0 dp box at the reference geometry: the control flank's column
     * overflowed, and a Column measures its children in order, so everything
     * after the callout stack was handed zero height. The button was present in
     * the tree — `assertExists` passed, and `hasText(str(R.string.action_got_it))` even matched the
     * overlay's own merged node, so a click "worked" by landing on the scrim —
     * while the control the requirement names as the way out could not be hit.
     * Size is therefore asserted explicitly, and the click is aimed at the
     * button's own tag rather than at its text.
     */
    @ExpandedWindow
    @Test
    fun coachOverlay_gotItIsAHittableTargetAndCloses() {
        fakeRepo.imagesFlow.value = mockImages
        runBlocking { settingsRepository.setLastFolderUri("content://test/test_folder") }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()
        requireExpandedLayout()

        // The intro tour only opens itself once per install, and sibling tests
        // in this class may already have marked it seen, so open the guide the
        // way the control block does rather than waiting for it to appear.
        if (composeRule.onAllNodesWithTag("selector_coach_overlay", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        ) {
            if (composeRule.onAllNodesWithTag("shortcuts_button", useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty()
            ) {
                return
            }
            composeRule.onAllNodesWithTag("shortcuts_button", useUnmergedTree = true)
                .onFirst().performClick()
        }
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithTag("selector_coach_overlay", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        forEachCoachPage { }
        val dismiss = composeRule.onAllNodesWithTag("coach_overlay_dismiss", useUnmergedTree = true)
        dismiss.fetchSemanticsNodes().forEach { node ->
            assertTrue(
                "\"Got it\" is laid out ${node.size.height}px tall — nothing can tap it",
                node.size.height > 0 && node.size.width > 0,
            )
        }

        dismiss.onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithTag("selector_coach_overlay", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
    }

    /**
     * Runs [action] on every page of the (paged) guide, ending on the last page,
     * where the closing control `coach_overlay_dismiss` lives.
     */
    private fun forEachCoachPage(action: () -> Unit) {
        var guard = 0
        while (true) {
            composeRule.waitForIdle()
            action()
            val next = composeRule.onAllNodesWithTag("coach_overlay_next", useUnmergedTree = true)
            if (next.fetchSemanticsNodes().isEmpty()) return
            next.onFirst().performClick()
            assertTrue("the guide never reaches its last page", ++guard < 20)
        }
    }

    private fun dismissGestureTutorialIfShown() {
        if (composeRule.onAllNodes(hasTestTag("gesture_tutorial_overlay"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onAllNodes(hasTestTag("gesture_tutorial_overlay"), useUnmergedTree = true).onFirst().performClick()
            composeRule.waitUntil(timeoutMillis = 15000) {
                composeRule.onAllNodes(hasTestTag("gesture_tutorial_overlay"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            }
        }
        // The coach guide is dismissed by its own button, never by its text.
        // `hasText(str(R.string.action_got_it))` also matches the overlay's merged node, so the
        // click used to land on the full-screen scrim — which dismisses too,
        // and therefore passed for months while the button itself was laid out
        // 0 px tall and could not be hit by a user at all.
        if (composeRule.onAllNodesWithTag("selector_coach_overlay", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        ) {
            forEachCoachPage { }
            composeRule.onAllNodesWithTag("coach_overlay_dismiss", useUnmergedTree = true)
                .fetchSemanticsNodes().forEach { node ->
                    assertTrue(
                        "\"Got it\" is laid out ${node.size.height}px tall — nothing can tap it",
                        node.size.height > 0 && node.size.width > 0,
                    )
                }
            composeRule.onAllNodesWithTag("coach_overlay_dismiss", useUnmergedTree = true)
                .onFirst().performClick()
            composeRule.waitUntil(timeoutMillis = 15000) {
                composeRule.onAllNodesWithTag("selector_coach_overlay", useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty()
            }
        }
        if (composeRule.onAllNodes(hasText(str(R.string.title_gestures), ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onAllNodes(hasText(str(R.string.title_gestures), ignoreCase = true), useUnmergedTree = true).onFirst().performClick()
        }
    }
}
