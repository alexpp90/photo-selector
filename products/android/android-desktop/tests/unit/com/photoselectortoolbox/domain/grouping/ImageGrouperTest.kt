package com.photoselectortoolbox.domain.grouping

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for ImageGrouper — focuses on the extractNamePrefix helper
 * which is pure string logic and the main grouping entry point.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImageGrouperTest {

    private val context: Context = mockk(relaxed = true)
    private val grouper = ImageGrouper(context, Dispatchers.IO)

    // --- extractNamePrefix tests ---

    @Test
    fun `extractNamePrefix strips trailing digits and extension`() {
        assertEquals("IMG_", grouper.extractNamePrefix("IMG_0001.jpg"))
    }

    @Test
    fun `extractNamePrefix handles DSC prefix`() {
        assertEquals("DSC", grouper.extractNamePrefix("DSC01234.ARW"))
    }

    @Test
    fun `extractNamePrefix handles no trailing digits`() {
        assertEquals("photo", grouper.extractNamePrefix("photo.jpg"))
    }

    @Test
    fun `extractNamePrefix handles all digits`() {
        assertEquals("", grouper.extractNamePrefix("12345.jpg"))
    }

    @Test
    fun `extractNamePrefix handles underscores with digits`() {
        assertEquals("IMG_", grouper.extractNamePrefix("IMG_20230101_123456.jpg"))
    }

    @Test
    fun `extractNamePrefix handles no extension`() {
        assertEquals("IMG_", grouper.extractNamePrefix("IMG_001"))
    }

    @Test
    fun `extractNamePrefix handles multiple dots`() {
        assertEquals("photo.raw.", grouper.extractNamePrefix("photo.raw.001.jpg"))
    }

    @Test
    fun `extractNamePrefix handles Samsung style`() {
        assertEquals("_", grouper.extractNamePrefix("_20240101_123456.jpg"))
    }

    @Test
    fun `extractNamePrefix handles iPhone HEIC`() {
        assertEquals("IMG_", grouper.extractNamePrefix("IMG_4521.HEIC"))
    }
}
