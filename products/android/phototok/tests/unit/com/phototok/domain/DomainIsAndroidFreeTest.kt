package com.phototok.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agent rule 3 and REQ-TOK-ARCH.06: `com.phototok.domain` is pure Kotlin that runs on the JVM.
 * Platform classes (`android.*`) belong in `data/`; resource ids and `androidx.annotation` markers
 * are allowed (they are plain constants and annotations).
 */
class DomainIsAndroidFreeTest {

    @Test
    fun `no file of the domain package imports a platform class`() {
        val dir = File("src/com/phototok/domain")
        assertTrue("run from the :phototok module directory: ${dir.absolutePath}", dir.isDirectory)

        val offenders = dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().filter { it.startsWith("import android.") }.map { "${file.name}: $it" }
            }
            .toList()

        assertEquals("Android classes in the domain package: $offenders", emptyList<String>(), offenders)
    }
}
