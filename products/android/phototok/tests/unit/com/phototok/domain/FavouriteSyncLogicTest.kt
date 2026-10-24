package com.phototok.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavouriteSyncLogicTest {

    private val tree = "content://com.android.externalstorage.documents/tree/primary%3ADCIM"
    private val cameraTree = "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera"
    private fun doc(treeUri: String, name: String) =
        "$treeUri/document/primary%3ADCIM%2FCamera%2F$name"

    private val jpg = doc(tree, "IMG_1.JPG")
    private val raw = doc(tree, "IMG_1.DNG")
    private val jpgMedia = "content://media/external/images/media/11"
    private val rawMedia = "content://media/external/images/media/12"

    private fun resolved() = FavouriteSyncLogic.attachMediaUris(
        FavouritesSnapshot(),
        mapOf(jpg to jpgMedia, raw to rawMedia),
    )

    @Test
    fun `document key drops the tree so two trees match the same file`() {
        val viaDcim = doc(tree, "IMG_1.JPG")
        val viaCamera = doc(cameraTree, "IMG_1.JPG")
        assertEquals(FavouriteKeys.documentKey(viaDcim), FavouriteKeys.documentKey(viaCamera))
        assertEquals(
            "content://com.android.externalstorage.documents/document/primary%3ADCIM%2FCamera%2FIMG_1.JPG",
            FavouriteKeys.documentKey(viaDcim),
        )
        assertEquals("content://media/external/images/media/1", FavouriteKeys.documentKey("content://media/external/images/media/1"))

        val fav = FavouriteSyncLogic.setFavourite(FavouritesSnapshot(), listOf(viaDcim), favourite = true)
        assertTrue(fav.isFavourite(viaCamera))
    }

    @Test
    fun `favouriting a resolved photo and its companion queues both with the wanted flag`() {
        val s = FavouriteSyncLogic.setFavourite(resolved(), listOf(jpg, raw), favourite = true)
        assertEquals(setOf(jpgMedia, rawMedia), s.favourites)
        assertEquals(mapOf(jpgMedia to true, rawMedia to true), s.pending)
        assertTrue(s.isFavourite(raw))
        assertFalse("not synced before the system confirms", s.isSynced(jpg))
    }

    @Test
    fun `a favourite of an unresolved document is queued once it resolves`() {
        val s0 = FavouriteSyncLogic.setFavourite(FavouritesSnapshot(), listOf(jpg), favourite = true)
        assertTrue(s0.pending.isEmpty())
        assertEquals(setOf(FavouriteKeys.documentKey(jpg)), s0.favourites)

        val s1 = FavouriteSyncLogic.attachMediaUris(s0, mapOf(jpg to jpgMedia))
        assertEquals(setOf(jpgMedia), s1.favourites)
        assertEquals(mapOf(jpgMedia to true), s1.pending)
        assertTrue(s1.isFavourite(jpg))
    }

    @Test
    fun `the same MediaStore item from another provider is recognised`() {
        val synced = FavouriteSyncLogic.setFavourite(resolved(), listOf(jpg), favourite = true)
        val mediaDoc = "content://com.android.providers.media.documents/document/image%3A11"
        assertFalse(synced.isFavourite(mediaDoc))
        val s = FavouriteSyncLogic.attachMediaUris(synced, mapOf(mediaDoc to jpgMedia))
        assertTrue(s.isFavourite(mediaDoc))
    }

    @Test
    fun `un-favouriting before the sync drops the queued change`() {
        val fav = FavouriteSyncLogic.setFavourite(resolved(), listOf(jpg), favourite = true)
        val unfav = FavouriteSyncLogic.setFavourite(fav, listOf(jpg), favourite = false)
        assertTrue(unfav.favourites.isEmpty())
        assertTrue("nothing to tell the system: it never had the flag", unfav.pending.isEmpty())
    }

    @Test
    fun `un-favouriting after the sync queues a clear (the old queue dropped it)`() {
        val fav = FavouriteSyncLogic.setFavourite(resolved(), listOf(jpg, raw), favourite = true)
        val batch = FavouriteSyncLogic.batches(fav).single()
        val synced = FavouriteSyncLogic.applyResult(fav, batch, accepted = true)
        assertTrue(synced.pending.isEmpty())
        assertTrue(synced.isSynced(jpg))

        val unfav = FavouriteSyncLogic.setFavourite(synced, listOf(jpg, raw), favourite = false)
        assertEquals(mapOf(jpgMedia to false, rawMedia to false), unfav.pending)
        assertEquals(listOf(FavouriteSyncBatch(listOf(jpgMedia, rawMedia), favourite = false)), FavouriteSyncLogic.batches(unfav))

        val cleared = FavouriteSyncLogic.applyResult(unfav, FavouriteSyncLogic.batches(unfav).single(), accepted = true)
        assertTrue(cleared.pending.isEmpty())
        assertTrue(cleared.systemFavourites.isEmpty())
    }

    @Test
    fun `a declined request keeps everything queued for the next flush`() {
        val fav = FavouriteSyncLogic.setFavourite(resolved(), listOf(jpg), favourite = true)
        val batch = FavouriteSyncLogic.batches(fav).single()
        val declined = FavouriteSyncLogic.applyResult(fav, batch, accepted = false)
        assertEquals(fav, declined)
        assertEquals(listOf(batch), FavouriteSyncLogic.batches(declined))
    }

    @Test
    fun `a change made while the request was out stays queued after it is accepted`() {
        val fav = FavouriteSyncLogic.setFavourite(resolved(), listOf(jpg), favourite = true)
        val batch = FavouriteSyncLogic.batches(fav).single()
        val changedMeanwhile = FavouriteSyncLogic.setFavourite(fav, listOf(jpg), favourite = false)
        val after = FavouriteSyncLogic.applyResult(changedMeanwhile, batch, accepted = true)
        assertEquals("the system has the flag now, the user wants it gone", mapOf(jpgMedia to false), after.pending)
    }

    @Test
    fun `batches send additions first and split long queues`() {
        val many = (1..1201).associate { "content://media/external/images/media/$it" to (it % 3 != 0) }
        val s = FavouritesSnapshot(
            favourites = many.filterValues { it }.keys,
            systemFavourites = many.filterValues { !it }.keys,
            pending = many,
        )
        val batches = FavouriteSyncLogic.batches(s, maxBatch = FavouriteSyncLogic.MAX_BATCH)
        assertEquals(listOf(true, true, false), batches.map { it.favourite })
        assertEquals(listOf(500, 301, 400), batches.map { it.mediaUris.size })
        assertEquals(1201, batches.flatMap { it.mediaUris }.toSet().size)
    }

    @Test
    fun `read-back adopts a favourite set in another app`() {
        val s = FavouriteSyncLogic.mergeSystemFlags(resolved(), mapOf(jpgMedia to true))
        assertTrue(s.isFavourite(jpg))
        assertTrue(s.isSynced(jpg))
        assertTrue(s.pending.isEmpty())
    }

    @Test
    fun `read-back adopts an un-favourite from another app when nothing is queued`() {
        val fav = FavouriteSyncLogic.setFavourite(resolved(), listOf(jpg), favourite = true)
        val synced = FavouriteSyncLogic.applyResult(fav, FavouriteSyncLogic.batches(fav).single(), accepted = true)
        val s = FavouriteSyncLogic.mergeSystemFlags(synced, mapOf(jpgMedia to false))
        assertFalse(s.isFavourite(jpg))
        assertTrue(s.pending.isEmpty())
    }

    @Test
    fun `read-back never overrides a change still queued in Jackpix`() {
        // Synced, then un-favourited in Jackpix; the gallery still says favourite.
        val fav = FavouriteSyncLogic.setFavourite(resolved(), listOf(jpg), favourite = true)
        val synced = FavouriteSyncLogic.applyResult(fav, FavouriteSyncLogic.batches(fav).single(), accepted = true)
        val unfav = FavouriteSyncLogic.setFavourite(synced, listOf(jpg), favourite = false)
        val s = FavouriteSyncLogic.mergeSystemFlags(unfav, mapOf(jpgMedia to true))
        assertFalse(s.isFavourite(jpg))
        assertEquals(mapOf(jpgMedia to false), s.pending)
    }

    @Test
    fun `a Jackpix favourite the system never saw is pushed, not dropped`() {
        val local = FavouriteSyncLogic.setFavourite(FavouritesSnapshot(), listOf(jpg), favourite = true)
        val attached = FavouriteSyncLogic.attachMediaUris(local, mapOf(jpg to jpgMedia))
        val s = FavouriteSyncLogic.mergeSystemFlags(attached, mapOf(jpgMedia to false))
        assertTrue(s.isFavourite(jpg))
        assertEquals(mapOf(jpgMedia to true), s.pending)
    }

    @Test
    fun `read-back agreeing with a queued change settles it`() {
        val fav = FavouriteSyncLogic.setFavourite(resolved(), listOf(jpg), favourite = true)
        val s = FavouriteSyncLogic.mergeSystemFlags(fav, mapOf(jpgMedia to true))
        assertTrue(s.pending.isEmpty())
        assertTrue(s.isSynced(jpg))
    }

    @Test
    fun `a document key favourite is never queued`() {
        val sd = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3ADCIM/document/1A2B-3C4D%3ADCIM%2FA.JPG"
        val s = FavouriteSyncLogic.setFavourite(FavouritesSnapshot(), listOf(sd), favourite = true)
        assertTrue(s.isFavourite(sd))
        assertFalse(s.isSynced(sd))
        assertTrue(FavouriteSyncLogic.batches(s).isEmpty())
    }
}
