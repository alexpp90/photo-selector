package com.photoselectortoolbox.domain.learn

import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnModulesTest {

    // --- U-22b / U-22c / U-23: TechniqueFactDetector ---

    @Test
    fun `computeSolarElevationDeg identifies high noon sun vs twilight`() {
        // Berlin (52.52 N, 13.405 E) at 2026-06-21 12:00:00 +02:00 -> high noon sun
        val noonAlt = TechniqueFactDetector.computeSolarElevationDeg(
            dateTimeOriginal = "2026:06:21 12:00:00",
            offsetTimeOriginal = "+02:00",
            latitude = 52.52,
            longitude = 13.405,
        )
        assertNotNull(noonAlt)
        assertTrue("Expected high noon elevation > 50 deg, got $noonAlt", noonAlt!! > 50.0)

        // Same location near sunset 2026-06-21 21:25:00 +02:00 -> golden/blue hour
        val eveningAlt = TechniqueFactDetector.computeSolarElevationDeg(
            dateTimeOriginal = "2026:06:21 21:25:00",
            offsetTimeOriginal = "+02:00",
            latitude = 52.52,
            longitude = 13.405,
        )
        assertNotNull(eveningAlt)
        assertTrue("Expected low evening elevation < 6 deg, got $eveningAlt", eveningAlt!! < 6.0)
    }

    @Test
    fun `detectFacts detects golden hour, shallow DOF, action freeze, and subject placement`() {
        val fakeSubjectDetector = SubjectDetector { _, _, _ ->
            SubjectBoundingBox(
                xMin = 0.25,
                yMin = 0.25,
                xMax = 0.42,
                yMax = 0.42,
                centroidX = 1.0 / 3.0,
                centroidY = 1.0 / 3.0,
                confidence = 0.90,
                subjectSharpnessRatio = 2.4,
            )
        }
        val detector = TechniqueFactDetector(fakeSubjectDetector)
        val luma = FloatArray(64 * 64) { 128f }
        val exif = ExifData(
            shutterSpeed = 1.0 / 2000.0,
            aperture = 1.8,
            focalLength = 50.0,
            focalLength35mm = 50.0,
            iso = 100,
            dateTimeOriginal = "2026:06:21 21:15:00",
            offsetTimeOriginal = "+02:00",
            gpsLatitude = 52.52,
            gpsLongitude = 13.405,
        )

        val facts = detector.detectFacts(
            exif = exif,
            luma = luma,
            width = 64,
            height = 64,
            highlightClipping = 0.2,
            shadowClipping = 0.1,
            isRawOrHasRawCompanion = true,
        )

        assertTrue(facts.any { it.kind == "composition.subject_placement" && it.value == "thirds_aligned" })
        assertTrue(facts.any { it.kind == "depth.background_separation" && it.value == "strong_isolation" })
        assertTrue(facts.any { it.kind == "intent.shutter" && it.value == "action_freeze" })
        assertTrue(facts.any { it.kind == "exposure.clipping" && it.value == "well_exposed" })
    }

    // --- U-24: CardGenerator ---

    @Test
    fun `CardGenerator generates Did Well, Try Next Time, and Guess-First challenge`() {
        val entities = listOf(
            TechniqueFactEntity(
                uri = "content://test/1.jpg",
                kind = "light.hour",
                value = "golden_hour",
                confidence = 0.92,
                geometryJson = null,
                fileSize = 1000L,
                lastModified = 1L,
                detectorVersion = 1,
            ),
            TechniqueFactEntity(
                uri = "content://test/1.jpg",
                kind = "composition.subject_placement",
                value = "thirds_aligned",
                confidence = 0.88,
                geometryJson = null,
                fileSize = 1000L,
                lastModified = 1L,
                detectorVersion = 1,
            ),
            TechniqueFactEntity(
                uri = "content://test/1.jpg",
                kind = "composition.horizon",
                value = "tilted",
                confidence = 0.85,
                geometryJson = """{"angleDeg":2.4,"yNorm":0.45}""",
                fileSize = 1000L,
                lastModified = 1L,
                detectorVersion = 1,
            ),
        )

        val result = CardGenerator.generateFromEntities(entities)
        assertEquals(2, result.didWellCards.size)
        assertNotNull(result.tryNextCard)
        assertTrue(result.tryNextCard!!.title.contains("Horizon"))
        assertEquals(3, result.guessFirstChallenge.options.size)
    }

    // --- U-25: CropSuggester ---

    @Test
    fun `CropSuggester proposes crop with area at least 50 percent and subject inside crop box`() {
        val subjectBox = SubjectBoundingBox(
            xMin = 0.42,
            yMin = 0.42,
            xMax = 0.56,
            yMax = 0.56,
            centroidX = 0.49,
            centroidY = 0.49,
            confidence = 0.90,
        )

        for (aspect in CropAspectRatio.entries) {
            val crop = CropSuggester.suggestCrop(
                subjectBox = subjectBox,
                imageWidth = 6000,
                imageHeight = 4000,
                requestedAspect = aspect,
            )
            assertNotNull("Expected crop suggestion for $aspect", crop)
            val c = crop!!
            assertTrue("Crop area ${c.areaFraction} must be >= 0.50", c.areaFraction >= 0.50 - 1e-6)
            assertTrue(c.xMin <= subjectBox.xMin && c.xMax >= subjectBox.xMax)
            assertTrue(c.yMin <= subjectBox.yMin && c.yMax >= subjectBox.yMax)
            assertTrue(c.rationale.isNotBlank())
        }
    }

    // --- U-26: LessonCatalog ---

    @Test
    fun `LessonCatalog contains 14 lessons across 4 tracks and builds 2-question Spot-It quiz`() {
        assertEquals(14, LessonCatalog.ALL_LESSONS.size)
        val byTrack = LessonCatalog.lessonsByTrack()
        assertEquals(4, byTrack.size)

        for (lesson in LessonCatalog.ALL_LESSONS) {
            val quiz = LessonCatalog.buildSpotItQuiz(lesson)
            assertEquals(2, quiz.size)
            for (q in quiz) {
                assertEquals(3, q.options.size)
                assertTrue(q.correctIndex in q.options.indices)
            }
        }
    }

    // --- U-27: SceneComparator ---

    @Test
    fun `SceneComparator clusters burst peers and explains EXIF and sharpness differences`() {
        val sharp = ImageItem(
            uri = "content://test/sharp.jpg",
            fileName = "DSC001.JPG",
            fileSize = 1000L,
            lastModified = 1_000_000L,
            mimeType = "image/jpeg",
            exifData = ExifData(shutterSpeed = 1.0 / 500.0, aperture = 2.8, iso = 200, focalLength35mm = 85.0),
            scanResult = ScanResult(filePath = "content://test/sharp.jpg", sharpnessScore = 64.0, noiseLevel = 1.2),
            groupId = 1,
        )
        val blurry = ImageItem(
            uri = "content://test/blurry.jpg",
            fileName = "DSC002.JPG",
            fileSize = 1000L,
            lastModified = 1_002_000L,
            mimeType = "image/jpeg",
            exifData = ExifData(shutterSpeed = 1.0 / 30.0, aperture = 2.8, iso = 200, focalLength35mm = 85.0),
            scanResult = ScanResult(filePath = "content://test/blurry.jpg", sharpnessScore = 18.0, noiseLevel = 1.2),
            groupId = 1,
        )

        val scenes = SceneComparator.findComparableScenes(listOf(sharp, blurry))
        assertEquals(1, scenes.size)
        val scene = scenes.first()
        assertEquals(sharp.uri, scene.primaryPhoto.uri)
        assertEquals(blurry.uri, scene.comparisonPhoto.uri)
        assertTrue(scene.diffExplanation.contains("1/500"))
        assertTrue(scene.diffExplanation.contains("1/30"))
    }

    @Test
    fun `SceneComparator ranking ignores a legacy cached aesthetic score`() {
        fun item(name: String, sharp: Double, legacy: Double?) = ImageItem(
            uri = "content://test/$name.jpg",
            fileName = "$name.JPG",
            fileSize = 1000L,
            lastModified = 1_000_000L,
            mimeType = "image/jpeg",
            exifData = ExifData(shutterSpeed = 1.0 / 500.0, aperture = 2.8, iso = 200, focalLength35mm = 85.0),
            scanResult = ScanResult(filePath = "content://test/$name.jpg", sharpnessScore = sharp, aestheticScore = legacy),
            groupId = 1,
        )
        val sharp = item("a", 64.0, null)
        val blurryWithLegacy = item("b", 18.0, 9.5)
        val scene = SceneComparator.findComparableScenes(listOf(blurryWithLegacy, sharp)).single()
        assertEquals(sharp.uri, scene.primaryPhoto.uri)
    }

    // --- U-28: PracticeAndInsightsEngine ---

    @Test
    fun `PracticeAndInsightsEngine enforces n at least 20 for insights and tracks practice goal progress`() {
        val smallList = (1..10).map { i ->
            ImageItem(
                uri = "content://test/$i.jpg",
                fileName = "$i.jpg",
                fileSize = 100L,
                lastModified = i * 1000L,
                mimeType = "image/jpeg",
            )
        }
        assertTrue(PracticeAndInsightsEngine.computeInsights(smallList, emptyMap()).isEmpty())

        val largeList = (1..25).map { i ->
            ImageItem(
                uri = "content://test/$i.jpg",
                fileName = "$i.jpg",
                fileSize = 100L,
                lastModified = i * 1000L,
                mimeType = "image/jpeg",
            )
        }
        val factsByUri = largeList.associate { img ->
            img.uri to listOf(
                TechniqueFactEntity(
                    uri = img.uri,
                    kind = "composition.subject_placement",
                    value = "thirds_aligned",
                    confidence = 0.85,
                    geometryJson = null,
                    fileSize = 100L,
                    lastModified = img.lastModified,
                    detectorVersion = 1,
                )
            )
        }
        val insights = PracticeAndInsightsEngine.computeInsights(largeList, factsByUri)
        assertFalse(insights.isEmpty())
        assertTrue(insights.any { it.id == "insight_subject_placement" })

        val goal = PracticeAndInsightsEngine.createPracticeGoal(
            lessonId = "composition.rule_of_thirds",
            images = largeList.take(20),
            factsByUri = factsByUri,
            nowEpochMs = 20_500L,
        )
        val status = PracticeAndInsightsEngine.evaluateGoalProgress(
            goal = goal,
            images = largeList,
            factsByUri = factsByUri,
        )
        assertEquals(5, status.recentSampleSize)
        assertEquals(5, status.recentMatchingCount)
    }
}
