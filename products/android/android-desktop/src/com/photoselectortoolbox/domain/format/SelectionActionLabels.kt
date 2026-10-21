package com.photoselectortoolbox.domain.format

import com.photoselectortoolbox.domain.interaction.FilingAction

/**
 * The words on the two controls that file a photograph into the Selection.
 *
 * There is one rule here and it is worth stating plainly: **the button says
 * what it does.** An earlier draft of this screen called the copy control
 * "Keep", which is a euphemism — it describes how the photographer feels about
 * the frame, not what happens to the file. Under a "move" configuration the
 * same euphemism is actively wrong, because the file leaves the source folder.
 * Jackpix already learned this exact lesson (see the 2026-07-31 entry in
 * `ai/memory/palette.md`), and a unit test here asserts the word never comes
 * back.
 *
 * Both verbs are always on screen. The one named by the user's Filing Action
 * setting is the [primary] — first position, tonal fill — and the other is
 * [secondary]. Neither is hidden: a photographer who wants to copy this one
 * frame while normally moving must not have to open Settings to do it.
 *
 * The second rule is newer and comes from the same family of defect: **the
 * destination is a setting, not a constant.** Every function here takes the
 * configured `selection_folder_name`, exactly as
 * [com.photoselectortoolbox.domain.guidance.SelectorHintText.message] does, so
 * the hint card and the snackbar that fire alongside each other cannot name
 * different folders. The parameter defaults to
 * [FilingAction.DEFAULT_SELECTION_FOLDER] for call sites that genuinely have no
 * settings in hand.
 *
 * Kept free of Compose and Android types so the wording is unit-testable.
 */
object SelectionActionLabels {

    /** The configured verb: the emphasised control. */
    fun primary(
        filingAction: FilingAction,
        selectionFolderName: String = FilingAction.DEFAULT_SELECTION_FOLDER,
    ): ActionLabel = labelFor(filingAction, selectionFolderName, isPrimary = true)

    /** The other verb: present, labelled, visually quieter. */
    fun secondary(
        filingAction: FilingAction,
        selectionFolderName: String = FilingAction.DEFAULT_SELECTION_FOLDER,
    ): ActionLabel = labelFor(filingAction.other, selectionFolderName, isPrimary = false)

    /**
     * Both controls in the order they are rendered, configured verb first.
     *
     * Callers iterate this rather than composing two buttons by hand, so the
     * ordering rule lives in one place and the control block cannot disagree
     * with the context menu.
     */
    fun both(
        filingAction: FilingAction,
        selectionFolderName: String = FilingAction.DEFAULT_SELECTION_FOLDER,
    ): List<ActionLabel> = listOf(
        primary(filingAction, selectionFolderName),
        secondary(filingAction, selectionFolderName),
    )

    /**
     * The snackbar shown after the action succeeds.
     *
     * Names [selectionFolderName], because "Moved to Selection" is simply false
     * for a photographer who renamed the folder — and the snackbar is the only
     * thing on screen that says where the photograph went.
     */
    fun confirmation(
        action: FilingAction,
        selectionFolderName: String = FilingAction.DEFAULT_SELECTION_FOLDER,
    ): String =
        "${action.pastTense} to ${FilingAction.selectionFolderOrDefault(selectionFolderName)}"

    /**
     * The snackbar after a transfer whose primary file had to be renamed to avoid a collision
     * (`docs/shared/FILE_OPERATIONS.md`, feedback templates):
     * "Moved to Selection as IMG_0001-2.JPG (+2 related)".
     */
    fun suffixedConfirmation(
        action: FilingAction,
        selectionFolderName: String,
        resultName: String,
        relatedCount: Int,
    ): String {
        val related = if (relatedCount > 0) " (+$relatedCount related)" else ""
        return "${confirmation(action, selectionFolderName)} as $resultName$related"
    }

    /** The snackbar after an undo that had to add a suffix because the original name was taken. */
    fun restoredAs(resultName: String): String =
        "Restored as $resultName (original name was in use)"

    /**
     * File name of a document URI string: the last path segment of the (percent-decoded) document
     * id, so `…/document/primary%3ADCIM%2FIMG_1.JPG` gives `IMG_1.JPG`. Pure string work, no Android.
     */
    fun displayNameOf(uri: String): String {
        val last = uri.substringAfterLast('/')
        val decoded = try {
            java.net.URLDecoder.decode(last, "UTF-8")
        } catch (_: Exception) {
            last
        }
        return decoded.substringAfterLast('/').substringAfterLast(':')
    }

    /**
     * Whether the frame advances after this action.
     *
     * A moved frame is finished with; a copied one may still be worth comparing
     * against its neighbours. That asymmetry is the culling loop, so it is
     * stated here rather than rediscovered at each call site.
     */
    fun advancesAfter(action: FilingAction): Boolean = action == FilingAction.MOVE

    private fun labelFor(
        action: FilingAction,
        selectionFolderName: String,
        isPrimary: Boolean,
    ) = ActionLabel(
        action = action,
        verb = action.verb,
        phrase = action.phraseFor(selectionFolderName),
        shortcut = action.shortcut,
        isPrimary = isPrimary,
    )
}

/**
 * One filing control's wording.
 *
 * [verb] goes on the button, [phrase] into the accessibility description and
 * the context menu, [shortcut] onto the permanent key cap beside the verb.
 *
 * Only [phrase] names the destination folder, and that split is deliberate: the
 * control block is 190dp wide with two filing buttons side by side, so a folder
 * called "2026 — client selects" would either wrap or truncate on the button
 * while being perfectly readable in a menu row or read aloud in full. The button
 * keeps the verb; the screen reader and the menu get the whole sentence.
 */
data class ActionLabel(
    val action: FilingAction,
    val verb: String,
    val phrase: String,
    val shortcut: String,
    val isPrimary: Boolean,
) {
    /** "Copy to Selection, shortcut C" — what a screen reader announces. */
    val accessibilityLabel: String get() = "$phrase, shortcut $shortcut"
}
