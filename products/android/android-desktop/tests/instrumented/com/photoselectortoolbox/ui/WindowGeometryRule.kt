package com.photoselectortoolbox.ui

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import kotlin.math.ceil
import kotlin.math.floor

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
                    applyReference(saved)
                    base.evaluate()
                } finally {
                    saved.restore()
                }
            }
        }
    }

    private fun applyReference(saved: Saved) {
        val naturalPortrait = saved.naturalPortrait
        shell("settings put system accelerometer_rotation 0")
        shell("settings put system user_rotation ${if (naturalPortrait) 1 else 0}")
        // The launcher (and any other activity that asks for SCREEN_ORIENTATION_NOSENSOR) holds a
        // phone in portrait whatever `user_rotation` says, and no test activity is on screen yet
        // when this rule runs: CI's emulator stayed at 922 x 1477 dp (2026-11-04). Fixing the
        // display to the user rotation makes the rotation independent of the foreground app.
        if (saved.fixedToUserRotation != null) shell("cmd window set-fix-to-user-rotation enabled")
        // `wm size` is given in the panel's natural orientation: portrait-natural devices (phones,
        // the Pixel Tablet) take 1848x2960 and are rotated; landscape-natural ones take 2960x1848.
        // A display cutout beside the window (a Pixel 4a's punch hole, scaled with the size) takes
        // width the activity cannot use, so the long edge grows by that inset until the usable
        // width is the reference 2960 px: the test sees the reference window, not a narrower one.
        //
        // The window manager clamps a forced size to twice the panel's own (WMS MAX_SCALE = 2), so
        // a panel smaller than 924 x 1480 px (CI's 320x640 emulator) can never be given the
        // reference size in pixels. Size and density are scaled together by [referenceScale]: the
        // result is the same window in dp, in fewer pixels.
        var referenceLong = REFERENCE_LONG_EDGE_PX // in px at the reference 320 dpi
        repeat(4) {
            val scale = referenceScale(saved.physicalShort, saved.physicalLong, referenceLong)
            // Density rounds down and the pixels up, so the window in dp is never narrower than
            // the reference (rounding both to nearest gave 1477 dp, three short of the 1480 dp
            // hosts); the pixels stay within the clamp because density <= 320 * scale.
            val density = floor(REFERENCE_DENSITY * scale).toInt()
            val short = ceil(REFERENCE_SHORT_EDGE_PX * density / REFERENCE_DENSITY.toDouble()).toInt()
            val long = ceil(referenceLong * density / REFERENCE_DENSITY.toDouble()).toInt()
            shell("wm density $density")
            shell(if (naturalPortrait) "wm size ${short}x$long" else "wm size ${long}x$short")
            waitForExpanded(naturalPortrait)
            Thread.sleep(500)
            val inset = horizontalCutoutPx() * REFERENCE_DENSITY / density
            if (referenceLong - inset >= REFERENCE_LONG_EDGE_PX - 1) return
            referenceLong = REFERENCE_LONG_EDGE_PX + inset
        }
    }

    /** Left plus right cutout inset of the display in its current rotation, in px (0 when none). */
    private fun horizontalCutoutPx(): Int {
        val match = Regex("""mDisplayCutout=DisplayCutout\{insets=Rect\((\d+), \d+ - (\d+), \d+\)""")
            .find(shell("dumpsys window displays")) ?: return 0
        return match.groupValues[1].toInt() + match.groupValues[2].toInt()
    }

    /**
     * Waits for a landscape window of at least 1400 dp. The rotation is put again every 2.5 s: when
     * the previous test's restore is still settling, the window manager can take the first
     * `user_rotation` for the one it is about to undo (a Pixel 4a, 2026-11-04).
     */
    private fun waitForExpanded(naturalPortrait: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 25_000
        var lastRotation = SystemClock.elapsedRealtime()
        while (true) {
            val dp = displayDp()
            if (dp != null && dp.first >= REFERENCE_MIN_WIDTH_DP && dp.first > dp.second) return
            val now = SystemClock.elapsedRealtime()
            check(now <= deadline) { "Timed out waiting for the display reports an expanded window (display is now $dp dp)" }
            if (now - lastRotation > 2_500) {
                shell("settings put system user_rotation ${if (naturalPortrait) 1 else 0}")
                lastRotation = now
            }
            Thread.sleep(100)
        }
    }

    private class Saved(
        val naturalPortrait: Boolean,
        val physicalShort: Int,
        val physicalLong: Int,
        /** Whether the display ignores app-requested orientations; null if the build does not say. */
        val fixedToUserRotation: Boolean?,
        private val sizeOverride: String?,
        private val densityOverride: String?,
        private val accelerometerRotation: String,
        private val userRotation: String,
        val widthDpBefore: Int?,
    ) {
        fun restore() {
            try {
                restoreDisplay()
            } finally {
                // Last, so the rotation loop below still runs with the display fixed to it.
                if (fixedToUserRotation != null) {
                    shell("cmd window set-fix-to-user-rotation ${if (fixedToUserRotation) "enabled" else "disabled"}")
                }
            }
        }

        private fun restoreDisplay() {
            if (sizeOverride == null) shell("wm size reset") else shell("wm size $sizeOverride")
            if (densityOverride == null) shell("wm density reset") else shell("wm density $densityOverride")
            // The rotation is put back only once the size has landed: set in the same breath, the
            // window manager re-saves the rotation it is still showing as the user's choice and the
            // phone stays in landscape (measured on a Pixel 4a, 2026-11-04). It is put again, up to
            // four times, until the display reports the width it had before.
            waitUntil("the display leaves the reference size", timeoutMillis = 5_000, fatal = false) {
                (displayDp()?.first ?: 0) < REFERENCE_MIN_WIDTH_DP
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
                    physicalShort = physical?.groupValues?.let { minOf(it[1].toInt(), it[2].toInt()) } ?: REFERENCE_SHORT_EDGE_PX,
                    fixedToUserRotation = Regex("""mFixedToUserRotation=(true|false)""")
                        .find(shell("dumpsys window displays"))?.groupValues?.get(1)?.toBooleanStrictOrNull(),
                    physicalLong = physical?.groupValues?.let { maxOf(it[1].toInt(), it[2].toInt()) } ?: REFERENCE_LONG_EDGE_PX,
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
        private const val REFERENCE_LONG_EDGE_PX = 2960
        private const val REFERENCE_SHORT_EDGE_PX = 1848
        private const val REFERENCE_DENSITY = 320

        /** WindowManagerService clamps a forced display size to this multiple of the panel's size. */
        private const val MAX_FORCED_SCALE = 2

        /**
         * The factor, at most 1, by which the reference size (1848 x [longEdge] px at 320 dpi) and
         * density shrink so that the forced size stays within [MAX_FORCED_SCALE] times the panel.
         * The window in dp is unchanged.
         */
        internal fun referenceScale(physicalShort: Int, physicalLong: Int, longEdge: Int): Double =
            minOf(
                1.0,
                MAX_FORCED_SCALE * physicalShort.toDouble() / REFERENCE_SHORT_EDGE_PX,
                MAX_FORCED_SCALE * physicalLong.toDouble() / longEdge,
            )

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
