package com.photoselectortoolbox.ui.selector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** AD-16: the exposure line may only break after a separator, never inside a token. */
class ExposureLineWrapTest {

    private val line = "1/250s  ·  f/2.8  ·  50mm  ·  ISO 400"

    @Test
    fun `the only ordinary spaces are the ones after a separator`() {
        val kept = keepExposureTokensWhole(line)
        val breakable = kept.indices.filter { kept[it] == ' ' }
        assertEquals(3, breakable.size)
        breakable.forEach { assertEquals('·', kept[it - 2]) }
    }

    @Test
    fun `a token with an inner space stays one token`() {
        val kept = keepExposureTokensWhole(line)
        assert(kept.endsWith("ISO 400")) { kept }
        assertFalse(kept.startsWith(" "))
    }

    @Test
    fun `it reads the same as the original once no-break spaces are shown as spaces`() {
        assertEquals(line, keepExposureTokensWhole(line).replace(' ', ' '))
    }
}
