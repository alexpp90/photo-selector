package com.phototok.ui.screenshots

import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.phototok.ui.theme.PhotoTokTheme
import java.io.File
import java.util.Locale
import org.junit.Assert.assertTrue

/**
 * One cell of the text-load matrix (REQ-TOK-UX.05): a language, a theme and a font scale.
 *
 * PhotoTok has one theme, [PhotoTokTheme], which is dark whatever the device setting is. There is
 * no light theme to render, so the theme axis is the device's night mode: `dark` (device dark) and
 * `dark-on-light-device` (device light, app still dark) — the second run proves the app does not
 * follow the device into a theme it does not have.
 */
data class Variant(val locale: Locale, val deviceNight: Boolean, val fontScale: Float) {
    val language: String get() = locale.language
    val theme: String get() = if (deviceNight) "dark" else "dark-on-light-device"
    fun fileName(surface: String): String = "${surface}_${language}_${theme}_fs${fontScale}.png"
    override fun toString(): String = "$language/$theme/fs$fontScale"
}

val VARIANTS: List<Variant> = buildList {
    for (locale in listOf(Locale.ENGLISH, Locale.GERMAN)) {
        for (night in listOf(true, false)) {
            for (scale in listOf(1.0f, 2.0f)) add(Variant(locale, night, scale))
        }
    }
}

private const val MIN_TARGET_DP = 48f
private val SCREENSHOT_DIR = File("build/outputs/screenshots")

/**
 * Hosts [content] in the PhotoTok theme with the language, night mode and font scale of the
 * variant [current] provides. Changing [current] re-composes the content from scratch, so one
 * test can walk the whole matrix without recreating the activity.
 */
class MatrixHost(private val rule: ComposeContentTestRule, first: Variant) {
    var current by mutableStateOf(first)

    fun setContent(content: @Composable () -> Unit) {
        rule.setContent {
            val variant = current
            key(variant) {
                val base = LocalContext.current
                val config = remember(variant) {
                    Configuration(base.resources.configuration).apply {
                        setLocale(variant.locale)
                        fontScale = variant.fontScale
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            (if (variant.deviceNight) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                    }
                }
                // A wrapper around the activity (not a separate configuration context), so that
                // anything that unwraps the context to find the activity still finds it.
                val context = remember(variant) {
                    val localised = base.createConfigurationContext(config)
                    object : ContextWrapper(base) {
                        override fun getResources() = localised.resources
                    }
                }
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalContext provides context,
                    LocalConfiguration provides config,
                    LocalDensity provides Density(density.density, variant.fontScale),
                ) {
                    PhotoTokTheme {
                        Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
                    }
                }
            }
        }
    }

    fun show(variant: Variant) {
        current = variant
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
    }
}

/** Every semantics node of every window (the activity and any dialog), unmerged. */
fun ComposeContentTestRule.allNodes(): List<SemanticsNode> =
    onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()

/** The merged nodes that react to a click. */
fun ComposeContentTestRule.clickableNodes(): List<SemanticsNode> =
    onAllNodes(SemanticsMatcher("clickable") { it.config.contains(SemanticsActions.OnClick) })
        .fetchSemanticsNodes()

fun SemanticsNode.visibleText(): String? =
    config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.takeIf { it.isNotBlank() }

private fun SemanticsNode.description(): String? =
    config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.takeIf { it.isNotBlank() }

/** A one-line description for assertion messages. */
fun SemanticsNode.label(): String = visibleText() ?: description() ?: "node#$id"

/**
 * Writes the current screen to `build/outputs/screenshots/<surface>_<locale>_<theme>_fs<scale>.png`
 * (the build directory; never committed). Dialog windows are not drawn by Robolectric and are skipped.
 */
fun ComposeContentTestRule.saveScreenshot(surface: String, variant: Variant) {
    SCREENSHOT_DIR.mkdirs()
    val roots = onAllNodes(isRoot())
    val count = roots.fetchSemanticsNodes().size
    // Robolectric does not draw a dialog's own window: the capture would be a black rectangle, so a
    // dialog is verified through its semantics only (the checks still run on its nodes).
    if (count > 1) return
    val image = roots[count - 1].captureToImage().asAndroidBitmap()
    File(SCREENSHOT_DIR, variant.fileName(surface)).outputStream().use {
        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
    }
}

/** What every surface must satisfy in every variant. */
class TextLoadChecks(private val rule: ComposeContentTestRule, private val screen: Rect, private val density: Float) {

    /** Every clickable control has a 48 dp target (its touch bounds, which include Material's minimum). */
    fun assertTouchTargets(variant: Variant, surface: String) {
        val minPx = MIN_TARGET_DP * density - 1f
        rule.clickableNodes().forEach { node ->
            val b = node.touchBoundsInRoot
            assertTrue(
                "$surface [$variant]: target of '${node.label()}' is ${b.width / density} x ${b.height / density} dp, needs $MIN_TARGET_DP",
                b.width >= minPx && b.height >= minPx,
            )
        }
    }

    /** No text is truncated or ellipsized by its own layout, in any window. */
    fun assertNoTextOverflow(variant: Variant, surface: String) {
        assertNoWordBroken(variant, surface)
        rule.allNodes().forEach { node ->
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return@forEach
            val results = ArrayList<TextLayoutResult>()
            if (action(results) && results.isNotEmpty()) {
                val r = results.first()
                val maxWidth = r.layoutInput.constraints.maxWidth
                val lines = 0 until r.lineCount
                // `hasVisualOverflow` is not used: Robolectric reports it for text that fits. A line is
                // clipped when it is ellipsized or wider than the space it was given; the text is cut
                // when it has more lines than its maxLines allows or is taller than its space.
                val ellipsized = lines.any { r.isLineEllipsized(it) }
                val tooWide = lines.any { r.getLineRight(it) - r.getLineLeft(it) > maxWidth + 1f }
                assertTrue(
                    "$surface [$variant]: text '${node.label()}' is clipped or ellipsized " +
                        "(size=${r.size} maxWidth=$maxWidth lines=${r.lineCount})",
                    !ellipsized && !tooWide && !r.didOverflowHeight,
                )
            }
        }
    }

    /** A single word (a German compound, "Einstellungen") never wraps: a break inside it is a defect. */
    fun assertNoWordBroken(variant: Variant, surface: String) {
        rule.allNodes().forEach { node ->
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return@forEach
            val results = ArrayList<TextLayoutResult>()
            val text = node.visibleText()
            if (text != null && text.none { it.isWhitespace() } && action(results) && results.isNotEmpty()) {
                assertTrue(
                    "$surface [$variant]: the word '$text' is broken over ${results.first().lineCount} lines",
                    results.first().lineCount == 1,
                )
            }
        }
    }

    /**
     * Nothing that carries text or a click leaves the screen sideways; and, for surfaces that do not
     * scroll, nothing leaves it vertically either.
     */
    fun assertInsideScreen(variant: Variant, surface: String, vertical: Boolean = true) {
        val tolerance = 1f
        rule.allNodes().filter { it.visibleText() != null || it.config.contains(SemanticsActions.OnClick) }
            .forEach { node ->
                val b = node.boundsInWindow
                assertTrue(
                    "$surface [$variant]: '${node.label()}' leaves the screen horizontally ($b in $screen)",
                    b.left >= screen.left - tolerance && b.right <= screen.right + tolerance,
                )
                if (vertical) {
                    assertTrue(
                        "$surface [$variant]: '${node.label()}' leaves the screen vertically ($b in $screen)",
                        b.top >= screen.top - tolerance && b.bottom <= screen.bottom + tolerance,
                    )
                }
            }
    }

    /** Icon-only bars: nothing in the tree is visible text, and every control is named for TalkBack. */
    fun assertIconOnly(variant: Variant, surface: String) {
        val texts = rule.allNodes().mapNotNull { it.visibleText() }
        assertTrue("$surface [$variant]: bar shows visible text $texts", texts.isEmpty())
        rule.clickableNodes().forEach { node ->
            assertTrue(
                "$surface [$variant]: control without a spoken name (node ${node.id})",
                node.description() != null,
            )
        }
    }

    fun assertHasClickableControls(surface: String, minimum: Int) {
        val count = rule.clickableNodes().size
        assertTrue("$surface: expected at least $minimum controls, found $count", count >= minimum)
    }
}
