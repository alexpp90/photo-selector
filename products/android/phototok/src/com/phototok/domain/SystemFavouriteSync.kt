package com.phototok.domain

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * Helper for Android 11+ (`API 30+`) `MediaStore` batch user-consent requests:
 * - `MediaStore.createTrashRequest` (`U-18`)
 * - `MediaStore.createDeleteRequest` (`U-18`)
 *
 * Not called by the app yet. Favourites (`U-17`) go through `data.source.MediaStoreFavourites`,
 * which also turns the `.../file/<id>` URI `getMediaUri` returns into the `images|video/media/<id>`
 * URI MediaProvider requires for requests; these builders do not, and need the same before use.
 *
 * Resolves SAF document URIs to `MediaStore` URIs via `MediaStore.getMediaUri` when
 * backed by `com.android.externalstorage.documents`. When a URI is not indexed by
 * `MediaStore` (e.g., removable SD card not scanned by MediaStore, cloud provider, or unit test),
 * falls back gracefully to local-only state / SAF deletion without crashing.
 */
object SystemFavouriteSync {

    /**
     * Resolve SAF tree document URIs to `MediaStore` content URIs where supported.
     */
    fun resolveMediaStoreUris(
        context: Context,
        uris: Collection<String>,
        resolver: (Context, Uri) -> Uri? = ::defaultGetMediaUri,
    ): List<Uri> {
        if (uris.isEmpty()) return emptyList()
        return uris.mapNotNull { raw ->
            try {
                val parsed = Uri.parse(raw)
                when {
                    parsed.authority == MediaStore.AUTHORITY -> parsed
                    else -> resolver(context, parsed)
                }
            } catch (_: Exception) {
                null
            }
        }.distinct()
    }

    private fun defaultGetMediaUri(context: Context, documentUri: Uri): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            MediaStore.getMediaUri(context, documentUri)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Build a batch `MediaStore.createTrashRequest` [IntentSender] on API 30+ (30-day OS Trash),
     * or null when no URIs are MediaStore-backed or the OS is below API 30.
     */
    fun buildTrashRequestIntentSender(
        context: Context,
        uris: Collection<String>,
        trash: Boolean = true,
    ): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val mediaUris = resolveMediaStoreUris(context, uris)
        if (mediaUris.isEmpty()) return null
        return try {
            MediaStore.createTrashRequest(context.contentResolver, mediaUris, trash).intentSender
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Build a batch `MediaStore.createDeleteRequest` [IntentSender] on API 30+ (permanent delete),
     * or null when no URIs are MediaStore-backed or the OS is below API 30.
     */
    fun buildDeleteRequestIntentSender(
        context: Context,
        uris: Collection<String>,
    ): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val mediaUris = resolveMediaStoreUris(context, uris)
        if (mediaUris.isEmpty()) return null
        return try {
            MediaStore.createDeleteRequest(context.contentResolver, mediaUris).intentSender
        } catch (_: Exception) {
            null
        }
    }
}
