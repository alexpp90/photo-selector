package com.photoselectortoolbox.domain.curation

/**
 * How long a snackbar stays.
 *
 * Decided in the ViewModel together with the message (REQ-AND-LAYOUT.21: the
 * message and its undo are one fact), because only the ViewModel knows what the
 * message is *about*. None of these is a deadline: a move's and a delete's undo
 * last until the folder is left (the 20-deep stack and `Ctrl+Z`), a copy has no
 * undo, and a notice has nothing to take back. The snackbar used to drain a
 * 30-second line under a delete although nothing was committed at 30 seconds
 * (FD-D-1); there is no line any more.
 */
enum class SnackbarLifetime(val millis: Long) {
    /** Move confirmation: long enough to read the folder name and reach UNDO. */
    MOVE(4_000L),

    /** Copy confirmation: nothing to take back, so it can leave sooner. */
    COPY(3_000L),

    /** Scan complete, rating notices, an undo's result. */
    NOTICE(4_000L),

    /** Errors carry a reason worth reading ("Move failed: …"), so they stay a little longer. */
    ERROR(7_000L),

    /** A deferred delete: long enough to reach UNDO; `Ctrl+Z` keeps working after it left. */
    DELETE(4_000L);

    /**
     * The time to keep the snackbar up: [millis] stretched to the platform's
     * accessibility timeout [recommend]
     * (`AccessibilityManager.getRecommendedTimeoutMillis`), which only ever lengthens.
     */
    fun displayMillis(recommend: (Long) -> Long): Long = maxOf(millis, recommend(millis))
}
