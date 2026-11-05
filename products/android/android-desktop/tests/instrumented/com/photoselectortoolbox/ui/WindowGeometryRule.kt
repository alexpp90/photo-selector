package com.photoselectortoolbox.ui

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.concurrent.atomic.AtomicReference

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
 * Four guarantees beyond forcing the size (device matrix, 2026-11-05):
 *
 * - **The window is the reference one, to the pixel.** A display cutout beside the window takes
 *   width the activity cannot use; the long edge grows until the usable width is the reference
 *   2960 px, exactly ([ReferenceWindowMath], proved on the JVM). One pixel short is 1479.5 dp and
 *   a 1480 dp host refuses to start.
 * - **The rotation is really fixed.** `cmd window fixed-to-user-rotation` (API 31+; API 30 spells it
 *   `set-fix-to-user-rotation`, see [FixedRotation]) makes the display follow `user_rotation` whatever
 *   the foreground app asks for. This rule used the API 30 spelling everywhere and the other releases
 *   answered "Unknown command" on stdout, so the launcher's NOSENSOR hold decided the rotation on
 *   every phone (a Pixel 4a: 924 x 1334 dp). Now the command is spelled for the release, a command
 *   that answers "Unknown command" fails the step, the mode is read back, and the original mode
 *   (`default|enabled|disabled|enabled_if_no_auto_rotation`) is restored exactly.
 * - **It holds.** [applyReference] returns only once the display has been landscape and wide, and
 *   unchanged, for a whole second.
 * - **A test cannot hang, and a killed run cannot leave the device forced.** Every shell call and
 *   the test body itself are bounded, and fail with the stack of the blocked thread; the original
 *   state is written to the device before anything is forced ([DisplayRecord]), so the run after
 *   a kill restores the device's own geometry instead of adopting the leftover.
 *
 * Order it before the Hilt and Compose rules: the activity must start in the new geometry.
 */
class WindowGeometryRule(
    private val testTimeoutMillis: Long = TEST_TIMEOUT_MILLIS,
) : TestRule {

    override fun apply(base: Statement, description: Description): Statement {
        val wanted = description.getAnnotation(ExpandedWindow::class.java) != null ||
            description.testClass?.getAnnotation(ExpandedWindow::class.java) != null
        if (!wanted) {
            return object : Statement() {
                override fun evaluate() = runBounded(base)
            }
        }
        return object : Statement() {
            override fun evaluate() {
                val saved = Saved.read()
                val timeline = DisplayTimeline().also { it.start() }
                var failure: Throwable? = null
                try {
                    applyReference(saved)
                    runBounded(base)
                } catch (t: Throwable) {
                    failure = t
                    // The failure's own message stays as it is (the matrix groups issues by it); the
                    // timeline rides along, so a test that lost its window names when it did.
                    t.addSuppressed(AssertionError("display timeline, from the start of the rule: ${timeline.render()}"))
                    throw t
                } finally {
                    timeline.stop()
                    try {
                        saved.restore()
                    } catch (t: Throwable) {
                        // The test's own failure is the news; a failed restore is a footnote to it.
                        if (failure != null) failure.addSuppressed(t) else throw t
                    }
                }
            }
        }
    }

    /** What the window manager reports, for a failure message: the process's own view is not the display's. */
    fun displayReport(): String = describeDisplay()

    /**
     * Runs [base] on a worker thread and waits [testTimeoutMillis] for it. A test blocked in a
     * call that never returns (a key injection waiting for a window or an IME, an idle wait, a shell
     * command) used to hold the instrumentation process until the matrix killed it ten minutes
     * later, leaving the device forced and no hint of where (Moto G77, 2026-11-05). Now the test
     * fails, with the worker's stack, and the rule's `finally` restores the display.
     */
    private fun runBounded(base: Statement) {
        val outcome = AtomicReference<Throwable?>(null)
        val worker = Thread({
            try {
                base.evaluate()
            } catch (t: Throwable) {
                outcome.set(t)
            }
        }, "frameduel-test-body")
        worker.isDaemon = true
        worker.start()
        worker.join(testTimeoutMillis)
        if (worker.isAlive) {
            val stack = worker.stackTrace
            worker.interrupt()
            throw AssertionError(
                "the test did not finish within ${testTimeoutMillis / 1000} s; its thread was blocked at:\n" +
                    stack.joinToString("\n") { "    at $it" } + "\n" + runCatching { describeDisplay() }.getOrDefault(""),
            )
        }
        outcome.get()?.let { throw it }
    }

    private fun applyReference(saved: Saved) {
        val naturalPortrait = saved.naturalPortrait
        shell("settings put system accelerometer_rotation 0")
        shell("settings put system user_rotation ${userRotationFor(naturalPortrait)}")
        // The launcher (and any other activity that asks for SCREEN_ORIENTATION_NOSENSOR) holds a
        // phone in portrait whatever `user_rotation` says, and no test activity is on screen yet
        // when this rule runs: CI's emulator stayed at 922 x 1477 dp (2026-11-04), a Pixel 4a at
        // 924 x 1334 dp (2026-11-05). Fixing the display to the user rotation makes the rotation
        // independent of the foreground app. The command is spelled differently on API 30 and from
        // API 31 ([FixedRotation]); this rule used the API 30 spelling on every device for months
        // and the others answered "Unknown command" on stdout, which nothing read. [shell] now fails
        // on that answer, and the mode is read back: a rule that does not fix the rotation stops here.
        shell(FixedRotation.setCommand(SDK_INT, FixedRotation.ENABLED))
        val fixedNow = FixedRotation.parseQuery(shellUnchecked(FixedRotation.queryCommand(SDK_INT)))
        check(fixedNow == null || fixedNow == FixedRotation.ENABLED) {
            "the display is still '$fixedNow' after fixed-to-user-rotation enabled\n${describeDisplay()}"
        }
        // `wm size` is given in the panel's natural orientation: portrait-natural devices (phones,
        // the Pixel Tablet) take 1848x2960 and are rotated; landscape-natural ones take 2960x1848.
        // A display cutout beside the window (a Pixel 4a's punch hole, scaled with the size) takes
        // width the activity cannot use, so the long edge grows by that inset until the usable
        // width is the reference 2960 px: the test sees the reference window, not a narrower one.
        //
        // The window manager clamps a forced size to twice the panel's own (WMS MAX_SCALE = 2), so
        // a panel smaller than 924 x 1480 px (CI's 320x640 emulator) can never be given the
        // reference size in pixels. Size and density are scaled together
        // ([ReferenceWindowMath.forced]): the result is the same window in dp, in fewer pixels.
        var referenceLong = ReferenceWindowMath.LONG_EDGE_PX // in px at the reference 320 dpi
        var rounds = 0
        while (rounds++ < MAX_CUTOUT_ROUNDS) {
            val forced = ReferenceWindowMath.forced(saved.physicalShort, saved.physicalLong, referenceLong)
            shell("wm density ${forced.density}")
            shell(
                if (naturalPortrait) "wm size ${forced.shortPx}x${forced.longPx}"
                else "wm size ${forced.longPx}x${forced.shortPx}",
            )
            waitForExpanded(naturalPortrait)
            // The cutout is scaled by the window manager a moment after the size lands; a reading
            // taken before it settles is the previous size's, one pixel off (3142 px: 182, then 183).
            waitForSize(forced)
            // Null: the usable width already is the reference one. Otherwise the next, longer edge;
            // when the rounds run out the tests that need the whole 1480 dp fail with the window
            // they were given, which their message states.
            referenceLong = ReferenceWindowMath.widerLongEdge(referenceLong, forced, settledCutoutPx()) ?: break
        }
        holdReferenceWindow(naturalPortrait)
    }

    private fun userRotationFor(naturalPortrait: Boolean) = if (naturalPortrait) 1 else 0

    private fun putLandscapeRotation(naturalPortrait: Boolean) {
        shell("settings put system user_rotation ${userRotationFor(naturalPortrait)}")
    }

    /** Left plus right cutout inset of the display in its current rotation, in px (0 when none). */
    private fun horizontalCutoutPx(): Int {
        val match = Regex("""mDisplayCutout=DisplayCutout\{insets=Rect\((\d+), \d+ - (\d+), \d+\)""")
            .find(shell("dumpsys window displays")) ?: return 0
        return match.groupValues[1].toInt() + match.groupValues[2].toInt()
    }

    /** The cutout once two readings 200 ms apart agree (at most six readings). */
    private fun settledCutoutPx(): Int {
        var last = horizontalCutoutPx()
        repeat(5) {
            Thread.sleep(200)
            val now = horizontalCutoutPx()
            if (now == last) return now
            last = now
        }
        return last
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
            check(now <= deadline) {
                "Timed out waiting for the display reports an expanded window (display is now $dp dp)\n${describeDisplay()}"
            }
            if (now - lastRotation > 2_500) {
                putLandscapeRotation(naturalPortrait)
                lastRotation = now
            }
            Thread.sleep(100)
        }
    }

    /** Waits (softly, five seconds) for the display to report exactly the forced size and density. */
    private fun waitForSize(forced: ReferenceWindowMath.Forced) {
        waitUntil("the display reports the forced size", timeoutMillis = 5_000, fatal = false) {
            displayState()?.isLandscapeOf(forced) == true
        }
    }

    /**
     * The last step of [applyReference]: the display must be landscape, wide, and unchanged for a
     * whole second before the test starts. A rotation that is undone after the first
     * `waitForExpanded` (a settle still in progress) shows up here and is put again, rather than
     * after the activity has launched into portrait, where nothing would ever repair it.
     */
    private fun holdReferenceWindow(naturalPortrait: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 25_000
        var last: DisplayState? = null
        var steadySince = SystemClock.elapsedRealtime()
        var lastRotation = steadySince
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val state = displayState()
            val expanded = state != null && state.widthPx > state.heightPx && state.widthDp >= REFERENCE_MIN_WIDTH_DP
            if (!expanded) {
                steadySince = now
                last = null
                if (now - lastRotation > 1_000) {
                    putLandscapeRotation(naturalPortrait)
                    lastRotation = now
                }
            } else if (state != last) {
                last = state
                steadySince = now
            } else if (now - steadySince >= HOLD_MILLIS) {
                return
            }
            check(now <= deadline) {
                "The display did not stay at the reference geometry (it reports $state)\n${describeDisplay()}"
            }
            Thread.sleep(150)
        }
    }

    private class Saved(
        val naturalPortrait: Boolean,
        val physicalShort: Int,
        val physicalLong: Int,
        /** What to put back, from this run's own reading or from the record a killed run left. */
        private val original: DisplayRecord,
    ) {
        fun restore() {
            try {
                restoreDisplay()
            } finally {
                // Last, so the rotation loop below still runs with the display fixed to it.
                shell(FixedRotation.setCommand(SDK_INT, original.fixedToUserRotation ?: FixedRotation.DEFAULT))
            }
            // Only a restore that ran to the end clears the record; a failed one leaves it for the next run.
            waitForStableDisplay()
            shell("settings delete global $RECORD_KEY")
        }

        private fun restoreDisplay() {
            val sizeOverride = original.sizeOverride
            val densityOverride = original.densityOverride
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
                putOrDelete("user_rotation", original.userRotation)
                putOrDelete("accelerometer_rotation", original.accelerometerRotation)
                waitUntil("the display returns to its previous size", timeoutMillis = 3_000, fatal = false) {
                    displayDp()?.first == original.widthDpBefore
                }
                if (displayDp()?.first == original.widthDpBefore) return
            }
        }

        /**
         * The display unchanged for 600 ms, so that the next test's first reading (the width it
         * will wait for on restore) is the settled one and not a frame of this restore's tail.
         */
        private fun waitForStableDisplay() {
            var last = displayState()
            var steadySince = SystemClock.elapsedRealtime()
            val deadline = steadySince + 5_000
            while (true) {
                Thread.sleep(150)
                val now = SystemClock.elapsedRealtime()
                val state = displayState()
                if (state != last) {
                    last = state
                    steadySince = now
                } else if (now - steadySince >= 600) {
                    return
                }
                if (now > deadline) return
            }
        }

        private fun putOrDelete(key: String, value: String) {
            if (value == "null" || value == "-" || value.isEmpty()) {
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
                val live = DisplayRecord(
                    sizeOverride = Regex("""Override size:\s*(\d+x\d+)""").find(size)?.groupValues?.get(1),
                    densityOverride = Regex("""Override density:\s*(\d+)""")
                        .find(shell("wm density"))?.groupValues?.get(1),
                    accelerometerRotation = shell("settings get system accelerometer_rotation").trim(),
                    userRotation = shell("settings get system user_rotation").trim(),
                    fixedToUserRotation = FixedRotation.parseQuery(shellUnchecked(FixedRotation.queryCommand(SDK_INT))),
                    widthDpBefore = displayDp()?.first,
                )
                // A record still on the device is from a run that was killed with the display
                // forced: it, not what is on the display now, is the device's own state.
                val leftover = DisplayRecord.decode(shell("settings get global $RECORD_KEY"))
                val original = leftover ?: live
                shell("settings put global $RECORD_KEY '${original.encode()}'")
                return Saved(
                    naturalPortrait = naturalPortrait,
                    physicalShort = physical?.groupValues?.let { minOf(it[1].toInt(), it[2].toInt()) }
                        ?: ReferenceWindowMath.SHORT_EDGE_PX,
                    physicalLong = physical?.groupValues?.let { maxOf(it[1].toInt(), it[2].toInt()) }
                        ?: ReferenceWindowMath.LONG_EDGE_PX,
                    original = original,
                )
            }
        }
    }

    /**
     * The display's size and density once a second, kept as the changes between readings. Three
     * failures of the 2026-11-05 matrix (a window one pixel short, one that was portrait for ten
     * seconds, a frame that never came back after UNDO) look like the display moving under the
     * test, and the run kept no log of the attempt that failed; this is that log, in the failure.
     */
    private class DisplayTimeline {
        private val startedAt = SystemClock.elapsedRealtime()
        private val changes = java.util.Collections.synchronizedList(mutableListOf<String>())

        @Volatile
        private var running = true

        private val sampler = Thread({
            var last: DisplayState? = null
            try {
                while (running) {
                    val state = runCatching { displayState() }.getOrNull()
                    if (state != null && state != last) {
                        last = state
                        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
                        changes += "t+%.1fs %dx%d px @%d dpi (%d x %d dp)".format(
                            seconds, state.widthPx, state.heightPx, state.dpi, state.widthDp, state.heightDp,
                        )
                    }
                    Thread.sleep(SAMPLE_MILLIS)
                }
            } catch (_: InterruptedException) {
                // stopped; an uncaught exception on any thread would take the instrumentation down
            }
        }, "frameduel-display-timeline").apply { isDaemon = true }

        fun start() = sampler.start()

        fun stop() {
            running = false
            sampler.interrupt()
        }

        fun render(): String = synchronized(changes) { changes.takeLast(15).joinToString("; ").ifEmpty { "no reading" } }

        private companion object {
            const val SAMPLE_MILLIS = 1_000L
        }
    }

    /** One reading of the default display: the size in the current rotation, and the density. */
    private data class DisplayState(val widthPx: Int, val heightPx: Int, val dpi: Int) {
        val widthDp get() = widthPx * 160 / dpi
        val heightDp get() = heightPx * 160 / dpi

        fun isLandscapeOf(forced: ReferenceWindowMath.Forced) =
            widthPx == forced.longPx && heightPx == forced.shortPx && dpi == forced.density
    }

    companion object {
        /** The reference landscape window is 1480 dp wide; anything from 1400 dp up is it. */
        const val REFERENCE_MIN_WIDTH_DP = 1400

        /**
         * The longest a test body may run. The slowest passing test takes 22 s on the slowest
         * device, so this is a bound on hangs, not a budget.
         */
        const val TEST_TIMEOUT_MILLIS = 150_000L

        private val SDK_INT = android.os.Build.VERSION.SDK_INT

        /** The longest a shell command may run; they take well under a second. */
        private const val SHELL_TIMEOUT_MILLIS = 20_000L

        /** How long the display must stay landscape at the forced size before a test may start. */
        private const val HOLD_MILLIS = 1_000L

        /** A Pixel 4a needs three rounds, one more to land exactly; the cutout of any panel stops growing by five. */
        private const val MAX_CUTOUT_ROUNDS = 8

        /** The system setting that holds the original display state while it is forced ([DisplayRecord]). */
        private const val RECORD_KEY = "frameduel_test_display_original"

        /**
         * Runs [command] through the instrumentation's shell identity, bounded: a pipe that never
         * closes (a `dumpsys` waiting on a wedged window manager) used to block the test thread
         * for ever.
         */
        private fun shell(command: String): String {
            val output = shellUnchecked(command)
            ShellOutput.failure(output)?.let { error("`$command` did not run: $it") }
            return output
        }

        /** [shell] without the check of the answer, for a query whose failure is an answer (an older build). */
        private fun shellUnchecked(command: String): String {
            val result = AtomicReference<Result<String>?>(null)
            val reader = Thread({
                result.set(
                    runCatching {
                        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
                        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
                    },
                )
            }, "frameduel-shell")
            reader.isDaemon = true
            reader.start()
            reader.join(SHELL_TIMEOUT_MILLIS)
            val outcome = result.get()
                ?: error("`$command` did not finish within ${SHELL_TIMEOUT_MILLIS / 1000} s: the shell or the window manager is wedged")
            return outcome.getOrThrow()
        }

        /**
         * The display's current size in px and its density, rotation included, read from the window
         * manager rather than from this process: a process with no activity on screen is not told
         * about display changes until it comes to the foreground again.
         */
        private fun displayState(): DisplayState? {
            val line = shell("dumpsys window displays").lineSequence().firstOrNull { "cur=" in it && "dpi" in it }
                ?: return null
            // `init=… 440dpi … base=… 320dpi`: the override, when there is one, comes last.
            val dpi = Regex("""(\d+)dpi""").findAll(line).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
                ?: return null
            val cur = Regex("""cur=(\d+)x(\d+)""").find(line) ?: return null
            return DisplayState(cur.groupValues[1].toInt(), cur.groupValues[2].toInt(), dpi)
        }

        /** The display's current size in dp (see [displayState]). */
        private fun displayDp(): Pair<Int, Int>? = displayState()?.let { it.widthDp to it.heightDp }

        /**
         * The lines of `dumpsys window displays` that say how the window manager sees the rotation,
         * with the settings behind it. A failure message carries this so that a host that did not
         * get the window it needed names the cause (the display, or only this process) at once.
         */
        private fun describeDisplay(): String {
            val keys = listOf("cur=", "mRotation=", "mUserRotation", "mFixedToUserRotation", "mCurrentAppOrientation", "mDisplayCutout=")
            val lines = shell("dumpsys window displays").lineSequence()
                .map { it.trim() }
                .filter { line -> keys.any { it in line } }
                .take(8)
                .joinToString(" | ") { it.take(160) }
            val settings = "user_rotation=" + shell("settings get system user_rotation").trim() +
                " accelerometer_rotation=" + shell("settings get system accelerometer_rotation").trim()
            return "window manager: $lines; $settings"
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
