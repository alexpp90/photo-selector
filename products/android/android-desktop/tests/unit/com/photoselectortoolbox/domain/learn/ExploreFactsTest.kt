package com.photoselectortoolbox.domain.learn

import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** Explore's facts: stored rows win per kind, on-demand rows fill the kinds the scan does not store (SD-1b). */
@Requirement("REQ-AND-LEARN.01")
class ExploreFactsTest {

    private fun fact(kind: String, value: String) = TechniqueFactEntity("content://a/1.jpg", kind, value, 0.9, null, 1L, 1L, 2)

    @Test
    fun `without a stored set every row is the on-demand one`() {
        val onDemand = listOf(fact("composition.horizon", "tilted"), fact("exposure.tonal_key", "low_key"))
        assertEquals(onDemand, ExploreFacts.merge(null, onDemand))
        assertEquals(onDemand, ExploreFacts.merge(emptyList(), onDemand))
    }

    @Test
    fun `a stored kind wins and an unstored kind comes from the detector`() {
        val stored = listOf(fact(ScanFacts.MARKER, "1"), fact("composition.horizon", "level"))
        val onDemand = listOf(fact("composition.horizon", "tilted"), fact("exposure.tonal_key", "low_key"))
        val merged = ExploreFacts.merge(stored, onDemand)
        assertEquals(listOf("level"), merged.filter { it.kind == "composition.horizon" }.map { it.value })
        assertEquals(listOf("low_key"), merged.filter { it.kind == "exposure.tonal_key" }.map { it.value })
    }

    @Test
    fun `a stored set that found nothing of a stored kind is not filled from the detector`() {
        val stored = listOf(fact(ScanFacts.MARKER, "1"))
        val onDemand = listOf(fact("composition.horizon", "tilted"), fact("composition.symmetry", "vertical"))
        val merged = ExploreFacts.merge(stored, onDemand)
        assertEquals(listOf(ScanFacts.MARKER, "composition.symmetry"), merged.map { it.kind })
    }
}
