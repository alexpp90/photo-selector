package com.phototok.data.source

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * The system bin of Android 11+ (`MediaStore.createTrashRequest`), with zero permissions: a SAF
 * document of the phone's storage is mapped to its MediaStore item (`MediaStore.getMediaUri`, which
 * also grants this process read access to it), the user consents in the system dialog, and the
 * item's `IS_TRASHED` is read back afterwards. Files MediaStore does not index have no bin.
 */
interface MediaStoreTrash {
    /** Android 11+: the bin exists and can be requested. */
    val canTrash: Boolean

    /** Document URI -> `images|video/media/<id>` for the documents the bin can take. Blocking I/O. */
    fun resolve(documentUris: List<String>): Map<String, String>

    /** One `createTrashRequest` for [mediaUris]. Throws when MediaProvider refuses the list. */
    fun trashRequest(mediaUris: List<String>): IntentSender

    /**
     * `IS_TRASHED` of [mediaUris] after the user answered; an item without a row is absent from
     * the map (it is gone). Blocking I/O.
     */
    fun readTrashed(mediaUris: List<String>): Map<String, Boolean>
}

class MediaStoreTrashImpl(
    private val context: Context,
) : MediaStoreTrash {

    /** Request URI -> the URI MediaProvider granted us read access to (`.../file/<id>`). */
    private val grantedUris = ConcurrentHashMap<String, Uri>()

    override val canTrash: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    override fun resolve(documentUris: List<String>): Map<String, String> {
        if (!canTrash) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (raw in documentUris) {
            resolveOne(Uri.parse(raw))?.let { out[raw] = it }
        }
        return out
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun resolveOne(documentUri: Uri): String? {
        if (!MediaStoreFavouritesImpl.isResolvableAuthority(documentUri.authority)) return null
        val granted = try {
            MediaStore.getMediaUri(context, documentUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not indexed (sidecar, .nomedia folder, unscanned file): no bin, deleted permanently.
            Log.d(TAG, "Not in MediaStore: $documentUri (${e.javaClass.simpleName})")
            null
        } ?: return null
        val (id, mediaType) = try {
            context.contentResolver.query(
                granted,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE),
                null,
                null,
                null,
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) to c.getInt(1) else null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore query failed for $granted", e)
            null
        } ?: return null
        // createTrashRequest takes images|video/media/<id> only, never the Files URI.
        val requestUri = MediaStoreFavouritesImpl.requestUriFor(mediaType, id) ?: return null
        grantedUris[requestUri] = granted
        return requestUri
    }

    override fun trashRequest(mediaUris: List<String>): IntentSender {
        check(canTrash) { "createTrashRequest needs Android 11" }
        return MediaStore.createTrashRequest(context.contentResolver, mediaUris.map(Uri::parse), true).intentSender
    }

    override fun readTrashed(mediaUris: List<String>): Map<String, Boolean> {
        if (!canTrash) return emptyMap()
        val out = LinkedHashMap<String, Boolean>()
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
        for (mediaUri in mediaUris) {
            val readable = grantedUris[mediaUri] ?: Uri.parse(mediaUri)
            try {
                context.contentResolver.query(readable, arrayOf(MediaStore.MediaColumns.IS_TRASHED), args, null)
                    ?.use { c -> if (c.moveToFirst()) out[mediaUri] = c.getInt(0) != 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "IS_TRASHED read failed for $mediaUri", e)
            }
        }
        return out
    }

    private companion object {
        const val TAG = "MediaStoreTrash"
    }
}
