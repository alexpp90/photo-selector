package com.phototok.domain

import com.phototok.data.model.ImageItem

/**
 * Pure ordering logic for the phone-mode feed, extracted from the ViewModel so it can
 * be unit-tested without Android dependencies.
 */
object PhoneFeedOrdering {

    /** Number of look-ahead cards after [currentIndex] kept frozen during progressive shuffle. */
    const val PREFETCH_WINDOW = 2

    data class Result(
        val images: List<ImageItem>,
        /** Index where the portrait section starts, or -1 when there is no split. */
        val portraitSectionStart: Int,
    )

    /**
     * Natural filename comparison (`IMG_2.JPG < IMG_10.JPG`), case-insensitive with
     * exact-case tie-breaker.
     */
    fun compareNatural(a: String, b: String): Int {
        var ia = 0
        var ib = 0
        val na = a.length
        val nb = b.length

        while (ia < na && ib < nb) {
            val ca = a[ia]
            val cb = b[ib]
            if (ca.isDigit() && cb.isDigit()) {
                var za = ia
                while (za < na && a[za] == '0') za++
                var zb = ib
                while (zb < nb && b[zb] == '0') zb++

                var ea = za
                while (ea < na && a[ea].isDigit()) ea++
                var eb = zb
                while (eb < nb && b[eb].isDigit()) eb++

                val lenA = ea - za
                val lenB = eb - zb
                if (lenA != lenB) return lenA.compareTo(lenB)

                for (k in 0 until lenA) {
                    val da = a[za + k]
                    val db = b[zb + k]
                    if (da != db) return da.compareTo(db)
                }

                val rawLenA = ea - ia
                val rawLenB = eb - ib
                if (rawLenA != rawLenB) return rawLenA.compareTo(rawLenB)

                ia = ea
                ib = eb
            } else {
                val lca = ca.lowercaseChar()
                val lcb = cb.lowercaseChar()
                if (lca != lcb) return lca.compareTo(lcb)
                ia++
                ib++
            }
        }
        if (ia != na || ib != nb) return (na - ia).compareTo(nb - ib)
        return a.compareTo(b)
    }

    fun order(
        images: List<ImageItem>,
        randomize: Boolean,
        sortByOrientation: Boolean,
        shuffler: (List<ImageItem>) -> List<ImageItem> = { it.shuffled() },
    ): Result = order(
        images = images,
        mode = FeedOrderMode.fromLegacy(randomize = randomize, sortByOrientation = sortByOrientation),
        seenUris = emptySet(),
        shuffler = shuffler,
    )

    /**
     * Orders [images] for [mode]. Only `SHUFFLE` puts unseen photos ([seenUris]) first; every
     * other mode is exactly its sort, so a photo keeps its position whether or not it was seen
     * and the newest photo is position 1 in a newest-first feed. Where the viewer lands is
     * decided separately (`FolderScanLogic`).
     */
    fun order(
        images: List<ImageItem>,
        mode: FeedOrderMode,
        seenUris: Set<String> = emptySet(),
        shuffler: (List<ImageItem>) -> List<ImageItem> = { it.shuffled() },
    ): Result {
        return when (mode) {
            FeedOrderMode.SHUFFLE -> {
                val shuffled = shuffleUnseenFirst(images, seenUris, shuffler)
                Result(shuffled, -1)
            }
            FeedOrderMode.SHOOTING -> {
                val byShooting = images.sortedWith { a, b ->
                    val cmpTime = a.lastModified.compareTo(b.lastModified)
                    if (cmpTime != 0) cmpTime
                    else {
                        val cmpName = compareNatural(a.fileName, b.fileName)
                        if (cmpName != 0) cmpName else a.uri.compareTo(b.uri)
                    }
                }
                Result(byShooting, -1)
            }
            FeedOrderMode.NEWEST_FIRST -> Result(sortNewestFirst(images), -1)
            FeedOrderMode.ORIENTATION_THEN_NEWEST -> {
                val byDate = sortNewestFirst(images)
                val landscape = byDate.filter { it.isLandscape }
                val portrait = byDate.filter { !it.isLandscape }
                val result = landscape + portrait
                val split = if (portrait.isEmpty()) -1 else landscape.size
                Result(result, split)
            }
        }
    }

    private fun sortNewestFirst(images: List<ImageItem>): List<ImageItem> =
        images.sortedWith(
            compareByDescending<ImageItem> { it.lastModified }
                .thenBy { it.fileName.lowercase() }
                .thenBy { it.uri }
        )

    private fun shuffleUnseenFirst(
        images: List<ImageItem>,
        seenUris: Set<String>,
        shuffler: (List<ImageItem>) -> List<ImageItem>,
    ): List<ImageItem> {
        if (seenUris.isEmpty()) return shuffler(images)
        val (unseen, seen) = images.partition { it.uri !in seenUris }
        val shuffledUnseen = if (unseen.isEmpty()) emptyList() else shuffler(unseen)
        val shuffledSeen = if (seen.isEmpty()) emptyList() else shuffler(seen)
        return shuffledUnseen + shuffledSeen
    }

    /**
     * The subset of [incoming] that is not yet known, identified by URI.
     */
    fun newItems(incoming: List<ImageItem>, knownUris: Set<String>): List<ImageItem> =
        if (knownUris.isEmpty()) incoming else incoming.filter { it.uri !in knownUris }

    /**
     * Append or merge a freshly discovered batch to an already published feed.
     */
    fun appendBatch(
        current: List<ImageItem>,
        fresh: List<ImageItem>,
        randomize: Boolean,
        sortByOrientation: Boolean,
        shuffler: (List<ImageItem>) -> List<ImageItem> = { it.shuffled() },
        reorder: Boolean = false,
        currentIndex: Int = 0,
        seenUris: Set<String> = emptySet(),
    ): Result = appendBatch(
        current = current,
        fresh = fresh,
        mode = FeedOrderMode.fromLegacy(randomize = randomize, sortByOrientation = sortByOrientation),
        shuffler = shuffler,
        reorder = reorder,
        currentIndex = currentIndex,
        seenUris = seenUris,
    )

    fun appendBatch(
        current: List<ImageItem>,
        fresh: List<ImageItem>,
        mode: FeedOrderMode,
        shuffler: (List<ImageItem>) -> List<ImageItem> = { it.shuffled() },
        reorder: Boolean = false,
        currentIndex: Int = 0,
        seenUris: Set<String> = emptySet(),
    ): Result {
        val sortByOrientation = mode.isSortByOrientation
        if (reorder && !mode.isRandomized && fresh.isNotEmpty()) {
            return order(current + fresh, mode = mode, seenUris = seenUris, shuffler = shuffler)
        }
        if (fresh.isEmpty()) {
            return Result(current, portraitSplit(current, sortByOrientation))
        }
        if (current.isEmpty()) {
            return order(fresh, mode = mode, seenUris = seenUris, shuffler = shuffler)
        }
        if (mode == FeedOrderMode.SHUFFLE) {
            val safeIndex = currentIndex.coerceAtLeast(0)
            val frozenCount = (safeIndex + PREFETCH_WINDOW + 1).coerceAtMost(current.size)
            val frozenPrefix = current.subList(0, frozenCount)
            val tailPool = current.subList(frozenCount, current.size) + fresh
            val reshuffledTail = shuffleUnseenFirst(tailPool, seenUris, shuffler)
            return Result(frozenPrefix + reshuffledTail, -1)
        }
        val ordered = order(
            fresh,
            mode = if (mode == FeedOrderMode.SHOOTING) FeedOrderMode.SHOOTING else FeedOrderMode.NEWEST_FIRST,
            seenUris = seenUris,
            shuffler = shuffler,
        ).images
        val merged = current + ordered
        return Result(merged, portraitSplit(merged, sortByOrientation))
    }

    /**
     * Recompute the portrait-section start for an already-ordered list, e.g. after an
     * item was removed or restored. Returns -1 when orientation sorting is off, the
     * list is empty, or there are no portrait images.
     */
    fun portraitSplit(images: List<ImageItem>, sortByOrientation: Boolean): Int {
        if (!sortByOrientation || images.isEmpty()) return -1
        val firstPortrait = images.indexOfFirst { !it.isLandscape }
        return if (firstPortrait >= 0) firstPortrait else -1
    }

    /** Apply the user's file-type filter to a list of images. */
    fun filterByType(images: List<ImageItem>, filter: FileTypeFilter): List<ImageItem> =
        when (filter) {
            FileTypeFilter.ALL -> images
            FileTypeFilter.RAW -> images.filter { PhotoExtensions.isRaw(it.fileName) }
            FileTypeFilter.JPG -> images.filter { PhotoExtensions.isJpeg(it.fileName) }
            FileTypeFilter.ONE_PER_SHOT -> RelatedFiles.collapseOnePerShot(images)
        }
}

