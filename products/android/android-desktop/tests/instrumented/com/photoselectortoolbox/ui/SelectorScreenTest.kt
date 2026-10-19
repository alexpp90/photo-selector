package com.photoselectortoolbox.ui

// Explicitly imported, never `androidx.compose.ui.test.*`. The wildcard is what
// let a non-existent matcher (`hasTag`, when the real one is `hasTestTag`) pass
// review and fail only inside the emulator job — see `docs/build/CI_PARITY.md`
// § Compose instrumented-test conventions.
// `assertExists`, `assertDoesNotExist`, `onNode` and `onAllNodes` are members of
// `SemanticsNodeInteraction`/`SemanticsNodeInteractionsProvider`, not extensions,
// so they carry no import. Everything below is a genuine extension function.
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
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
import com.photoselectortoolbox.ui.selector.FrameGeometry
import com.photoselectortoolbox.ui.selector.SidebarWidth
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SelectorScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

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
            settingsRepository.clearPendingDeleteJournal()
        }
    }

    @After
    fun teardown() {
        fakeRepo.folderName = "Test Folder"
        runBlocking {
            scoreDao.deleteAll()
            settingsRepository.setLastFolderUri(null)
            settingsRepository.clearPendingDeleteJournal()
        }
    }

    @Test
    fun initialEmptyState_isDisplayed() {
        // App starts with no folder selected, should show the empty state card.
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("Select a Folder", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onAllNodesWithText("Select a Folder", ignoreCase = true).onFirst()
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
        composeRule.onNodeWithText("Folder Access Needed", ignoreCase = true).assertIsDisplayed()
        composeRule.onNodeWithText("Re-select Folder", ignoreCase = true).assertIsDisplayed()
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

        if (isCompactLayout()) return
        if (composeRule.onAllNodesWithTag("column_next").fetchSemanticsNodes().isEmpty()) return

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

        if (isCompactLayout()) return
        if (composeRule.onAllNodesWithTag("details_toggle").fetchSemanticsNodes().isEmpty()) return

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

        if (isCompactLayout()) return
        if (composeRule.onAllNodesWithTag("column_current").fetchSemanticsNodes().isEmpty()) return

        val rootBounds = composeRule.onRoot().getUnclippedBoundsInRoot()
        val windowWidth = rootBounds.right - rootBounds.left
        val windowHeight = rootBounds.bottom - rootBounds.top
        val frame = composeRule.onNodeWithTag("column_current").getUnclippedBoundsInRoot()
        val frameHeight = frame.bottom - frame.top

        val imageRegionBounds = composeRule.onNodeWithTag("image_region").getUnclippedBoundsInRoot()
        val statusBarHeight = (imageRegionBounds.top - rootBounds.top).coerceAtLeast(0.dp)

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
                "something is consuming the height budget"
        }

        // The absolute floor, only where the window is the size the product is
        // designed for. A 1280x800 CI emulator is a smaller display than a Tab
        // S11 Ultra, not a regression.
        if (windowHeight >= FrameGeometry.ReferenceWindowHeight) {
            assert(frameHeight >= FrameGeometry.MinimumReferenceFrameHeight) {
                "frame is only ${frameHeight.value}dp on a reference-class " +
                    "${windowHeight.value}dp window; the floor is " +
                    "${FrameGeometry.MinimumReferenceFrameHeight.value}dp"
            }
        }
    }

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

        if (isCompactLayout()) return
        if (composeRule.onAllNodesWithTag("column_current").fetchSemanticsNodes().isEmpty()) return

        composeRule.onAllNodesWithTag("filmstrip").assertCountEquals(0)
        val hidden = composeRule.onNodeWithTag("column_current").getUnclippedBoundsInRoot()

        runBlocking { settingsRepository.setFilmstripVisible(true) }
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithTag("filmstrip").fetchSemanticsNodes().isNotEmpty()
        }
        val shown = composeRule.onNodeWithTag("column_current").getUnclippedBoundsInRoot()

        assert(
            kotlin.math.abs(
                (shown.bottom - shown.top).value - (hidden.bottom - hidden.top).value,
            ) < 1f
        ) {
            "turning the filmstrip on moved the frames from " +
                "${(hidden.bottom - hidden.top).value}dp to " +
                "${(shown.bottom - shown.top).value}dp — it is back in the height budget"
        }
    }

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

        if (isCompactLayout()) return
        val strips = composeRule.onAllNodesWithTag("filmstrip").fetchSemanticsNodes()
        if (strips.isEmpty()) return

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

        if (isCompactLayout()) return
        val overlays = composeRule.onAllNodesWithTag("neighbour_overlay").fetchSemanticsNodes()
        val badges = composeRule.onAllNodesWithTag("maximise_badge").fetchSemanticsNodes()
        if (overlays.isEmpty() || badges.isEmpty()) return

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

    @Test
    fun firstRightArrowAfterLaunch_movesToTheSecondFrame() {
        loadTwoImages()
        // CI's phone-class emulator runs the compact layout, which has its own keys.
        if (isCompactLayout()) return

        // One key, through the window, like a keyboard: `sendKeyDownUpSync`
        // leaves touch mode. On a real Tab S11 Ultra the first press was spent on
        // Android's initial-focus pass and only the second one navigated.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)

        composeRule.waitUntil(timeoutMillis = 5000) { positionCounterShows("2 / 2") }
    }

    @Test
    fun firstRightArrowAfterClosingTheGuide_movesToTheSecondFrame() {
        loadTwoImages()
        // CI's phone-class emulator runs the compact layout, which has its own keys.
        if (isCompactLayout()) return

        composeRule.onAllNodesWithTag("shortcuts_button", useUnmergedTree = true)
            .onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithTag("selector_coach_overlay", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()
        composeRule.waitForIdle()

        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)

        composeRule.waitUntil(timeoutMillis = 5000) { positionCounterShows("2 / 2") }
    }

    /** True when the window is narrow enough to be running the phone layout. */
    private fun isCompactLayout(): Boolean =
        composeRule.onAllNodesWithTag("copy_button_compact").fetchSemanticsNodes().isNotEmpty()

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
                composeRule.onAllNodesWithText("Copied to Selection", ignoreCase = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Exception) {
            try { composeRule.onRoot().printToLog("SelectorScreenTest_Copy") } catch (_: Exception) {}
            throw e
        }
        composeRule.onNodeWithText("Copied to Selection", ignoreCase = true).assertIsDisplayed()

        // Move straight away, as a photographer would. The copy snackbar stays up
        // for the whole 30 s undo window, and waiting it out made this the slowest
        // test on every device (31–40 s, device matrix 2026-10-19). It cannot block
        // the button: the card has no pointer input, and the next confirmation
        // replaces it. What can be late after the copy is the compact action bar
        // recomposing, so wait for the button itself (code_health 2026-10-10).
        val moveTag = if (isCompact) "move_button_compact" else "move_button_expanded"
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithTag(moveTag).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(moveTag).performClick()
        composeRule.waitForIdle()

        try {
            composeRule.waitUntil(timeoutMillis = 15000) {
                composeRule.onAllNodesWithText("Moved to Selection", ignoreCase = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Exception) {
            try { composeRule.onRoot().printToLog("SelectorScreenTest_Move") } catch (_: Exception) {}
            throw e
        }
        composeRule.onNodeWithText("Moved to Selection", ignoreCase = true).assertIsDisplayed()
        // One confirmation at a time: the move's replaced the copy's. Scoped to the
        // snackbar, because the one-time filing hint card repeats the sentence.
        assertTrue(
            "the copy snackbar is still showing beside the move's",
            composeRule.onAllNodes(
                hasText("Copied to Selection", ignoreCase = true) and
                    hasAnyAncestor(hasTestTag("selector_snackbar")),
            ).fetchSemanticsNodes().isEmpty(),
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
                composeRule.onAllNodesWithText("Delete Image", ignoreCase = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Exception) {
            try { composeRule.onRoot().printToLog("SelectorScreenTest_DeleteDialogTimeout") } catch (_: Exception) {}
            throw e
        }
        composeRule.onNodeWithText("Delete Image", ignoreCase = true).assertIsDisplayed()
        composeRule.onNodeWithText("Cancel", ignoreCase = true).assertIsDisplayed()

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

        if (isCompactLayout()) return

        // Tap Scan button on sidebar/chrome
        composeRule.onAllNodesWithTag("scan_button", useUnmergedTree = true).onFirst().performClick()

        // Verify Scan Configuration sheet is shown and start scan
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("Scan Configuration", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Scan Configuration", useUnmergedTree = true).onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("Start Scan", useUnmergedTree = true).onFirst().performClick()

        // Wait until metrics update and display in the UI
        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("78.5", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Verify metrics are visible
        composeRule.onAllNodesWithText("78.5", substring = true).onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("1.2", substring = true).onFirst().assertIsDisplayed()
    }

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

        if (isCompactLayout()) return

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

        if (isCompactLayout()) return

        composeRule.onAllNodesWithText("Keep", substring = true, ignoreCase = true)
            .assertCountEquals(0)
        composeRule.onAllNodesWithText("Copy").fetchSemanticsNodes().let {
            assert(it.isNotEmpty()) { "no worded Copy control on screen" }
        }
        composeRule.onAllNodesWithText("Move").fetchSemanticsNodes().let {
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

        composeRule.onAllNodes(hasText("Got it", ignoreCase = true), useUnmergedTree = true)
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
            composeRule.onAllNodesWithContentDescription("higher is better", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithContentDescription("higher is better", substring = true)
            .onFirst().assertExists()
    }

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

        if (isCompactLayout()) return

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
        composeRule.onAllNodes(hasText("Sharpness") and inOverlay, useUnmergedTree = true)
            .onFirst().assertExists()
        composeRule.onAllNodes(hasText("Noise") and inOverlay, useUnmergedTree = true)
            .onFirst().assertExists()
        composeRule.onAllNodes(
            hasText("higher is better", substring = true) and inOverlay,
            useUnmergedTree = true,
        ).onFirst().assertExists()
        composeRule.onAllNodes(
            hasText("lower is better", substring = true) and inOverlay,
            useUnmergedTree = true,
        ).onFirst().assertExists()

        // ...and the icons down the left, which the sheet this replaces never
        // mentioned at all.
        SidebarAction.entries.forEach { action ->
            composeRule.onAllNodes(hasText(action.label) and inOverlay, useUnmergedTree = true)
                .onFirst().assertExists()
        }
    }

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

        if (isCompactLayout()) return
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

    @Test
    fun positionReadout_hasNoPlusOnceEnumerationHasSettled() {
        fakeRepo.imagesFlow.value = mockImages
        runBlocking { settingsRepository.setLastFolderUri("content://test/test_folder") }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()

        if (isCompactLayout()) return
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

        if (isCompactLayout()) return

        composeRule.onAllNodesWithTag("delete_button_expanded", useUnmergedTree = true)
            .onFirst().performClick()

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("Delete Image", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("dialog_confirm_delete").performClick()

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithTag("snackbar_undo", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val inSnackbar = hasAnyAncestor(hasTestTag("selector_snackbar"))
        composeRule.onAllNodes(hasText("1 image deleted") and inSnackbar, useUnmergedTree = true)
            .onFirst().assertExists()

        composeRule.onAllNodesWithTag("snackbar_undo", useUnmergedTree = true)
            .onFirst().performClick()

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true).onFirst()
            .assertIsDisplayed()
    }

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

        if (isCompactLayout()) return

        composeRule.onAllNodesWithTag("copy_button_expanded", useUnmergedTree = true)
            .onFirst().performClick()

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("Copied to Selection", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag("snackbar_undo", useUnmergedTree = true).assertCountEquals(0)
    }

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

        if (isCompactLayout()) return
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

        if (isCompactLayout()) return
        if (composeRule.onAllNodesWithTag("column_current").fetchSemanticsNodes().isEmpty()) return

        val frames = listOf("column_current", "column_previous", "column_next")
            .flatMap { tag ->
                composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().map { tag to it.boundsInRoot }
            }
        if (frames.isEmpty()) return

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

    @Test
    fun coachOverlay_opensFromTheControlBlockAndClosesOnGotIt() {
        fakeRepo.imagesFlow.value = mockImages
        runBlocking { settingsRepository.setLastFolderUri("content://test/test_folder") }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()

        if (isCompactLayout()) return
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
            if (composeRule.onAllNodes(hasText("The comparison") and inOverlay, useUnmergedTree = true)
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

        if (isCompactLayout()) return
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
        composeRule.onAllNodes(hasText("Copied to Selection") and inCard, useUnmergedTree = true)
            .onFirst().assertExists()
        composeRule.onAllNodes(hasText("Keep", substring = true, ignoreCase = true) and inCard,
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
     * the tree — `assertExists` passed, and `hasText("Got it")` even matched the
     * overlay's own merged node, so a click "worked" by landing on the scrim —
     * while the control the requirement names as the way out could not be hit.
     * Size is therefore asserted explicitly, and the click is aimed at the
     * button's own tag rather than at its text.
     */
    @Test
    fun coachOverlay_gotItIsAHittableTargetAndCloses() {
        fakeRepo.imagesFlow.value = mockImages
        runBlocking { settingsRepository.setLastFolderUri("content://test/test_folder") }

        composeRule.waitUntil(timeoutMillis = 15000) {
            composeRule.onAllNodesWithText("image1.jpg", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        dismissGestureTutorialIfShown()
        if (isCompactLayout()) return

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
        // `hasText("GOT IT")` also matches the overlay's merged node, so the
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
        if (composeRule.onAllNodes(hasText("Gestures", ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onAllNodes(hasText("Gestures", ignoreCase = true), useUnmergedTree = true).onFirst().performClick()
        }
    }
}
