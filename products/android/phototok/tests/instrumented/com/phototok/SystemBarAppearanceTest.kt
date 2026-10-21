package com.phototok

import android.app.UiAutomation
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PhotoTok is always dark, so the system bars must always draw light icons. On a Pixel 4a in
 * light system mode (2026-10-18) the window ended up with `LIGHT_STATUS_BARS` and
 * `LIGHT_NAVIGATION_BARS` — dark icons on PhotoTok's dark bars and a light navigation-bar
 * scrim — after a rotation from landscape back to portrait, although the theme requested light
 * icons.
 *
 * The check reads the appearance the window manager actually applies (the `apr=` line of
 * `dumpsys window windows`) after a real rotation: the insets controller's getter returns the
 * *requested* value, and `ActivityScenario.recreate()` is not a rotation — a test built on
 * either passed before the fix.
 *
 * The rotation part needs a display that can be rotated. It is skipped (reported as skipped, not
 * passed; the launch check still runs) where [rotationBlocker] names a reason, and never calls
 * `setRotation` there. Anything else that fails to rotate fails the test.
 */
@RunWith(AndroidJUnit4::class)
class SystemBarAppearanceTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** True once this test has frozen the rotation, so [restoreRotation] only undoes its own work. */
    private var rotationFrozen = false

    @After
    fun restoreRotation() {
        if (!rotationFrozen) return
        // Back to portrait first: a bare unfreeze left the Pixel 4a's display in landscape.
        // Then wait until the display really is there, so the restore cannot land asynchronously
        // during the next test (Acer Chromebook 714, 2026-10-19: the next test lost its compose
        // hierarchy mid-run). Best effort: never fails the test.
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        val displayManager = instrumentation.targetContext.getSystemService(DisplayManager::class.java)
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation == Surface.ROTATION_0) break
            SystemClock.sleep(POLL_MS)
        }
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        rotationFrozen = false
    }

    /**
     * Why this device's display cannot be rotated by the test, or null when it can.
     * Deliberately a device-class check, not "rotation timed out": an ordinary phone that fails
     * to rotate must still fail.
     */
    private fun rotationBlocker(): String? {
        val context = instrumentation.targetContext
        val pm = context.packageManager
        // ChromeOS (ARC, freeform windows): the Acer Chromebook 714 run of 2026-10-19 passed the
        // body, but UiAutomation.setRotation threw IllegalStateException "Not connected!" in the
        // teardown, and a late rotation restore broke the next test.
        if (pm.hasSystemFeature("org.chromium.arc") ||
            pm.hasSystemFeature("org.chromium.arc.device_management")
        ) {
            return "ChromeOS display cannot be rotated through UiAutomation"
        }
        // A dock pins the rotation: the Pixel Tablet on its desk dock (2026-10-19) showed
        // mDockMode=EXTRA_DOCK_STATE_HE_DESK, mDeskDockRotation=ROTATION_270 and uiMode type DESK,
        // and setRotation(FREEZE_0) never took effect.
        val uiModeType = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        if (uiModeType == Configuration.UI_MODE_TYPE_DESK || uiModeType == Configuration.UI_MODE_TYPE_CAR) {
            return "dock/car UI mode pins the display rotation"
        }
        return null
    }

    @Test
    fun systemBars_drawLightIcons_afterLaunchAndAfterRotation() {
        val blocker = rotationBlocker()
        if (blocker != null) {
            ActivityScenario.launch(MainActivity::class.java).use { assertLightIcons("after launch") }
            assumeTrue("Rotation part skipped: $blocker", false)
            return
        }
        rotationFrozen = true
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitRotation(scenario, Surface.ROTATION_0)
            assertLightIcons("after launch")
            instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90)
            awaitRotation(scenario, Surface.ROTATION_90)
            assertLightIcons("in landscape")
            instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
            awaitRotation(scenario, Surface.ROTATION_0)
            assertLightIcons("back in portrait")
        }
    }

    /** Polls until the activity's display reports [rotation] (the activity may be recreated). */
    private fun awaitRotation(scenario: ActivityScenario<MainActivity>, rotation: Int) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            var current = -1
            scenario.onActivity { current = it.display?.rotation ?: -1 }
            if (current == rotation) return
            SystemClock.sleep(POLL_MS)
        }
        fail("display did not reach rotation $rotation within ${TIMEOUT_MS}ms")
    }

    /**
     * Polls the applied appearance until two reads in a row agree (the window manager applies it
     * a little after the configuration change), then asserts it holds no LIGHT flag.
     */
    private fun assertLightIcons(phase: String) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        var previous: String? = null
        var appearance: String? = null
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            appearance = appliedAppearance(phase)
            if (appearance == previous) break
            previous = appearance
            SystemClock.sleep(POLL_MS)
        }
        val applied = appearance.orEmpty()
        assertFalse("$phase: dark status-bar icons on PhotoTok's dark UI ($applied)", applied.contains("LIGHT_STATUS_BARS"))
        assertFalse("$phase: dark navigation-bar icons on PhotoTok's dark UI ($applied)", applied.contains("LIGHT_NAVIGATION_BARS"))
    }

    /**
     * The `apr=` line of this activity's window. The window manager omits the line when the
     * appearance is 0 (no LIGHT flags — the correct state here), verified on Android 13 (Pixel 4a)
     * and Android 17 (LineageOS 24, Sony H3113); before the fix the same dump printed
     * `apr=LIGHT_STATUS_BARS LIGHT_NAVIGATION_BARS`. The block must be our window's real
     * attribute block (`mAttrs=`), so a format change fails instead of passing silently.
     */
    private fun appliedAppearance(phase: String): String {
        val window = "${instrumentation.targetContext.packageName}/com.phototok.MainActivity"
        val block = shell("dumpsys window windows").lines()
            .dropWhile { !(it.contains("Window #") && it.contains(window)) }
            .drop(1)
            .takeWhile { !it.contains("Window #") }
        assertNotNull("$phase: no window found for $window", block.firstOrNull())
        assertNotNull(
            "$phase: window dump of $window has no mAttrs= line (dump format changed?)",
            block.firstOrNull { it.trim().startsWith("mAttrs=") },
        )
        return block.firstOrNull { it.trim().startsWith("apr=") }?.trim() ?: "apr=<none>"
    }

    private fun shell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 200L
    }
}
