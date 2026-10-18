package com.photoselector.saf

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger

/**
 * SAF-shaped [ContentProvider] backed by a temporary directory with hooks to
 * simulate provider auto-renames, truncated writes, delete failures, and
 * [Document.FLAG_SUPPORTS_MOVE].
 */
class FakeSafDocumentsProvider : android.content.ContentProvider() {
    companion object {
        const val AUTHORITY = "com.photoselector.saf.test.saftransfer"
        const val ROOT_ID = "root"

        @Volatile
        var rootDir: File = File(".")

        @Volatile
        var advertiseSupportsMove: Boolean = false

        @Volatile
        var failDeletes: Boolean = false
        /** When set, only documents whose id contains this text refuse to be deleted. */
        var failDeletesMatching: String? = null

        @Volatile
        var autoRenameOnCreate: ((String) -> String)? = null

        @Volatile
        var reportedSizeOverride: ((File) -> Long?)? = null

        /** Milliseconds each file creation takes, to widen race windows in concurrency tests. */
        @Volatile
        var createDelayMs: Long = 0L

        /** When true, sub-folders get provider-opaque ids ("msf:N") instead of path-like ones. */
        @Volatile
        var opaqueFolderIds: Boolean = false
        private val opaqueRegistry = java.util.concurrent.ConcurrentHashMap<String, File>()

        /** The id the provider hands out for [file] (opaque for folders when [opaqueFolderIds]). */
        fun documentIdFor(file: File): String {
            val rel = file.relativeTo(rootDir).invariantSeparatorsPath
            if (rel.isEmpty()) return ROOT_ID
            if (opaqueFolderIds && file.isDirectory) {
                val id = "msf:" + rel.hashCode().toUInt()
                opaqueRegistry[id] = file
                return id
            }
            return "$ROOT_ID/$rel"
        }

        fun opaqueFileFor(id: String): File? = opaqueRegistry[id]

        val moveDocumentCalls = AtomicInteger(0)
        val childrenQueryCalls = AtomicInteger(0)

        fun reset(root: File) {
            rootDir = root
            advertiseSupportsMove = false
            failDeletes = false
            failDeletesMatching = null
            opaqueFolderIds = false
            createDelayMs = 0L
            opaqueRegistry.clear()
            autoRenameOnCreate = null
            reportedSizeOverride = null
            moveDocumentCalls.set(0)
            childrenQueryCalls.set(0)
        }
    }

    override fun onCreate(): Boolean = true

    private fun documentIdOf(uri: Uri): String {
        val s = uri.pathSegments
        val i = s.indexOf("document")
        return if (i >= 0 && i + 1 < s.size) s[i + 1] else s[1]
    }

    private fun fileForDocId(documentId: String): File {
        opaqueFileFor(documentId)?.let { return it }
        require(documentId == ROOT_ID || documentId.startsWith("$ROOT_ID/")) {
            "Unexpected documentId: $documentId"
        }
        return if (documentId == ROOT_ID) {
            rootDir
        } else {
            File(rootDir, documentId.removePrefix("$ROOT_ID/"))
        }
    }

    private fun docIdForFile(file: File): String = documentIdFor(file)

    private fun mimeForFile(file: File): String = when {
        file.isDirectory -> Document.MIME_TYPE_DIR
        else -> MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"
    }

    private fun addRow(cursor: MatrixCursor, cols: Array<out String>, file: File) {
        val flags = buildFlags(file)
        val size = reportedSizeOverride?.invoke(file) ?: file.length()
        cursor.addRow(
            cols.map { col ->
                when (col) {
                    Document.COLUMN_DOCUMENT_ID -> docIdForFile(file)
                    Document.COLUMN_DISPLAY_NAME -> file.name
                    Document.COLUMN_MIME_TYPE -> mimeForFile(file)
                    Document.COLUMN_SIZE -> size
                    Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                    Document.COLUMN_FLAGS -> flags
                    else -> null
                }
            }.toTypedArray<Any?>()
        )
    }

    private fun buildFlags(file: File): Int {
        var flags = Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
        if (file.isDirectory) {
            flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        }
        if (advertiseSupportsMove) {
            flags = flags or Document.FLAG_SUPPORTS_MOVE
        }
        return flags
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cols = projection ?: arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
        val cursor = MatrixCursor(cols)
        val id = documentIdOf(uri)
        if (uri.pathSegments.lastOrNull() == "children") {
            childrenQueryCalls.incrementAndGet()
            val dir = fileForDocId(id)
            dir.listFiles()?.sortedBy { it.name }?.forEach { child ->
                addRow(cursor, cols, child)
            }
        } else {
            val file = fileForDocId(id)
            if (file.exists()) {
                addRow(cursor, cols, file)
            }
        }
        return cursor
    }

    override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? {
        @Suppress("DEPRECATION")
        val uri = extras?.getParcelable<Uri>("uri")
        val out = android.os.Bundle()
        return when (method) {
            "android:createDocument" -> {
                val parentUri = requireNotNull(uri)
                val parentDocId = documentIdOf(parentUri)
                val mimeType = extras.getString(Document.COLUMN_MIME_TYPE) ?: "application/octet-stream"
                val displayName = requireNotNull(extras.getString(Document.COLUMN_DISPLAY_NAME))
                val createdId = handleCreateDocument(parentDocId, mimeType, displayName)
                val createdUri = if (DocumentsContract.isTreeUri(parentUri)) {
                    DocumentsContract.buildDocumentUriUsingTree(parentUri, createdId)
                } else {
                    DocumentsContract.buildDocumentUri(AUTHORITY, createdId)
                }
                out.putParcelable("uri", createdUri)
                out
            }
            "android:deleteDocument" -> {
                val targetUri = requireNotNull(uri)
                handleDeleteDocument(documentIdOf(targetUri))
                out
            }
            "android:moveDocument" -> {
                val sourceUri = requireNotNull(uri)
                @Suppress("DEPRECATION")
                val sourceParentUri = requireNotNull(
                    extras.getParcelable<Uri>("parentUri")
                        ?: extras.getParcelable<Uri>("android.content.extra.PARENT_URI")
                )
                @Suppress("DEPRECATION")
                val targetParentUri = requireNotNull(
                    extras.getParcelable<Uri>("android.content.extra.TARGET_URI")
                        ?: extras.getParcelable<Uri>("targetUri")
                )
                val movedId = handleMoveDocument(
                    documentIdOf(sourceUri),
                    documentIdOf(sourceParentUri),
                    documentIdOf(targetParentUri),
                )
                val movedUri = if (DocumentsContract.isTreeUri(targetParentUri)) {
                    DocumentsContract.buildDocumentUriUsingTree(targetParentUri, movedId)
                } else {
                    DocumentsContract.buildDocumentUri(AUTHORITY, movedId)
                }
                out.putParcelable("uri", movedUri)
                out
            }
            else -> super.call(method, arg, extras)
        }
    }

    private fun handleCreateDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val parent = fileForDocId(parentDocumentId)
        val actualName = if (mimeType == Document.MIME_TYPE_DIR) {
            displayName
        } else {
            autoRenameOnCreate?.invoke(displayName) ?: displayName
        }
        val target = File(parent, actualName)
        if (createDelayMs > 0L) Thread.sleep(createDelayMs)
        if (mimeType == Document.MIME_TYPE_DIR) {
            if (!target.exists() && !target.mkdirs()) {
                throw FileNotFoundException("Could not mkdir $target")
            }
        } else {
            target.parentFile?.mkdirs()
            if (!target.exists() && !target.createNewFile()) {
                throw FileNotFoundException("Could not create $target")
            }
        }
        return docIdForFile(target)
    }

    private fun handleDeleteDocument(documentId: String) {
        if (failDeletes || failDeletesMatching?.let { documentId.contains(it) } == true) {
            throw SecurityException("Simulated read-only delete failure for $documentId")
        }
        val file = fileForDocId(documentId)
        if (!file.delete()) {
            throw FileNotFoundException("Could not delete $documentId")
        }
    }

    private fun handleMoveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String,
    ): String {
        moveDocumentCalls.incrementAndGet()
        val src = fileForDocId(sourceDocumentId)
        val targetDir = fileForDocId(targetParentDocumentId)
        targetDir.mkdirs()
        val dest = File(targetDir, src.name)
        if (dest.exists() || !src.renameTo(dest)) {
            throw IllegalStateException("Atomic rename failed from $src to $dest")
        }
        return docIdForFile(dest)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = fileForDocId(documentIdOf(uri))
        val pfdMode = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, pfdMode)
    }

    override fun getType(uri: Uri): String = mimeForFile(fileForDocId(documentIdOf(uri)))
    override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: android.content.ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
