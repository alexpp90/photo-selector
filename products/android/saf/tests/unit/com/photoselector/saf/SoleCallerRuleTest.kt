package com.photoselector.saf

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sole-caller rule: in the Android products, only :saf's `FolderAccessManager.kt` calls
 * `takePersistableUriPermission` / `releasePersistableUriPermission`. Every other grant goes
 * through [FolderAccessManager], so a purpose's reference is never lost and the per-app grant
 * limit is never exhausted by a forgotten grant.
 */
class SoleCallerRuleTest {

    private val calls = Regex("""\b(take|release)PersistableUriPermission\s*\(""")

    private fun androidRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "saf").isDirectory) return dir
            dir = dir.parentFile
        }
        error("products/android not found above ${File("").absolutePath}")
    }

    @Test
    fun `only the shared FolderAccessManager takes or releases persistable grants`() {
        val root = androidRoot()
        val offenders = root.listFiles()!!
            .map { File(it, "src") }
            .filter { it.isDirectory }
            .flatMap { src -> src.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") } }
            .filter { file -> calls.containsMatchIn(file.readText()) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()

        assertEquals(
            (listOf("saf/src/com/photoselector/saf/FolderAccessManager.kt") + notYetAdopted).sorted(),
            offenders,
        )
    }

    /**
     * The products' own copies, until each product adopts :saf and deletes its copy; the
     * adopting change removes its line here.
     */
    private val notYetAdopted = listOf(
        "phototok/src/com/phototok/data/repository/FolderAccessManager.kt",
    )

    @Test
    fun `the scan sees both products`() {
        val root = androidRoot()
        assertTrue(File(root, "android-desktop/src").isDirectory)
        assertTrue(File(root, "phototok/src").isDirectory)
    }
}
