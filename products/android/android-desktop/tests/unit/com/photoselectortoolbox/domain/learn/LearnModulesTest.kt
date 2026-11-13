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
import com.photoselectortoolbox.testing.en

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
        assertTrue(result.tryNextCard!!.title.en().contains("Horizon"))
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
            assertTrue(c.rationale.en().isNotBlank())
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

    // --- REQ-AND-LEARN.03, [USER 2026-11-12] decision #17: which frames Compare pairs ---

    private fun frame(
        name: String,
        minute: Double = 0.0,
        sharpness: Double? = 50.0,
        highlights: Double? = 0.2,
        shadows: Double? = 0.2,
        noise: Double? = 0.4,
        focal: Double? = 50.0,
        group: Int? = null,
        captured: String? = null,
    ) = ImageItem(
        uri = "content://test/$name.jpg",
        fileName = "$name.JPG",
        fileSize = 1000L,
        lastModified = 1_700_000_000_000L + (minute * 60_000).toLong(),
        mimeType = "image/jpeg",
        exifData = ExifData(
            shutterSpeed = 1.0 / 250.0,
            aperture = 4.0,
            iso = 200,
            focalLength35mm = focal,
            dateTimeOriginal = captured,
        ),
        scanResult = ScanResult(
            filePath = "content://test/$name.jpg",
            sharpnessScore = sharpness,
            noiseLevel = noise,
            highlightClipping = highlights,
            shadowClipping = shadows,
        ),
        groupId = group,
    )

    @Test
    fun `Compare pairs frames up to 10 minutes apart and not beyond`() {
        val inside = SceneComparator.findComparableScenes(listOf(frame("a", 0.0, 100.0), frame("b", 10.0, 40.0)))
        assertEquals(1, inside.size)
        assertEquals("content://test/a.jpg", inside.single().primaryPhoto.uri)

        val outside = SceneComparator.findComparableScenes(listOf(frame("a", 0.0, 100.0), frame("b", 10.1, 40.0)))
        assertTrue("10 min 6 s apart is not one scene", outside.isEmpty())
    }

    @Test
    fun `Compare reads the capture time before the file time`() {
        // Files copied at the same minute, shot an hour apart: not one scene.
        val a = frame("a", 0.0, 100.0, captured = "2026:06:01 10:00:00")
        val b = frame("b", 0.0, 40.0, captured = "2026:06:01 11:00:00")
        assertTrue(SceneComparator.findComparableScenes(listOf(a, b)).isEmpty())
    }

    @Test
    fun `Compare needs the focal lengths within 25 percent where both have one`() {
        // 50 vs 66 mm: 16 of 66 = 24 %.
        assertEquals(1, SceneComparator.findComparableScenes(listOf(frame("a", focal = 50.0), frame("b", 1.0, 20.0, focal = 66.0))).size)
        // 50 vs 70 mm: 20 of 70 = 29 %.
        assertTrue(SceneComparator.findComparableScenes(listOf(frame("a", focal = 50.0), frame("b", 1.0, 20.0, focal = 70.0))).isEmpty())
        // One frame without a focal length: the time decides alone.
        assertEquals(1, SceneComparator.findComparableScenes(listOf(frame("a", focal = 50.0), frame("b", 1.0, 20.0, focal = null))).size)
    }

    @Test
    fun `a phone folder at one focal length is not paired by its focal length alone`() {
        // The old "or": every 26 mm frame of a day paired. Hours apart, nothing pairs.
        val day = (0 until 6).map { frame("p$it", minute = it * 90.0, sharpness = if (it % 2 == 0) 100.0 else 20.0, focal = 26.0) }
        assertTrue(SceneComparator.findComparableScenes(day).isEmpty())
    }

    @Test
    fun `sharpness pairs from 40 percent of the higher score`() {
        // 100 vs 60: 40 % of 100.
        assertEquals(1, SceneComparator.findComparableScenes(listOf(frame("a", sharpness = 100.0), frame("b", 1.0, 60.0))).size)
        // 100 vs 61: 39 %.
        assertTrue(SceneComparator.findComparableScenes(listOf(frame("a", sharpness = 100.0), frame("b", 1.0, 61.0))).isEmpty())
    }

    @Test
    fun `clipping and noise pair at twice the other with the larger at least 1`() {
        // Highlights 2.0 vs 1.0 %: 2x, larger >= 1 %. The less clipped frame leads.
        val clipped = SceneComparator.findComparableScenes(
            listOf(frame("a", highlights = 2.0), frame("b", 1.0, highlights = 1.0)),
        ).single()
        assertEquals("content://test/b.jpg", clipped.primaryPhoto.uri)
        assertTrue(clipped.differenceBullets.first().startsWith("Highlight clipping"))
        // 0.8 vs 0.3 %: more than 2x but the larger is under 1 %.
        assertTrue(SceneComparator.findComparableScenes(listOf(frame("a", highlights = 0.8), frame("b", 1.0, highlights = 0.3))).isEmpty())
        // 3.0 vs 1.6 %: over 1 % but under 2x.
        assertTrue(SceneComparator.findComparableScenes(listOf(frame("a", highlights = 3.0), frame("b", 1.0, highlights = 1.6))).isEmpty())
        // Shadows 1.0 vs 0.0 %: a zero beside a measured 1 % is a difference.
        assertEquals(1, SceneComparator.findComparableScenes(listOf(frame("a", shadows = 1.0), frame("b", 1.0, shadows = 0.0))).size)
        // Noise 1.0 vs 0.5: pairs; 0.9 vs 0.3: the larger is under 1.0.
        assertEquals(1, SceneComparator.findComparableScenes(listOf(frame("a", noise = 1.0), frame("b", 1.0, noise = 0.5))).size)
        assertTrue(SceneComparator.findComparableScenes(listOf(frame("a", noise = 0.9), frame("b", 1.0, noise = 0.3))).isEmpty())
    }

    @Test
    fun `burst peers pair whatever their times and focal lengths`() {
        val a = frame("a", 0.0, 100.0, focal = 24.0, group = 7)
        val b = frame("b", 45.0, 30.0, focal = 70.0, group = 7)
        assertEquals(1, SceneComparator.findComparableScenes(listOf(a, b)).size)
    }

    @Test
    fun `no pair without a measured difference, whatever the EXIF says, and no filler`() {
        val a = frame("a")
        val b = frame("b", 1.0).let { it.copy(exifData = it.exifData!!.copy(shutterSpeed = 1.0 / 30.0, iso = 3200, aperture = 1.8)) }
        assertTrue("EXIF-only differences are not a pair", SceneComparator.findComparableScenes(listOf(a, b)).isEmpty())
        // And no whole-folder fallback: two unrelated frames hours apart are not compared.
        assertTrue(SceneComparator.findComparableScenes(listOf(frame("x", 0.0, 100.0), frame("y", 300.0, 10.0))).isEmpty())

        val paired = SceneComparator.findComparableScenes(listOf(frame("s", sharpness = 100.0), frame("t", 1.0, 20.0))).single()
        assertTrue(paired.differenceBullets.none { "similar camera settings" in it || "micro-expressions" in it })
        assertEquals(listOf("Measured sharpness: 100 on s.JPG vs 20 on t.JPG."), paired.differenceBullets)
    }

    // backlog 2026-11-12: Learn's Compare treated a missing sharpness as 0.0
    @Test
    fun `a frame without a sharpness value is not paired on sharpness`() {
        val measured = frame("a", sharpness = 64.0)
        val unmeasured = frame("b", 1.0, sharpness = null)
        assertTrue(SceneComparator.findComparableScenes(listOf(measured, unmeasured)).isEmpty())
        // Its other measurements still count, and the explanation names no sharpness.
        val clipped = frame("c", 2.0, sharpness = null, highlights = 4.0)
        val scene = SceneComparator.findComparableScenes(listOf(measured, clipped)).single()
        assertEquals("content://test/a.jpg", scene.primaryPhoto.uri)
        assertTrue(scene.differenceBullets.none { it.startsWith("Measured sharpness") })
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
