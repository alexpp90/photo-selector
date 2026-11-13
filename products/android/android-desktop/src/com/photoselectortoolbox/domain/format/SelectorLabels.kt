package com.photoselectortoolbox.domain.format

import com.photoselectortoolbox.R
import com.photoselectortoolbox.domain.session.DateJump
import com.photoselectortoolbox.domain.text.UiText
import com.photoselectortoolbox.domain.text.plural
import com.photoselectortoolbox.domain.text.raw
import com.photoselectortoolbox.domain.text.text
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Short strings the selector chrome shows, kept out of the composables so the
 * wording can be tested without a device. Each is a [UiText]: the words are the string
 * resources (`[USER 2026-11-12]` decision #34: English and German), the numbers and names
 * are the arguments.
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
    fun filmstripRange(firstVisible: Int, lastVisible: Int, total: Int): UiText {
        if (total <= 0) return UiText.Empty
        val first = (firstVisible + 1).coerceIn(1, total)
        val last = (lastVisible + 1).coerceIn(first, total)
        return if (first == 1 && last == total) {
            text(R.string.label_filmstrip_all, total, total)
        } else {
            text(R.string.label_filmstrip_range, first, last, total)
        }
    }

    /**
     * The burst chip in the app bar: `burst 3/7`, where [indexInSeries] is
     * 0-based. Returns null when the frame is not part of a series, so the
     * caller can omit the chip entirely rather than render an empty one.
     */
    fun burstChip(indexInSeries: Int?, seriesLength: Int?): UiText? {
        if (indexInSeries == null || seriesLength == null || seriesLength < 2) return null
        return text(R.string.chip_burst_position, indexInSeries + 1, seriesLength)
    }

    /** The scanning counter shown in place of the Scan button: `412 / 842`. */
    fun scanProgress(done: Int, total: Int): String = "$done / $total"

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
    val QUEUED: UiText = text(R.string.label_queued)

    /** The sidebar line while Group Similar Series is rebuilding the bursts. */
    val GROUPING: UiText = text(R.string.label_grouping)

    /**
     * Snackbar text after a delete. It counts **files**, and only when a frame took more than
     * one with it (a RAW+JPEG pair, a sidecar): the position counter counts photos and falls by
     * one per deleted frame, so "1 image deleted" beside a count that fell by one, while two
     * files went, was read as the count falling by two (usability panel 2026-10-24).
     */
    fun deletedMessage(fileCount: Int): UiText =
        if (fileCount <= 1) text(R.string.snackbar_deleted) else plural(R.plurals.snackbar_deleted_files, fileCount)

    /**
     * Body text for the one-time per-folder delete confirmation dialog.
     *
     * Never says "cannot be undone" while UNDO is offered; states the folder-commit
     * boundary honestly without naming a key (touch has the More menu's Undo, backlog
     * 2026-11-11): Undo works until the folder is left (or 20 newer actions push it off the
     * undo stack) — there is no timer (FD-D-1), and the hint says the same. Appends a
     * cloud-sync warning when [isCloudProvider] is true (`U-30`).
     */
    fun deleteConfirmationMessage(
        fileName: String,
        isCloudProvider: Boolean = false,
        providerLabel: UiText? = null,
    ): UiText {
        val base = text(R.string.dialog_body_delete, fileName)
        return if (isCloudProvider) {
            val provider = providerLabel ?: text(R.string.label_cloud_provider_generic)
            UiText.Joined(listOf(base, text(R.string.dialog_body_delete_cloud_warning, provider)))
        } else {
            base
        }
    }

    /** Confirmation prompt before running a full pixel scan on a cloud provider (`U-30`). */
    fun cloudScanConfirmationMessage(providerLabel: UiText, imageCount: Int): UiText =
        plural(R.plurals.dialog_body_cloud_scan, imageCount, imageCount, providerLabel)

    /** Banner text when worst-first reject triage reaches the sharpness stop-line (`U-34`). */
    fun worstFirstStopLineMessage(threshold: Double): UiText =
        text(R.string.snackbar_stop_line_reached, String.format(java.util.Locale.ROOT, "%.0f", threshold))

    /** Recovery prompt text when a previous session had uncommitted pending deletions. */
    fun uncompletedDeletionsMessage(count: Int): UiText = plural(R.plurals.dialog_body_uncompleted_deletions, count)

    /**
     * After "Delete now": recovered photos that were kept because the file now at their place is
     * not, or cannot be shown to be, the one that was deleted (REQ-AND-LAYOUT.23). At most three
     * names, then the count.
     */
    fun recoveryKeptMessage(names: List<String>): UiText {
        val shown = names.take(3).joinToString() + if (names.size > 3) ", …" else ""
        return if (names.size == 1) {
            text(R.string.error_recovery_kept_one, shown)
        } else {
            plural(R.plurals.error_recovery_kept, names.size, names.size, shown)
        }
    }

    /** Snackbar text after a scan finishes. */
    fun scanCompleteMessage(count: Int): UiText = plural(R.plurals.snackbar_scan_complete, count)

    /** A bundle delete that removed some of its files and not others (FD-D-16). */
    fun partialDeleteMessage(fileName: UiText, deletedCount: Int, totalCount: Int): UiText =
        text(R.string.error_partial_delete, deletedCount, totalCount, fileName)

    /** Snackbar text when a rating was kept in the app but could not be written to the photo's file or sidecar. */
    fun ratingNotWrittenMessage(fileName: String, reason: UiText?): UiText =
        if (reason == null) {
            text(R.string.error_rating_not_written, fileName)
        } else {
            text(R.string.error_rating_not_written_reason, fileName, reason)
        }

    /**
     * What a folder open did with the backups of interrupted JPEG rating writes (FD-D-11), or null
     * when there were none. [kept] pairs a photo with the backup left beside it.
     */
    fun ratingRecoveryMessage(restored: List<String>, kept: List<Pair<String, String>>): UiText? {
        val parts = buildList {
            when (restored.size) {
                0 -> Unit
                1 -> add(text(R.string.error_rating_backup_restored_one, restored[0]))
                else -> add(plural(R.plurals.error_rating_backups_restored, restored.size))
            }
            when (kept.size) {
                0 -> Unit
                1 -> add(text(R.string.error_rating_backup_kept_one, kept[0].first, kept[0].second))
                else -> add(plural(R.plurals.error_rating_backups_kept, kept.size))
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.let { UiText.Joined(it, ". ") }
    }

    // ── Sort order and "Go to photo" ─────────────────────────────────────

    /** The overflow-menu radio group, in the order it is shown. */
    val SORT_NEWEST_FIRST: UiText = text(R.string.label_sort_newest_first)
    val SORT_OLDEST_FIRST: UiText = text(R.string.label_sort_oldest_first)
    val SORT_FILE_NAME: UiText = text(R.string.label_sort_file_name)

    /** Worst-first reject triage (`U-34`), the fourth item of the same radio group. */
    val SORT_BLURRIEST_FIRST: UiText = text(R.string.label_sort_blurriest_first)

    /**
     * The sort chip's word where the cell cannot hold the phrase: one word per order, a
     * word each so no two chips ever read alike.
     */
    val SORT_CHIP_NEWEST: UiText = text(R.string.chip_sort_newest)
    val SORT_CHIP_OLDEST: UiText = text(R.string.chip_sort_oldest)
    val SORT_CHIP_FILE_NAME: UiText = text(R.string.chip_sort_name)
    val SORT_CHIP_BLURRIEST: UiText = text(R.string.chip_sort_blurriest)

    /** The sort chip, spoken: the order now in force, then what the chip does. */
    fun sortChipDescription(orderLabel: UiText): UiText = text(R.string.cd_sort_chip, orderLabel)

    /**
     * Why Blurriest first is greyed out until a scan has run, on the menu item itself
     * (usability panel 2026-10-24: the item was disabled with no reason given).
     */
    val SORT_NEEDS_SCAN: UiText = text(R.string.label_sort_needs_scan)

    /**
     * The view toggles' one-word labels under their glyphs, in the order the control block draws
     * them: photo details, filmstrip, the values on Previous and Next, the guide. One word each,
     * none naming another's thing (`docs/products/android-desktop/DESIGN.md` § Control block).
     */
    val TOGGLE_INFO: UiText = text(R.string.label_toggle_info)
    val TOGGLE_STRIP: UiText = text(R.string.label_toggle_strip)
    val TOGGLE_OVERLAY: UiText = text(R.string.label_toggle_overlay)
    val TOGGLE_KEYS: UiText = text(R.string.label_toggle_keys)
    val VIEW_TOGGLE_LABELS: List<UiText> = listOf(TOGGLE_INFO, TOGGLE_STRIP, TOGGLE_OVERLAY, TOGGLE_KEYS)

    /** The overflow item that opens the dialog; `G` is its key hint. */
    val GO_TO_PHOTO_MENU: UiText = text(R.string.action_go_to_photo_menu)

    /** Next on the last frame of the folder: every photo has been seen. */
    val LAST_PHOTO: UiText = text(R.string.snackbar_last_photo)

    /** A second Copy of a frame copied in this session: nothing is written (no "-2" twin). */
    val ALREADY_COPIED: UiText = text(R.string.snackbar_already_copied)

    /** What a screen reader hears for the copied mark on a frame and its filmstrip tile. */
    fun copiedMarkSpoken(folderName: String): UiText = text(R.string.cd_copied_mark, folderName)

    /** The More menu's first row: one step back through the undo history (`Ctrl+Z`). */
    val UNDO_MENU: UiText = text(R.string.action_undo)

    /** What a screen reader hears for the Undo row. */
    fun undoMenuSpoken(steps: Int): UiText = text(R.string.cd_undo_menu, steps)

    /** Dialog title, button words and the counter's spoken click label. */
    val GO_TO_PHOTO: UiText = text(R.string.dialog_title_go_to_photo)
    val GO_TO_PHOTO_GO: UiText = text(R.string.action_go)
    val GO_TO_PHOTO_CANCEL: UiText = text(R.string.action_cancel)

    /** The field's label; a name is accepted as well as a number. */
    val GO_TO_PHOTO_FIELD: UiText = text(R.string.label_go_to_photo_field)

    /** The calendar button inside the field, which swaps the date picker into the dialog. */
    val GO_TO_PHOTO_PICK_DATE: UiText = text(R.string.cd_pick_date)

    /** Leaves the date picker for the field again. */
    val GO_TO_PHOTO_BACK: UiText = text(R.string.action_back)

    /**
     * The Go chip, spoken: its action and where the user stands. Names the running total
     * for what it is while the folder is still being listed.
     */
    fun goToChipDescription(position: Int, total: Int, stillEnumerating: Boolean): UiText =
        text(if (stillEnumerating) R.string.cd_go_chip_loading else R.string.cd_go_chip, position, total)

    /** The empty field's supporting line: the days the folder spans, `3 Oct 2026 – 2 Nov 2026`. */
    fun goToPhotoDateSpan(first: LocalDate, last: LocalDate): UiText =
        if (first == last) jumpDate(first) else UiText.Joined(listOf(jumpDate(first), jumpDate(last)), " – ")

    /** What the supporting line says when the input names no photo. */
    val GO_TO_PHOTO_NO_MATCH: UiText = text(R.string.label_no_match)

    /** The field's placeholder: the numbers the counter can show. */
    fun goToPhotoPlaceholder(total: Int): String = if (total <= 1) "1" else "1–$total"

    /** The supporting line for an empty or out-of-range number. */
    fun goToPhotoRange(total: Int): UiText = text(R.string.label_enter_range, goToPhotoPlaceholder(total))

    /**
     * A day as the Go to photo dialog writes it, in the UI's language: `12 Oct 2026`,
     * `12. Okt. 2026` (`res/values/formats.xml`).
     */
    fun jumpDate(date: LocalDate): UiText = UiText.Date(R.string.format_date, date)

    /** A photo's time as the dialog writes it: `12 Oct 2026, 14:32`. */
    fun jumpDateTime(dateTime: LocalDateTime): UiText = UiText.Date(R.string.format_date_time, dateTime)

    /**
     * The supporting line for a photo the input lands on: its file name, and when it
     * was taken if that is known — `DSC_0123.JPG · 12 Oct 2026, 14:32`.
     */
    fun goToPhotoPreview(fileName: String, takenAt: LocalDateTime?): UiText =
        if (takenAt == null) raw(fileName) else UiText.Joined(listOf(raw(fileName), jumpDateTime(takenAt)), " · ")

    /**
     * What a date jump says when the day asked for has no photo and the nearest day
     * was taken instead: `No photos on 13 Oct 2026 — showing 14 Oct 2026`; null when
     * the day was found (nothing to say).
     */
    fun goToPhotoNearestDay(jump: DateJump): UiText? =
        if (jump.isExact) null else text(R.string.label_no_photos_on_day, jumpDate(jump.requested), jumpDate(jump.landed))
}
