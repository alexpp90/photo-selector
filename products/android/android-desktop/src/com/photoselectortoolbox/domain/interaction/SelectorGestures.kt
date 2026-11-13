package com.photoselectortoolbox.domain.interaction

import com.photoselectortoolbox.R
import com.photoselectortoolbox.domain.text.UiText
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
data class GestureRow(val input: String, val effect: String)

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
    val verb: String,
    /** "Copied" / "Moved" — for wording written after the file has been touched. */
    val pastTense: String,
    /** The keyboard shortcut that performs it. */
    val shortcut: String,
) {
    COPY(
        storedValue = "copy",
        verb = "Copy",
        pastTense = "Copied",
        shortcut = "C",
    ),
    MOVE(
        storedValue = "move",
        verb = "Move",
        pastTense = "Moved",
        shortcut = "M",
    );

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
    fun phraseFor(selectionFolderName: String): String =
        "$verb to ${selectionFolderOrDefault(selectionFolderName)}"

    /**
     * The phrase under the default folder name.
     *
     * Only for call sites that genuinely have no settings in hand; anything with
     * access to the configuration must use [phraseFor].
     */
    val phrase: String get() = phraseFor(DEFAULT_SELECTION_FOLDER)

    /** The verb on its own, "Copy" or "Move", in the UI's language. */
    val verbText: UiText get() = text(if (this == COPY) R.string.action_copy else R.string.action_move)

    /** "Copy to Picks" in the UI's language: [phraseFor], as a resource. */

    fun phraseText(selectionFolderName: String): UiText =
        text(if (this == COPY) R.string.action_copy_to else R.string.action_move_to, selectionFolderOrDefault(selectionFolderName))


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
    val input: String,
    val effect: String,
    val destructive: Boolean = false,
) {
    PINCH("pinch", "zoom"),
    DOUBLE_TAP("double-tap", "fit ↔ 100%"),
    HORIZONTAL_SWIPE("swipe ← →", "previous / next"),
    SWIPE_DOWN("swipe down", "dismiss"),

    /**
     * Pins the top bar (name, position, EXIF) so it stops fading out. In the
     * selector `I` means Inspect; the fullscreen viewer is its own window with
     * its own key handler, so the two never meet.
     */
    INFO("I", "pin / unpin the info panel"),
    ESCAPE("Esc", "exit"),
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
    val input: String,
    val effect: String,
    val destructive: Boolean = false,
) {
    TAP_NEIGHBOUR("tap a neighbour", "go to that frame"),
    TAP_CURRENT("tap the current frame", "open fullscreen"),
    TAP_BADGE("tap a frame's maximise badge", "fill the screen with that frame"),
    LONG_PRESS("long-press a frame", "context menu"),
}

/**
 * Every keyboard shortcut the selector binds, with the words for it.
 *
 * The coach-mark guide renders these; [com.photoselectortoolbox.ui.selector.handleSelectorKey]
 * implements them. A test asserts the two agree.
 */
enum class SelectorShortcut(val input: String) {
    PREVIOUS("←"),
    NEXT("→"),
    NEXT_PREVIOUS("J / K"),
    FILE_PRIMARY("C / M"),
    DELETE("Del"),
    FULLSCREEN("F"),
    DETAILS("I"),
    FILMSTRIP("T"),
    MAXIMISE("1 / 2 / 3"),
    GO_TO("G"),
    SORT("S"),
    LEARN("L"),
    UNDO("Ctrl+Z"),
    ZOOM("Space"),
    PAN("Shift ← →"),
    ESCAPE("Esc"),
    SHORTCUTS("?");

    fun describeEffect(
        filingAction: FilingAction,
        selectionFolderName: String = FilingAction.DEFAULT_SELECTION_FOLDER,
    ): String = when (this) {
        PREVIOUS -> "previous image"
        NEXT -> "next image"
        NEXT_PREVIOUS -> "next / previous image"
        FILE_PRIMARY ->
            "${filingAction.phraseFor(selectionFolderName)} · " +
                filingAction.other.phraseFor(selectionFolderName)
        DELETE -> "delete, with confirmation"
        FULLSCREEN -> "open fullscreen"
        DETAILS -> "show or hide this frame's values"
        FILMSTRIP -> "show or hide the filmstrip"
        MAXIMISE -> "enlarge left / middle / right frame"
        GO_TO -> "go to photo"
        SORT -> "choose the sort order"
        LEARN -> "open Learn"
        UNDO -> "undo the last delete or move"
        ZOOM -> "fit or 100 % (enlarged frame, fullscreen)"
        PAN -> "pan while zoomed"
        ESCAPE -> "leave the zoom, fullscreen or maximised; close a sheet"
        SHORTCUTS -> "show this guide"
    }
}
