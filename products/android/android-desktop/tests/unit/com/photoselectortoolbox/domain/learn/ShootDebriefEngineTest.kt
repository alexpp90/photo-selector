package com.photoselectortoolbox.domain.learn

import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.data.model.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shoot debrief's groups and counts (SD-3): grouped per camera class, never averaged. */
class ShootDebriefEngineTest {

    private var next = 0

    /** One scanned photo: its facts, with the marker, a camera and the given labels. */
    private fun shot(
        make: String,
        model: String,
        cameraClass: CameraClass? = null,
        horizon: String? = null,
        clipping: String? = null,
        exposure: Double? = null,
        focal35: Double? = null,
        takenAt: Long? = null,
    ): Pair<ImageItem, List<TechniqueFactEntity>> {
        val uri = "content://shoot/${next++}.jpg"
        fun f(kind: String, value: String) = TechniqueFactEntity(uri, kind, value, 0.9, null, 1L, 1L, 2)
        val facts = listOfNotNull(
            f(ScanFacts.MARKER, "1"),
            f(ScanFacts.CAMERA_MAKE, make),
            f(ScanFacts.CAMERA_MODEL, model),
            cameraClass?.let { f("camera.class", it.key) },
            horizon?.let { f("composition.horizon", it) },
            clipping?.let { f("exposure.clipping", it) },
            exposure?.let { f(ScanFacts.EXPOSURE_TIME, it.toString()) },
            focal35?.let { f("optics.effective_focal_length", it.toString()) },
            takenAt?.let { f(ScanFacts.CAPTURE_TIME, it.toString()) },
        )
        return ImageItem(uri, "$next.jpg", 1L, 1L, "image/jpeg") to facts
    }

    private fun run(
        shots: List<Pair<ImageItem, List<TechniqueFactEntity>>>,
        overrides: Map<CameraId, CameraClass> = emptyMap(),
    ) = ShootDebriefEngine.groups(shots.map { it.first }, shots.associate { it.first.uri to it.second }, CameraClassifier.withOverrides(overrides))

    @Test
    fun `a mixed folder gives one group per class and no merged number`() {
        val phone = List(25) { shot("Google", "Pixel 8", CameraClass.PHONE, horizon = if (it < 20) "level" else "tilted") }
        val camera = List(30) { shot("Sony", "ILCE-7M4", CameraClass.INTERCHANGEABLE, horizon = if (it < 15) "level" else "slight_tilt") }

        val groups = run(phone + camera)

        assertEquals(listOf(CameraClass.INTERCHANGEABLE, CameraClass.PHONE), groups.map { it.cameraClass })
        val level = { g: DebriefGroup -> g.metrics.single { it.metric == DebriefMetric.LEVEL_HORIZON } }
        assertEquals(MetricCount(DebriefMetric.LEVEL_HORIZON, 15, 30), level(groups[0]))
        assertEquals(MetricCount(DebriefMetric.LEVEL_HORIZON, 20, 25), level(groups[1]))
        assertEquals(listOf("Sony ILCE-7M4"), groups[0].cameraNames)
    }

    @Test
    fun `under the sample size a metric is a count without a percentage or a previous value`() {
        val groups = run(List(5) { shot("Sony", "ILCE-7M4", CameraClass.INTERCHANGEABLE, clipping = "well_exposed") })

        val count = groups.single().metrics.single { it.metric == DebriefMetric.HIGHLIGHTS_KEPT }
        assertEquals(5, count.total)
        assertNull(count.percent)
        assertNull(groups.single().focus)
        assertNull(ShootDebriefEngine.previousValue(count, MetricCount(DebriefMetric.HIGHLIGHTS_KEPT, 30, 40)))
    }

    @Test
    fun `a drone or an unknown camera gets no hand-holding count, a camera with controls does`() {
        val slow = { m: String, c: CameraClass? -> shot("X", m, c, exposure = 1.0 / 30, focal35 = 100.0) }

        val drone = run(List(20) { slow("Mavic", CameraClass.DRONE) }).single()
        val unknown = run(List(20) { slow("Mystery", null) }).single()
        val camera = run(List(20) { slow("Body", CameraClass.INTERCHANGEABLE) }).single()

        assertTrue(drone.metrics.none { it.metric == DebriefMetric.HAND_HOLD })
        assertEquals(CameraClass.UNKNOWN, unknown.cameraClass)
        assertTrue(unknown.metrics.none { it.metric == DebriefMetric.HAND_HOLD })
        assertEquals(MetricCount(DebriefMetric.HAND_HOLD, 0, 20), camera.metrics.single())
        assertEquals(DebriefMetric.HAND_HOLD, camera.focus)
    }

    @Test
    fun `the user's class for a camera wins, and unknown comes last`() {
        val shots = List(3) { shot("DJI", "FC3582") } + List(2) { shot("Mystery", "Box") }

        val groups = run(shots, overrides = mapOf(CameraId("DJI", "FC3582") to CameraClass.DRONE))

        assertEquals(listOf(CameraClass.DRONE, CameraClass.UNKNOWN), groups.map { it.cameraClass })
    }

    @Test
    fun `the focus is the metric with the largest share of problems, a photo without facts counts for nothing`() {
        val shots = List(40) {
            shot(
                "Sony", "ILCE-7M4", CameraClass.INTERCHANGEABLE,
                horizon = if (it < 30) "level" else "tilted", // 25 % problems
                clipping = if (it < 20) "well_exposed" else "highlights_clipped", // 50 % problems
                takenAt = 1_000L + it,
            )
        }
        val unscanned = ImageItem("content://shoot/never.jpg", "never.jpg", 1L, 1L, "image/jpeg")

        val group = ShootDebriefEngine.groups(shots.map { it.first } + unscanned, shots.associate { it.first.uri to it.second }).single()

        assertEquals(DebriefMetric.HIGHLIGHTS_KEPT, group.focus)
        assertEquals(40, group.photoCount)
        assertEquals(1_000L, group.firstCaptureTime)
        assertEquals(1_039L, group.lastCaptureTime)
        assertTrue(group.metrics.size <= ShootDebriefEngine.MAX_METRICS)
    }
}
