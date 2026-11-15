package com.photoselectortoolbox.testing

import java.io.File
import org.junit.Assert.assertTrue

/**
 * The usage-conditions contract `testdata/conditions/conditions.json` (rules:
 * docs/shared/USAGE_CONDITIONS.md) as Frameduel's scale tests read it. Folder sizes and the
 * growth rule come from the file, never from a literal in a test. Its path is the system property
 * `frameduel.conditions`, set by `products/android/android-desktop/build.gradle.kts` (an input of
 * the test task, so an edit of the contract re-runs the tests).
 *
 * The few numbers are read with anchored patterns, not a JSON parser: `org.json` is a stub in a
 * plain JVM unit test, and the scale tests run both with and without Robolectric.
 */
object ScaleConditions {
    private val text: String by lazy {
        val path = System.getProperty("frameduel.conditions")
            ?: error("system property frameduel.conditions is not set (products/android/android-desktop/build.gradle.kts)")
        File(path).readText()
    }

    private fun number(pattern: String): Double =
        Regex(pattern).find(text)?.groupValues?.get(1)?.toDouble()
            ?: error("conditions.json: no match for $pattern")

    /** Photos of the folder size [name] (`typical`, `pr`, `stress`). */
    fun photos(name: String): Int =
        number("\"folder_sizes\"[\\s\\S]*?\"$name\"\\s*:\\s*\\{\\s*\"photos\"\\s*:\\s*(\\d+)").toInt()

    /** The folder size of every scale test in the PR gate (10,000). */
    val prPhotos: Int get() = photos("pr")
    val growthFactor: Int get() = number("\"growth_check\"[\\s\\S]*?\"factor\"\\s*:\\s*(\\d+)").toInt()
    val maxRatio: Double get() = number("\"growth_check\"[\\s\\S]*?\"max_ratio\"\\s*:\\s*([\\d.]+)")

    /** Files per folder of a camera card's DCF layout (9,999: JEITA CP-3461 caps a folder there). */
    val dcfFilesPerDir: Int
        get() = number("\"id\"\\s*:\\s*\"dcf-card\"[^}]*?\"files_per_dir\"\\s*:\\s*(\\d+)").toInt()

    /** N of the growth check: `pr / factor` (2,500); the check times N and `factor × N`. */
    val baseN: Int get() = prPhotos / growthFactor

    /** Timings below this count as this: a sub-millisecond run at N must not inflate the ratio. */
    private const val FLOOR_NANOS = 2_000_000L

    /** Runs before the timed ones (JIT, class loading). */
    private const val WARM_UPS = 2

    /** Timed runs per size; the best one counts. */
    private const val RUNS = 3

    /** Whole measurements before a ratio above the limit fails: a loaded machine once gave x8.4 for linear work. */
    private const val ATTEMPTS = 3

    /**
     * Best of [RUNS] timings, in nanoseconds, of the action [prepare] returns for [n] items, after
     * [WARM_UPS] untimed runs. [prepare] is not timed: it builds the fixture and returns the one
     * operation to time, and is called afresh for every run.
     */
    fun bestNanos(n: Int, prepare: (Int) -> () -> Unit): Long {
        repeat(WARM_UPS) { prepare(n)() }
        return (1..RUNS).minOf {
            val action = prepare(n)
            val t0 = System.nanoTime()
            action()
            System.nanoTime() - t0
        }
    }

    /**
     * The growth check of the contract: times the operation at [baseN] and at `factor × baseN`
     * and fails when the ratio exceeds [maxRatio] in every one of [ATTEMPTS] measurements.
     * Returns the ratio that passed (printed with [what] for the record).
     */
    fun assertGrowth(what: String, prepare: (Int) -> () -> Unit): Double {
        val n = baseN
        val big = n * growthFactor
        val ratios = mutableListOf<Double>()
        repeat(ATTEMPTS) {
            val small = bestNanos(n, prepare).coerceAtLeast(FLOOR_NANOS)
            val large = bestNanos(big, prepare).coerceAtLeast(FLOOR_NANOS)
            val ratio = large.toDouble() / small
            ratios += ratio
            println("Scale growth [$what]: N=$n ${small / 1_000_000.0} ms, ${growthFactor}N=$big ${large / 1_000_000.0} ms, ratio %.2f".format(java.util.Locale.ROOT, ratio))
            if (ratio <= maxRatio) return ratio
        }
        assertTrue(
            "$what grows faster than linear: time(${growthFactor}N)/time(N) = ${ratios.joinToString { "%.1f".format(java.util.Locale.ROOT, it) }} " +
                "> $maxRatio (N = $n, testdata/conditions/conditions.json)",
            false,
        )
        return ratios.last()
    }
}
