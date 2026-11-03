package com.photoselectortoolbox.ui.inspect

import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.domain.learn.CropSuggester
import com.photoselectortoolbox.domain.learn.DetectedSubject
import com.photoselectortoolbox.domain.learn.SubjectBoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Learn's crop suggestion is built on the subject the scan detected, never on an assumed one
 * (FD-B-1): the screen used a constant centre box with "confidence 0.70" for every photo.
 */
class LearnCropSuggestionTest {

    private fun placement(xMin: Double, yMin: Double, xMax: Double, yMax: Double, cx: Double, cy: Double) =
        TechniqueFactEntity(
            uri = "content://test/a.jpg",
            kind = DetectedSubject.PLACEMENT_KIND,
            value = "off_center",
            confidence = 0.62,
            geometryJson = """{"type":"subject_box","xMin":$xMin,"yMin":$yMin,"xMax":$xMax,"yMax":$yMax,"centroidX":$cx,"centroidY":$cy,"targetX":0.333,"targetY":0.333}""",
            fileSize = 1,
            lastModified = 1,
            detectorVersion = 1,
        )

    /** What the screen used to compute for every photo. */
    private val formerConstantBox = SubjectBoundingBox(0.36, 0.36, 0.64, 0.64, 0.48, 0.48, 0.70)

    @Test
    fun `a subject far from the centre gets its own crop, not the constant-box one`() {
        val facts = listOf(placement(0.70, 0.15, 0.90, 0.40, cx = 0.80, cy = 0.27))

        val crop = learnCropSuggestion(facts, imageWidth = 6000, imageHeight = 4000, requestedAspect = null)
        val former = CropSuggester.suggestCrop(formerConstantBox, 6000, 4000, null)

        assertNotNull(crop)
        assertNotEquals(former, crop)
    }

    @Test
    fun `no detected subject means no crop suggestion`() {
        assertNull(learnCropSuggestion(emptyList(), imageWidth = 6000, imageHeight = 4000, requestedAspect = null))
    }

    @Test
    fun `unknown dimensions mean no crop suggestion instead of an assumed 1500 x 1000`() {
        val facts = listOf(placement(0.70, 0.15, 0.90, 0.40, cx = 0.80, cy = 0.27))
        assertNull(learnCropSuggestion(facts, imageWidth = 0, imageHeight = 0, requestedAspect = null))
    }

    @Test
    fun `the subject box and its confidence come from the fact`() {
        val subject = DetectedSubject.fromFacts(listOf(placement(0.1, 0.2, 0.3, 0.5, cx = 0.2, cy = 0.35)))!!
        assertEquals(0.1, subject.xMin, 1e-9)
        assertEquals(0.5, subject.yMax, 1e-9)
        assertEquals(0.35, subject.centroidY, 1e-9)
        assertEquals(0.62, subject.confidence, 1e-9)
    }
}
