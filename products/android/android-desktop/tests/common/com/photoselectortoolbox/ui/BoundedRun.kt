package com.photoselectortoolbox.ui

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * How far the test body (its `@Before`, the test, its `@After`) had got when `WindowGeometryRule`'s
 * bound ran out. The bound wraps every rule of the class, so a test can hang after its body has
 * returned: in the Compose rule's teardown idle wait (Moto G77, 2026-11-09, M-9), or before it
 * starts, in an activity launch. The outcome tells those apart from a hang in the test itself.
 */
internal sealed interface BodyOutcome {
    /** The class does not register [BodyProbe] as its innermost rule, so nothing was recorded. */
    data object Unrecorded : BodyOutcome

    /** The probe is wired but the body had not been entered: an outer rule's set-up is blocked. */
    data object NotStarted : BodyOutcome

    data class Running(val startedAtMillis: Long) : BodyOutcome

    data class Passed(val endedAtMillis: Long) : BodyOutcome

    data class Threw(val endedAtMillis: Long, val error: Throwable) : BodyOutcome
}

/**
 * Records [BodyOutcome] for the statement it wraps. Register it as the class's innermost rule (the
 * highest `order`), so that it wraps exactly the `@Before`, the test and the `@After`:
 *
 * ```
 * @get:Rule(order = 3)
 * val bodyProbe = geometryRule.bodyProbe
 * ```
 */
internal class BodyProbe(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : TestRule {
    private val state = AtomicReference<BodyOutcome>(BodyOutcome.NotStarted)
    private val appliedTo = AtomicReference<Description?>(null)

    /** Clock of [BodyOutcome]'s times, for the reader that compares them with "now". */
    fun now(): Long = clock()

    /** The outcome of the body of [description]'s test, or [BodyOutcome.Unrecorded] if this probe does not wrap it. */
    fun outcomeFor(description: Description): BodyOutcome =
        if (appliedTo.get() == description) state.get() else BodyOutcome.Unrecorded

    override fun apply(base: Statement, description: Description): Statement {
        // JUnit applies the innermost rule first, so this runs before the outer rule's own `apply`.
        appliedTo.set(description)
        state.set(BodyOutcome.NotStarted)
        return object : Statement() {
            override fun evaluate() {
                state.set(BodyOutcome.Running(clock()))
                try {
                    base.evaluate()
                } catch (t: Throwable) {
                    state.set(BodyOutcome.Threw(clock(), t))
                    throw t
                }
                state.set(BodyOutcome.Passed(clock()))
            }
        }
    }
}

/**
 * The main thread as another thread saw it: its stack and a dump of its looper's queue
 * (`Looper.dump`, which reads the queue under the queue's own lock and never waits for the main
 * thread). Null fields were not captured in time; [note] then says so.
 */
internal data class MainThreadSnapshot(
    val stack: List<StackTraceElement>?,
    val queueDump: String?,
    val note: String? = null,
)

/** The failure message of a test that outlived `WindowGeometryRule`'s bound. Pure, so the JVM proves it. */
internal object BoundedRunReport {
    const val MAX_MAIN_FRAMES = 80
    const val MAX_ERROR_FRAMES = 12
    const val MAX_QUEUE_LINES = 60
    const val MAX_LINE_CHARS = 300

    /**
     * The first line is the one the device matrix groups failures by; it is unchanged from before
     * the main thread was added, so earlier runs' issues still match.
     */
    fun timeoutMessage(
        timeoutMillis: Long,
        workerStack: List<StackTraceElement>,
        body: BodyOutcome,
        nowMillis: Long,
        main: MainThreadSnapshot,
        display: String,
    ): String = buildString {
        append("the test did not finish within ${timeoutMillis / 1000} s; its thread was blocked at:\n")
        append(frames(workerStack, Int.MAX_VALUE))
        append('\n')
        append(describeBody(body, nowMillis))
        append('\n')
        append("main thread at the bound:\n")
        append(main.stack?.let { frames(it, MAX_MAIN_FRAMES) } ?: "    (not captured)")
        append('\n')
        append("main looper queue at the bound:\n")
        append(main.queueDump?.let { capQueueDump(it) } ?: "    (not captured)")
        main.note?.let { append('\n').append(it) }
        if (display.isNotEmpty()) append('\n').append(display)
    }

    fun describeBody(body: BodyOutcome, nowMillis: Long): String {
        val what = "the test body (@Before, test, @After)"
        return when (body) {
            BodyOutcome.Unrecorded ->
                "$what: outcome not recorded (the class does not register WindowGeometryRule.bodyProbe as its innermost rule)"
            BodyOutcome.NotStarted ->
                "$what had not started: an outer rule's set-up is blocked (Hilt injection, the Compose rule's activity launch)"
            is BodyOutcome.Running ->
                "$what was still running, for ${seconds(nowMillis - body.startedAtMillis)} s"
            is BodyOutcome.Passed ->
                "$what had passed ${seconds(nowMillis - body.endedAtMillis)} s before the bound: " +
                    "the hang is in an outer rule's teardown (the Compose rule's idle wait, the activity's close)"
            is BodyOutcome.Threw ->
                "$what had thrown ${seconds(nowMillis - body.endedAtMillis)} s before the bound " +
                    "(the hang is in an outer rule's teardown); it threw:\n" + describeError(body.error)
        }
    }

    /** The queue dump with each line cut to [MAX_LINE_CHARS] and at most [MAX_QUEUE_LINES] lines. */
    fun capQueueDump(dump: String): String {
        val lines = dump.lines().map { it.trimEnd() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return "    (empty)"
        val kept = lines.take(MAX_QUEUE_LINES).joinToString("\n") { "    " + it.trimStart().take(MAX_LINE_CHARS) }
        val dropped = lines.size - MAX_QUEUE_LINES
        return if (dropped > 0) "$kept\n    … $dropped more lines" else kept
    }

    private fun describeError(error: Throwable): String {
        val head = "    ${error.javaClass.name}: ${error.message?.lineSequence()?.firstOrNull().orEmpty()}"
        val stack = error.stackTrace.toList()
        return if (stack.isEmpty()) head else head + "\n" + frames(stack, MAX_ERROR_FRAMES, indent = "        ")
    }

    private fun frames(stack: List<StackTraceElement>, max: Int, indent: String = "    "): String {
        if (stack.isEmpty()) return "$indent(no frames)"
        val kept = stack.take(max).joinToString("\n") { "${indent}at $it" }
        val dropped = stack.size - max
        return if (dropped > 0) "$kept\n$indent… $dropped more frames" else kept
    }

    private fun seconds(millis: Long): String = String.format(Locale.ROOT, "%.1f", millis / 1000.0)
}
