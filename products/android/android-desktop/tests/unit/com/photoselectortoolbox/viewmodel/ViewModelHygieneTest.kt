package com.photoselectortoolbox.viewmodel

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Three rules the view models keep, read from their sources because each is a property of every
 * line of the file and not of one behaviour (FD-Q-2, FD-UX-4):
 * - a catch-all around a suspending call rethrows cancellation first;
 * - no literal `Dispatchers.IO` at a call: the dispatcher is the injected `ioDispatcher`;
 * - no message shown to the photographer carries an exception's own text.
 */
class ViewModelHygieneTest {

    private fun sources() = File("src/com/photoselectortoolbox/viewmodel").listFiles { f -> f.extension == "kt" }!!.toList()

    private fun lineOf(source: String, index: Int) = source.substring(0, index).count { it == '\n' } + 1

    @Test
    fun `every catch-all rethrows cancellation first`() {
        val bad = mutableListOf<String>()
        sources().forEach { file ->
            val source = file.readText()
            Regex("""\}\s*catch \((\w+|_): Exception\)\s*\{""").findAll(source).forEach { m ->
                val before = source.substring(maxOf(0, m.range.first - 300), m.range.first)
                if ("CancellationException" !in before) bad += "${file.name}:${lineOf(source, m.range.first)}"
            }
        }
        assertTrue("catch (Exception) without a CancellationException rethrow before it: $bad", bad.isEmpty())
    }

    @Test
    fun `no call site names Dispatchers IO`() {
        val bad = mutableListOf<String>()
        sources().forEach { file ->
            file.readLines().forEachIndexed { i, line ->
                if ("Dispatchers.IO" in line && !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") &&
                    "ioDispatcher" !in line && "CoroutineDispatcher = Dispatchers.IO" !in line
                ) bad += "${file.name}:${i + 1}"
            }
        }
        assertTrue("literal Dispatchers.IO: $bad", bad.isEmpty())
    }

    @Test
    fun `no message carries an exception's text`() {
        val bad = mutableListOf<String>()
        sources().forEach { file ->
            file.readLines().forEachIndexed { i, line ->
                if (Regex("""\$\{e\.message\}|\$\{it\.message\}""").containsMatchIn(line)) bad += "${file.name}:${i + 1}"
            }
        }
        assertTrue("exception text in a string: $bad", bad.isEmpty())
    }
}
