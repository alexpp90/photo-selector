package com.phototok.data.repository

import com.phototok.data.model.ImageItem
import com.phototok.data.source.MediaStoreFavourites
import com.phototok.domain.FavouriteKeys
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavouritesRepositoryTest {

    private lateinit var dir: File
    private val tree = "content://com.android.externalstorage.documents/tree/primary%3ADCIM"
    private val jpg = "$tree/document/primary%3ADCIM%2FIMG_1.JPG"
    private val raw = "$tree/document/primary%3ADCIM%2FIMG_1.DNG"
    private val jpgMedia = "content://media/external/images/media/1"
    private val rawMedia = "content://media/external/images/media/2"

    private fun item(uri: String) =
        ImageItem(uri = uri, fileName = uri.substringAfterLast("%2F"), fileSize = 1, lastModified = 1, mimeType = null)

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("favs_test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun repo(file: File, gateway: MediaStoreFavourites? = null) =
        FavouritesRepository(FavouritesStore(storeFile = file), gateway).also { it.ioDispatcher = Dispatchers.Unconfined }

    @Test
    fun `a version 1 store loads without losing an entry and re-queues its phone favourites`() = runTest {
        val file = File(dir, "favourites_store.json")
        val sd = "content://com.android.externalstorage.documents/tree/1A2B%3ADCIM/document/1A2B%3ADCIM%2FSD.JPG"
        file.writeText("F\t$jpg\nF\t$raw\nF\t$sd\nU\t$jpg\nU\t$raw\n")
        val gateway = FakeMediaStoreFavourites(index = mapOf(jpg to jpgMedia, raw to rawMedia))
        val r = repo(file, gateway)

        assertTrue(r.isFavourite(jpg))
        assertTrue(r.isFavourite(raw))
        assertTrue(r.isFavourite(sd))
        // A different tree onto the same files still finds them.
        assertTrue(r.isFavourite("content://com.android.externalstorage.documents/tree/primary%3A/document/primary%3ADCIM%2FIMG_1.JPG"))

        r.indexFromSystem(listOf(item(jpg), item(raw), item(sd)))
        assertEquals(mapOf(jpgMedia to true, rawMedia to true), r.snapshot.value.pending)
        assertTrue("the card photo stays a Jackpix favourite", r.isFavourite(sd))

        val reloaded = repo(file)
        assertEquals(r.snapshot.value.favourites, reloaded.snapshot.value.favourites)
        assertEquals(r.snapshot.value.pending, reloaded.snapshot.value.pending)
        assertTrue(reloaded.isFavourite(jpg))
        assertTrue(file.readText().startsWith("V\t2\n"))
    }

    @Test
    fun `flush sends the queue, confirms it by reading back, and persists the state`() = runTest {
        val file = File(dir, "favourites_store.json")
        val gateway = FakeMediaStoreFavourites(index = mapOf(jpg to jpgMedia, raw to rawMedia))
        val r = repo(file, gateway)
        r.indexFromSystem(listOf(item(jpg), item(raw)))
        r.setFavourite(jpg, favourite = true, companionUris = listOf(raw))
        assertEquals(2, r.sendablePendingCount())

        val request = r.nextSyncRequest()!!
        assertEquals(listOf(jpgMedia, rawMedia), request.batch.mediaUris)
        assertTrue(request.batch.favourite)
        gateway.apply(request.batch.mediaUris, request.batch.favourite)
        r.onSyncResult(request, accepted = true)

        assertEquals(0, r.sendablePendingCount())
        assertNull(r.nextSyncRequest())
        assertTrue(r.snapshot.value.isSynced(jpg))
        assertTrue(repo(file).snapshot.value.isSynced(raw))
    }

    @Test
    fun `un-favouriting a synced photo sends a clear for it and its companion`() = runTest {
        val gateway = FakeMediaStoreFavourites(
            index = mapOf(jpg to jpgMedia, raw to rawMedia),
            initialFlags = mapOf(jpgMedia to true, rawMedia to true),
        )
        val r = repo(File(dir, "s"), gateway)
        r.indexFromSystem(listOf(item(jpg), item(raw)))
        assertTrue(r.isFavourite(jpg))

        r.setFavourite(jpg, favourite = false, companionUris = listOf(raw))
        val request = r.nextSyncRequest()!!
        assertEquals(listOf(jpgMedia, rawMedia) to false, request.batch.mediaUris to request.batch.favourite)
        gateway.apply(request.batch.mediaUris, false)
        r.onSyncResult(request, accepted = true)
        assertEquals(mapOf(jpgMedia to false, rawMedia to false), gateway.flags)
        assertTrue(r.snapshot.value.pending.isEmpty())
    }

    @Test
    fun `a declined request leaves the queue and the next request carries it again`() = runTest {
        val gateway = FakeMediaStoreFavourites(index = mapOf(jpg to jpgMedia))
        val r = repo(File(dir, "s"), gateway)
        r.indexFromSystem(listOf(item(jpg)))
        r.setFavourite(jpg, favourite = true)
        val first = r.nextSyncRequest()!!
        r.onSyncResult(first, accepted = false)
        assertEquals(1, r.sendablePendingCount())
        assertEquals(first.batch, r.nextSyncRequest()!!.batch)
    }

    @Test
    fun `an OK whose flag did not change stays queued but is not sent again this session`() = runTest {
        val gateway = FakeMediaStoreFavourites(index = mapOf(jpg to jpgMedia, raw to rawMedia))
        gateway.stuck += rawMedia
        val r = repo(File(dir, "s"), gateway)
        r.indexFromSystem(listOf(item(jpg), item(raw)))
        r.setFavourite(jpg, favourite = true, companionUris = listOf(raw))
        val request = r.nextSyncRequest()!!
        gateway.apply(request.batch.mediaUris, true)
        r.onSyncResult(request, accepted = true)

        assertTrue(r.snapshot.value.isSynced(jpg))
        assertFalse(r.snapshot.value.isSynced(raw))
        assertEquals(mapOf(rawMedia to true), r.snapshot.value.pending)
        assertNull(r.nextSyncRequest())
    }

    @Test
    fun `read-back shows a favourite set in another app`() = runTest {
        val gateway = FakeMediaStoreFavourites(index = mapOf(jpg to jpgMedia), initialFlags = mapOf(jpgMedia to true))
        val r = repo(File(dir, "s"), gateway)
        assertFalse(r.isFavourite(jpg))
        r.indexFromSystem(listOf(item(jpg)))
        assertTrue(r.isFavourite(jpg))
        assertTrue(r.snapshot.value.isSynced(jpg))
        assertNull(r.nextSyncRequest())
    }

    @Test
    fun `below Android 11 nothing is sent and favourites stay Jackpix-only`() = runTest {
        val gateway = FakeMediaStoreFavourites(index = mapOf(jpg to jpgMedia), canSync = false)
        val r = repo(File(dir, "s"), gateway)
        r.indexFromSystem(listOf(item(jpg)))
        r.setFavourite(jpg, favourite = true)
        assertTrue(r.isFavourite(jpg))
        assertFalse(r.snapshot.value.isSynced(jpg))
        assertEquals(0, r.sendablePendingCount())
        assertNull(r.nextSyncRequest())
    }

    @Test
    fun `toggle marks primary and companion under one identity`() {
        val r = repo(File(dir, "s"))
        assertTrue(r.toggleFavourite(jpg, companionUris = listOf(raw)))
        assertEquals(setOf(FavouriteKeys.documentKey(jpg), FavouriteKeys.documentKey(raw)), r.snapshot.value.favourites)
        assertFalse(r.toggleFavourite(jpg, companionUris = listOf(raw)))
        assertTrue(r.snapshot.value.favourites.isEmpty())
    }
}
