package com.photoselectortoolbox.ui.theme

import com.photoselector.core.Requirement
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Text on the dark surfaces stays at WCAG AA (4.5:1). Pairs are the text tokens the UI uses
 * against every surface it draws them on; the source scan keeps the low-contrast greys (Zinc500
 * and below, 2.1 to 3.4:1 on the card) out of `Text` calls, which is where they sat before.
 */
class ContrastTokensTest {

    private fun contrast(a: Color, b: Color): Double {
        val l1 = maxOf(a.luminance(), b.luminance()).toDouble()
        val l2 = minOf(a.luminance(), b.luminance()).toDouble()
        return (l1 + 0.05) / (l2 + 0.05)
    }

    /** Every surface text is drawn on: canvas, panel, control card, and the Zinc800/Zinc900 cards. */
    private val surfaces = mapOf(
        "StudioCanvas" to StudioCanvas,
        "StudioPanel" to StudioPanel,
        "StudioCard" to StudioCard,
        "Zinc800" to Zinc800,
        "Zinc900" to Zinc900,
    )

    private val texts = mapOf(
        "HintText" to HintText,
        "Zinc400" to Zinc400,
        "Zinc50" to Zinc50,
        "DestructiveText" to DestructiveText,
        "BrandAccent" to BrandAccent,
        "BrandAccentText" to BrandAccentText,
        "ScoreGood" to ScoreGood,
        "ScoreWarn" to ScoreWarn,
        "ScoreBad" to ScoreBad,
    )

    @Test
    @Requirement("REQ-AND-THEME.02")
    fun `every text token reads at 4_5 to 1 on every surface`() {
        val failures = texts.flatMap { (textName, text) ->
            surfaces.mapNotNull { (surfaceName, surface) ->
                val ratio = contrast(text, surface)
                "$textName on $surfaceName = ${"%.2f".format(ratio)}".takeIf { ratio < 4.5 }
            }
        }
        assertTrue("below 4.5:1: $failures", failures.isEmpty())
    }

    @Test
    fun `a destructive button reads at 4_5 to 1`() {
        assertTrue(contrast(OnDestructiveFill, DestructiveFill) >= 4.5)
    }

    @Test
    fun `the colour scheme pairs its content colours at 4_5 to 1`() {
        val s = DarkColorScheme
        mapOf(
            "onPrimary/primary" to (s.onPrimary to s.primary),
            "onError/error" to (s.onError to s.error),
            "onSurface/surface" to (s.onSurface to s.surface),
            "onSurfaceVariant/surfaceVariant" to (s.onSurfaceVariant to s.surfaceVariant),
            "onSurfaceVariant/surfaceContainer" to (s.onSurfaceVariant to s.surfaceContainer),
            "error/surfaceContainer" to (s.error to s.surfaceContainer),
            "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
        ).forEach { (name, pair) ->
            val ratio = contrast(pair.first, pair.second)
            assertTrue("$name = ${"%.2f".format(ratio)}", ratio >= 4.5)
        }
    }

    @Test
    fun `the old hint greys really were below the floor`() {
        // Why the tokens above are what they are: these were the colours in use.
        assertEquals(2.13, contrast(Zinc600, StudioCard), 0.05)
        assertEquals(3.4, contrast(Zinc500, StudioCard), 0.1)
        assertEquals(3.76, contrast(Color.White, ErrorRed), 0.05)
    }

    @Test
    fun `no Text call draws its string in a grey below the floor`() {
        val low = Regex("""color\s*=\s*Zinc[567]00\b""")
        val offenders = mutableListOf<String>()
        File("src/com/photoselectortoolbox/ui").walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val source = file.readText()
            Regex("""\bText\(""").findAll(source).forEach { start ->
                var depth = 0
                var i = start.range.last
                while (i < source.length) {
                    when (source[i]) {
                        '(' -> depth++
                        ')' -> { depth--; if (depth == 0) break }
                    }
                    i++
                }
                val call = source.substring(start.range.first, minOf(i + 1, source.length))
                if (low.containsMatchIn(call)) {
                    val line = source.substring(0, start.range.first).count { it == '\n' } + 1
                    offenders += "${file.name}:$line"
                }
            }
        }
        assertTrue("Text drawn in Zinc500/600/700 (use HintText): $offenders", offenders.isEmpty())
    }
}
