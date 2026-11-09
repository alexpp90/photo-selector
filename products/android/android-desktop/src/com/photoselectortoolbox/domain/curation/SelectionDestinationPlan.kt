package com.photoselectortoolbox.domain.curation

/**
 * Where Copy and Move put a photo: the one resolution both the file operation
 * (`ImageRepositoryImpl.selectionDestination`) and the Settings preview use, so the
 * label can never disagree with where files actually go (`[USER 2026-11-04]`).
 */
enum class DestinationMode {
    /** Into the Selection folder inside the folder being culled. The default. */
    WORKING_FOLDER,

    /** Into the Selection folder inside a folder the photographer picked. */
    CUSTOM;

    companion object {
        /**
         * The mode the stored custom destination stands for. There is no separate mode
         * key: a stored custom folder **is** Custom (that is what files did before the
         * choice existed, so an upgrade keeps it), and no folder is Working folder.
         */
        fun of(customDestinationUri: String?): DestinationMode =
            if (customDestinationUri.isNullOrBlank()) WORKING_FOLDER else CUSTOM
    }
}

/** The persisted settings that decide the destination. Blank names fall back to the defaults. */
data class DestinationSettings(
    val customDestinationUri: String? = null,
    val selectionFolderName: String = DEFAULT_SELECTION_FOLDER_NAME,
    val secondPassFolderName: String = DEFAULT_SECOND_PASS_FOLDER_NAME,
    val sortingEnabled: Boolean = true,
) {
    val mode: DestinationMode get() = DestinationMode.of(customDestinationUri)

    /** [customDestinationUri] trimmed, or null when it is absent or blank. */
    val customUri: String? get() = customDestinationUri?.trim()?.takeIf { it.isNotEmpty() }

    /** [selectionFolderName] trimmed, `Selection` when blank. */
    val selectionName: String get() = selectionFolderName.trim().ifEmpty { DEFAULT_SELECTION_FOLDER_NAME }

    /** [secondPassFolderName] trimmed, `Final` when blank. */
    val secondPassName: String get() = secondPassFolderName.trim().ifEmpty { DEFAULT_SECOND_PASS_FOLDER_NAME }

    companion object {
        const val DEFAULT_SELECTION_FOLDER_NAME = "Selection"
        const val DEFAULT_SECOND_PASS_FOLDER_NAME = "Final"
    }
}

/**
 * The folder being culled, as far as the destination cares: its URI, and whether it is
 * itself a Selection folder (its name is the Selection name, or it holds the
 * `.photoselector-selection` marker), which makes this a second pass (`U-32`).
 */
data class OpenedFolder(
    val uri: String,
    val name: String? = null,
    val hasSelectionMarker: Boolean = false,
)

/** Why a resolved destination will not take files as it stands. */
enum class DestinationProblem {
    /** The custom folder lost its permission, was deleted, or is on a volume that is not mounted. */
    CUSTOM_FOLDER_UNREACHABLE,
}

/**
 * Where the next Copy or Move goes: `<rootUri>/<folderName>/`, with `RAW/` and `JPEG/`
 * inside when [sortsIntoRawAndJpeg].
 *
 * @property rootUri the folder the Selection folder is created in — the custom folder,
 *   or the opened folder; null only in [DestinationMode.WORKING_FOLDER] with no folder open.
 * @property folderName the Selection folder name, or the second-pass name ([isSecondPass]).
 * @property problem set when the destination is known not to take files; the file
 *   operation still targets [rootUri] and reports the failure — it never falls back to
 *   another folder, because a photo filed somewhere the photographer did not choose is
 *   the defect this exists to prevent.
 */
data class SelectionDestinationPlan(
    val mode: DestinationMode,
    val rootUri: String?,
    val folderName: String,
    val isSecondPass: Boolean,
    val sortsIntoRawAndJpeg: Boolean,
    val problem: DestinationProblem? = null,
) {
    /** Whether a folder is known, so the preview can name it. */
    val hasRoot: Boolean get() = rootUri != null

    companion object {
        /**
         * The destination for [settings] while [openedFolder] is open (null: none).
         *
         * - **Custom**: `<custom>/<Selection name>`, whatever folder is open — never a
         *   second pass, because the custom folder is not the folder being culled.
         * - **Working folder**: `<opened>/<Selection name>`; when the opened folder is
         *   itself a Selection folder (second pass, `U-32`), `<opened>/<second-pass name>`.
         *
         * [customReachable] is what Settings knows about the custom folder (grant present
         * and the tree resolves); the file operation does not ask and passes the default.
         */
        fun resolve(
            settings: DestinationSettings,
            openedFolder: OpenedFolder?,
            customReachable: Boolean = true,
        ): SelectionDestinationPlan {
            val custom = settings.customUri
            if (custom != null) {
                val secondPass = openedFolder != null && isCustomParentOfOpenedSelection(custom, openedFolder, settings)
                return SelectionDestinationPlan(
                    mode = DestinationMode.CUSTOM,
                    rootUri = if (secondPass) openedFolder.uri else custom,
                    folderName = if (secondPass) settings.secondPassName else settings.selectionName,
                    isSecondPass = secondPass,
                    sortsIntoRawAndJpeg = settings.sortingEnabled,
                    problem = if (customReachable) null else DestinationProblem.CUSTOM_FOLDER_UNREACHABLE,
                )
            }
            val secondPass = openedFolder != null && isSelectionFolder(openedFolder, settings)
            return SelectionDestinationPlan(
                mode = DestinationMode.WORKING_FOLDER,
                rootUri = openedFolder?.uri,
                folderName = if (secondPass) settings.secondPassName else settings.selectionName,
                isSecondPass = secondPass,
                sortsIntoRawAndJpeg = settings.sortingEnabled,
            )
        }

        private fun isCustomParentOfOpenedSelection(
            customUri: String,
            openedFolder: OpenedFolder,
            settings: DestinationSettings,
        ): Boolean {
            if (!isSelectionFolder(openedFolder, settings)) return false
            val normCustom = customUri.replace("%2F", "/").replace("%2f", "/").trimEnd('/')
            val normOpened = openedFolder.uri.replace("%2F", "/").replace("%2f", "/").trimEnd('/')
            val expectedChild = "$normCustom/${settings.selectionName}"
            return normOpened.equals(expectedChild, ignoreCase = true) || normOpened.equals(normCustom, ignoreCase = true)
        }

        /** Whether [folder] is itself a Selection folder: by name (case-insensitively) or by its marker. */
        fun isSelectionFolder(folder: OpenedFolder, settings: DestinationSettings): Boolean =
            folder.hasSelectionMarker || folder.name?.trim()?.equals(settings.selectionName, ignoreCase = true) == true

        /**
         * Whether resolving the folder name needs to know if the opened folder is a
         * Selection folder — only in Working folder mode. Lets the file operation skip
         * the marker lookup when the answer cannot change anything.
         */
        fun needsOpenedFolderDetails(settings: DestinationSettings): Boolean = settings.customUri == null
    }
}
