package com.phototok.data.repository

import com.photoselector.core.Requirement
import com.photoselector.core.storage.SourceKind
import com.phototok.domain.TrashItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The system bin's consent requests ([USER 2026-11-12] decision #13): which files go to the bin,
 * one held request per finalisation until an Activity launches it, the read-back after an OK, and
 * the notice with Retry after a decline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Requirement("REQ-TOK-GESTURE.03")
class SystemTrashRepositoryTest {

    private val phoneJpg = "content://com.android.externalstorage.documents/tree/primary%3ADCIM/document/primary%3ADCIM%2FIMG_1.jpg"
    private val phoneXmp = "content://com.android.externalstorage.documents/tree/primary%3ADCIM/document/primary%3ADCIM%2FIMG_1.xmp"
    private val mediaJpg = "content://media/external/images/media/41"
    private val gateway = FakeMediaStoreTrash(index = mapOf(phoneJpg to mediaJpg))
    private val stats = StatsRepository()
    private val repo = SystemTrashRepository(gateway, stats, UnconfinedTestDispatcher())

    private fun item() = TrashItem(phoneJpg, mediaJpg, bytes = 4_000_000)

    @Test
    fun onlyIndexedPhoneFilesGoToTheBin() = runTest {
        assertEquals(mapOf(phoneJpg to mediaJpg), repo.binTargets(listOf(phoneJpg, phoneXmp), SourceKind.PHONE_LIBRARY))
        for (kind in listOf(SourceKind.REMOVABLE, SourceKind.CAMERA_MTP, SourceKind.CLOUD_OTHER)) {
            assertTrue("$kind has no bin", repo.binTargets(listOf(phoneJpg), kind).isEmpty())
            assertFalse(repo.offersBin(kind))
        }
        val split = repo.split(listOf(phoneJpg, phoneXmp), SourceKind.PHONE_LIBRARY)
        assertEquals(1, split.bin)
        assertEquals(1, split.permanent)
    }

    @Test
    fun androidTenHasNoBin() = runTest {
        val old = SystemTrashRepository(FakeMediaStoreTrash(mapOf(phoneJpg to mediaJpg), canTrash = false), stats, UnconfinedTestDispatcher())
        assertFalse(old.offersBin(SourceKind.PHONE_LIBRARY))
        assertEquals(0, old.split(listOf(phoneJpg), SourceKind.PHONE_LIBRARY).bin)
    }

    @Test
    fun aRequestIsHeldUntilAnActivityLaunchesIt_thenAcceptedByReadBack() = runTest {
        val answer = repo.submit(listOf(item()))
        val request = repo.state.value.request
        assertNotNull("published for whichever Activity collects it", request)
        assertEquals(listOf(listOf(mediaJpg)), gateway.requests)
        repo.onLaunched(request!!)
        assertNull("launched once, not again on recomposition", repo.state.value.request)

        gateway.acceptLast()
        repo.onResult(accepted = true)
        val done = answer.await()
        assertEquals(listOf(phoneJpg), done.trashed.map { it.documentUri })
        assertNull(repo.state.value.notice)
        assertEquals("a file in the bin is counted, but frees nothing yet", 0L, stats.stats.value.sessionBytesFreedPhone)
        assertEquals(1, stats.stats.value.sessionDeletesCommittedCount)
    }

    @Test
    fun okWithoutTheFlagSetKeepsThePhoto() = runTest {
        val answer = repo.submit(listOf(item()))
        repo.onLaunched(repo.state.value.request!!)
        // The result code says OK, IS_TRASHED says 0: act on the column.
        repo.onResult(accepted = true)
        assertTrue(answer.await().trashed.isEmpty())
        assertEquals(1, repo.state.value.notice?.items?.size)
    }

    @Test
    fun declineKeepsThePhotosAndRetryReissuesTheSameFiles() = runTest {
        val answer = repo.submit(listOf(item()))
        repo.onLaunched(repo.state.value.request!!)
        repo.onResult(accepted = false)
        val kept = answer.await()
        assertTrue(kept.trashed.isEmpty())
        val notice = repo.state.value.notice!!
        assertEquals(listOf(phoneJpg), notice.items.map { it.documentUri })

        repo.retry(notice)
        assertNull(repo.state.value.notice)
        assertEquals("Retry asks for the same files", listOf(listOf(mediaJpg), listOf(mediaJpg)), gateway.requests)
        assertNotNull(repo.state.value.request)
    }

    @Test
    fun aRefusedRequestDeletesNothingAndSaysSo() = runTest {
        gateway.refused += mediaJpg
        val answer = repo.submit(listOf(item()))
        assertNull(repo.state.value.request)
        assertTrue(answer.await().trashed.isEmpty())
        assertEquals(1, repo.state.value.notice?.items?.size)
    }

    @Test
    fun aHeldEvictionGoesOutWithTheNextRelease() = runTest {
        repo.submit(listOf(item()), release = false)
        assertNull("no dialog for the swipe that evicted it", repo.state.value.request)
        repo.release()
        assertNotNull(repo.state.value.request)
    }

    @Test
    fun aRetryThatReachesTheBinIsAnnouncedToOpenFeeds() = runTest {
        val seen = mutableListOf<Set<String>>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.trashed.collect { seen += it } }
        repo.submit(listOf(item()))
        repo.onLaunched(repo.state.value.request!!)
        repo.onResult(accepted = false)
        repo.retry(repo.state.value.notice!!)
        repo.onLaunched(repo.state.value.request!!)
        gateway.acceptLast()
        repo.onResult(accepted = true)
        assertEquals(listOf(setOf(phoneJpg)), seen)
        job.cancel()
    }
}
