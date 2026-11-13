package com.photoselectortoolbox.domain.learn

import com.photoselector.core.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.photoselectortoolbox.testing.en

/**
 * Advice fits the camera (C2, shoot debrief SD-2b): for every camera class and every finding, no
 * line names a control the class lacks. The word list per control is the photo researcher's
 * (`SD2B_RESEARCH.md` §2): aperture, f-stop, ISO and exposure compensation for a phone, an action
 * cam and an unknown camera (a drone: aperture), "lens" for every class without interchangeable
 * lenses, "shutter" for a phone. "Lens" is matched as a word: "wide lenses push subjects toward the
 * edges" describes an action cam, it does not ask for another lens.
 */
@Requirement("REQ-AND-COACH.01")
class AdviceCatalogTest {

    private val words: Map<Control, List<Regex>> = mapOf(
        Control.APERTURE to listOf(Regex("""(?i)\baperture\b"""), Regex("""(?i)f-stop"""), Regex("""(?i)\bf/\d"""), Regex("""(?i)stop down""")),
        Control.ISO to listOf(Regex("""\bISO\b""")),
        Control.EXPOSURE_COMPENSATION to listOf(Regex("""(?i)exposure compensation"""), Regex("""(?i)\bEV\b""")),
        Control.LENS to listOf(Regex("""(?i)\blens\b""")),
        Control.SHUTTER to listOf(Regex("""(?i)\bshutter""")),
    )

    /** The controls each class lacks that the test looks for (the researcher's list). */
    private fun lacking(c: CameraClass): Set<Control> = when (c) {
        CameraClass.PHONE -> setOf(Control.APERTURE, Control.ISO, Control.EXPOSURE_COMPENSATION, Control.LENS, Control.SHUTTER)
        CameraClass.DRONE -> setOf(Control.APERTURE, Control.LENS)
        CameraClass.ACTION, CameraClass.UNKNOWN -> setOf(Control.APERTURE, Control.ISO, Control.EXPOSURE_COMPENSATION, Control.LENS)
        CameraClass.COMPACT -> setOf(Control.LENS)
        CameraClass.INTERCHANGEABLE -> emptySet()
    }

    private fun violations(c: CameraClass, text: String, where: String): List<String> =
        lacking(c).flatMap { control -> words.getValue(control).filter { it.containsMatchIn(text) }.map { "$where ($c): \"$text\" names $control" } }

    private fun profile(c: CameraClass, by: DecidedBy = DecidedBy.UNKNOWN) =
        CameraProfile(c, CameraProfile.controlsOf(c), by, 0.9, "test")

    @Test
    fun `no debrief line names a control the class lacks`() {
        val bad = CameraClass.entries.flatMap { c ->
            DebriefMetric.entries.flatMap { metric ->
                DecidedBy.entries.flatMap { by ->
                    listOf(false, true).flatMap { raw ->
                        listOf(null, 0.1, 0.9).flatMap { share ->
                            AdviceCatalog.focus(metric, c, by, raw, share).flatMap { violations(c, it.en(), "$metric") }
                        }
                    }
                }
            }
        }
        assertEquals(emptyList<String>(), bad.distinct())
    }

    @Test
    fun `no coach or card recommendation names a control the class lacks`() {
        val bad = CameraClass.entries.flatMap { c ->
            CoachFinding.entries.flatMap { finding ->
                DecidedBy.entries.mapNotNull { by -> AdviceCatalog.coach(finding, profile(c, by), "1/250s", "-0.7") }
                    .flatMap { violations(c, it.en(), "$finding") }
            }
        }
        assertEquals(emptyList<String>(), bad.distinct())
    }

    @Test
    fun `a debrief line is at most twelve words`() {
        val long = CameraClass.entries.flatMap { c ->
            DebriefMetric.entries.flatMap { m -> DecidedBy.entries.flatMap { by -> AdviceCatalog.focus(m, c, by) } }
        }.map { it.en() }.filter { it.split(' ').size > 12 }
        assertEquals(emptyList<String>(), long)
    }

    @Test
    fun `no hand-holding advice to a drone, an action cam, a phone or an unknown camera`() {
        for (c in listOf(CameraClass.DRONE, CameraClass.ACTION, CameraClass.PHONE, CameraClass.UNKNOWN)) {
            assertEquals("$c", emptyList<String>(), AdviceCatalog.focus(DebriefMetric.HAND_HOLD, c))
        }
        assertEquals(null, AdviceCatalog.coach(CoachFinding.SLOW_AND_SOFT, profile(CameraClass.DRONE)))
        assertEquals(null, AdviceCatalog.coach(CoachFinding.SLOW_UNSCORED, profile(CameraClass.ACTION)))
    }

    @Test
    fun `the wording follows who chose the exposure`() {
        val mine = AdviceCatalog.focus(DebriefMetric.HAND_HOLD, CameraClass.INTERCHANGEABLE, DecidedBy.PHOTOGRAPHER).first().en()
        val its = AdviceCatalog.focus(DebriefMetric.HAND_HOLD, CameraClass.INTERCHANGEABLE, DecidedBy.CAMERA).first().en()
        assertTrue(mine, mine.startsWith("You chose"))
        assertTrue(its, its.startsWith("The camera chose"))
        val gotAway = AdviceCatalog.focus(DebriefMetric.HAND_HOLD, CameraClass.COMPACT, DecidedBy.PHOTOGRAPHER, slowSoftShare = 0.1)
        assertEquals("under a quarter of the slow frames soft: a neutral note", 1, gotAway.size)
    }

    @Test
    fun `a mostly RAW group gets no compensation line for its highlights`() {
        assertEquals(emptyList<String>(), AdviceCatalog.focus(DebriefMetric.HIGHLIGHTS_KEPT, CameraClass.INTERCHANGEABLE, mostlyRaw = true))
        assertEquals(1, AdviceCatalog.focus(DebriefMetric.HIGHLIGHTS_KEPT, CameraClass.PHONE, mostlyRaw = true).size)
    }
}
