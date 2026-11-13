package com.photoselectortoolbox.viewmodel

import androidx.annotation.StringRes
import com.photoselectortoolbox.R

/**
 * Learn's tabs (shoot debrief SD-4, SD-5): the debrief of the folder first, the photo on screen
 * ("Photo", formerly Explore), and the lessons. Compare and Practice folded into the debrief: its
 * worked pair and its tracked focus.
 */
enum class LearnTab(@StringRes val label: Int, val tag: String) {
    DEBRIEF(R.string.label_tab_debrief, "learn_tab_debrief"),
    EXPLORE(R.string.label_tab_photo, "learn_tab_explore"),
    LESSONS(R.string.label_tab_lessons, "learn_tab_lessons"),
}
