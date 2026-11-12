package com.phototok.domain

import com.photoselector.core.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The consent-request bookkeeping of the system bin ([USER 2026-11-12] decision #13). */
@Requirement("REQ-TOK-GESTURE.03")
class SystemTrashQueueTest {

    private fun item(n: Int) = TrashItem("doc/$n", "content://media/external/images/media/$n", bytes = 10L * n)

    @Test
    fun aReleasedSubmissionIsOneRequest() {
        val q = SystemTrashQueue()
        val id = q.submit(listOf(item(1), item(2)), release = true)
        val request = q.take()!!
        assertEquals(listOf("doc/1", "doc/2"), request.items.map { it.documentUri })
        assertNull("one request at a time", q.take())
        val answers = q.answer(request, setOf("doc/1", "doc/2"))
        assertEquals(listOf(id), answers.map { it.submissionId })
        assertEquals(2, answers.single().trashed.size)
        assertTrue(answers.single().kept.isEmpty())
    }

    @Test
    fun anEvictedDeleteWaitsAndGoesWithTheNextFinalisation() {
        val q = SystemTrashQueue()
        val evicted = q.submit(listOf(item(1)), release = false)
        assertNull("the swipe that evicted it opens no dialog", q.take())
        assertEquals(1, q.heldCount)
        val later = q.submit(listOf(item(2)), release = true)
        val request = q.take()!!
        assertEquals("one dialog for both", 2, request.items.size)
        val answers = q.answer(request, setOf("doc/1", "doc/2"))
        assertEquals(setOf(evicted, later), answers.map { it.submissionId }.toSet())
    }

    @Test
    fun aDeclinedRequestKeepsEveryFile() {
        val q = SystemTrashQueue()
        q.submit(listOf(item(1), item(2)), release = true)
        val answer = q.answer(q.take()!!, trashedDocuments = emptySet()).single()
        assertTrue(answer.trashed.isEmpty())
        assertEquals(listOf("doc/1", "doc/2"), answer.kept.map { it.documentUri })
    }

    @Test
    fun aPartlyAppliedRequestSplitsByReadBack() {
        val q = SystemTrashQueue()
        q.submit(listOf(item(1), item(2)), release = true)
        val answer = q.answer(q.take()!!, trashedDocuments = setOf("doc/2")).single()
        assertEquals(listOf("doc/2"), answer.trashed.map { it.documentUri })
        assertEquals(listOf("doc/1"), answer.kept.map { it.documentUri })
    }

    @Test
    fun aLongBatchGoesOutInBoundedRequestsAndCompletesAfterTheLast() {
        val q = SystemTrashQueue(maxBatch = 2)
        val id = q.submit((1..5).map(::item), release = true)
        val sizes = mutableListOf<Int>()
        var answers = emptyList<SystemTrashQueue.Answer>()
        while (true) {
            val r = q.take() ?: break
            sizes += r.items.size
            answers = q.answer(r, r.items.map { it.documentUri }.toSet())
            if (sizes.size < 3) assertTrue("not complete before its last request", answers.isEmpty())
        }
        assertEquals(listOf(2, 2, 1), sizes)
        assertEquals(id, answers.single().submissionId)
        assertEquals(5, answers.single().trashed.size)
    }

    @Test
    fun anAnswerToARequestNotInFlightIsIgnored() {
        val q = SystemTrashQueue()
        q.submit(listOf(item(1)), release = true)
        val request = q.take()!!
        q.answer(request, emptySet())
        assertTrue("answered twice", q.answer(request, setOf("doc/1")).isEmpty())
    }

    @Test
    fun theDialogSplitNamesTheCase() {
        assertTrue(DeleteSplit(bin = 2, permanent = 0).allToBin)
        assertTrue(DeleteSplit(bin = 1, permanent = 1).mixed)
        val none = DeleteSplit.permanentOnly(3)
        assertTrue(!none.allToBin && !none.mixed)
        assertEquals(3, none.total)
    }
}
