package com.photoselectortoolbox.domain.format

import com.photoselectortoolbox.domain.session.DateJump
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

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

    // ── Sort order and "Go to photo" ─────────────────────────────────────

    /** The overflow-menu radio group, in the order it is shown. */
    const val SORT_NEWEST_FIRST = "Newest first"
    const val SORT_OLDEST_FIRST = "Oldest first"
    const val SORT_FILE_NAME = "File name"

    /** Worst-first reject triage (`U-34`), the fourth item of the same radio group. */
    const val SORT_BLURRIEST_FIRST = "Blurriest first"

    /**
     * The sort chip's word where the cell cannot hold the phrase: one word per order, a
     * word each so no two chips ever read alike.
     */
    const val SORT_CHIP_NEWEST = "Newest"
    const val SORT_CHIP_OLDEST = "Oldest"
    const val SORT_CHIP_FILE_NAME = "Name"
    const val SORT_CHIP_BLURRIEST = "Blurry"

    /** The sort chip, spoken: the order now in force, then what the chip does. */
    fun sortChipDescription(orderLabel: String): String = "Sort order: $orderLabel. Change"

    /** The overflow item that opens the dialog; `G` is its key hint. */
    const val GO_TO_PHOTO_MENU = "Go to photo…"

    /** Dialog title, button words and the counter's spoken click label. */
    const val GO_TO_PHOTO = "Go to photo"
    const val GO_TO_PHOTO_GO = "Go"
    const val GO_TO_PHOTO_CANCEL = "Cancel"

    /** The field's label; a name is accepted as well as a number. */
    const val GO_TO_PHOTO_FIELD = "Number, name or date"

    /** The calendar button inside the field, which swaps the date picker into the dialog. */
    const val GO_TO_PHOTO_PICK_DATE = "Pick a date"

    /** Leaves the date picker for the field again. */
    const val GO_TO_PHOTO_BACK = "Back"

    /**
     * The Go chip, spoken: its action and where the user stands. Names the running total
     * for what it is while the folder is still being listed.
     */
    fun goToChipDescription(position: Int, total: Int, stillEnumerating: Boolean): String =
        if (stillEnumerating) {
            "$GO_TO_PHOTO, $position of $total so far, still loading"
        } else {
            "$GO_TO_PHOTO, $position of $total"
        }

    /** The empty field's supporting line: the days the folder spans, `3 Oct 2026 – 2 Nov 2026`. */
    fun goToPhotoDateSpan(first: LocalDate, last: LocalDate): String =
        if (first == last) jumpDate(first) else "${jumpDate(first)} – ${jumpDate(last)}"

    /** What the supporting line says when the input names no photo. */
    const val GO_TO_PHOTO_NO_MATCH = "No match"

    /** The field's placeholder: the numbers the counter can show. */
    fun goToPhotoPlaceholder(total: Int): String = if (total <= 1) "1" else "1–$total"

    /** The supporting line for an empty or out-of-range number. */
    fun goToPhotoRange(total: Int): String = "Enter ${goToPhotoPlaceholder(total)}"

    /**
     * A day as the Go to photo dialog writes it: `12 Oct 2026`. English, like every
     * other word in the app, with a fixed locale so a German device does not mix
     * `12 Okt.` into English copy.
     */
    fun jumpDate(date: LocalDate): String = date.format(JUMP_DATE)

    /** A photo's time as the dialog writes it: `12 Oct 2026, 14:32`. */
    fun jumpDateTime(dateTime: LocalDateTime): String = dateTime.format(JUMP_DATE_TIME)

    /**
     * The supporting line for a photo the input lands on: its file name, and when it
     * was taken if that is known — `DSC_0123.JPG · 12 Oct 2026, 14:32`.
     */
    fun goToPhotoPreview(fileName: String, takenAt: LocalDateTime?): String =
        if (takenAt == null) fileName else "$fileName · ${jumpDateTime(takenAt)}"

    /**
     * What a date jump says when the day asked for has no photo and the nearest day
     * was taken instead: `No photos on 13 Oct 2026 — showing 14 Oct 2026`; null when
     * the day was found (nothing to say).
     */
    fun goToPhotoNearestDay(jump: DateJump): String? =
        if (jump.isExact) null else "No photos on ${jumpDate(jump.requested)} — showing ${jumpDate(jump.landed)}"

    private val JUMP_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val JUMP_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
}
