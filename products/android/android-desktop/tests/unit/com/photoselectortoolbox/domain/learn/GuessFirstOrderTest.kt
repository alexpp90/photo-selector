package com.photoselectortoolbox.domain.learn

import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Explore's "Guess first" (backlog sweep 2026-11-12, item 7): the right answer was always the first
 * option (`correctIndex = 0`). The options now come in an order fixed by the photo and the card, as
 * the quiz's do since SD-1b: never always first, never reshuffled for the same photo.
 */
@Requirement("REQ-AND-LEARN.02")
class GuessFirstOrderTest {

    private fun challenge(uri: String, kind: String = "composition.horizon", value: String = "tilted") =
        CardGenerator.generateFromEntities(listOf(TechniqueFactEntity(uri, kind, value, 0.9, null, 1L, 1L, 3))).guessFirstChallenge

    @Test
    fun `the right answer is not always first`() {
        val positions = (0 until 30).map { challenge("content://f/$it.jpg").correctIndex }.toSet()
        assertTrue("positions seen: $positions", positions.size > 1)
    }

    @Test
    fun `the right answer is the option at its index, and the order is stable`() {
        repeat(30) { i ->
            val uri = "content://f/$i.jpg"
            val c = challenge(uri)
            val card = CardGenerator.generateFromEntities(listOf(TechniqueFactEntity(uri, "composition.horizon", "tilted", 0.9, null, 1L, 1L, 3))).tryNextCard!!
            assertEquals(card.title, c.options[c.correctIndex])
            assertEquals(c, challenge(uri))
        }
    }

    @Test
    fun `a photo with no finding is shuffled too`() {
        val positions = (0 until 30).map { i ->
            CardGenerator.generateFromEntities(listOf(TechniqueFactEntity("content://f/$i.jpg", ScanFacts.MARKER, "1", 1.0, null, 1L, 1L, 3)))
                .guessFirstChallenge.let { it.options[it.correctIndex] to it.correctIndex }
        }
        assertEquals(1, positions.map { it.first }.toSet().size)
        assertTrue(positions.map { it.second }.toSet().size > 1)
    }
}
