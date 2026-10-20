package com.phototok.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Reads the module's `res/` directly (Gradle runs unit tests with the module as working directory). */
class BrandResourcesTest {

    private val res = File("res")

    private fun adaptiveIcon(name: String): String {
        val f = File(res, "mipmap-anydpi-v26/$name.xml")
        assertTrue("$f missing", f.isFile)
        return f.readText()
    }

    @Test
    fun `adaptive icons declare background, foreground and monochrome layers`() {
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val xml = adaptiveIcon(name)
            assertTrue("$name background", xml.contains("<background android:drawable=\"@drawable/ic_launcher_background\""))
            assertTrue("$name foreground", xml.contains("<foreground android:drawable=\"@drawable/ic_launcher_foreground\""))
            assertTrue("$name monochrome", xml.contains("<monochrome android:drawable=\"@drawable/ic_launcher_monochrome\""))
        }
    }

    @Test
    fun `icon layer drawables exist`() {
        for (n in listOf("ic_launcher_background", "ic_launcher_foreground", "ic_launcher_monochrome", "ic_brand_mark")) {
            assertTrue("$n.xml missing", File(res, "drawable/$n.xml").isFile)
        }
    }

    @Test
    fun `app name is Jackpix`() {
        val strings = File(res, "values/strings.xml").readText()
        val name = Regex("""<string name="app_name">([^<]*)</string>""").find(strings)?.groupValues?.get(1)
        assertEquals("Jackpix", name)
    }
}
