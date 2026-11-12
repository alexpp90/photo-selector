package com.photoselector.core.storage

import java.io.ByteArrayOutputStream

/**
 * A SAF tree URI as the path a person recognises, for **display only**.
 *
 * `content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FTrip` reads
 * `Internal storage › DCIM › Trip`; a removable volume (`1234-ABCD:Pictures`) reads with
 * the volume's label the caller looked up (`StorageVolume.getDescription`), else
 * `SD card (1234-ABCD)`; any other provider reads as the display name the provider gave
 * the tree, else the last segment of its id.
 *
 * Reading a document id is acceptable here and nowhere else: the result is a label, and
 * no file operation may take a path from it (Android Desktop REQ-AND-PLATFORM.06 — the
 * Downloads, MTP and cloud providers hand out opaque ids). Pure string work, so it runs
 * on the JVM without Android; it never throws, and anything it cannot read becomes
 * [Labels.unknownFolder] or the name the caller supplied.
 */
object SafTreeDisplayPath {

    /** What a person reads between two segments. */
    const val SEPARATOR = " › "

    /**
     * The words the path is built from, so each product keeps its own copy and language.
     * [removableVolume] renders a volume with no known label from its uuid.
     *
     * There are no default words on purpose: a product that forgot to pass its own would show
     * English in another language, and the UI-text budget cannot see a string that is not a
     * resource. Every caller passes labels it owns.
     */
    data class Labels(
        val internalStorage: String,
        val documents: String,
        val removableVolume: (uuid: String) -> String,
        val unknownFolder: String,
    )

    /**
     * The segments of [treeUri], outermost first; never empty.
     *
     * @param volumeLabel the label of the removable volume the tree lives on, when the
     *   caller could look it up; ignored for internal storage and other providers.
     * @param providerTreeName the display name the provider reports for the tree's root
     *   document (`DocumentFile.getName()`), used for providers whose ids are opaque.
     */
    fun segments(
        treeUri: String?,
        volumeLabel: String? = null,
        providerTreeName: String? = null,
        labels: Labels,
    ): List<String> {
        val providerName = providerTreeName?.trim()?.takeIf { it.isNotEmpty() }
        val fallback = listOf(providerName ?: labels.unknownFolder)
        val uri = treeUri?.trim().orEmpty()
        if (uri.isEmpty()) return fallback

        val authority = authorityOf(uri)
        val documentId = documentIdOf(uri)?.takeIf { it.isNotBlank() } ?: return fallback

        if (authority.equals(SourceKindClassifier.EXTERNAL_STORAGE_AUTHORITY, ignoreCase = true)) {
            val colon = documentId.indexOf(':')
            if (colon <= 0) return fallback
            val volume = documentId.substring(0, colon).trim()
            val path = pathSegments(documentId.substring(colon + 1))
            val root = when (volume.lowercase()) {
                "primary" -> listOf(labels.internalStorage)
                // The "home" root is the Documents folder of internal storage.
                "home" -> listOf(labels.internalStorage, labels.documents)
                else -> listOf(
                    volumeLabel?.trim()?.takeIf { it.isNotEmpty() } ?: labels.removableVolume(volume),
                )
            }
            return root + path
        }

        if (providerName != null) return listOf(providerName)
        val last = pathSegments(documentId).lastOrNull() ?: return fallback
        return listOf(last)
    }

    /** [segments] joined with [SEPARATOR]. */
    fun format(
        treeUri: String?,
        volumeLabel: String? = null,
        providerTreeName: String? = null,
        labels: Labels,
    ): String = segments(treeUri, volumeLabel, providerTreeName, labels).joinToString(SEPARATOR)

    /**
     * The uuid of the removable volume an external-storage tree lives on, so the caller
     * knows which volume label to look up; null for internal storage, other providers and
     * anything unreadable.
     */
    fun removableVolumeId(treeUri: String?): String? {
        val uri = treeUri?.trim().orEmpty()
        if (!authorityOf(uri).equals(SourceKindClassifier.EXTERNAL_STORAGE_AUTHORITY, ignoreCase = true)) return null
        val documentId = documentIdOf(uri) ?: return null
        val colon = documentId.indexOf(':')
        if (colon <= 0) return null
        val volume = documentId.substring(0, colon).trim()
        return volume.takeUnless { it.isEmpty() || it.lowercase() in setOf("primary", "home") }
    }

    private fun authorityOf(uri: String): String {
        val schemeEnd = uri.indexOf("://")
        if (schemeEnd < 0) return ""
        val rest = uri.substring(schemeEnd + 3)
        return rest.substringBefore('/').substringBefore('?')
    }

    /**
     * The document id the URI names: the `document/` id when present (a folder inside a
     * tree), else the `tree/` id. Percent-decoded; null when the URI has neither.
     */
    private fun documentIdOf(uri: String): String? {
        val path = uri.substringBefore('?').substringBefore('#')
        val parts = path.split('/')
        val document = parts.indexOf("document").takeIf { it >= 0 && it + 1 < parts.size }
        val tree = parts.indexOf("tree").takeIf { it >= 0 && it + 1 < parts.size }
        val raw = document?.let { parts[it + 1] } ?: tree?.let { parts[it + 1] } ?: return null
        return percentDecode(raw)
    }

    private fun pathSegments(path: String): List<String> =
        path.split('/').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * RFC 3986 percent-decoding as `Uri.decode` does it: UTF-8, `+` stays a plus (a URL
     * form decoder would turn it into a space). A malformed escape is kept as written.
     */
    private fun percentDecode(text: String): String {
        if ('%' !in text) return text
        val out = StringBuilder(text.length)
        val bytes = ByteArrayOutputStream()
        var i = 0
        fun flush() {
            if (bytes.size() > 0) {
                out.append(bytes.toByteArray().toString(Charsets.UTF_8))
                bytes.reset()
            }
        }
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 < text.length) {
                val value = text.substring(i + 1, i + 3).toIntOrNull(16)
                if (value != null) {
                    bytes.write(value)
                    i += 3
                    continue
                }
            }
            flush()
            out.append(c)
            i++
        }
        flush()
        return out.toString()
    }
}
