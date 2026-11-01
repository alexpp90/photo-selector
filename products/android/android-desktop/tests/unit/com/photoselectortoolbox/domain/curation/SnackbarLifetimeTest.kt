package com.photoselectortoolbox.domain.curation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snackbar's time is a promise only where the data keeps it: a deferred
 * delete. Everything else is a notice that leaves on its own.
 */
class SnackbarLifetimeTest {

    @Test
    fun `only the delete window counts down, and it is the real undo window`() {
        assertEquals(listOf(SnackbarLifetime.DELETE), SnackbarLifetime.entries.filter { it.countsDown })
        assertEquals(DeferredDeletion.UNDO_WINDOW_MILLIS, SnackbarLifetime.DELETE.millis)
    }

    @Test
    fun `transient notices are short, a move longer than a copy, an error longest`() {
        assertEquals(4_000L, SnackbarLifetime.MOVE.millis)
        assertEquals(3_000L, SnackbarLifetime.COPY.millis)
        assertEquals(4_000L, SnackbarLifetime.NOTICE.millis)
        assertTrue(SnackbarLifetime.ERROR.millis > SnackbarLifetime.MOVE.millis)
        SnackbarLifetime.entries.filter { !it.countsDown }.forEach {
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
    fun `a real deadline is never stretched, because UNDO past the commit would be a lie`() {
        assertEquals(30_000L, SnackbarLifetime.DELETE.displayMillis { 90_000L })
    }
}
