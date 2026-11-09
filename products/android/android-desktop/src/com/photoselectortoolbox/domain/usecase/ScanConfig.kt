package com.photoselectortoolbox.domain.usecase

/**
 * Configuration for which analyses to run during a scan (REQ-AND-SCAN.01).
 */
data class ScanConfig(
    val sharpness: Boolean = true,
    val noise: Boolean = true,
    val highlightClipping: Boolean = true,
    val shadowClipping: Boolean = true,
)
