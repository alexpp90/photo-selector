package com.photoselectortoolbox.domain.curation

/**
 * How long a snackbar stays, and whether that time is a promise.
 *
 * Decided in the ViewModel together with the message (REQ-AND-LAYOUT.21: the
 * message and its undo are one fact), because only the ViewModel knows what the
 * message is *about*. The snackbar used to drain a 30-second line under every
 * message. That deadline is true for one action only: a deferred delete really
 * is committed when the window closes. A move's undo never expires (it is the
 * 20-deep stack and `Ctrl+Z`), a copy has no undo, and a scan notice has nothing
 * to take back — for those the line was noise that also lied about a deadline.
 *
 * [countsDown] is the whole distinction: only a lifetime that is a real deadline
 * draws the line, and only a real deadline is exempt from the accessibility
 * stretch ([displayMillis]), because showing "UNDO" past the moment the deletion
 * is committed would be a lie of its own.
 */
enum class SnackbarLifetime(val millis: Long, val countsDown: Boolean) {
    /** Move confirmation: long enough to read the folder name and reach UNDO. */
    MOVE(4_000L, false),

    /** Copy confirmation: nothing to take back, so it can leave sooner. */
    COPY(3_000L, false),

    /** Scan complete, rating notices, an undo's result. */
    NOTICE(4_000L, false),

    /** Errors carry a reason worth reading ("Move failed: …"), so they stay a little longer. */
    ERROR(7_000L, false),

    /** A deferred delete: the real [DeferredDeletion.UNDO_WINDOW_MILLIS], drawn as a draining line. */
    DELETE(DeferredDeletion.UNDO_WINDOW_MILLIS, true);

    /**
     * The time to keep the snackbar up.
     *
     * [recommend] is the platform's accessibility timeout
     * (`AccessibilityManager.getRecommendedTimeoutMillis`), which only ever
     * lengthens. It is not applied to a [countsDown] lifetime: that duration is a
     * fact about the data, not about how long the reader needs.
     */
    fun displayMillis(recommend: (Long) -> Long): Long =
        if (countsDown) millis else maxOf(millis, recommend(millis))
}
