package com.photoselectortoolbox.domain.guidance

/**
 * The one-time explanations this product can show, identified by their persisted
 * keys.
 *
 * **Identities only — no wording lives here.** Every one of these hints
 * describes an action whose effect depends on configuration: what the filing
 * control does is decided by the *Filing Action* setting, so the copy must be
 * built from
 * [com.photoselectortoolbox.domain.format.SelectionActionLabels] /
 * [com.photoselectortoolbox.domain.interaction.FilingAction] at render time. A
 * hard-coded "Keep"/"Save"/"Favourite" is prohibited by REQUIREMENTS §2 and was
 * shipped once already in Jackpix (see the 2026-07-31 entries in
 * `ai/memory/palette.md`). Putting a string on this enum is how that comes back.
 *
 * [key] is persisted in DataStore and is therefore on users' devices: renaming
 * one re-shows a hint that has already been dismissed. Add new entries, do not
 * repurpose old ones.
 */
enum class SelectorHint(
    /** The stored key. Stable — asserted by unit test. */
    val key: String,
    /**
     * Whether the wording must name the configured filing verb.
     *
     * True means "this hint cannot be written as a constant": its text has to be
     * derived from the user's Filing Action, or it will be wrong under one of
     * the two configurations.
     */
    val namesFilingVerb: Boolean = false,
) {
    /** First Move or Copy: what just happened to the file, and where it went. */
    FILING("filing_action", namesFilingVerb = true),

    /** First Delete: that it is deferred, and that the countdown can be stopped. */
    DELETE_UNDO("delete_undo"),

    /** First maximise: that `Esc` or a second press returns to three-up. */
    MAXIMISE("maximise_frame"),

    /** First time scores appear: that the bars compare and the legend explains. */
    SCORES("scan_scores"),

    /**
     * The coach-mark guide, shown once at first launch and thereafter on demand.
     *
     * Not a card like the four above — it is the whole-screen overlay
     * ([SelectorTour]) — but it is one-shot guidance with a dismissal that has
     * to persist and has to be cleared by *Reset guidance*, which is exactly
     * what this set is. Giving it its own boolean would be a second thing for
     * the reset to forget.
     */
    TOUR("selector_tour");

    companion object {
        /** The hint with this stored key, or null for a key we no longer know. */
        fun fromKey(key: String?): SelectorHint? = entries.firstOrNull { it.key == key }

        /**
         * Decode the persisted set, ignoring keys this build does not recognise.
         *
         * A downgrade must not crash on a key written by a newer version, and an
         * unknown key must not be treated as "some hint was seen".
         */
        fun decode(stored: Set<String>?): Set<SelectorHint> =
            stored.orEmpty().mapNotNull { fromKey(it) }.toSet()

        /** Encode for persistence. */
        fun encode(hints: Set<SelectorHint>): Set<String> = hints.map { it.key }.toSet()
    }
}
