package com.phototok.domain

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import com.phototok.data.model.ImageItem

/**
 * Pure companion resolution and Android share/open-with `Intent` builders (`U-13`).
 *
 * Uses `Intent.createChooser` with `ACTION_SEND`, `ACTION_SEND_MULTIPLE`, and `ACTION_VIEW`
 * so zero `<queries>` manifest declarations are needed.
 */
object ShareIntentBuilder {

    /** Maximum number of URIs in a single `ACTION_SEND_MULTIPLE` intent to avoid `TransactionTooLargeException`. */
    const val MAX_SHARE_ITEMS = 100

    data class ShareTarget(
        val uri: String,
        val mimeType: String,
        val fileName: String,
    )

    data class BatchResolution(
        val targets: List<ShareTarget>,
        /** How many items were omitted because the batch exceeded [MAX_SHARE_ITEMS]. */
        val truncatedCount: Int,
    )

    /**
     * Resolve which file to share for [item]:
     * - When [moveRelatedFiles] or [FileTypeFilter.ONE_PER_SHOT] is active and [item] is part of
     *   a `RAW + JPEG` pair in [allImages], prefer the processed (`JPEG` / `HEIC`) companion so
     *   recipient apps receive a universally-decodable image.
     * - When [item] is a lone RAW (no processed sibling) or already processed, share [item] as-is.
     */
    fun resolveShareTarget(
        item: ImageItem,
        allImages: List<ImageItem>,
        moveRelatedFiles: Boolean,
        fileTypeFilter: FileTypeFilter,
    ): ShareTarget {
        val chosen = if (moveRelatedFiles || fileTypeFilter == FileTypeFilter.ONE_PER_SHOT) {
            RelatedFiles.preferredShareItem(allImages, item)
        } else {
            item
        }
        return ShareTarget(
            uri = chosen.uri,
            mimeType = effectiveMimeType(chosen),
            fileName = chosen.fileName,
        )
    }

    /**
     * Resolve a batch of items for `ACTION_SEND_MULTIPLE`, deduplicating resolved targets
     * and capping at [maxItems] (`100`).
     */
    fun resolveShareBatch(
        items: List<ImageItem>,
        allImages: List<ImageItem>,
        moveRelatedFiles: Boolean,
        fileTypeFilter: FileTypeFilter,
        maxItems: Int = MAX_SHARE_ITEMS,
    ): BatchResolution {
        val cap = maxItems.coerceAtLeast(1)
        val distinctTargets = items
            .map { resolveShareTarget(it, allImages, moveRelatedFiles, fileTypeFilter) }
            .distinctBy { it.uri }
        val truncated = (distinctTargets.size - cap).coerceAtLeast(0)
        return BatchResolution(
            targets = distinctTargets.take(cap),
            truncatedCount = truncated,
        )
    }

    /** Build an `ACTION_SEND` chooser intent for a single [ShareTarget]. */
    fun buildSingleShareChooser(
        target: ShareTarget,
        title: CharSequence? = null,
    ): Intent {
        val contentUri = Uri.parse(target.uri)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = target.mimeType
            putExtra(Intent.EXTRA_STREAM, contentUri)
            clipData = ClipData.newRawUri(target.fileName, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(sendIntent, title).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Build an `ACTION_SEND_MULTIPLE` chooser intent for [targets] (or `ACTION_SEND` when
     * [targets] has a single element). Returns null when [targets] is empty.
     */
    fun buildBatchShareChooser(
        targets: List<ShareTarget>,
        title: CharSequence? = null,
    ): Intent? {
        if (targets.isEmpty()) return null
        if (targets.size == 1) return buildSingleShareChooser(targets.first(), title)

        val uris = ArrayList<Uri>(targets.size)
        var clip: ClipData? = null
        for (target in targets) {
            val u = Uri.parse(target.uri)
            uris.add(u)
            if (clip == null) {
                clip = ClipData.newRawUri(target.fileName, u)
            } else {
                clip.addItem(ClipData.Item(u))
            }
        }
        val commonMime = commonMimeType(targets)
        val sendMultiple = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = commonMime
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(sendMultiple, title).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Build an `ACTION_VIEW` chooser intent (`Open with…`) for [target]. */
    fun buildOpenWithChooser(
        target: ShareTarget,
        title: CharSequence? = null,
    ): Intent {
        val contentUri = Uri.parse(target.uri)
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, target.mimeType)
            clipData = ClipData.newRawUri(target.fileName, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(viewIntent, title).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    internal fun effectiveMimeType(item: ImageItem): String {
        val rawMime = item.mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
        if (rawMime != null) return rawMime
        return when {
            PhotoExtensions.isJpeg(item.fileName) -> "image/jpeg"
            PhotoExtensions.isRaw(item.fileName) -> "image/x-raw"
            else -> "image/*"
        }
    }

    private fun commonMimeType(targets: List<ShareTarget>): String {
        val first = targets.firstOrNull()?.mimeType ?: return "image/*"
        return if (targets.all { it.mimeType == first }) first else "image/*"
    }
}
