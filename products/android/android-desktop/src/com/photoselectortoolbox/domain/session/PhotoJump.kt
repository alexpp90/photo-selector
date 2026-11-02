package com.photoselectortoolbox.domain.session

import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.model.RatingFilter

/** What the "Go to photo" input resolves to. */
sealed interface JumpResolution {

    /**
     * Jump to [index] in the selector's list (`SelectorUiState.images`).
     *
     * [hiddenByFilter] is true when the frame does not pass the active rating
     * filter: it exists and has the number the photographer typed, but
     * Previous/Next would skip it. What to do then is the screen's decision.
     */
    data class Resolved(val index: Int, val hiddenByFilter: Boolean = false) : JumpResolution

    /** A number outside 1…[total]. [total] is 0 for an empty list. */
    data class OutOfRange(val total: Int) : JumpResolution

    /** Nothing typed (or only whitespace). */
    data object Empty : JumpResolution

    /** Text that names no photo; [text] is the trimmed input, for the message. */
    data class NoNameMatch(val text: String) : JumpResolution
}

/**
 * Resolution of the "Go to photo" input, pure.
 *
 * **The numbering is the counter's.** Every position readout in the selector
 * (control block `n / N`, the readout block, the neighbour readouts, fullscreen)
 * shows `currentIndex + 1` of `images.size` — the position in the whole list in
 * its current order, **not** in the rating-filtered subset; a rating filter
 * changes which frames Previous/Next visit, not their numbers. So the number
 * typed is a 1-based index into `images`, whatever filter is active.
 *
 * Input that is a whole number (optionally signed) is a position. Anything else
 * is matched against file names — the primary file's and its companions', so
 * `0123.ARW` finds the RAW twin of `DSC_0123.JPG` — case-insensitively as a
 * substring, first match in list order, preferring a frame the rating filter
 * shows over one it hides.
 */
object PhotoJump {

    private val NUMBER = Regex("[+-]?[0-9]+")

    fun resolve(
        input: String,
        images: List<ImageItem>,
        ratingFilter: RatingFilter = RatingFilter.All,
    ): JumpResolution {
        val text = input.trim()
        if (text.isEmpty()) return JumpResolution.Empty

        if (NUMBER.matches(text)) {
            // Too many digits for an Int is still just a number out of range.
            val position = text.toIntOrNull() ?: return JumpResolution.OutOfRange(images.size)
            if (position !in 1..images.size) return JumpResolution.OutOfRange(images.size)
            val index = position - 1
            return JumpResolution.Resolved(
                index = index,
                hiddenByFilter = !ratingFilter.matches(images[index].rating),
            )
        }

        val needle = text.lowercase()
        var firstHidden = -1
        images.forEachIndexed { index, image ->
            if (namesMatch(image, needle)) {
                if (ratingFilter.matches(image.rating)) return JumpResolution.Resolved(index)
                if (firstHidden < 0) firstHidden = index
            }
        }
        return if (firstHidden >= 0) {
            JumpResolution.Resolved(firstHidden, hiddenByFilter = true)
        } else {
            JumpResolution.NoNameMatch(text)
        }
    }

    private fun namesMatch(image: ImageItem, needle: String): Boolean =
        needle in image.fileName.lowercase() ||
            image.companions.any { needle in it.fileName.lowercase() }
}
