package com.photoselectortoolbox.viewmodel

import com.photoselectortoolbox.data.model.ImageDimensions
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.domain.usecase.ScanProgress
import com.photoselectortoolbox.domain.usecase.allResults
import com.photoselectortoolbox.viewmodel.ScanUpdates.coalesced
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** REQ-AND-PERF.02, REQ-AND-PERF.04, REQ-AND-PERF.05: how a background pass's results reach the list. */
class ScanUpdatesTest {

    private fun photo(i: Int) = ImageItem("content://t/IMG_$i.jpg", "IMG_$i.jpg", 1L, 1L, "image/jpeg")
    private fun score(i: Int, sharpness: Double = 50.0) = ScanResult(filePath = "content://t/IMG_$i.jpg", sharpnessScore = sharpness)
    private fun progress(done: Int, vararg scored: Int) =
        ScanProgress(done, 5, "IMG_$done.jpg", scored.associate { "content://t/IMG_$it.jpg" to score(it) })

    @Test
    fun `emissions waiting behind a busy collector are folded into one, in order`() = runBlocking {
        val emissions = listOf(progress(0), progress(1, 1), progress(3, 2, 3), progress(5, 4, 5))
        val out = flowOf(*emissions.toTypedArray()).coalesced().toList()

        assertEquals("every result once", emissions.allResults(), out.allResults())
        assertEquals(listOf("content://t/IMG_1.jpg", "content://t/IMG_2.jpg", "content://t/IMG_3.jpg", "content://t/IMG_4.jpg", "content://t/IMG_5.jpg"), out.flatMap { it.scored.keys })
        assertEquals("the counters of the latest", 5, out.last().processed)
        assertEquals("IMG_5.jpg", out.last().currentFile)
    }

    @Test
    fun `an emission nobody waits behind passes through unchanged`() = runBlocking {
        val upstream = Channel<ScanProgress>()
        val seen = mutableListOf<ScanProgress>()
        val job = launch { upstream.consumeAsFlow().coalesced().collect { seen += it } }
        val sent = listOf(progress(0), progress(1, 1), progress(2, 2))
        sent.forEach { upstream.send(it); yield(); yield() }
        upstream.close()
        job.join()
        assertEquals(sent, seen)
    }

    @Test
    fun `scores land by position and an item that already has one keeps it`() {
        val images = (0 until 5).map(::photo).toMutableList()
        images[2] = images[2].copy(scanResult = score(2, sharpness = 90.0))
        val applier = ScanScoreApplier()

        val out = applier.apply(images, mapOf(images[1].uri to score(1), images[2].uri to score(2, 10.0)))

        assertEquals(50.0, out[1].scanResult?.sharpnessScore)
        assertEquals("the score on screen stays", 90.0, out[2].scanResult?.sharpnessScore)
        assertSame(images[0], out[0])
        assertSame(images[2], out[2])
    }

    @Test
    fun `a list that lost photos or was re-ordered is indexed again`() {
        val images = (0 until 6).map(::photo)
        val applier = ScanScoreApplier()
        val first = applier.apply(images, mapOf(images[5].uri to score(5)))

        val withoutFirstTwo = first.drop(2)
        val afterRemoval = applier.apply(withoutFirstTwo, mapOf(images[4].uri to score(4)))
        assertEquals(50.0, afterRemoval[2].scanResult?.sharpnessScore)

        val reversed = afterRemoval.reversed()
        val afterReorder = applier.apply(reversed, mapOf(images[3].uri to score(3)))
        assertEquals(listOf(5, 4, 3, 2).map { photo(it).uri }, afterReorder.map { it.uri })
        assertEquals(listOf(50.0, 50.0, 50.0, null), afterReorder.map { it.scanResult?.sharpnessScore })
    }

    @Test
    fun `a photo that is no longer listed is skipped and an empty batch changes nothing`() {
        val images = (0 until 3).map(::photo)
        val applier = ScanScoreApplier()
        assertSame(images, applier.apply(images, mapOf(photo(9).uri to score(9))))
        assertSame(images, applier.apply(images, emptyMap()))
    }

    @Test
    fun `dimensions land by position and an unchanged photo is kept as it is`() {
        val images = listOf(photo(0), photo(1).copy(imageWidth = 6000, imageHeight = 4000), photo(2))
        val positions = ListPositions()

        val out = positions.applyDimensions(images, mapOf(images[1].uri to ImageDimensions(6000, 4000), images[2].uri to ImageDimensions(4000, 6000)))

        assertSame(images[1], out[1])
        assertEquals(4000 to 6000, out[2].imageWidth to out[2].imageHeight)
        assertSame("nothing changed: the same list", out, positions.applyDimensions(out, mapOf(images[1].uri to ImageDimensions(6000, 4000))))
        assertNull(positions.of(out, photo(7).uri))
    }
}
