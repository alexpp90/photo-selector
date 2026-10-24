package com.phototok.data.source

import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** A document that MediaStore indexes: its request URI and, on Android 11+, its `IS_FAVORITE` flag. */
data class ResolvedMedia(
    val mediaUri: String,
    val isFavourite: Boolean?,
)

/**
 * The MediaStore side of favourites (`U-17`), with zero permissions:
 * - `MediaStore.getMediaUri` (Android 10+) turns a SAF document of the external-storage or Media
 *   provider into its MediaStore item, and MediaProvider copies the caller's read grant onto that
 *   item, so its `IS_FAVORITE` flag can be read back.
 * - `MediaStore.createFavoriteRequest` (Android 11+) sets or clears the flag. Stock MediaProvider
 *   grants favourite requests without a dialog; the `IntentSender` must still be launched.
 */
interface MediaStoreFavourites {
    /** Android 11+: `IS_FAVORITE` exists and can be requested. */
    val canSync: Boolean

    /** Whether [documentUri]'s provider can map it to MediaStore at all (no I/O). */
    fun canResolve(documentUri: String): Boolean

    /** Document URI -> MediaStore item, for the documents MediaStore indexes. Blocking I/O. */
    fun resolve(documentUris: List<String>): Map<String, ResolvedMedia>

    /** Current `IS_FAVORITE` of MediaStore items resolved in this process. Blocking I/O. */
    fun readFlags(mediaUris: List<String>): Map<String, Boolean>

    /** One `createFavoriteRequest` for [mediaUris]. Throws when MediaProvider refuses the list. */
    fun favoriteRequest(mediaUris: List<String>, favourite: Boolean): IntentSender
}

class MediaStoreFavouritesImpl(
    private val context: Context,
) : MediaStoreFavourites {

    /** Request URI -> the URI MediaProvider granted us read access to (`.../file/<id>`). */
    private val grantedUris = ConcurrentHashMap<String, Uri>()

    override val canSync: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    override fun canResolve(documentUri: String): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isResolvableAuthority(Uri.parse(documentUri).authority)

    override fun resolve(documentUris: List<String>): Map<String, ResolvedMedia> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyMap()
        val out = LinkedHashMap<String, ResolvedMedia>()
        for (raw in documentUris) {
            val resolved = resolveOne(Uri.parse(raw)) ?: continue
            out[raw] = resolved
        }
        return out
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun resolveOne(documentUri: Uri): ResolvedMedia? {
        if (!isResolvableAuthority(documentUri.authority)) return null
        val granted = try {
            MediaStore.getMediaUri(context, documentUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Expected for files MediaStore does not index (unscanned card, .nomedia folder):
            // the favourite stays in Jackpix.
            Log.d(TAG, "Not in MediaStore: $documentUri (${e.javaClass.simpleName})")
            null
        } ?: return null
        val row = query(granted, withFlag = canSync) ?: return null
        val requestUri = requestUriFor(row.mediaType, row.id) ?: return null
        grantedUris[requestUri] = granted
        return ResolvedMedia(mediaUri = requestUri, isFavourite = row.isFavourite)
    }

    override fun readFlags(mediaUris: List<String>): Map<String, Boolean> {
        if (!canSync) return emptyMap()
        val out = LinkedHashMap<String, Boolean>()
        for (mediaUri in mediaUris) {
            // Without a grant from this process (e.g. resolved before a restart) the item is
            // readable only if Jackpix owns it; otherwise the query finds no row and it is skipped.
            val readable = grantedUris[mediaUri] ?: Uri.parse(mediaUri)
            readFlag(readable)?.let { out[mediaUri] = it }
        }
        return out
    }

    override fun favoriteRequest(mediaUris: List<String>, favourite: Boolean): IntentSender {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { "createFavoriteRequest needs Android 11" }
        return MediaStore.createFavoriteRequest(
            context.contentResolver,
            mediaUris.map(Uri::parse),
            favourite,
        ).intentSender
    }

    private class Row(val id: Long, val mediaType: Int, val isFavourite: Boolean?)

    /** `IS_FAVORITE` alone: the `images|video/media` collections reject `media_type`. */
    private fun readFlag(uri: Uri): Boolean? = try {
        context.contentResolver.query(uri, arrayOf(IS_FAVORITE), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getInt(0) != 0 else null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "MediaStore query failed for $uri", e)
        null
    }

    /** One row of the Files table (`getMediaUri`'s `.../file/<id>`), which has `media_type`. */
    private fun query(uri: Uri, withFlag: Boolean): Row? {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.Files.FileColumns.MEDIA_TYPE)
            if (withFlag) add(IS_FAVORITE)
        }.toTypedArray()
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                Row(
                    id = c.getLong(0),
                    mediaType = c.getInt(1),
                    isFavourite = if (withFlag) c.getInt(2) != 0 else null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore query failed for $uri", e)
            null
        }
    }

    companion object {
        private const val TAG = "MediaStoreFavourites"

        /** `MediaStore.MediaColumns.IS_FAVORITE`, inlined so the projection compiles below API 30. */
        private const val IS_FAVORITE = "is_favorite"

        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private const val MEDIA_DOCUMENTS_AUTHORITY = "com.android.providers.media.documents"

        /** The only providers `MediaStore.getMediaUri` serves; anything else throws. */
        fun isResolvableAuthority(authority: String?): Boolean =
            authority == EXTERNAL_STORAGE_AUTHORITY || authority == MEDIA_DOCUMENTS_AUTHORITY

        /**
         * `createFavoriteRequest` accepts only `images|video/media/<id>`; `getMediaUri` returns
         * `.../file/<id>` for external-storage documents. One id is one row in every collection,
         * so the `external` volume form identifies the item whichever volume and provider it
         * came from. Null for anything that is not an image or a video.
         */
        fun requestUriFor(mediaType: Int, id: Long): String? {
            val collection = when (mediaType) {
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else -> return null
            }
            return ContentUris.withAppendedId(collection, id).toString()
        }
    }
}
