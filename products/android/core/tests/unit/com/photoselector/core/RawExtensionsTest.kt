package com.photoselector.core

import com.photoselector.core.conformance.FormatManifest
import com.photoselector.core.format.RawExtensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The one RAW extension table both Android apps use (improvement plan D-6). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RawExtensionsTest {

    @Test
    fun `the table covers every RAW extension of the supported-format manifest`() {
        val manifest = FormatManifest.formats.filter { it.kind == "raw" }.flatMap { it.extensions }
        assertTrue(manifest.isNotEmpty())
        assertEquals("manifest RAW extensions missing from RawExtensions", emptyList<String>(), manifest - RawExtensions.ALL)
    }

    @Test
    fun `extensions are lower case without a dot, and never name a rendered format`() {
        for (ext in RawExtensions.ALL) {
            assertEquals(ext, ext.lowercase().trim('.'))
        }
        val bitmap = FormatManifest.formats.filter { it.kind != "raw" }.flatMap { it.extensions }
        assertEquals(emptyList<String>(), RawExtensions.ALL.intersect(bitmap.toSet()).toList())
    }

    @Test
    fun `isRaw matches a file name case-insensitively`() {
        assertTrue(RawExtensions.isRaw("IMG_0001.CR3"))
        assertTrue(RawExtensions.isRaw("a.b.NeF"))
        assertEquals(false, RawExtensions.isRaw("IMG_0001.jpg"))
        assertEquals(false, RawExtensions.isRaw("cr3"))
    }
}
