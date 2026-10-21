package com.phototok.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeColorsTest {

    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble()
        val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun assertNeutral(name: String, c: Color) {
        val r = (c.red * 255).toInt()
        val g = (c.green * 255).toInt()
        val b = (c.blue * 255).toInt()
        assertTrue("$name must be neutral grey, was #%02X%02X%02X".format(r, g, b), r == g && g == b)
    }

    @Test
    fun `surfaces and outlines are neutral grey`() {
        mapOf(
            "surface" to PtSurface, "surfaceDim" to PtSurfaceDim, "surfaceBright" to PtSurfaceBright,
            "containerLowest" to PtSurfaceContainerLowest, "containerLow" to PtSurfaceContainerLow,
            "container" to PtSurfaceContainer, "containerHigh" to PtSurfaceContainerHigh,
            "containerHighest" to PtSurfaceContainerHighest, "surfaceVariant" to PtSurfaceVariant,
            "onSurface" to PtOnSurface, "onSurfaceVariant" to PtOnSurfaceVariant,
            "background" to PtBackground, "onBackground" to PtOnBackground,
            "outline" to PtOutline, "outlineVariant" to PtOutlineVariant,
            "secondary" to PtSecondary, "tertiary" to PtTertiary,
        ).forEach { (n, c) -> assertNeutral(n, c) }
    }

    @Test
    fun `primary is the lime of the icon`() {
        assertEquals(Color(0xFFB8EB4F), PtPrimary)
        assertEquals(Color(0xFF1E2A10), PtOnPrimary)
    }

    @Test
    fun `text pairs meet 4_5 to 1`() {
        val pairs = mapOf(
            "onPrimary/primary" to (PtOnPrimary to PtPrimary),
            "primary/surface" to (PtPrimary to PtSurface),
            "onSurface/surface" to (PtOnSurface to PtSurface),
            "onSurfaceVariant/surface" to (PtOnSurfaceVariant to PtSurface),
            "onPrimaryContainer/primaryContainer" to (PtOnPrimaryContainer to PtPrimaryContainer),
            "onPrimaryFixed/primaryFixed" to (PtOnPrimaryFixed to PtPrimaryFixed),
            "onSecondary/secondary" to (PtOnSecondary to PtSecondary),
            "onSecondaryContainer/secondaryContainer" to (PtOnSecondaryContainer to PtSecondaryContainer),
            "onTertiary/tertiary" to (PtOnTertiary to PtTertiary),
            "onTertiaryContainer/tertiaryContainer" to (PtOnTertiaryContainer to PtTertiaryContainer),
            "inverseOnSurface/inverseSurface" to (PtInverseOnSurface to PtInverseSurface),
            "onError/error" to (PtOnError to PtError),
            "onErrorContainer/errorContainer" to (PtOnErrorContainer to PtErrorContainer),
        )
        pairs.forEach { (n, p) ->
            val ratio = contrast(p.first, p.second)
            assertTrue("$n contrast %.2f < 4.5".format(ratio), ratio >= 4.5)
        }
    }

    @Test
    fun `semantic colours keep their values`() {
        assertEquals(Color(0xFF22C55E), SuccessGreen)
        assertEquals(Color(0xFFF59E0B), WarningAmber)
    }
}
