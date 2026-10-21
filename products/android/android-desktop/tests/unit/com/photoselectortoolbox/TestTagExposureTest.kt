package com.photoselectortoolbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
