package com.photoselectortoolbox.conformance

import android.os.Looper
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.robolectric.Shadows

/**
 * A suspending call of the conformance test, run off the Robolectric main thread with a bounded,
 * cancellable wait (backlog 2026-11-11: one `:android-desktop:testDebugUnitTest` run sat for 18 h in
 * `runBlocking { loader.execute(…) }` on the "SDK 34 Main Thread").
 *
 * The call runs on a thread of its own, so the test thread only waits on `await`, which a timeout
 * cancels even when the call itself never returns. A call that times out fails with [what] (the
 * sample id) and with what the hang looked like — whether a message was waiting on the paused main
 * looper (a continuation posted to Main, which the blocked test thread can never run), and the
 * stack of the thread the call ran on — so the next hang names its cause instead of only its place.
 */
internal object BoundedCall {

    /** Longest a sample's display decode may take; one takes well under a second here. */
    const val DISPLAY_TIMEOUT_MS = 60_000L

    fun <T> run(what: String, timeoutMs: Long, block: suspend () -> T): T {
        val threadName = "conformance: $what"
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, threadName).apply { isDaemon = true } }
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        try {
            val work = scope.async { block() }
            val result = runBlocking { withTimeoutOrNull(timeoutMs) { Finished(work.await()) } }
            if (result != null) return result.value
            throw AssertionError("$what did not finish within ${timeoutMs / 1000.0} s (hung). ${diagnosis(threadName)}")
        } finally {
            scope.cancel()
            executor.shutdownNow()
        }
    }

    private class Finished<T>(val value: T)

    private fun diagnosis(threadName: String): String {
        val mainLooperIdle = try {
            Shadows.shadowOf(Looper.getMainLooper()).isIdle
        } catch (e: Exception) {
            null
        }
        val stack = Thread.getAllStackTraces().entries.firstOrNull { it.key.name == threadName }?.value
            ?.take(12)?.joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
        return "Main looper idle: $mainLooperIdle (false: a task is waiting for the blocked main thread). " +
            "Call thread: ${stack ?: "not running (the call is suspended, waiting to be resumed)"}"
    }
}
