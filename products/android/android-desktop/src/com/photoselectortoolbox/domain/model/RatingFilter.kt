package com.photoselectortoolbox.domain.model

import com.photoselectortoolbox.R
import com.photoselectortoolbox.domain.text.UiText
import com.photoselectortoolbox.domain.text.text

/**
 * Filter algebra defining criteria for filtering photos by user/metadata star ratings.
 *
 * Ratings:
 * - `0`: Unrated
 * - `1..5`: Star ratings (1 to 5 stars)
 * - `-1`: Rejected
 */
sealed interface RatingFilter {
    /** A stable English key (test tags, logs); what the chip shows is [text]. */
    val label: String

    /** The chip's words in the UI's language. */
    val text: UiText

    fun matches(rating: Int): Boolean

    /** Passes all photographs regardless of rating. */
    data object All : RatingFilter {
        override val label: String get() = "All"
        override val text: UiText get() = text(R.string.chip_filter_all)
        override fun matches(rating: Int): Boolean = true
    }

    /** Matches photographs with rating >= [minStars]. */
    data class MinRating(val minStars: Int) : RatingFilter {
        init {
            require(minStars in 1..5) { "minStars must be between 1 and 5, got $minStars" }
        }
        override val label: String get() = "★ $minStars+"
        override val text: UiText get() = text(R.string.chip_filter_min_stars, minStars)
        override fun matches(rating: Int): Boolean = rating >= minStars
    }

    /** Matches photographs with exactly [stars] rating. */
    data class ExactRating(val stars: Int) : RatingFilter {
        override val label: String get() = when (stars) {
            -1 -> "Rejected"
            0 -> "Unrated"
            else -> "★ $stars"
        }
        override val text: UiText get() = when (stars) {
            -1 -> text(R.string.chip_filter_rejected)
            0 -> text(R.string.chip_filter_unrated)
            else -> text(R.string.chip_filter_stars, stars)
        }
        override fun matches(rating: Int): Boolean = rating == stars
    }

    /** Matches unrated photographs (rating == 0). */
    data object Unrated : RatingFilter {
        override val label: String get() = "Unrated"
        override val text: UiText get() = text(R.string.chip_filter_unrated)
        override fun matches(rating: Int): Boolean = rating == 0
    }

    /** Matches rejected photographs (rating == -1). */
    data object Rejected : RatingFilter {
        override val label: String get() = "Rejected"
        override val text: UiText get() = text(R.string.chip_filter_rejected)
        override fun matches(rating: Int): Boolean = rating == -1
    }

    companion object {
        /** Commonly used filter presets for UI chips and dropdowns. */
        val PRESETS: List<RatingFilter> = listOf(
            All,
            MinRating(3),
            MinRating(4),
            ExactRating(5),
            Unrated,
            Rejected,
        )
    }
}
