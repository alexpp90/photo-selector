package com.photoselectortoolbox.domain.curation

/**
 * What Settings shows under the destination choice: the resolved destination of the next
 * Copy or Move, built from a [SelectionDestinationPlan] — the same plan the file
 * operation uses — and the readable path of its root folder.
 *
 * @property segments the full readable path, the Selection (or second-pass) folder last:
 *   `Internal storage › DCIM › Trip › Selection`. Null when no folder is known — Working
 *   folder mode with no folder open — so the UI says "«Selection» inside the folder you
 *   open" instead of inventing a path.
 * @property folderName the folder created inside the root ([SelectionDestinationPlan.folderName]).
 * @property sortsIntoRawAndJpeg whether `RAW/` and `JPEG/` are created inside it.
 * @property isSecondPass whether this is the second-pass folder (`Final`), because the
 *   opened folder is itself a Selection folder.
 * @property problem set when the destination will not take files (a custom folder that
 *   lost its permission or is gone): a warning, not a valid destination.
 */
data class DestinationPreview(
    val mode: DestinationMode,
    val segments: List<String>?,
    val folderName: String,
    val sortsIntoRawAndJpeg: Boolean,
    val isSecondPass: Boolean,
    val problem: DestinationProblem? = null,
) {
    /** Whether the preview names a real folder rather than "the folder you open". */
    val hasPath: Boolean get() = segments != null

    companion object {
        /**
         * The preview of [plan], [rootDisplayPath] being the readable path of
         * [SelectionDestinationPlan.rootUri] (null or empty when it has none or it could not
         * be read: the path is then omitted rather than guessed).
         */
        fun of(plan: SelectionDestinationPlan, rootDisplayPath: List<String>?): DestinationPreview =
            DestinationPreview(
                mode = plan.mode,
                segments = if (plan.hasRoot && !rootDisplayPath.isNullOrEmpty()) {
                    rootDisplayPath + plan.folderName
                } else {
                    null
                },
                folderName = plan.folderName,
                sortsIntoRawAndJpeg = plan.sortsIntoRawAndJpeg,
                isSecondPass = plan.isSecondPass,
                problem = plan.problem,
            )

        /** The fresh-install preview: Working folder, no folder open, default names, sorting on. */
        val DEFAULT: DestinationPreview = of(
            SelectionDestinationPlan.resolve(DestinationSettings(), openedFolder = null),
            rootDisplayPath = null,
        )
    }
}
