package com.photoselectortoolbox.ui.theme

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The theme re-provides `LocalDensity` as `Density(density, fontScale)` (for the TV display
 * scale, REQ-AND-WORKFLOW.05). The lead that this drops Android 14's non-linear font scaling
 * (large text growing less than small text) is wrong for the pinned Compose (ui-unit 1.12.1):
 * `Density(Float, Float)` is a `DensityImpl` whose `FontScaling.toDp` applies
 * `FontScaleConverterFactory` from font scale 1.03 up, the same table the platform's
 * `Density(Context)` uses. This test pins that, so a Compose upgrade that changes it fails here
 * rather than as large-font layouts that silently grew.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ThemeDensityTest {

    @get:Rule
    val rule = createComposeRule()

    /** The platform's density at [fontScale]: what `LocalDensity` is outside the theme. */
    private fun platformDensity(fontScale: Float): Density {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val configuration = Configuration(context.resources.configuration).apply { this.fontScale = fontScale }
        return Density(context.createConfigurationContext(configuration))
    }

    private fun themeDensity(platform: Density, tvDisplayScaleEnabled: Boolean = false): Density {
        lateinit var seen: Density
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides platform) {
                PhotoSelectorToolboxTheme(tvDisplayScaleEnabled = tvDisplayScaleEnabled) {
                    seen = LocalDensity.current
                }
            }
        }
        rule.waitForIdle()
        return seen
    }

    private val sizes = listOf(9, 10, 12, 13, 14, 16, 22, 28)

    @Test
    @Requirement("REQ-AND-THEME.01")
    fun `at font scale 2_0 the theme converts sp exactly as the platform does`() {
        val platform = platformDensity(2f)
        val theme = themeDensity(platform)
        for (size in sizes) {
            val expected = with(platform) { size.sp.toDp() }.value
            val actual = with(theme) { size.sp.toDp() }.value
            assertEquals("$size sp at font scale 2.0", expected, actual, 0.01f)
        }
    }

    @Test
    fun `at font scale 2_0 large text grows less than double, as on Android 14`() {
        val theme = themeDensity(platformDensity(2f))
        val small = with(theme) { 10.sp.toDp() }.value
        val large = with(theme) { 28.sp.toDp() }.value
        assertTrue("28 sp grew to $large dp, linear would be 56", large < 28f * 2f - 1f)
        assertTrue("10 sp ($small dp) grows proportionally more than 28 sp ($large dp)", small / 10f > large / 28f)
    }

    @Test
    fun `with the TV display scale the scaled font still follows the non-linear curve`() {
        val platform = platformDensity(1.3f)
        val theme = themeDensity(platform, tvDisplayScaleEnabled = true)
        val scale = computeEffectiveFontScale(1.3f, tvDisplayScaleEnabled = true)
        assertEquals(scale, theme.fontScale, 0.001f)
        val large = with(theme) { 28.sp.toDp() }.value
        assertTrue("28 sp at scale $scale grew to $large dp, linear would be ${28 * scale}", large < 28f * scale - 1f)
    }
}
