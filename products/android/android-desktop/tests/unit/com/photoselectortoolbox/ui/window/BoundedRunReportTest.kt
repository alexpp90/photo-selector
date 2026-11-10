package com.photoselectortoolbox.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * The failure message of a test that outlived `WindowGeometryRule`'s 150 s bound, and the probe
 * that records how far its body had got. M-9 (Moto G77, 2026-11-09): the body had returned and the
 * Compose rule's teardown `waitForIdleSync` was blocked, but the message carried only the worker's
 * stack, which names the wait and not what the main thread was doing.
 */
class BoundedRunReportTest {

    private val workerStack = listOf(
        StackTraceElement("android.app.Instrumentation", "waitForIdleSync", "Instrumentation.java", 400),
        StackTraceElement("androidx.compose.ui.test.junit4.AndroidComposeTestRule\$apply\$testWithDisposal\$1", "evaluate", "AndroidComposeTestRule.android.kt", 433),
    )
    private val mainStack = listOf(
        StackTraceElement("android.os.MessageQueue", "nativePollOnce", null, -2),
        StackTraceElement("android.os.Looper", "loop", "Looper.java", 300),
    )
    private val description = Description.createTestDescription("Cls", "test")

    private fun message(body: BodyOutcome, main: MainThreadSnapshot = MainThreadSnapshot(mainStack, "Looper (main, tid 2) {1}\n  Message 0: { when=-1s }\n"), display: String = "window manager: cur=1x1") =
        BoundedRunReport.timeoutMessage(150_000L, workerStack, body, nowMillis = 150_000L, main = main, display = display)

    @Test
    fun theFirstLineIsTheOneTheMatrixAlreadyGroupsBy() {
        assertEquals(
            "the test did not finish within 150 s; its thread was blocked at:",
            message(BodyOutcome.Passed(10_000L)).lineSequence().first(),
        )
    }

    @Test
    fun theMessageCarriesTheWorkerTheBodyTheMainThreadItsQueueAndTheDisplay_inThatOrder() {
        val text = message(BodyOutcome.Passed(10_000L))
        val order = listOf(
            "at android.app.Instrumentation.waitForIdleSync(Instrumentation.java:400)",
            "had passed 140.0 s before the bound",
            "main thread at the bound:",
            "at android.os.MessageQueue.nativePollOnce(Native Method)",
            "main looper queue at the bound:",
            "    Looper (main, tid 2) {1}",
            "    Message 0: { when=-1s }",
            "window manager: cur=1x1",
        ).map { needle -> text.indexOf(needle).also { assertTrue("missing '$needle' in:\n$text", it >= 0) } }
        assertEquals(order.sorted(), order)
    }

    @Test
    fun eachBodyOutcomeSaysWhereTheHangIs() {
        assertTrue(BoundedRunReport.describeBody(BodyOutcome.Unrecorded, 0).contains("not recorded"))
        assertTrue(BoundedRunReport.describeBody(BodyOutcome.NotStarted, 0).contains("had not started"))
        assertTrue(BoundedRunReport.describeBody(BodyOutcome.Running(1_000L), 3_500L).contains("still running, for 2.5 s"))
        val passed = BoundedRunReport.describeBody(BodyOutcome.Passed(1_000L), 141_000L)
        assertTrue(passed, passed.contains("had passed 140.0 s before the bound") && passed.contains("teardown"))
    }

    @Test
    fun aBodyThatThrewNamesTheErrorWithAShortStack() {
        val error = IllegalStateException("the counter never read 2 / 2\nsecond line")
        error.stackTrace = Array(30) { StackTraceElement("C", "m$it", "C.kt", it + 1) }
        val text = BoundedRunReport.describeBody(BodyOutcome.Threw(0L, error), 2_000L)
        assertTrue(text, text.contains("had thrown 2.0 s before the bound"))
        assertTrue(text, text.contains("java.lang.IllegalStateException: the counter never read 2 / 2"))
        assertTrue("only the error's first line", !text.contains("second line"))
        assertTrue(text, text.contains("at C.m11(C.kt:12)"))
        assertTrue("capped at ${BoundedRunReport.MAX_ERROR_FRAMES} frames", !text.contains("at C.m12("))
        assertTrue(text, text.contains("… 18 more frames"))
    }

    @Test
    fun aSnapshotThatDidNotFinishSaysSo_andTheMessageIsStillWhole() {
        val text = message(
            BodyOutcome.Passed(0L),
            main = MainThreadSnapshot(null, null, "(the main thread snapshot did not finish within 5 s)"),
            display = "",
        )
        assertEquals(2, Regex("""\(not captured\)""").findAll(text).count())
        assertTrue(text, text.endsWith("(the main thread snapshot did not finish within 5 s)"))
    }

    @Test
    fun theQueueDumpIsCappedInLinesAndInWidth() {
        val dump = (1..100).joinToString("\n") { "  Message $it: " + "x".repeat(1_000) }
        val capped = BoundedRunReport.capQueueDump(dump)
        val lines = capped.lines()
        assertEquals(BoundedRunReport.MAX_QUEUE_LINES + 1, lines.size)
        assertEquals("    … 40 more lines", lines.last())
        assertTrue(lines.dropLast(1).all { it.length == 4 + BoundedRunReport.MAX_LINE_CHARS })
        assertEquals("    (empty)", BoundedRunReport.capQueueDump("\n \n"))
    }

    @Test
    fun theMainStackIsCapped() {
        val deep = List(200) { StackTraceElement("D", "f$it", "D.kt", it) }
        val text = message(BodyOutcome.NotStarted, main = MainThreadSnapshot(deep, ""))
        assertTrue(text.contains("at D.f79(D.kt:79)"))
        assertTrue(!text.contains("at D.f80("))
        assertTrue(text.contains("… 120 more frames"))
    }

    // ── BodyProbe ────────────────────────────────────────────────────────

    private class Clock(var now: Long = 0L)

    @Test
    fun theProbeRecordsAPassingBody() {
        val clock = Clock()
        val probe = BodyProbe { clock.now }
        val statement = probe.apply(object : Statement() {
            override fun evaluate() { clock.now = 42L }
        }, description)
        assertEquals(BodyOutcome.NotStarted, probe.outcomeFor(description))
        statement.evaluate()
        assertEquals(BodyOutcome.Passed(42L), probe.outcomeFor(description))
    }

    @Test
    fun theProbeRecordsAThrowingBody_andRethrowsTheSameError() {
        val clock = Clock(7L)
        val probe = BodyProbe { clock.now }
        val error = AssertionError("red")
        val statement = probe.apply(object : Statement() {
            override fun evaluate() = throw error
        }, description)
        val thrown = assertThrows(AssertionError::class.java) { statement.evaluate() }
        assertSame(error, thrown)
        val outcome = probe.outcomeFor(description) as BodyOutcome.Threw
        assertEquals(7L, outcome.endedAtMillis)
        assertSame(error, outcome.error)
    }

    @Test
    fun theProbeRecordsARunningBody() {
        val clock = Clock(5L)
        val probe = BodyProbe { clock.now }
        var seen: BodyOutcome? = null
        probe.apply(object : Statement() {
            override fun evaluate() { seen = probe.outcomeFor(description) }
        }, description).evaluate()
        assertEquals(BodyOutcome.Running(5L), seen)
    }

    @Test
    fun aProbeThatDoesNotWrapTheTestReportsNothingRecorded() {
        val probe = BodyProbe { 0L }
        assertEquals(BodyOutcome.Unrecorded, probe.outcomeFor(description))
        probe.apply(object : Statement() { override fun evaluate() = Unit }, description).evaluate()
        assertEquals(BodyOutcome.Unrecorded, probe.outcomeFor(Description.createTestDescription("Cls", "another")))
    }
}
