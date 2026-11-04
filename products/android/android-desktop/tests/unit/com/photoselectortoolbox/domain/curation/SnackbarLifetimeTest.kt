package com.photoselectortoolbox.domain.curation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No snackbar time is a deadline: a delete's undo, like a move's, lasts until the
 * folder is left (FD-D-1). Every card is a notice that leaves on its own.
 */
class SnackbarLifetimeTest {

    @Test
    fun `a delete's card lasts like a move's, because neither undo has a deadline`() {
        assertEquals(SnackbarLifetime.MOVE.millis, SnackbarLifetime.DELETE.millis)
    }

    @Test
    fun `transient notices are short, a move longer than a copy, an error longest`() {
        assertEquals(4_000L, SnackbarLifetime.MOVE.millis)
        assertEquals(3_000L, SnackbarLifetime.COPY.millis)
        assertEquals(4_000L, SnackbarLifetime.NOTICE.millis)
        assertTrue(SnackbarLifetime.ERROR.millis > SnackbarLifetime.MOVE.millis)
        SnackbarLifetime.entries.forEach {
            assertTrue("${it.name} is ${it.millis} ms", it.millis <= 10_000L)
        }
    }

    @Test
    fun `the platform's accessibility timeout lengthens a notice and never shortens it`() {
        assertEquals(12_000L, SnackbarLifetime.MOVE.displayMillis { 12_000L })
        assertEquals(4_000L, SnackbarLifetime.MOVE.displayMillis { 1_000L })
        assertEquals(4_000L, SnackbarLifetime.MOVE.displayMillis { it })
    }

    @Test
    fun `a delete's card is stretched for accessibility like every other`() {
        assertEquals(90_000L, SnackbarLifetime.DELETE.displayMillis { 90_000L })
    }
}
