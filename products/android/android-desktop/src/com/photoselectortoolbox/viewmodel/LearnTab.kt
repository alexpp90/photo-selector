package com.photoselectortoolbox.viewmodel

/**
 * Learn's tabs (shoot debrief SD-4, SD-5): the debrief of the folder first, the photo on screen
 * ("Photo", formerly Explore), and the lessons. Compare and Practice folded into the debrief: its
 * worked pair and its tracked focus.
 */
enum class LearnTab(val label: String, val tag: String) {
    DEBRIEF("Debrief", "learn_tab_debrief"),
    EXPLORE("Photo", "learn_tab_explore"),
    LESSONS("Lessons", "learn_tab_lessons"),
}
