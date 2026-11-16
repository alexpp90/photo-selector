package com.photoselectortoolbox.viewmodel

import com.photoselectortoolbox.data.model.ImageDimensions
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.domain.session.ScoreMerge
import com.photoselectortoolbox.domain.usecase.ScanProgress
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn

/**
 * How a background pass's results reach the selector's list (REQ-AND-PERF.02, .04, .05, .10).
 *
 * A new list built by looking every photo up is a pass over the whole folder. The scan emits once
 * per photo when each finishes on its own, and the dimension backfill once per 24 photos: one pass
 * per emission made a scan of N photos N passes over N on the main thread (1.4–1.5 s at 10,000
 * photos, ratio 15–17 against 2,500) and the backfill N/24 passes (an open of 10,000 photos 150–170
 * ms, ratio 8–10; `SelectorViewModelScaleTest`). Two things bound it:
 *
 * - [coalesced]: whatever arrived while the collector was busy is applied in one update, so the
 *   list is rebuilt at most once per wake-up of the collector, not once per photo.
 * - [ListPositions]: an update writes only the positions of the photos it carries, found through an
 *   index of the list.
 */
internal object ScanUpdates {

    /**
     * This progress with every emission that is already waiting folded into one: [ScanProgress.scored]
     * merged in order, the counters and the file name of the latest. An emission nobody is waiting
     * behind passes through unchanged, so a scan the main thread keeps up with looks as before.
     */
    fun Flow<ScanProgress>.coalesced(): Flow<ScanProgress> = flow {
        coroutineScope {
            val waiting = this@coalesced.buffer(Channel.UNLIMITED).produceIn(this)
            for (first in waiting) {
                var latest = first
                var scored: LinkedHashMap<String, ScanResult>? = null
                while (true) {
                    val next = waiting.tryReceive().getOrNull() ?: break
                    val merged = scored ?: LinkedHashMap(latest.scored).also { scored = it }
                    merged.putAll(next.scored)
                    latest = next
                }
                emit(scored?.let { latest.copy(scored = it) } ?: latest)
            }
        }
    }
}

/**
 * The positions of a list's photos by URI, reused while the list keeps its order. EXIF, dimensions,
 * scores and ratings replace items in place and discovery appends, so an index holds across them;
 * each position is checked against the photo's URI before it is used, and a list that was
 * re-ordered or lost photos is indexed again, once. Not thread-safe: one per pass.
 */
internal class ListPositions {
    private var positions = HashMap<String, Int>()

    /** The list [positions] was built for; a miss on it means the photo is not listed. */
    private var indexed: List<ImageItem>? = null

    /** Where [uri] is in [images], or null when it is not listed. */
    fun of(images: List<ImageItem>, uri: String): Int? {
        val known = positions[uri]
        if (known != null && known < images.size && images[known].uri == uri) return known
        if (indexed === images) return null
        positions = HashMap<String, Int>(images.size * 2).apply { images.forEachIndexed { i, image -> put(image.uri, i) } }
        indexed = images
        return positions[uri]
    }

    /**
     * [images] with the photo of each key of [values] replaced by [change] of it (the same item for
     * no change); [images] itself when nothing changed. Only the listed photos of [values] are read.
     */
    fun <V> replace(images: List<ImageItem>, values: Map<String, V>, change: (List<ImageItem>) -> List<ImageItem>): List<ImageItem> {
        if (values.isEmpty() || images.isEmpty()) return images
        val at = ArrayList<Int>(values.size)
        for (uri in values.keys) of(images, uri)?.let(at::add)
        if (at.isEmpty()) return images
        val hits = at.map(images::get)
        val changed = change(hits)
        var out: ArrayList<ImageItem>? = null
        for (i in at.indices) {
            if (changed[i] !== hits[i]) (out ?: ArrayList(images).also { out = it })[at[i]] = changed[i]
        }
        val result = out ?: return images
        // Same order as [images]: the index holds for the new list as well.
        if (indexed === images) indexed = result
        return result
    }
}

/** A scan's scores applied by position, with [ScoreMerge]'s rule (the score alone, never the item). */
internal class ScanScoreApplier {
    private val positions = ListPositions()

    fun apply(images: List<ImageItem>, scores: Map<String, ScanResult>): List<ImageItem> =
        positions.replace(images, scores) { hits -> ScoreMerge.apply(hits, scores) }
}

/** Known header sizes applied by position: width and height alone, an unchanged photo kept as it is. */
internal fun ListPositions.applyDimensions(images: List<ImageItem>, known: Map<String, ImageDimensions>): List<ImageItem> =
    replace(images, known) { hits ->
        hits.map { image ->
            val dims = known.getValue(image.uri)
            if (image.imageWidth == dims.width && image.imageHeight == dims.height) image else image.copy(imageWidth = dims.width, imageHeight = dims.height)
        }
    }
