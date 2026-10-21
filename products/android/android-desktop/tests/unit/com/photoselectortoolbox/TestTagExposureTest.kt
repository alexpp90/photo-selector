package com.photoselectortoolbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TestTagExposureTest {

    @Test
    fun `release builds do not expose test tags as resource ids`() {
        assertFalse(TestTagExposure.enabledFor("release"))
    }

    @Test
    fun `debug and lab builds expose them for uiautomator`() {
        assertTrue(TestTagExposure.enabledFor("debug"))
        assertTrue(TestTagExposure.enabledFor("lab"))
    }

    @Test
    fun `the build's own flag follows its build type`() {
        assertEquals(TestTagExposure.enabledFor(BuildConfig.BUILD_TYPE), BuildConfig.EXPOSE_TEST_TAGS)
    }

    @Test
    fun `the shipped wiring reads the flag and release sets it off`() {
        fun source(path: String): String =
            listOf(File(path), File("products/android/android-desktop/$path"))
                .firstOrNull { it.exists() }?.readText() ?: error("$path not found from ${File(".").absolutePath}")
        val activity = source("src/com/photoselectortoolbox/MainActivity.kt")
        assertTrue(activity.contains("testTagsAsResourceId = BuildConfig.EXPOSE_TEST_TAGS"))
        assertFalse(activity.contains("testTagsAsResourceId = true"))
        val release = source("build.gradle.kts").substringAfter("release {").substringBefore("}")
        assertTrue(release.contains("buildConfigField(\"boolean\", \"EXPOSE_TEST_TAGS\", \"false\")"))
    }
}
