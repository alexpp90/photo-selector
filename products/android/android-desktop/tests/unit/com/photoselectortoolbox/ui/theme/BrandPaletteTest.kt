package com.photoselectortoolbox.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandPaletteTest {

    private fun contrast(a: Color, b: Color): Float {
        val l1 = maxOf(a.luminance(), b.luminance())
        val l2 = minOf(a.luminance(), b.luminance())
        return (l1 + 0.05f) / (l2 + 0.05f)
    }

    private fun Color.isNeutral(): Boolean = red == green && green == blue

    @Test
    fun `surface tokens are neutral grey`() {
        listOf(
            StudioCanvas, StudioPanel, StudioCard, StudioBorder, StudioBorderActive,
            Zinc50, Zinc100, Zinc200, Zinc300, Zinc400, Zinc500, Zinc600, Zinc700,
            Zinc800, Zinc900, Zinc950,
        ).forEach { assertTrue("$it is not neutral", it.isNeutral()) }
    }

    @Test
    fun `accent and text pairs meet 4_5 to 1`() {
        assertTrue(contrast(BrandAccent, BrandOnAccent) >= 4.5f)
        assertTrue(contrast(Zinc50, StudioCanvas) >= 4.5f)
        assertTrue(contrast(BrandAccent, StudioCanvas) >= 4.5f)
        assertTrue(contrast(Color.White, BrandAccentDeep) >= 4.5f)
    }

    @Test
    fun `accent is distinct from the semantic colours`() {
        listOf(CurationAmber, ScoreGood, ScoreWarn, ScoreBad, ErrorRed, SuccessGreen, WarningAmber)
            .forEach { assertNotEquals(BrandAccent, it) }
    }

    @Test
    fun `brand accent is the Frameduel lavender`() {
        assertEquals(Color(0xFFC4B8FF), BrandAccent)
        assertEquals(Color(0xFF2D2570), BrandOnAccent)
    }

    @Test
    fun `semantic colours keep their values`() {
        assertEquals(Color(0xFFF59E0B), CurationAmber)
        assertEquals(Color(0xFF34D399), ScoreGood)
        assertEquals(Color(0xFFFBBF24), ScoreWarn)
        assertEquals(Color(0xFFF87171), ScoreBad)
    }
}
