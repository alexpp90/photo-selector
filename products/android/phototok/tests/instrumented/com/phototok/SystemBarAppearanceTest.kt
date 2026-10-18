package com.phototok

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PhotoTok is always dark, so the system bars must always draw light icons. On a Pixel 4a in
 * light system mode (2026-10-18) the window ended up with `LIGHT_STATUS_BARS` and
 * `LIGHT_NAVIGATION_BARS` — dark icons on PhotoTok's dark bars and a light navigation-bar
 * scrim — after a rotation from landscape back to portrait, although the theme requested light
 * icons.
 *
 * The check reads the appearance the window manager actually applies (`dumpsys window`): the
 * insets controller's requested value said "light icons" while the screen showed dark ones.
 */
@RunWith(AndroidJUnit4::class)
class SystemBarAppearanceTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @After
    fun unfreezeRotation() {
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
    }

    @Test
    fun systemBars_drawLightIcons_afterLaunchAndAfterRotation() {
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        ActivityScenario.launch(MainActivity::class.java).use {
            settle()
            assertLightIcons("after launch")
            instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90)
            settle()
            assertLightIcons("in landscape")
            instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
            settle()
            assertLightIcons("back in portrait")
        }
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        Thread.sleep(1500)
        instrumentation.waitForIdleSync()
    }

    private fun assertLightIcons(phase: String) {
        val window = "${instrumentation.targetContext.packageName}/com.phototok.MainActivity"
        val block = shell("dumpsys window windows").lines()
            .dropWhile { !(it.contains("Window #") && it.contains(window)) }
            .drop(1)
            .takeWhile { !it.contains("Window #") }
        assertNotNull("$phase: no window found for $window", block.firstOrNull())
        val appearance = block.firstOrNull { it.trim().startsWith("apr=") }.orEmpty()
        assertFalse(
            "$phase: dark status-bar icons on PhotoTok's dark UI ($appearance)",
            appearance.contains("LIGHT_STATUS_BARS"),
        )
        assertFalse(
            "$phase: dark navigation-bar icons on PhotoTok's dark UI ($appearance)",
            appearance.contains("LIGHT_NAVIGATION_BARS"),
        )
    }

    private fun shell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }
}
