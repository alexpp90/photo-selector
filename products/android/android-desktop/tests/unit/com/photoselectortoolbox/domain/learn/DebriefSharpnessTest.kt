package com.photoselectortoolbox.domain.learn

import com.photoselector.core.Requirement
import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debrief's two sharpness rules (`SD2B_RESEARCH.md` §3 and §4, shoot debrief SD-2b): "sharp
 * frames" is the coach's floor of 40 plus a burst guard and leaves noisy frames out; "fast enough
 * to hand-hold" counts a slow frame against it only when it is also soft or unscored, leaves out
 * exposures of 1 s or more, and is counted for interchangeable-lens cameras and compacts only.
 */
@Requirement("REQ-AND-LEARN.04")
class DebriefSharpnessTest {

    private var next = 0

    private fun shot(
        cameraClass: CameraClass = CameraClass.INTERCHANGEABLE,
        sharpness: Double? = null,
        noise: Double? = null,
        iso: Int? = null,
        burst: Int? = null,
        exposure: Double? = null,
        focal35: Double? = null,
        decidedBy: DecidedBy? = null,
    ): Pair<ImageItem, List<TechniqueFactEntity>> {
        val uri = "content://shoot/${next++}.jpg"
        fun f(kind: String, value: String) = TechniqueFactEntity(uri, kind, value, 0.9, null, 1L, 1L, 3)
        val facts = listOfNotNull(
            f(ScanFacts.MARKER, "1"),
            f(ScanFacts.CAMERA_MAKE, "Make"),
            f(ScanFacts.CAMERA_MODEL, cameraClass.key),
            f(ScanFacts.CAMERA_CLASS, cameraClass.key),
            decidedBy?.let { f(ScanFacts.DECIDED_BY, it.key) },
            exposure?.let { f(ScanFacts.EXPOSURE_TIME, it.toString()) },
            focal35?.let { f("optics.effective_focal_length", it.toString()) },
        )
        val scan = sharpness?.let { ScanResult(uri, sharpnessScore = it, noiseLevel = noise) }
        return ImageItem(uri, "$next.jpg", 1L, 1L, "image/jpeg", scanResult = scan, groupId = burst, exifData = iso?.let { ExifData(iso = it) }) to facts
    }

    private fun run(shots: List<Pair<ImageItem, List<TechniqueFactEntity>>>) =
        ShootDebriefEngine.groups(shots.map { it.first }, shots.associate { it.first.uri to it.second })

    private fun DebriefGroup.count(metric: DebriefMetric) = metrics.single { it.metric == metric }

    @Test
    fun `a frame is sharp from 40 up, and a frame without a score is not counted`() {
        val group = run(List(10) { shot(sharpness = 60.0) } + List(10) { shot(sharpness = 39.9) } + List(5) { shot() }).single()
        assertEquals(MetricCount(DebriefMetric.SHARP_FRAMES, 10, 20), group.count(DebriefMetric.SHARP_FRAMES))
    }

    @Test
    fun `in a burst of three or more a frame must reach half the burst's best`() {
        val burst = listOf(shot(sharpness = 200.0, burst = 1), shot(sharpness = 120.0, burst = 1), shot(sharpness = 90.0, burst = 1))
        // A pair is no burst for the guard: 50 against 200 stays sharp there.
        val pair = listOf(shot(sharpness = 200.0, burst = 2), shot(sharpness = 50.0, burst = 2))
        val group = run(burst + pair).single()
        assertEquals("the 90 of a burst whose best is 200 is soft", MetricCount(DebriefMetric.SHARP_FRAMES, 4, 5), group.count(DebriefMetric.SHARP_FRAMES))
    }

    @Test
    fun `noisy frames are left out of the sharp-frames count`() {
        val group = run(listOf(shot(sharpness = 80.0), shot(sharpness = 80.0, noise = 30.0), shot(sharpness = 80.0, iso = 12800))).single()
        assertEquals(MetricCount(DebriefMetric.SHARP_FRAMES, 1, 1), group.count(DebriefMetric.SHARP_FRAMES))
    }

    @Test
    fun `a slow frame counts against hand-holding only when it is soft or unscored`() {
        val slow = 1.0 / 30
        val group = run(
            listOf(
                shot(sharpness = 80.0, exposure = slow, focal35 = 100.0), // slow but sharp: fine
                shot(sharpness = 20.0, exposure = slow, focal35 = 100.0), // slow and soft
                shot(exposure = slow, focal35 = 100.0), // slow, unscored
                shot(sharpness = 20.0, exposure = 1.0 / 500, focal35 = 100.0), // fast enough (soft for another reason)
                shot(sharpness = 10.0, exposure = 2.0, focal35 = 100.0), // 2 s: a tripod, left out
            ),
        ).single()
        assertEquals(MetricCount(DebriefMetric.HAND_HOLD, 2, 4), group.count(DebriefMetric.HAND_HOLD))
    }

    @Test
    fun `hand-holding is counted for interchangeable-lens cameras and compacts only`() {
        for (c in CameraClass.entries) {
            val group = run(List(3) { shot(cameraClass = c, exposure = 1.0 / 30, focal35 = 100.0) }).single()
            val counted = group.metrics.any { it.metric == DebriefMetric.HAND_HOLD }
            assertEquals("$c", c == CameraClass.INTERCHANGEABLE || c == CameraClass.COMPACT, counted)
        }
    }

    @Test
    fun `the focus carries the catalog's advice, worded by who chose the exposure`() {
        val group = run(List(25) { shot(sharpness = 10.0, exposure = 1.0 / 15, focal35 = 200.0, decidedBy = DecidedBy.PHOTOGRAPHER) }).single()
        assertEquals(DebriefMetric.HAND_HOLD, group.focus)
        assertEquals(DecidedBy.PHOTOGRAPHER, group.decidedBy)
        assertTrue(group.advice.first(), group.advice.first().startsWith("You chose"))
    }
}
