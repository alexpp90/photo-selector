package com.photoselectortoolbox.domain.format

/**
 * Short strings the selector chrome shows, kept out of the composables so the
 * wording can be tested without a device.
 */
object SelectorLabels {

    /**
     * The caption at the right end of the filmstrip: `115–156 of 842`.
     *
     * Both bounds are 1-based and inclusive, matching what the photographer
     * reads everywhere else in the app. A folder small enough that the whole
     * strip is visible gets just the total — a range that covers everything is
     * noise.
     */
    fun filmstripRange(firstVisible: Int, lastVisible: Int, total: Int): String {
        if (total <= 0) return ""
        val first = (firstVisible + 1).coerceIn(1, total)
        val last = (lastVisible + 1).coerceIn(first, total)
        return if (first == 1 && last == total) {
            "$total of $total"
        } else {
            "$first–$last of $total"
        }
    }

    /**
     * The burst chip in the app bar: `burst 3/7`, where [indexInSeries] is
     * 0-based. Returns null when the frame is not part of a series, so the
     * caller can omit the chip entirely rather than render an empty one.
     */
    fun burstChip(indexInSeries: Int?, seriesLength: Int?): String? {
        if (indexInSeries == null || seriesLength == null || seriesLength < 2) return null
        return "burst ${indexInSeries + 1}/$seriesLength"
    }

    /** The scanning counter shown in place of the Scan button: `Scanning 412 / 842`. */
    fun scanProgress(done: Int, total: Int): String = "Scanning $done / $total"

    /**
     * The position readout closing the control block: `127 / 842`, or
     * `127 / 842+` while the folder is still being enumerated.
     *
     * The trailing `+` is not decoration. Discovery streams, so the total is a
     * *running* total for the first seconds of a large folder; showing `24 / 24`
     * during that window tells the photographer the shoot contains 24 frames,
     * which is false. One character says "there are more coming" without
     * inventing a number nobody has counted yet.
     */
    fun position(position: Int, total: Int, stillEnumerating: Boolean): String =
        "$position / $total" + if (stillEnumerating) "+" else ""

    /**
     * The sidebar line shown in place of a control whose request is waiting for
     * the other long-running pass to finish.
     *
     * The control stays enabled and gains a Cancel — a disabled control reads as
     * a frozen app (`ai/memory/palette.md`, 2026-07-24).
     */
    const val QUEUED = "Queued"

    /** The sidebar line while Group Similar Series is rebuilding the bursts. */
    const val GROUPING = "Grouping"

    /** Snackbar text after a delete, pluralised. */
    fun deletedMessage(count: Int): String =
        if (count == 1) "1 image deleted" else "$count images deleted"

    /**
     * Body text for the one-time per-folder delete confirmation dialog.
     *
     * Never says "cannot be undone" while UNDO / Ctrl+Z is offered; states the
     * 20-step undo history and folder-commit boundary honestly, and appends a
     * cloud-sync warning when [isCloudProvider] is true (`U-30`).
     */
    fun deleteConfirmationMessage(
        fileName: String,
        isCloudProvider: Boolean = false,
        providerLabel: String? = null,
    ): String {
        val base = "Delete \"$fileName\"? Deletions in this folder are permanent once committed. " +
            "You can undo up to 20 actions with Ctrl+Z before leaving the folder."
        return if (isCloudProvider) {
            val provider = providerLabel?.takeIf { it.isNotBlank() } ?: "a cloud provider"
            "$base Warning: This folder is backed by $provider — deleting here may permanently remove the photo across all synced devices."
        } else {
            base
        }
    }

    /** Confirmation prompt before running a full pixel scan on a cloud provider (`U-30`). */
    fun cloudScanConfirmationMessage(providerLabel: String, imageCount: Int): String =
        "Scanning $imageCount photos in $providerLabel will download every image over the network to compute sharpness scores. Continue?"

    /** Banner text when worst-first reject triage reaches the sharpness stop-line (`U-34`). */
    fun worstFirstStopLineMessage(threshold: Double): String =
        String.format(
            java.util.Locale.US,
            "Reached sharpness stop-line (%.0f) — remaining photos meet or exceed your threshold.",
            threshold,
        )

    /** Recovery prompt text when a previous session had uncommitted pending deletions. */
    fun uncompletedDeletionsMessage(count: Int): String =
        if (count == 1) {
            "1 deletion was not completed — delete now / keep"
        } else {
            "$count deletions were not completed — delete now / keep"
        }

    /** Snackbar text after a scan finishes. */
    fun scanCompleteMessage(count: Int): String = "Scan complete · $count images analysed"

    /** Snackbar text when a rating was kept in the app but could not be written to the photo's file or sidecar. */
    fun ratingNotWrittenMessage(fileName: String, reason: String?): String =
        "Rating kept in the app only — not saved to $fileName" + if (reason.isNullOrBlank()) "" else " ($reason)"

    /**
     * What a folder open did with the backups of interrupted JPEG rating writes (FD-D-11), or null
     * when there were none. [kept] pairs a photo with the backup left beside it.
     */
    fun ratingRecoveryMessage(restored: List<String>, kept: List<Pair<String, String>>): String? {
        val parts = buildList {
            when (restored.size) {
                0 -> Unit
                1 -> add("Restored ${restored[0]} from its backup after an interrupted rating write")
                else -> add("Restored ${restored.size} photos from their backups after an interrupted rating write")
            }
            when (kept.size) {
                0 -> Unit
                1 -> add("Backup of ${kept[0].first} kept as ${kept[0].second} — check the photo, then delete the backup")
                else -> add("${kept.size} rating backups kept (*.frameduel-backup) — check the photos, then delete the backups")
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(". ")
    }

    // ── Sort order and "Go to photo" ─────────────────────────────────────

    /** The overflow-menu radio group, in the order it is shown. */
    const val SORT_NEWEST_FIRST = "Newest first"
    const val SORT_OLDEST_FIRST = "Oldest first"
    const val SORT_FILE_NAME = "File name"

    /** Worst-first reject triage (`U-34`), the fourth item of the same radio group. */
    const val SORT_BLURRIEST_FIRST = "Blurriest first"

    /** The overflow item that opens the dialog; `G` is its key hint. */
    const val GO_TO_PHOTO_MENU = "Go to photo…"

    /** Dialog title, button words and the counter's spoken click label. */
    const val GO_TO_PHOTO = "Go to photo"
    const val GO_TO_PHOTO_GO = "Go"
    const val GO_TO_PHOTO_CANCEL = "Cancel"

    /** The field's label; a name is accepted as well as a number. */
    const val GO_TO_PHOTO_FIELD = "Number or name"

    /** What the supporting line says when the input names no photo. */
    const val GO_TO_PHOTO_NO_MATCH = "No match"

    /** The field's placeholder: the numbers the counter can show. */
    fun goToPhotoPlaceholder(total: Int): String = if (total <= 1) "1" else "1–$total"

    /** The supporting line for an empty or out-of-range number. */
    fun goToPhotoRange(total: Int): String = "Enter ${goToPhotoPlaceholder(total)}"
}
