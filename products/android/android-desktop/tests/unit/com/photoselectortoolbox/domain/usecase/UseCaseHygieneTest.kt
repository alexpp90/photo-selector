package com.photoselectortoolbox.domain.usecase

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The use cases keep the two coroutine rules the view models keep (`ViewModelHygieneTest`),
 * read from their sources (backlog 2026-10-22, FD-Q-12):
 * - a catch-all rethrows cancellation first (invariant "cancellation is not an error");
 * - no literal `Dispatchers.*`: the dispatchers are injected (`di/CoroutineModule`).
 */
class UseCaseHygieneTest {

    private fun sources() =
        File("src/com/photoselectortoolbox/domain/usecase").listFiles { f -> f.extension == "kt" }!!.toList()

    private fun lineOf(source: String, index: Int) = source.substring(0, index).count { it == '\n' } + 1

    @Test
    fun `there are use cases to check`() {
        assertTrue(sources().any { it.name == "ScanImagesUseCase.kt" })
    }

    @Test
    fun `every catch-all rethrows cancellation first`() {
        val bad = mutableListOf<String>()
        sources().forEach { file ->
            val source = file.readText()
            Regex("""\}\s*catch \((\w+|_): (Exception|Throwable)\)\s*\{""").findAll(source).forEach { m ->
                val before = source.substring(maxOf(0, m.range.first - 300), m.range.first)
                if ("CancellationException" !in before) bad += "${file.name}:${lineOf(source, m.range.first)}"
            }
        }
        assertTrue("catch-all without a CancellationException rethrow before it: $bad", bad.isEmpty())
    }

    @Test
    fun `no use case names a literal dispatcher`() {
        val bad = mutableListOf<String>()
        sources().forEach { file ->
            file.readLines().forEachIndexed { i, line ->
                val code = line.trimStart()
                if (Regex("""\bDispatchers\.(IO|Default|Main|Unconfined)\b""").containsMatchIn(line) &&
                    !code.startsWith("//") && !code.startsWith("*")
                ) bad += "${file.name}:${i + 1}"
            }
        }
        assertTrue("literal dispatcher: $bad", bad.isEmpty())
    }
}
