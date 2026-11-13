package com.photoselectortoolbox.domain.interaction

import com.photoselectortoolbox.R
import androidx.annotation.StringRes
import com.photoselectortoolbox.domain.text.UiText
import com.photoselectortoolbox.domain.text.raw
import com.photoselectortoolbox.domain.text.text

/**
 * The one place a gesture's meaning is written down.
 *
 * The shipped viewer claimed "swipe ← → navigate" on its hint card while a
 * leftward swipe **deleted the photograph**, a rightward swipe dismissed the
 * viewer, and navigation was a vertical pager. Three of the five lines on that
 * card were false, and the single destructive action in the viewer was bound to
 * the gesture the card described as harmless.
 *
 * That was not a copy bug. It was a card written by hand next to bindings
 * written somewhere else, with nothing forcing them to agree. So the bindings
 * and the wording are now the same object: the viewer reads [FullscreenGesture]
 * to decide what a gesture does, the hint card reads it to decide what to say,
 * and a hint row that does not correspond to a binding cannot be expressed.
 *
 * Kept free of Compose and Android types so every rule here is unit-testable.
 */
object SelectorGestures {

    /**
     * The rows the fullscreen hint card renders, in order.
     *
     * Derived from the bindings, never written out. No gesture files a
     * photograph any more, so no row here depends on the filing setting — the
     * filing *button*'s wording comes from
     * [com.photoselectortoolbox.domain.format.SelectionActionLabels] instead.
     */
    fun fullscreenHintRows(): List<GestureRow> =
        FullscreenGesture.entries.map { gesture ->
            GestureRow(input = gesture.input, effect = gesture.effect)
        }

    /**
     * The rows the selector's guidance renders, in order.
     *
     * Same contract as above: every surface that advertises a shortcut is
     * generated from the bindings, so a shortcut that stops working stops being
     * advertised in the same commit.
     *
     * [shortcuts] exists so a coach mark can render only the keys belonging to
     * the control it labels, without any call site writing a key literal. The
     * default is every binding, in declaration order.
     *
     * [selectionFolderName] is the *Storage* setting, so the filing row names
     * the folder the photograph actually lands in. It defaults to
     * [FilingAction.DEFAULT_SELECTION_FOLDER] for the call sites that have no
     * settings in hand.
     */
    fun selectorShortcutRows(
        filingAction: FilingAction,
        shortcuts: Collection<SelectorShortcut> = SelectorShortcut.entries,
        selectionFolderName: String = FilingAction.DEFAULT_SELECTION_FOLDER,
    ): List<GestureRow> =
        SelectorShortcut.entries
            .filter { it in shortcuts }
            .map { shortcut ->
                GestureRow(
                    input = shortcut.input,
                    effect = shortcut.describeEffect(filingAction, selectionFolderName),
                )
            }

    /**
     * The rows describing what the frames themselves respond to.
     *
     * These used to be four hand-written `GestureRow`s inside the shortcut
     * sheet, one of which claimed the maximise control was a `⛱` badge — a
     * glyph nothing in this product has ever rendered
     * ([com.photoselectortoolbox.ui.selector.MaximiseBadge] draws an
     * open-in-full arrow pair). That is precisely the class of plausible lie the
     * 2026-07-31 lesson is about, and it survived because the wording lived next
     * to the bindings rather than in them. No row here names a glyph.
     */
    fun frameGestureRows(): List<GestureRow> =
        FrameGesture.entries.map { gesture ->
            GestureRow(input = gesture.input, effect = gesture.effect)
        }
}

/** One line of a hint card: what you do, and what happens. */
data class GestureRow(val input: UiText, val effect: UiText)

/**
 * Which verb the configured filing control performs.
 *
 * Replaces the free-form `"copy"` / `"move"` strings that were compared by hand
 * at four call sites. The persisted value is unchanged, so no migration is
 * needed; only the in-memory type is now closed.
 */
enum class FilingAction(
    /** The persisted DataStore value. Do not change — it is on users' devices. */
    val storedValue: String,
    /** The word on the button. Never "Keep": the button says what it does. */
    @StringRes private val verbRes: Int,
    /** "Copied" / "Moved" — for wording written after the file has been touched. */
    @StringRes private val pastTenseRes: Int,
    /** "Copy to %1$s" — the verb with its destination. */
    @StringRes private val phraseRes: Int,
    /** The keyboard shortcut that performs it. */
    val shortcut: String,
) {
    COPY(
        storedValue = "copy",
        verbRes = R.string.action_copy,
        pastTenseRes = R.string.label_copied,
        phraseRes = R.string.action_copy_to,
        shortcut = "C",
    ),
    MOVE(
        storedValue = "move",
        verbRes = R.string.action_move,
        pastTenseRes = R.string.label_moved,
        phraseRes = R.string.action_move_to,
        shortcut = "M",
    );

    /** The word on the button, "Copy" or "Move", in the UI's language. */
    val verb: UiText get() = text(verbRes)

    /** "Copied" / "Moved", in the UI's language. */
    val pastTense: UiText get() = text(pastTenseRes)

    /** The other verb — the one that is available but not configured as primary. */
    val other: FilingAction get() = if (this == COPY) MOVE else COPY

    /**
     * "Copy to Picks" — the full phrase, naming the folder the file actually
     * lands in.
     *
     * The destination is the `selection_folder_name` setting, so no caller may
     * write the folder into a string literal: a photographer who renamed it to
     * `Picks` was being told about a folder that does not exist. A blank name
     * falls back to [DEFAULT_SELECTION_FOLDER] rather than producing "Copy to ".
     */
    fun phraseFor(selectionFolderName: String): UiText =
        text(phraseRes, selectionFolderOrDefault(selectionFolderName))

    /**
     * The phrase under the default folder name.
     *
     * Only for call sites that genuinely have no settings in hand; anything with
     * access to the configuration must use [phraseFor].
     */
    val phrase: UiText get() = phraseFor(DEFAULT_SELECTION_FOLDER)


    companion object {
        val DEFAULT = COPY

        /**
         * The folder name to use when the setting has not been read yet or is
         * blank.
         *
         * Must equal `SettingsRepository.DEFAULT_SELECTION_FOLDER_NAME`: the
         * persisted default and the wording default describe one folder, and a
         * unit test asserts the two constants have not drifted apart. It lives
         * here rather than in the repository because the wording layer is kept
         * free of Android and DataStore types.
         */
        const val DEFAULT_SELECTION_FOLDER = "Selection"

        /** The configured folder name, or the default if it is blank. */
        fun selectionFolderOrDefault(selectionFolderName: String): String =
            selectionFolderName.ifBlank { DEFAULT_SELECTION_FOLDER }

        /**
         * Parse a persisted value, falling back rather than throwing.
         *
         * A settings string that has been on disk across an upgrade is not
         * trustworthy input, and a crash on launch is a worse outcome than a
         * default.
         */
        fun fromStored(value: String?): FilingAction =
            entries.firstOrNull { it.storedValue == value } ?: DEFAULT
    }
}

/**
 * Every gesture the fullscreen viewer binds, with the words for it.
 *
 * [destructive] exists so the product rule — *no destructive action is ever a
 * bare gesture* — is data a test can assert, rather than a sentence in a design
 * document that the next refactor will not read.
 */
enum class FullscreenGesture(
    @StringRes private val inputRes: Int,
    @StringRes private val effectRes: Int,
    val destructive: Boolean = false,
    /** A key cap, written as it is printed (`I`, `Esc`); null when [inputRes] names a gesture. */
    private val key: String? = null,
) {
    PINCH(R.string.hint_body_gesture_pinch, R.string.hint_body_effect_zoom),
    DOUBLE_TAP(R.string.hint_body_gesture_double_tap, R.string.hint_body_effect_fit_100),
    HORIZONTAL_SWIPE(R.string.hint_body_gesture_swipe_sideways, R.string.hint_body_effect_previous_next),
    SWIPE_DOWN(R.string.hint_body_gesture_swipe_down, R.string.hint_body_effect_dismiss),

    /**
     * Pins the top bar (name, position, EXIF) so it stops fading out. In the
     * selector `I` means Inspect; the fullscreen viewer is its own window with
     * its own key handler, so the two never meet.
     */
    INFO(0, R.string.hint_body_effect_pin_info, key = "I"),
    ESCAPE(0, R.string.hint_body_effect_exit, key = "Esc"),
    ;

    /** What you do, in the UI's language (a key cap is shown as printed). */
    val input: UiText get() = key?.let(::raw) ?: text(inputRes)

    /** What happens, in the UI's language. */
    val effect: UiText get() = text(effectRes)
}

/**
 * Every gesture the three frames themselves bind, with the words for it.
 *
 * Same contract as [FullscreenGesture] and the same reason: the guidance is
 * generated from this, so a gesture row that does not correspond to a binding
 * cannot be expressed, and [destructive] keeps the product rule — *no
 * destructive action is ever a bare gesture* — as data a test asserts.
 */
enum class FrameGesture(
    @StringRes private val inputRes: Int,
    @StringRes private val effectRes: Int,
    val destructive: Boolean = false,
) {
    TAP_NEIGHBOUR(R.string.hint_body_gesture_tap_neighbour, R.string.hint_body_effect_go_to_frame),
    TAP_CURRENT(R.string.hint_body_gesture_tap_current, R.string.hint_body_effect_open_fullscreen),
    TAP_BADGE(R.string.hint_body_gesture_tap_badge, R.string.hint_body_effect_fill_screen),
    LONG_PRESS(R.string.hint_body_gesture_long_press, R.string.hint_body_effect_context_menu),
    ;

    /** What you do, in the UI's language. */
    val input: UiText get() = text(inputRes)

    /** What happens, in the UI's language. */
    val effect: UiText get() = text(effectRes)
}

/**
 * Every keyboard shortcut the selector binds, with the words for it.
 *
 * The coach-mark guide renders these; [com.photoselectortoolbox.ui.selector.handleSelectorKey]
 * implements them. A test asserts the two agree.
 */
enum class SelectorShortcut(
    /** The key cap as printed where it carries no word (`←`, `F`, `1 / 2 / 3`). */
    private val cap: String,
    /** The key cap's name where keyboards print a word (`Del`, `Entf`), else 0. */
    @StringRes private val capRes: Int = 0,
) {
    PREVIOUS("←"),
    NEXT("→"),
    NEXT_PREVIOUS("J / K"),
    FILE_PRIMARY("C / M"),
    DELETE("", R.string.label_key_delete),
    FULLSCREEN("F"),
    DETAILS("I"),
    FILMSTRIP("T"),
    MAXIMISE("1 / 2 / 3"),
    GO_TO("G"),
    SORT("S"),
    LEARN("L"),
    UNDO("", R.string.label_key_undo),
    ZOOM("", R.string.label_key_space),
    PAN("", R.string.label_key_pan),
    ESCAPE("Esc"),
    SHORTCUTS("?");

    /** The key cap in the UI's language: `Del` / `Entf`, `Ctrl+Z` / `Strg+Z`; symbols as printed. */
    val input: UiText get() = if (capRes != 0) text(capRes) else raw(cap)

    fun describeEffect(
        filingAction: FilingAction,
        selectionFolderName: String = FilingAction.DEFAULT_SELECTION_FOLDER,
    ): UiText = when (this) {
        PREVIOUS -> text(R.string.hint_body_effect_previous_image)
        NEXT -> text(R.string.hint_body_effect_next_image)
        NEXT_PREVIOUS -> text(R.string.hint_body_effect_next_previous_image)
        FILE_PRIMARY -> UiText.Joined(
            listOf(filingAction.phraseFor(selectionFolderName), filingAction.other.phraseFor(selectionFolderName)),
            " · ",
        )
        DELETE -> text(R.string.hint_body_effect_delete_confirmed)
        FULLSCREEN -> text(R.string.hint_body_effect_open_fullscreen)
        DETAILS -> text(R.string.hint_body_effect_toggle_values)
        FILMSTRIP -> text(R.string.hint_body_effect_toggle_filmstrip)
        MAXIMISE -> text(R.string.hint_body_effect_enlarge_frame)
        GO_TO -> text(R.string.hint_body_effect_go_to_photo)
        SORT -> text(R.string.hint_body_effect_choose_sort)
        LEARN -> text(R.string.hint_body_effect_open_learn)
        UNDO -> text(R.string.hint_body_effect_undo)
        ZOOM -> text(R.string.hint_body_effect_zoom_100)
        PAN -> text(R.string.hint_body_effect_pan)
        ESCAPE -> text(R.string.hint_body_effect_escape)
        SHORTCUTS -> text(R.string.hint_body_effect_show_guide)
    }
}
