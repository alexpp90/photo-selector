package com.photoselectortoolbox.ui

import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference

/**
 * A hardware key through the window, like `Instrumentation.sendKeyDownUpSync`, that cannot block
 * the test for ever.
 *
 * `sendKeyDownUpSync` returns when the focused window (or the IME in front of it) has finished the
 * event. A window that never takes focus, or a soft keyboard that never answers, leaves it waiting
 * in the input dispatcher, and the instrumentation process produced no output for ten minutes until
 * the matrix killed it (Moto G77, 2026-11-05, `goToPhoto_keyG…`: the one test that types through a
 * real IME). Here the call runs on a worker thread; when it does not return within [timeoutMillis]
 * the test fails with the key, the worker's stack and a note to look at the IME, and the rule's
 * `finally` restores the display.
 */
internal object BoundedKeys {
    private const val DEFAULT_TIMEOUT_MILLIS = 10_000L

    fun send(keyCode: Int, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS) {
        val outcome = AtomicReference<Throwable?>(null)
        val worker = Thread({
            try {
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
            } catch (t: Throwable) {
                outcome.set(t)
            }
        }, "frameduel-key-${KeyEvent.keyCodeToString(keyCode)}")
        worker.isDaemon = true
        worker.start()
        worker.join(timeoutMillis)
        if (worker.isAlive) {
            val stack = worker.stackTrace.joinToString("\n") { "    at $it" }
            worker.interrupt()
            throw AssertionError(
                "${KeyEvent.keyCodeToString(keyCode)} was injected but the window system did not finish it " +
                    "within ${timeoutMillis / 1000} s: no window in focus, or an input method that does not answer " +
                    "(try `adb shell dumpsys input_method` and `dumpsys window | grep mCurrentFocus`). " +
                    "The injecting thread was blocked at:\n$stack",
            )
        }
        outcome.get()?.let { throw it }
    }
}
