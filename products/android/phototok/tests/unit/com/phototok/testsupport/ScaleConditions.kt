package com.phototok.testsupport

import java.io.File

/**
 * The conditions contract `testdata/conditions/conditions.json` (rules: docs/shared/USAGE_CONDITIONS.md)
 * as the scale tests read it. Folder sizes and the growth rule come from the file, never from a
 * literal in a test. The path is the system property `phototok.conditions` that `build.gradle.kts`
 * sets (with `inputs.file`, so an edit of the contract re-runs the tests).
 *
 * Plain JVM: `org.json` is only a stub in a non-Robolectric unit test, so the few numbers are read
 * with anchored patterns instead of a JSON parser.
 */
object ScaleConditions {
    private val text: String by lazy {
        val path = System.getProperty("phototok.conditions")
            ?: error("system property phototok.conditions is not set (products/android/phototok/build.gradle.kts)")
        File(path).readText()
    }

    private fun number(pattern: String): Double =
        Regex(pattern).find(text)?.groupValues?.get(1)?.toDouble()
            ?: error("conditions.json: no match for $pattern")

    /** Photos of the size [name] (`typical`, `pr`, `stress`). */
    fun photos(name: String): Int =
        number("\"folder_sizes\"[\\s\\S]*?\"$name\"\\s*:\\s*\\{\\s*\"photos\"\\s*:\\s*(\\d+)").toInt()

    val prPhotos: Int get() = photos("pr")
    val growthFactor: Int get() = number("\"growth_check\"[\\s\\S]*?\"factor\"\\s*:\\s*(\\d+)").toInt()
    val maxRatio: Double get() = number("\"growth_check\"[\\s\\S]*?\"max_ratio\"\\s*:\\s*([\\d.]+)")

    /** N of the growth check: `pr / factor` (2,500). */
    val baseN: Int get() = prPhotos / growthFactor

    /** Best of [runs] timings of [block] in nanoseconds, after [warmUps] untimed runs. */
    fun bestNanos(runs: Int = 3, warmUps: Int = 2, block: () -> Unit): Long {
        repeat(warmUps) { block() }
        return (1..runs).minOf {
            val t0 = System.nanoTime()
            block()
            System.nanoTime() - t0
        }
    }
}
