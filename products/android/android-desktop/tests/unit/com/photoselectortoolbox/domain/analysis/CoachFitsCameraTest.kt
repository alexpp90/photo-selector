package com.photoselectortoolbox.domain.analysis

import com.photoselector.core.Requirement
import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.domain.learn.CardGenerator
import com.photoselectortoolbox.domain.learn.TechniqueFact
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.photoselectortoolbox.testing.en

/**
 * The Photo Coach and Explore's cards give advice that fits the camera (shoot debrief SD-2b, C2):
 * a drone gets no hand-holding remark, a phone no aperture advice, an unknown camera no camera
 * control at all. Before SD-2b the recommendations were fixed text for every camera.
 */
@Requirement("REQ-AND-COACH.01")
class CoachFitsCameraTest {

    private val engine = PhotoCoachEngineImpl()

    /** Slow for the focal length and soft; noisy at a high ISO; highlights clipped. */
    private fun everything(make: String, model: String, f: Double, f35: Double, aperture: Double = 2.0) = ExifData(
        make = make, model = model, focalLength = f, focalLength35mm = f35, shutterSpeed = 1.0 / 8, aperture = aperture, iso = 6400,
    )
    private val soft = ScanResult("x", sharpnessScore = 15.0, noiseLevel = 12.0, highlightClipping = 8.0)

    private fun advised(exif: ExifData) = engine.evaluate(exif, soft).tips.joinToString("\n") { it.recommendation.en() }

    @Test
    fun `a drone gets no hand-holding remark`() {
        val tips = engine.evaluate(everything("DJI", "FC3582", 6.7, 24.0), soft).tips
        assertTrue(tips.none { it.category.en() == "Shutter Speed" || it.category.en() == "Stabilization" || "Hand-Holding" in it.title.en() })
    }

    @Test
    fun `a phone gets no aperture advice`() {
        val text = advised(everything("Google", "Pixel 8", 6.9, 25.0, aperture = 1.7))
        assertFalse(text, Regex("""(?i)aperture|stop down|\bISO\b|EV compensation""").containsMatchIn(text))
        assertTrue("a phone still hears about holding still", Regex("(?i)steadier|brace").containsMatchIn(text))
    }

    @Test
    fun `an interchangeable-lens camera keeps its control advice`() {
        val text = advised(everything("SONY", "ILCE-7M4", 200.0, 200.0, aperture = 8.0))
        assertTrue(text, text.contains("ISO") || text.contains("aperture"))
    }

    @Test
    fun `an Explore card for a phone's busy background does not say aperture`() {
        val phone = everything("Apple", "iPhone 15 Pro", 6.8, 24.0)
        val result = CardGenerator.generate(listOf(TechniqueFact("composition.simplicity", "busy", 0.9)), exif = phone)
        val tryNext = result.tryNextCard?.tryNextTime?.en().orEmpty()
        assertTrue(tryNext.isNotEmpty())
        assertFalse(tryNext, tryNext.contains("aperture", ignoreCase = true))
    }

    @Test
    fun `an unknown camera gets image-based remarks only`() {
        val tips = engine.evaluate(null, ScanResult("x", highlightClipping = 8.0, shadowClipping = 0.2)).tips
        val highlights = tips.single { it.title.en().contains("Blown Highlights") }
        assertFalse(highlights.recommendation.en(), Regex("(?i)EV|compensation|aperture|ISO").containsMatchIn(highlights.recommendation.en()))
        assertTrue(engine.evaluate(ExifData(aperture = 22.0), null).tips.none { it.category.en() == "Optics" })
    }
}
