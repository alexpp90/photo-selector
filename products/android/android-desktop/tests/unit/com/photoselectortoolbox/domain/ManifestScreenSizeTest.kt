package com.photoselectortoolbox.domain

import android.content.pm.ApplicationInfo
import com.photoselector.core.Requirement
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Element

/**
 * Frameduel is for large screens (`[USER 2026-11-12]` decision #5, REQ-AND-PLATFORM.02): the
 * merged manifest — the one a build ships, dependencies merged in — declares
 * `<supports-screens>` with small and normal screens off and large and xlarge on, so Google Play
 * filters phones out by screen size. Read from the merged manifest the build hands Robolectric
 * (`android_merged_manifest`), and from the package the system parsed out of it.
 */
@Requirement("REQ-AND-PLATFORM.02")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManifestScreenSizeTest {

    private val android = "http://schemas.android.com/apk/res/android"

    private fun mergedManifest(): File {
        val config = javaClass.classLoader!!.getResourceAsStream("com/android/tools/test_config.properties")
        assertNotNull("AGP's test config is on the classpath", config)
        val path = Properties().apply { config!!.use { load(it) } }.getProperty("android_merged_manifest")
        return File(path).let { if (it.isAbsolute || it.exists()) it else File(System.getProperty("user.dir"), path) }
    }

    @Test
    fun `the merged manifest admits large and xlarge screens only`() {
        val file = mergedManifest()
        assertTrue("merged manifest at $file", file.exists())
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("supports-screens")
        assertEquals("exactly one <supports-screens>", 1, nodes.length)
        val element = nodes.item(0) as Element
        mapOf(
            "smallScreens" to "false",
            "normalScreens" to "false",
            "largeScreens" to "true",
            "xlargeScreens" to "true",
        ).forEach { (attribute, value) ->
            assertEquals(attribute, value, element.getAttributeNS(android, attribute))
        }
    }

    @Test
    fun `the parsed package says the same`() {
        val flags = RuntimeEnvironment.getApplication().applicationInfo.flags
        assertEquals("small", 0, flags and ApplicationInfo.FLAG_SUPPORTS_SMALL_SCREENS)
        assertEquals("normal", 0, flags and ApplicationInfo.FLAG_SUPPORTS_NORMAL_SCREENS)
        assertTrue("large", flags and ApplicationInfo.FLAG_SUPPORTS_LARGE_SCREENS != 0)
        assertTrue("xlarge", flags and ApplicationInfo.FLAG_SUPPORTS_XLARGE_SCREENS != 0)
    }
}
