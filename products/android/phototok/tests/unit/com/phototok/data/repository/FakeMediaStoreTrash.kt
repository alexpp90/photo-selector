package com.phototok.data.repository

import android.content.IntentSender
import com.phototok.data.source.MediaStoreTrash
import io.mockk.mockk

/** A system bin over the documents in [index] (document URI -> MediaStore item), in memory. */
class FakeMediaStoreTrash(
    private val index: Map<String, String>,
    override val canTrash: Boolean = true,
) : MediaStoreTrash {
    /** The media URIs of every consent request, in order. */
    val requests = mutableListOf<List<String>>()

    /** Items now in the bin; what the system activity did once the user accepted. */
    val trashed = mutableSetOf<String>()

    /** Media URIs whose request MediaProvider refuses outright. */
    val refused = mutableSetOf<String>()

    override fun resolve(documentUris: List<String>): Map<String, String> =
        if (!canTrash) emptyMap() else documentUris.mapNotNull { uri -> index[uri]?.let { uri to it } }.toMap()

    override fun trashRequest(mediaUris: List<String>): IntentSender {
        check(mediaUris.none { it in refused }) { "refused" }
        requests += mediaUris
        return mockk(relaxed = true)
    }

    override fun readTrashed(mediaUris: List<String>): Map<String, Boolean> =
        mediaUris.associateWith { it in trashed }

    /** The user accepts the last request: the system moves its items to the bin. */
    fun acceptLast() {
        trashed += requests.last()
    }
}
