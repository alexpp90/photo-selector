package com.photoselectortoolbox.ui.selector

import com.photoselector.core.Requirement
import org.junit.Assert.assertEquals
import org.junit.Test

/** AD-16: the exposure line breaks only between tokens, never inside one. */
class ExposureLineWrapTest {

    private val line = "1/250s  ·  f/2.8  ·  50mm  ·  ISO 400"

    @Test
    @Requirement("REQ-AND-LAYOUT.13")
    fun `every token but the last carries its separator and the last does not`() {
        assertEquals(
            listOf("1/250s ·", "f/2.8 ·", "50mm ·", "ISO 400"),
            exposureTokenLabels(line),
        )
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.13")
    fun `a token with an inner space stays one token`() {
        assertEquals("ISO 400", exposureTokenLabels(line).last())
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.13")
    fun `a single token has no separator and an empty line has no tokens`() {
        assertEquals(listOf("50mm"), exposureTokenLabels("50mm"))
        assertEquals(emptyList<String>(), exposureTokenLabels(""))
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.13")
    fun `the slash of an aperture or shutter token is never a token boundary`() {
        val labels = exposureTokenLabels(line)
        assert(labels.any { it.startsWith("f/2.8") })
        assert(labels.any { it.startsWith("1/250s") })
    }
}
