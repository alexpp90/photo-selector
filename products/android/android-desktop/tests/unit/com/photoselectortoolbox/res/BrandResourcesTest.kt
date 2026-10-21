package com.photoselectortoolbox.res

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reads the module's `res/` directly: the launcher identity must not drift unnoticed. */
class BrandResourcesTest {

    private fun res(path: String): File {
        val candidates = listOf(File("res/$path"), File("products/android/android-desktop/res/$path"))
        return candidates.firstOrNull { it.exists() } ?: error("res/$path not found from ${File(".").absolutePath}")
    }

    @Test
    fun `app name is Frameduel`() {
        val xml = res("values/strings.xml").readText()
        val name = Regex("""<string name="app_name">([^<]*)</string>""").find(xml)?.groupValues?.get(1)
        assertEquals("Frameduel", name)
    }

    @Test
    fun `adaptive icons declare background, foreground and monochrome layers`() {
        listOf("ic_launcher.xml", "ic_launcher_round.xml").forEach { file ->
            val xml = res("mipmap-anydpi-v26/$file").readText()
            listOf("background", "foreground", "monochrome").forEach { layer ->
                val ref = Regex("""<$layer\s+android:drawable="@drawable/([a-z_]+)"""").find(xml)?.groupValues?.get(1)
                assertTrue("$file lacks a $layer layer", ref != null)
                assertTrue("drawable $ref of $file is missing", res("drawable/$ref.xml").exists())
            }
        }
    }

    @Test
    fun `in-app brand mark exists`() {
        assertTrue(res("drawable/ic_brand_mark.xml").exists())
    }
}
