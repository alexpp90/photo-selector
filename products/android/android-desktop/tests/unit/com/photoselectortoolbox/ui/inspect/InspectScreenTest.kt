package com.photoselectortoolbox.ui.inspect

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.ui.input.key.Key
import com.photoselector.core.Requirement
import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.domain.analysis.PhotoCoachEngineImpl
import com.photoselectortoolbox.domain.format.ExifFormatter
import com.photoselectortoolbox.domain.model.CoachingTip
import com.photoselectortoolbox.domain.model.CompositionGuideType
import com.photoselectortoolbox.domain.model.PhotoCoachReport
import com.photoselectortoolbox.domain.model.SpiralOrientation
import com.photoselectortoolbox.domain.model.TipSeverity
import com.photoselectortoolbox.ui.navigation.Screen
import com.photoselectortoolbox.ui.selector.SelectorActions
import com.photoselectortoolbox.ui.selector.handleSelectorKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.photoselectortoolbox.testing.en

/**
 * Unit tests for the Inspect & Learn Workstation screen and navigation integration.
 *
 * Verifies:
 * 1. [Screen.Inspect] registration in [Screen.all] and route properties.
 * 2. Hardware keyboard shortcuts in [handleInspectKey] ('L', 'I', 'Escape', '0'–'5', Arrow keys).
 * 3. Selector navigation wiring in [handleSelectorKey] ('L', 'I').
 * 4. 4-way Golden Spiral orientation cycling ([cycleSpiralOrientation]).
 * 5. Photo Coach evaluation heuristics and technical readout rendering.
 */
@Requirement("REQ-AND-INSPECT.01")
class InspectScreenTest {

    // ── 1. Screen.Inspect Navigation Registration ─────────────────────────────

    @Test
    fun `Screen Inspect is registered with expected route, icons, and labels`() {
        assertEquals("inspect", Screen.Inspect.route)
        assertEquals("Learn", Screen.Inspect.label.en())
        assertEquals("Learn", Screen.Inspect.shortLabel.en())
        assertEquals(Icons.Default.CenterFocusStrong, Screen.Inspect.icon)
        assertTrue(Screen.Inspect.meaning.en().contains("Inspect composition"))
    }

    @Test
    fun `Screen all contains Screen Inspect at second position`() {
        assertEquals(5, Screen.all.size)
        assertTrue(Screen.all.contains(Screen.Inspect))
        assertEquals(Screen.PhotoSelector, Screen.all[0])
        assertEquals(Screen.Inspect, Screen.all[1])
        assertEquals(Screen.Statistics, Screen.all[2])
        assertEquals(Screen.DuplicateFinder, Screen.all[3])
        assertEquals(Screen.Settings, Screen.all[4])
    }

    @Test
    fun `all screens in Screen all have unique routes and non-empty labels`() {
        val routes = Screen.all.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
        Screen.all.forEach { screen ->
            assertTrue(screen.label.en().isNotEmpty())
            assertTrue(screen.shortLabel.en().isNotEmpty())
            assertTrue(screen.meaning.en().isNotEmpty())
        }
    }

    // ── 2. Hardware Keyboard Hotkeys in Inspect Workstation ───────────────────

    @Test
    fun `handleInspectKey returns to culling on Key L`() {
        var navigated = false
        val handled = handleInspectKey(
            key = Key.L,
            onNavigateToSelector = { navigated = true },
            onPrevious = {},
            onNext = {},
            onSetRating = {},
        )
        assertTrue(handled)
        assertTrue(navigated)
    }

    @Test
    fun `handleInspectKey leaves Key I alone, it is the selector's info key`() {
        var navigated = false
        val handled = handleInspectKey(
            key = Key.I,
            onNavigateToSelector = { navigated = true },
            onPrevious = {},
            onNext = {},
            onSetRating = {},
        )
        assertFalse(handled)
        assertFalse(navigated)
    }

    @Test
    fun `handleInspectKey returns to culling on Key Escape`() {
        var navigated = false
        val handled = handleInspectKey(
            key = Key.Escape,
            onNavigateToSelector = { navigated = true },
            onPrevious = {},
            onNext = {},
            onSetRating = {},
        )
        assertTrue(handled)
        assertTrue(navigated)
    }

    @Test
    fun `handleInspectKey navigates previous and next on DirectionLeft and DirectionRight`() {
        var prevCalled = false
        var nextCalled = false

        val handledPrev = handleInspectKey(
            key = Key.DirectionLeft,
            onNavigateToSelector = {},
            onPrevious = { prevCalled = true },
            onNext = {},
            onSetRating = {},
        )
        assertTrue(handledPrev)
        assertTrue(prevCalled)
        assertFalse(nextCalled)

        val handledNext = handleInspectKey(
            key = Key.DirectionRight,
            onNavigateToSelector = {},
            onPrevious = {},
            onNext = { nextCalled = true },
            onSetRating = {},
        )
        assertTrue(handledNext)
        assertTrue(nextCalled)
    }

    @Test
    fun `handleInspectKey maps number keys 0 to 5 and X to ratings`() {
        val ratings = mutableListOf<Int>()
        val onRate: (Int) -> Unit = { ratings.add(it) }

        val keys = listOf(
            Key.Zero to 0,
            Key.One to 1,
            Key.Two to 2,
            Key.Three to 3,
            Key.Four to 4,
            Key.Five to 5,
            Key.NumPad0 to 0,
            Key.NumPad1 to 1,
            Key.NumPad2 to 2,
            Key.NumPad3 to 3,
            Key.NumPad4 to 4,
            Key.NumPad5 to 5,
            Key.X to -1,
        )

        for ((key, expectedRating) in keys) {
            val handled = handleInspectKey(
                key = key,
                onNavigateToSelector = {},
                onPrevious = {},
                onNext = {},
                onSetRating = onRate,
            )
            assertTrue("Key $key was not handled", handled)
        }

        assertEquals(
            listOf(0, 1, 2, 3, 4, 5, 0, 1, 2, 3, 4, 5, -1),
            ratings,
        )
    }

    @Test
    fun `handleInspectKey ignores unmapped keys`() {
        var actionOccurred = false
        val handled = handleInspectKey(
            key = Key.A,
            onNavigateToSelector = { actionOccurred = true },
            onPrevious = { actionOccurred = true },
            onNext = { actionOccurred = true },
            onSetRating = { actionOccurred = true },
        )
        assertFalse(handled)
        assertFalse(actionOccurred)
    }

    // ── 3. Selector Screen Routing to Inspect via Hotkeys ─────────────────────

    @Test
    fun `handleSelectorKey routes Key L, not Key I, to Inspect`() {
        var targetScreen: Screen? = null
        val dummyActions = SelectorActions(
            onMove = {},
            onCopy = {},
            onDelete = {},
            onFullscreen = {},
            onToggleDetails = {},
            onToggleFilmstrip = {},
            onToggleOverlayValues = {},
            onShowShortcuts = {},
        )

        val handledL = handleSelectorKey(
            key = Key.L,
            sheetOpen = false,
            contextMenuOpen = false,
            fullscreenOpen = false,
            maximised = false,
            actions = dummyActions,
            onPrevious = {},
            onNext = {},
            onMaximise = {},
            onShowShortcuts = {},
            onCloseOverlays = {},
            onNavigate = { targetScreen = it },
        )
        assertTrue(handledL)
        assertEquals(Screen.Inspect, targetScreen)

        // I is the info (readout) toggle in every desk app now (docs/shared/KEYBOARD.md).
        targetScreen = null
        handleSelectorKey(
            key = Key.I,
            sheetOpen = false,
            contextMenuOpen = false,
            fullscreenOpen = false,
            maximised = false,
            actions = dummyActions,
            onPrevious = {},
            onNext = {},
            onMaximise = {},
            onShowShortcuts = {},
            onCloseOverlays = {},
            onNavigate = { targetScreen = it },
        )
        assertEquals(null, targetScreen)
    }

    @Test
    fun `handleSelectorKey suppresses Key L when sheet is open`() {
        var targetScreen: Screen? = null
        val dummyActions = SelectorActions(
            onMove = {},
            onCopy = {},
            onDelete = {},
            onFullscreen = {},
            onToggleDetails = {},
            onToggleFilmstrip = {},
            onToggleOverlayValues = {},
            onShowShortcuts = {},
        )

        val handled = handleSelectorKey(
            key = Key.L,
            sheetOpen = true,
            contextMenuOpen = false,
            fullscreenOpen = false,
            maximised = false,
            actions = dummyActions,
            onPrevious = {},
            onNext = {},
            onMaximise = {},
            onShowShortcuts = {},
            onCloseOverlays = {},
            onNavigate = { targetScreen = it },
        )
        assertFalse(handled)
        assertEquals(null, targetScreen)
    }

    @Test
    fun `handleSelectorKey triggers onDelete for both Delete and Backspace keys`() {
        var deleteCount = 0
        val dummyActions = SelectorActions(
            onMove = {},
            onCopy = {},
            onDelete = { deleteCount++ },
            onFullscreen = {},
            onToggleDetails = {},
            onToggleFilmstrip = {},
            onToggleOverlayValues = {},
            onShowShortcuts = {},
        )

        val handledDelete = handleSelectorKey(
            key = Key.Delete,
            sheetOpen = false,
            contextMenuOpen = false,
            fullscreenOpen = false,
            maximised = false,
            actions = dummyActions,
            onPrevious = {},
            onNext = {},
            onMaximise = {},
            onShowShortcuts = {},
            onCloseOverlays = {},
        )
        assertTrue(handledDelete)
        assertEquals(1, deleteCount)

        // Key.Backspace (Chromebook keyboards lack dedicated Delete key)
        val handledBackspace = handleSelectorKey(
            key = Key.Backspace,
            sheetOpen = false,
            contextMenuOpen = false,
            fullscreenOpen = false,
            maximised = false,
            actions = dummyActions,
            onPrevious = {},
            onNext = {},
            onMaximise = {},
            onShowShortcuts = {},
            onCloseOverlays = {},
        )
        assertTrue(handledBackspace)
        assertEquals(2, deleteCount)
    }

    // ── 4. Golden Spiral 4-Way Flip Cycling ───────────────────────────────────

    @Test
    fun `cycleSpiralOrientation follows TL to TR to BR to BL cycle`() {
        assertEquals(SpiralOrientation.TOP_RIGHT, cycleSpiralOrientation(SpiralOrientation.TOP_LEFT))
        assertEquals(SpiralOrientation.BOTTOM_RIGHT, cycleSpiralOrientation(SpiralOrientation.TOP_RIGHT))
        assertEquals(SpiralOrientation.BOTTOM_LEFT, cycleSpiralOrientation(SpiralOrientation.BOTTOM_RIGHT))
        assertEquals(SpiralOrientation.TOP_LEFT, cycleSpiralOrientation(SpiralOrientation.BOTTOM_LEFT))
    }

    // ── 5. Photo Coach Engine Integration in Inspect ──────────────────────────

    @Test
    fun `PhotoCoachEngine computes reciprocal rule camera shake and safe shutter speed`() {
        val engine = PhotoCoachEngineImpl()

        // 50mm lens: safe shutter speed is 1/50s = 0.02s
        // Shooting at 1/10s (0.1s) with low sharpness (15.0) -> high camera shake risk
        val exif = ExifData(
            shutterSpeed = 0.1,
            focalLength = 50.0,
            focalLength35mm = 50.0,
            aperture = 2.8,
            iso = 400,
            lens = "50mm F1.8",
        )
        val scan = ScanResult(
            filePath = "test.jpg",
            sharpnessScore = 15.0,
            noiseLevel = 1.0,
        )

        val report = engine.evaluate(exif, scan)

        assertEquals(0.02, report.safeShutterSpeed!!, 1e-4)
        assertTrue(report.cameraShakeRisk >= 0.7f)
        assertTrue(report.tips.any { it.category.en() == "Shutter Speed" && it.severity == TipSeverity.CRITICAL })
    }

    @Test
    fun `PhotoCoachEngine suggests negative EV compensation for highlight clipping`() {
        val engine = PhotoCoachEngineImpl()

        val exif = ExifData(shutterSpeed = 0.004, focalLength = 35.0, aperture = 4.0, iso = 100)
        val scan = ScanResult(
            filePath = "bright.jpg",
            sharpnessScore = 60.0,
            highlightClipping = 6.0, // > 5.0% severe highlight clipping
            shadowClipping = 0.5,
        )

        val report = engine.evaluate(exif, scan)

        assertNotNull(report.recommendedEvCompensation)
        assertEquals(-1.0, report.recommendedEvCompensation!!, 1e-4)
        assertTrue(report.tips.any { it.category.en() == "Exposure" && it.title.en().contains("Blown Highlights") })
    }

    @Test
    fun `PhotoCoachEngine suggests positive EV compensation for shadow clipping`() {
        val engine = PhotoCoachEngineImpl()

        val exif = ExifData(shutterSpeed = 0.002, focalLength = 85.0, aperture = 1.8, iso = 200)
        val scan = ScanResult(
            filePath = "dark.jpg",
            sharpnessScore = 55.0,
            highlightClipping = 0.0,
            shadowClipping = 8.0, // > 5.0% shadow clipping
        )

        val report = engine.evaluate(exif, scan)

        assertNotNull(report.recommendedEvCompensation)
        assertEquals(0.7, report.recommendedEvCompensation!!, 1e-4)
        assertTrue(report.tips.any { it.category.en() == "Exposure" && it.title.en().contains("Crushed Shadows") })
    }

    @Test
    fun `EXIF readout detailRows formats all technical parameters correctly`() {
        val exif = ExifData(
            shutterSpeed = 0.004, // 1/250s
            aperture = 2.8,
            focalLength = 50.0,
            focalLength35mm = 75.0,
            iso = 800,
            lens = "FE 50mm F1.8",
        )

        val rows = ExifFormatter.detailRows(exif).associate { (k, v) -> k.en() to v.en() }

        assertEquals("1/250s", rows["Shutter Speed"])
        assertEquals("f/2.8", rows["Aperture"])
        assertEquals("50mm", rows["Focal Length"])
        assertEquals("75mm", rows["35mm Equiv."])
        assertEquals("800", rows["ISO"])
        assertEquals("FE 50mm F1.8", rows["Lens"])
    }

    // ── 6. Empirical Stress Tests & Adversarial Verification ──────────────────

    @Test
    fun `bidirectional navigation toggling cycle between Culling and Inspect preserves state`() {
        var currentScreen: Screen = Screen.PhotoSelector
        val dummyActions = SelectorActions(
            onMove = {}, onCopy = {}, onDelete = {}, onFullscreen = {},
            onToggleDetails = {}, onToggleFilmstrip = {}, onToggleOverlayValues = {}, onShowShortcuts = {},
        )

        // State machine transition helper
        fun pressKey(key: Key) {
            when (currentScreen) {
                Screen.PhotoSelector -> {
                    handleSelectorKey(
                        key = key,
                        sheetOpen = false,
                        contextMenuOpen = false,
                        fullscreenOpen = false,
                        maximised = false,
                        actions = dummyActions,
                        onPrevious = {},
                        onNext = {},
                        onMaximise = {},
                        onShowShortcuts = {},
                        onCloseOverlays = {},
                        onNavigate = { currentScreen = it },
                    )
                }
                Screen.Inspect -> {
                    handleInspectKey(
                        key = key,
                        onNavigateToSelector = { currentScreen = Screen.PhotoSelector },
                        onPrevious = {},
                        onNext = {},
                        onSetRating = {},
                    )
                }
                else -> Unit
            }
        }

        // 1. Initial state is Culling
        assertEquals(Screen.PhotoSelector, currentScreen)

        // 2. Press 'L' -> Navigates to Inspect
        pressKey(Key.L)
        assertEquals(Screen.Inspect, currentScreen)

        // 3. Press 'L' again -> Returns to Culling
        pressKey(Key.L)
        assertEquals(Screen.PhotoSelector, currentScreen)

        // 4. 'I' is the info toggle now (docs/shared/KEYBOARD.md): it changes no screen
        pressKey(Key.I)
        assertEquals(Screen.PhotoSelector, currentScreen)

        // 6. Press 'L' -> Navigates to Inspect
        pressKey(Key.L)
        assertEquals(Screen.Inspect, currentScreen)

        // 7. Press 'Escape' -> Returns to Culling
        pressKey(Key.Escape)
        assertEquals(Screen.PhotoSelector, currentScreen)
    }

    @Test
    fun `handleSelectorKey routing during maximised frame routes L to Inspect`() {
        var targetScreen: Screen? = null
        val dummyActions = SelectorActions(
            onMove = {}, onCopy = {}, onDelete = {}, onFullscreen = {},
            onToggleDetails = {}, onToggleFilmstrip = {}, onToggleOverlayValues = {}, onShowShortcuts = {},
        )

        // Press 'L' while a frame is maximised
        val handledL = handleSelectorKey(
            key = Key.L,
            sheetOpen = false,
            contextMenuOpen = false,
            fullscreenOpen = false,
            maximised = true,
            actions = dummyActions,
            onPrevious = {},
            onNext = {},
            onMaximise = {},
            onShowShortcuts = {},
            onCloseOverlays = {},
            onNavigate = { targetScreen = it },
        )
        assertTrue(handledL)
        assertEquals(Screen.Inspect, targetScreen)

        targetScreen = null
        // Press 'I' while a frame is maximised
        val handledI = handleSelectorKey(
            key = Key.I,
            sheetOpen = false,
            contextMenuOpen = false,
            fullscreenOpen = false,
            maximised = true,
            actions = dummyActions,
            onPrevious = {},
            onNext = {},
            onMaximise = {},
            onShowShortcuts = {},
            onCloseOverlays = {},
            onNavigate = { targetScreen = it },
        )
        // 'I' toggles the details in the maximised frame too; it opens no screen.
        assertTrue(handledI)
        assertEquals(null, targetScreen)
    }

    @Test
    fun `handleInspectKey rejects all invalid hotkeys outside ratings and navigation`() {
        val invalidKeys = listOf(
            Key.Six, Key.Seven, Key.Eight, Key.Nine,
            Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9,
            Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.J, Key.K, Key.M,
            Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.Y, Key.Z,
            Key.Spacebar, Key.Tab, Key.Enter, Key.Backspace, Key.Delete,
            Key.DirectionUp, Key.DirectionDown,
        )

        for (invalidKey in invalidKeys) {
            val handled = handleInspectKey(
                key = invalidKey,
                onNavigateToSelector = { org.junit.Assert.fail("Unexpected navigation on $invalidKey") },
                onPrevious = { org.junit.Assert.fail("Unexpected onPrevious on $invalidKey") },
                onNext = { org.junit.Assert.fail("Unexpected onNext on $invalidKey") },
                onSetRating = { org.junit.Assert.fail("Unexpected onSetRating on $invalidKey") },
            )
            assertFalse("Expected key $invalidKey to be unhandled, but was handled", handled)
        }
    }

    @Test
    fun `handleInspectKey rating sequence stress test executes deterministically`() {
        val capturedRatings = mutableListOf<Int>()
        val sequence = listOf(
            Key.Zero, Key.Five, Key.One, Key.Four, Key.Two, Key.Three,
            Key.NumPad0, Key.NumPad5, Key.NumPad1, Key.NumPad4, Key.NumPad2, Key.NumPad3,
            Key.Five, Key.Five, Key.Zero, Key.Three, Key.One, Key.Two, Key.Four, Key.Zero,
        )
        val expectedValues = listOf(
            0, 5, 1, 4, 2, 3,
            0, 5, 1, 4, 2, 3,
            5, 5, 0, 3, 1, 2, 4, 0,
        )

        for (key in sequence) {
            val handled = handleInspectKey(
                key = key,
                onNavigateToSelector = {},
                onPrevious = {},
                onNext = {},
                onSetRating = { capturedRatings.add(it) },
            )
            assertTrue("Expected rating key $key to be handled", handled)
        }

        assertEquals(expectedValues, capturedRatings)
    }

    @Test
    fun `handleInspectKey safely handles rating keys when currentImage is null`() {
        // Simulates the lambda inside InspectScreenContent when currentImage is null
        var ratingInvoked = false
        val currentImage: ImageItem? = null
        val onSetRatingLambda: (Int) -> Unit = { rating ->
            currentImage?.let { ratingInvoked = true }
        }

        val ratingKeys = listOf(
            Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five,
            Key.NumPad0, Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4, Key.NumPad5,
        )

        for (key in ratingKeys) {
            val handled = handleInspectKey(
                key = key,
                onNavigateToSelector = {},
                onPrevious = {},
                onNext = {},
                onSetRating = onSetRatingLambda,
            )
            assertTrue(handled)
            assertFalse(ratingInvoked)
        }
    }

    @Test
    fun `candidate navigation across empty, single-element, and boundary indices clamps safely`() {
        // Simulates navigation state model across empty, single, and boundary cases
        class NavigationHarness(val size: Int) {
            var currentIndex = 0
            fun previous() {
                if (size > 0 && currentIndex > 0) currentIndex--
            }
            fun next() {
                if (size > 0 && currentIndex < size - 1) currentIndex++
            }
        }

        // Case 1: Empty list
        val emptyHarness = NavigationHarness(0)
        repeat(10) {
            handleInspectKey(
                key = Key.DirectionLeft,
                onNavigateToSelector = {},
                onPrevious = { emptyHarness.previous() },
                onNext = { emptyHarness.next() },
                onSetRating = {},
            )
            handleInspectKey(
                key = Key.DirectionRight,
                onNavigateToSelector = {},
                onPrevious = { emptyHarness.previous() },
                onNext = { emptyHarness.next() },
                onSetRating = {},
            )
        }
        assertEquals(0, emptyHarness.currentIndex)

        // Case 2: Single element list
        val singleHarness = NavigationHarness(1)
        repeat(10) {
            handleInspectKey(
                key = Key.DirectionLeft,
                onNavigateToSelector = {},
                onPrevious = { singleHarness.previous() },
                onNext = { singleHarness.next() },
                onSetRating = {},
            )
        }
        assertEquals(0, singleHarness.currentIndex)
        repeat(10) {
            handleInspectKey(
                key = Key.DirectionRight,
                onNavigateToSelector = {},
                onPrevious = { singleHarness.previous() },
                onNext = { singleHarness.next() },
                onSetRating = {},
            )
        }
        assertEquals(0, singleHarness.currentIndex)

        // Case 3: Boundary clamping on 5-element list (0..4)
        val multiHarness = NavigationHarness(5)
        // At index 0, going left stays at 0
        handleInspectKey(Key.DirectionLeft, {}, { multiHarness.previous() }, { multiHarness.next() }, {})
        assertEquals(0, multiHarness.currentIndex)

        // Step forward 4 times to reach boundary index 4
        repeat(4) {
            handleInspectKey(Key.DirectionRight, {}, { multiHarness.previous() }, { multiHarness.next() }, {})
        }
        assertEquals(4, multiHarness.currentIndex)

        // At index 4, going right stays at 4
        repeat(5) {
            handleInspectKey(Key.DirectionRight, {}, { multiHarness.previous() }, { multiHarness.next() }, {})
        }
        assertEquals(4, multiHarness.currentIndex)

        // Step back 4 times to index 0
        repeat(4) {
            handleInspectKey(Key.DirectionLeft, {}, { multiHarness.previous() }, { multiHarness.next() }, {})
        }
        assertEquals(0, multiHarness.currentIndex)
    }

    @Test
    fun `cycleSpiralOrientation preserves strict period 4 periodicity across multiple loops`() {
        var orientation = SpiralOrientation.TOP_LEFT
        val expectedCycle = listOf(
            SpiralOrientation.TOP_LEFT,
            SpiralOrientation.TOP_RIGHT,
            SpiralOrientation.BOTTOM_RIGHT,
            SpiralOrientation.BOTTOM_LEFT,
        )

        for (i in 0 until 40) {
            assertEquals(expectedCycle[i % 4], orientation)
            orientation = cycleSpiralOrientation(orientation)
        }
    }

    @Test
    fun `CompositionGeometry Golden Spiral points are geometrically bounded across all 4 orientations`() {
        val orientations = listOf(
            SpiralOrientation.TOP_LEFT,
            SpiralOrientation.TOP_RIGHT,
            SpiralOrientation.BOTTOM_RIGHT,
            SpiralOrientation.BOTTOM_LEFT,
        )

        for (orientation in orientations) {
            val points = com.photoselectortoolbox.ui.components.CompositionGeometry.getFibonacciSpiralPoints(
                orientation = orientation,
                width = 1480f,
                height = 924f,
                steps = 120,
            )
            assertEquals(120, points.size)

            for (point in points) {
                assertTrue("Point X out of bounds: ${point.x}", point.x in 0f..1480f)
                assertTrue("Point Y out of bounds: ${point.y}", point.y in 0f..924f)
            }

            val rects = com.photoselectortoolbox.ui.components.CompositionGeometry.getFibonacciSubdivisionRects(
                orientation = orientation,
                width = 1480f,
                height = 924f,
            )
            assertTrue("Expected subdivision rects", rects.isNotEmpty())
            for (rect in rects) {
                assertTrue("Invalid rect bounds: left=${rect.left}, right=${rect.right}", rect.right >= rect.left)
                assertTrue("Invalid rect bounds: top=${rect.top}, bottom=${rect.bottom}", rect.bottom >= rect.top)
            }
        }
    }

    @Test
    fun `CompositionGeometry Golden Spiral focal eye is reflected into correct quadrant for all 4 orientations`() {
        // Spiral converges towards the focal eye at the end of the steps
        for (orientation in SpiralOrientation.entries) {
            val points = com.photoselectortoolbox.ui.components.CompositionGeometry.getFibonacciSpiralPoints(
                orientation = orientation,
                width = 1000f,
                height = 1000f,
                steps = 120,
            )
            val eyePoint = points.last() // innermost focal point

            when (orientation) {
                SpiralOrientation.TOP_LEFT -> {
                    assertTrue("TOP_LEFT eye should have X < 500, got ${eyePoint.x}", eyePoint.x < 500f)
                    assertTrue("TOP_LEFT eye should have Y < 500, got ${eyePoint.y}", eyePoint.y < 500f)
                }
                SpiralOrientation.TOP_RIGHT -> {
                    assertTrue("TOP_RIGHT eye should have X > 500, got ${eyePoint.x}", eyePoint.x > 500f)
                    assertTrue("TOP_RIGHT eye should have Y < 500, got ${eyePoint.y}", eyePoint.y < 500f)
                }
                SpiralOrientation.BOTTOM_RIGHT -> {
                    assertTrue("BOTTOM_RIGHT eye should have X > 500, got ${eyePoint.x}", eyePoint.x > 500f)
                    assertTrue("BOTTOM_RIGHT eye should have Y > 500, got ${eyePoint.y}", eyePoint.y > 500f)
                }
                SpiralOrientation.BOTTOM_LEFT -> {
                    assertTrue("BOTTOM_LEFT eye should have X < 500, got ${eyePoint.x}", eyePoint.x < 500f)
                    assertTrue("BOTTOM_LEFT eye should have Y > 500, got ${eyePoint.y}", eyePoint.y > 500f)
                }
            }
        }
    }

    @Test
    fun `CompositionGeometry displayed bounds solver maintains exact aspect ratio and zero overflow on tablet and DeX geometries`() {
        // 1. Reference tablet: Galaxy Tab S11 Ultra in landscape (1480x924 dp)
        val tabletBounds3x2 = com.photoselectortoolbox.ui.components.CompositionGeometry.calculateDisplayedBounds(
            containerWidth = 1480f,
            containerHeight = 924f,
            aspectRatio = 1.5f, // 3:2
        )
        // Expected: height-bound (924 * 1.5 = 1386 <= 1480)
        assertEquals(924f, tabletBounds3x2.height, 1e-4f)
        assertEquals(1386f, tabletBounds3x2.width, 1e-4f)
        assertEquals(47f, tabletBounds3x2.left, 1e-4f)
        assertEquals(0f, tabletBounds3x2.top, 1e-4f)

        // 2. Reference tablet: 2:3 vertical portrait on tablet landscape
        val tabletBounds2x3 = com.photoselectortoolbox.ui.components.CompositionGeometry.calculateDisplayedBounds(
            containerWidth = 1480f,
            containerHeight = 924f,
            aspectRatio = 2f / 3f, // 2:3 portrait
        )
        // Expected: height-bound, pillarboxed centered
        assertEquals(924f, tabletBounds2x3.height, 1e-4f)
        assertEquals(616f, tabletBounds2x3.width, 1e-4f)
        assertEquals((1480f - 616f) / 2f, tabletBounds2x3.left, 1e-4f)
        assertEquals(0f, tabletBounds2x3.top, 1e-4f)

        // 3. Samsung DeX Full HD (1920x1080)
        val dexBounds3x2 = com.photoselectortoolbox.ui.components.CompositionGeometry.calculateDisplayedBounds(
            containerWidth = 1920f,
            containerHeight = 1080f,
            aspectRatio = 1.5f,
        )
        assertEquals(1080f, dexBounds3x2.height, 1e-4f)
        assertEquals(1620f, dexBounds3x2.width, 1e-4f)
        assertEquals(150f, dexBounds3x2.left, 1e-4f)
        assertEquals(0f, dexBounds3x2.top, 1e-4f)

        // 4. Samsung DeX Widescreen 16:9 on DeX FHD
        val dexBounds16x9 = com.photoselectortoolbox.ui.components.CompositionGeometry.calculateDisplayedBounds(
            containerWidth = 1920f,
            containerHeight = 1080f,
            aspectRatio = 16f / 9f,
        )
        assertEquals(1920f, dexBounds16x9.width, 1e-4f)
        assertEquals(1080f, dexBounds16x9.height, 1e-4f)
        assertEquals(0f, dexBounds16x9.left, 1e-4f)
        assertEquals(0f, dexBounds16x9.top, 1e-4f)
    }

    @Test
    fun `CompositionGeometry stress test verifies bounds invariants across randomized geometries`() {
        val aspectRatios = listOf(0.4f, 0.5f, 0.6667f, 0.75f, 1.0f, 1.3333f, 1.5f, 1.7778f, 2.3333f, 3.0f)
        val widths = listOf(600f, 840f, 1024f, 1280f, 1480f, 1920f, 2560f, 3840f)
        val heights = listOf(400f, 600f, 800f, 924f, 1080f, 1200f, 1440f, 2160f)

        for (w in widths) {
            for (h in heights) {
                for (ar in aspectRatios) {
                    val bounds = com.photoselectortoolbox.ui.components.CompositionGeometry.calculateDisplayedBounds(w, h, ar)

                    // 1. Must never exceed container
                    assertTrue("Bounds width $bounds exceeds container $w", bounds.width <= w + 1e-3f)
                    assertTrue("Bounds height $bounds exceeds container $h", bounds.height <= h + 1e-3f)

                    // 2. Offsets non-negative
                    assertTrue("Left offset negative: ${bounds.left}", bounds.left >= -1e-4f)
                    assertTrue("Top offset negative: ${bounds.top}", bounds.top >= -1e-4f)

                    // 3. Exactly fits inside container with centered margins
                    val expectedLeft = (w - bounds.width) / 2f
                    val expectedTop = (h - bounds.height) / 2f
                    assertEquals("Left not centered", expectedLeft, bounds.left, 1e-3f)
                    assertEquals("Top not centered", expectedTop, bounds.top, 1e-3f)

                    // 4. Exact aspect ratio preserved
                    val measuredAr = bounds.width / bounds.height
                    assertEquals("Aspect ratio mismatch", ar, measuredAr, 1e-3f)

                    // 5. At least one dimension touches container edge
                    val widthTouches = kotlin.math.abs(bounds.width - w) < 1e-3f
                    val heightTouches = kotlin.math.abs(bounds.height - h) < 1e-3f
                    assertTrue("Neither dimension touches container", widthTouches || heightTouches)
                }
            }
        }
    }
}
