package com.photoselector.core.reader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/**
 * The XMP sidecar rating of a photograph opened through the Storage Access Framework.
 *
 * A `file` reads its sidecar with `File`; a `content://` document URI has no sibling path, so the
 * sidecar is found the way SAF allows: list the photograph's parent folder inside the granted tree
 * and look for `<file name>.xmp`, then `<stem>.xmp`, ignoring case (a camera writes `IMG.xmp`, an
 * editor `IMG.XMP`). Only a tree document URI can be resolved: a grant on a single document says
 * nothing about its siblings.
 */
internal object SafSidecarRating {
    private const val TAG = "SafSidecarRating"
    private const val MAX_SIDECAR_BYTES = 2L * 1024 * 1024
    private const val MAX_WALKED_FOLDERS = 2000

    /** The sidecar's rating, or null when the URI is not a tree document, no sidecar exists or it carries no rating. */
    fun read(context: Context, uri: Uri): Int? {
        if (uri.scheme != "content") return null
        return try {
            val segments = uri.pathSegments
            if (segments.size < 4 || segments[0] != "tree" || segments[2] != "document") return null
            val resolver = context.contentResolver
            val name = displayName(context, uri) ?: return null
            val parentId = parentDocumentId(context, uri) ?: return null
            val siblings = children(context, uri, parentId)
            val stem = name.substringBeforeLast('.', name)
            val candidates = listOf("$name.xmp", "$stem.xmp")
            for (candidate in candidates) {
                val documentId = siblings.entries
                    .firstOrNull { it.key.equals(candidate, ignoreCase = true) }?.value ?: continue
                val sidecarUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
                val text = resolver.openInputStream(sidecarUri)?.use(::readCapped) ?: continue
                XmpRatingParser.parseRating(text)?.let { return it }
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "No sidecar rating for $uri (${e.javaClass.simpleName})")
            null
        }
    }

    /** At most [MAX_SIDECAR_BYTES] of the stream as text (`readNBytes` needs API 33). */
    private fun readCapped(stream: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < MAX_SIDECAR_BYTES) {
            val n = stream.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun parentDocumentId(context: Context, uri: Uri): String? {
        val path = try {
            DocumentsContract.findDocumentPath(context.contentResolver, uri)?.path
        } catch (_: Exception) {
            null
        }
        if (path != null && path.size >= 2) return path[path.size - 2]
        // Document ids are opaque and never split: walk the tree breadth-first and compare the ids
        // the provider issued (the rule `XmpSidecarWriter` follows for the same lookup).
        return walkForParent(context, uri, DocumentsContract.getDocumentId(uri))
    }

    private fun walkForParent(context: Context, uri: Uri, targetId: String): String? {
        val queue = ArrayDeque<String>()
        queue.add(DocumentsContract.getTreeDocumentId(uri))
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_WALKED_FOLDERS) {
            val folderId = queue.removeFirst()
            for (child in listChildren(context, uri, folderId)) {
                if (child.id == targetId) return folderId
                if (child.isFolder) queue.add(child.id)
            }
        }
        return null
    }

    private class Child(val id: String, val name: String, val isFolder: Boolean)

    private fun listChildren(context: Context, uri: Uri, parentId: String): List<Child> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, parentId)
        val result = ArrayList<Child>()
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                result += Child(id, cursor.getString(1).orEmpty(), cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
            }
        }
        return result
    }

    /** Display name to document id of the `.xmp` documents in a folder. */
    private fun children(context: Context, uri: Uri, parentId: String): Map<String, String> =
        listChildren(context, uri, parentId)
            .filter { it.name.endsWith(".xmp", ignoreCase = true) }
            .associate { it.name to it.id }
}
