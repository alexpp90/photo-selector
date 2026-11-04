package com.photoselectortoolbox.ui

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Marks a test (or a whole class) that asserts the expanded layout. [WindowGeometryRule] puts the
 * display into the reference geometry (a Galaxy Tab S11 Ultra in landscape, 1480 x 924 dp) before
 * the test starts and puts it back afterwards, on whatever device the suite is running on.
 *
 * Why it exists (FD-T-1): CI's emulator is phone-class, so 28 screen tests began with
 * `if (isCompactLayout()) return` and passed there without asserting anything. A test that carries
 * this annotation cannot return early: the rule makes the window expanded, and
 * [WindowGeometryRule.requireExpanded] fails the test when it is not.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ExpandedWindow

/**
 * Applies the reference display geometry for tests annotated [ExpandedWindow], through the shell
 * identity of the instrumentation (`wm size`, `wm density`, `user_rotation`), as
 * `playbook-verify-android-desktop-on-reference-device` does by hand. The previous override, if
 * any, is restored in a `finally`, so a failed test does not leave a phone at tablet size.
 *
 * Order it before the Hilt and Compose rules: the activity must start in the new geometry.
 */
class WindowGeometryRule : TestRule {

    override fun apply(base: Statement, description: Description): Statement {
        val wanted = description.getAnnotation(ExpandedWindow::class.java) != null ||
            description.testClass?.getAnnotation(ExpandedWindow::class.java) != null
        if (!wanted) return base
        return object : Statement() {
            override fun evaluate() {
                val saved = Saved.read()
                try {
                    applyReference(saved.naturalPortrait)
                    waitForExpanded()
                    base.evaluate()
                } finally {
                    saved.restore()
                }
            }
        }
    }

    private fun applyReference(naturalPortrait: Boolean) {
        // `wm size` is given in the panel's natural orientation: portrait-natural devices (phones,
        // the Pixel Tablet) take 1848x2960 and are rotated; landscape-natural ones take 2960x1848.
        if (naturalPortrait) {
            shell("settings put system accelerometer_rotation 0")
            shell("settings put system user_rotation 1")
            shell("wm size 1848x2960")
        } else {
            shell("settings put system accelerometer_rotation 0")
            shell("settings put system user_rotation 0")
            shell("wm size 2960x1848")
        }
        shell("wm density 320")
    }

    private fun waitForExpanded() = waitUntil("the display reports an expanded window") {
        val dp = displayDp()
        dp != null && dp.first >= REFERENCE_MIN_WIDTH_DP && dp.first > dp.second
    }

    private class Saved(
        val naturalPortrait: Boolean,
        private val sizeOverride: String?,
        private val densityOverride: String?,
        private val accelerometerRotation: String,
        private val userRotation: String,
        val widthDpBefore: Int?,
    ) {
        fun restore() {
            if (sizeOverride == null) shell("wm size reset") else shell("wm size $sizeOverride")
            if (densityOverride == null) shell("wm density reset") else shell("wm density $densityOverride")
            // The rotation is put back only once the size has landed: set in the same breath, the
            // window manager re-saves the rotation it is still showing as the user's choice and the
            // phone stays in landscape (measured on a Pixel 4a, 2026-11-04). It is put again, up to
            // four times, until the display reports the width it had before.
            waitUntil("the display leaves the reference size", timeoutMillis = 5_000, fatal = false) {
                displayDp()?.first != REFERENCE_WIDTH_DP
            }
            Thread.sleep(1_500)
            repeat(4) {
                putOrDelete("user_rotation", userRotation)
                putOrDelete("accelerometer_rotation", accelerometerRotation)
                waitUntil("the display returns to its previous size", timeoutMillis = 3_000, fatal = false) {
                    displayDp()?.first == widthDpBefore
                }
                if (displayDp()?.first == widthDpBefore) return
            }
        }

        private fun putOrDelete(key: String, value: String) {
            if (value == "null" || value.isEmpty()) {
                shell("settings delete system $key")
            } else {
                shell("settings put system $key $value")
            }
        }

        companion object {
            fun read(): Saved {
                val size = shell("wm size")
                val physical = Regex("""Physical size:\s*(\d+)x(\d+)""").find(size)
                val naturalPortrait = physical?.let { it.groupValues[1].toInt() < it.groupValues[2].toInt() } ?: true
                return Saved(
                    naturalPortrait = naturalPortrait,
                    sizeOverride = Regex("""Override size:\s*(\d+x\d+)""").find(size)?.groupValues?.get(1),
                    densityOverride = Regex("""Override density:\s*(\d+)""")
                        .find(shell("wm density"))?.groupValues?.get(1),
                    accelerometerRotation = shell("settings get system accelerometer_rotation").trim(),
                    userRotation = shell("settings get system user_rotation").trim(),
                    widthDpBefore = displayDp()?.first,
                )
            }
        }
    }

    companion object {
        /** The reference landscape window is 1480 dp wide; anything from 1400 dp up is it. */
        const val REFERENCE_MIN_WIDTH_DP = 1400
        private const val REFERENCE_WIDTH_DP = 1480

        private fun shell(command: String): String {
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }

        /**
         * The display's current size in dp, rotation included, read from the window manager rather
         * than from this process: a process with no activity on screen is not told about display
         * changes until it comes to the foreground again.
         */
        private fun displayDp(): Pair<Int, Int>? {
            val line = shell("dumpsys window displays").lineSequence().firstOrNull { "cur=" in it && "dpi" in it }
                ?: return null
            // `init=… 440dpi … base=… 320dpi`: the override, when there is one, comes last.
            val dpi = Regex("""(\d+)dpi""").findAll(line).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
                ?: return null
            val cur = Regex("""cur=(\d+)x(\d+)""").find(line) ?: return null
            return (cur.groupValues[1].toInt() * 160 / dpi) to (cur.groupValues[2].toInt() * 160 / dpi)
        }

        private fun waitUntil(
            what: String,
            timeoutMillis: Long = 20_000,
            fatal: Boolean = true,
            condition: () -> Boolean,
        ) {
            val deadline = SystemClock.elapsedRealtime() + timeoutMillis
            while (!condition()) {
                if (SystemClock.elapsedRealtime() > deadline) {
                    if (!fatal) return
                    error("Timed out waiting for $what (display is now ${displayDp()} dp)")
                }
                Thread.sleep(100)
            }
        }
    }
}
