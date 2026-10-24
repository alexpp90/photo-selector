package com.phototok.data.repository

import android.content.IntentSender
import com.phototok.data.source.MediaStoreFavourites
import com.phototok.data.source.ResolvedMedia
import io.mockk.mockk

/** A MediaStore that indexes the documents in [index] and keeps their flags in memory. */
class FakeMediaStoreFavourites(
    private val index: Map<String, String>,
    initialFlags: Map<String, Boolean> = emptyMap(),
    override val canSync: Boolean = true,
) : MediaStoreFavourites {
    val flags = initialFlags.toMutableMap()
    val requests = mutableListOf<Pair<List<String>, Boolean>>()

    /** Media URIs whose flag the "system" silently keeps (it still answers OK). */
    val stuck = mutableSetOf<String>()

    override fun canResolve(documentUri: String) = documentUri in index

    override fun resolve(documentUris: List<String>): Map<String, ResolvedMedia> =
        documentUris.mapNotNull { uri ->
            index[uri]?.let { media -> uri to ResolvedMedia(media, if (canSync) flags[media] ?: false else null) }
        }.toMap()

    override fun readFlags(mediaUris: List<String>): Map<String, Boolean> =
        mediaUris.associateWith { flags[it] ?: false }

    override fun favoriteRequest(mediaUris: List<String>, favourite: Boolean): IntentSender {
        requests += mediaUris to favourite
        return mockk(relaxed = true)
    }

    /** What the system activity does when it runs. */
    fun apply(mediaUris: List<String>, favourite: Boolean) {
        for (uri in mediaUris) if (uri !in stuck) flags[uri] = favourite
    }
}
