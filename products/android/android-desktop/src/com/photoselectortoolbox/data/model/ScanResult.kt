package com.photoselectortoolbox.data.model

import com.photoselector.core.model.ExifData

data class ScanResult(
    val filePath: String,
    val sharpnessScore: Double? = null,
    val noiseLevel: Double? = null,
    val highlightClipping: Double? = null,
    val shadowClipping: Double? = null,
    /**
     * Not computed on this product (REQ-AND-FEATURE.02, dropped for good on 2026-11-16, D5): a
     * fresh scan writes null. Kept only so cached rows keep their Room shape.
     */
    val aestheticScore: Double? = null,
    val exifData: ExifData? = null
)
