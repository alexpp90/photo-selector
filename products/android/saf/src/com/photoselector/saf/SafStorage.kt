package com.photoselector.saf

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile

/**
 * Everything the transfer engine needs from the platform: the resolver and the two
 * `DocumentFile` factories. The product hands one in per call — Android Desktop builds it
 * from the call's `Context`, PhotoTok holds one for its application context — so :saf never
 * keeps a `Context` of its own.
 */
interface SafStorage {
    val contentResolver: ContentResolver

    /** `DocumentFile.fromTreeUri`: the folder a tree (or a document inside one) names. */
    fun treeDocument(uri: Uri): DocumentFile?

    /** `DocumentFile.fromSingleUri`: one document. */
    fun singleDocument(uri: Uri): DocumentFile?

    companion object {
        fun of(context: Context): SafStorage = ContextSafStorage(context)
    }
}

private class ContextSafStorage(private val context: Context) : SafStorage {
    override val contentResolver: ContentResolver get() = context.contentResolver
    override fun treeDocument(uri: Uri): DocumentFile? = DocumentFile.fromTreeUri(context, uri)
    override fun singleDocument(uri: Uri): DocumentFile? = DocumentFile.fromSingleUri(context, uri)
}

/**
 * Collaborator for atomic SAF moves (`DocumentsContract.moveDocument`),
 * extracted so unit tests can inspect or override the provider move decision.
 */
interface SafDocumentMover {
    fun canMoveDocument(
        contentResolver: ContentResolver,
        sourceUri: Uri,
        sourceParentUri: Uri?,
        targetFolderUri: Uri,
    ): Boolean

    fun moveDocument(
        contentResolver: ContentResolver,
        sourceUri: Uri,
        sourceParentUri: Uri,
        targetFolderUri: Uri,
    ): Uri?
}

object DefaultSafDocumentMover : SafDocumentMover {
    private const val TAG = "DefaultSafDocumentMover"

    override fun canMoveDocument(
        contentResolver: ContentResolver,
        sourceUri: Uri,
        sourceParentUri: Uri?,
        targetFolderUri: Uri,
    ): Boolean {
        if (sourceParentUri == null) return false
        val authority = sourceUri.authority ?: return false
        if (authority != targetFolderUri.authority) return false

        val flags = try {
            contentResolver.query(
                sourceUri,
                arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
                null,
                null,
                null,
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) {
                    cursor.getInt(idx)
                } else {
                    0
                }
            } ?: 0
        } catch (_: Exception) {
            0
        }
        return (flags and DocumentsContract.Document.FLAG_SUPPORTS_MOVE) != 0
    }

    override fun moveDocument(
        contentResolver: ContentResolver,
        sourceUri: Uri,
        sourceParentUri: Uri,
        targetFolderUri: Uri,
    ): Uri? = try {
        DocumentsContract.moveDocument(
            contentResolver,
            sourceUri,
            sourceParentUri,
            targetFolderUri,
        )
    } catch (e: Exception) {
        Log.w(TAG, "DocumentsContract.moveDocument failed for $sourceUri, falling back to stream copy", e)
        null
    }
}
