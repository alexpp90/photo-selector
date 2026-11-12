package com.photoselector.core.model

/**
 * Capture metadata of one photograph.
 *
 * [make], [model] and [lens] are trimmed — some cameras (Pentax, Olympus) pad
 * them with spaces, which must never reach the screen. [make] and [model] are
 * null when the file does not carry them.
 */
data class ExifData(
    val shutterSpeed: Double? = null,
    val aperture: Double? = null,
    val focalLength: Double? = null,
    val focalLength35mm: Double? = null,
    val iso: Int? = null,
    val lens: String = "Unknown",
    val isFallback: Boolean = false,
    val rating: Int = 0,
    val make: String? = null,
    val model: String? = null,
    val gpsLatitude: Double? = null,
    val gpsLongitude: Double? = null,
    /** GPS UTC time as the EXIF date stamp and time, `YYYY:MM:DD HH:MM:SS`. Location data: never log, persist or transmit. */
    val gpsDateTimeUtc: String? = null,
    val dateTimeOriginal: String? = null,
    val offsetTimeOriginal: String? = null,
    val focalPlaneXResolution: Double? = null,
    val focalPlaneResolutionUnit: Int? = null,
    /*
     * Capture settings, raw as the file wrote them (EXIF 2.32 code values; meaning is the
     * product's, not `:core`'s). Null means the file does not carry the tag; 0 is a value
     * (flash did not fire, auto white balance, auto exposure, 0 EV). Never read from a CR3 file
     * or the MediaStore fallback. Jackpix reads none of them (REQ-TOK-FEATURE.24).
     */
    val exposureProgram: Int? = null,
    val exposureMode: Int? = null,
    /** Exposure compensation in EV. */
    val exposureCompensationEv: Double? = null,
    val meteringMode: Int? = null,
    /** The EXIF Flash bit field. */
    val flash: Int? = null,
    val whiteBalance: Int? = null,
    val digitalZoomRatio: Double? = null,
    val lensSpecification: LensSpecification? = null,
    val customRendered: Int? = null,
    val sceneCaptureType: Int? = null,
    /** Drone gimbal pitch in degrees from the XMP packet's `drone-dji:GimbalPitchDegree`. */
    val gimbalPitchDegrees: Double? = null,
)

/**
 * The EXIF LensSpecification: shortest and longest focal length (mm) and the widest aperture
 * (f-number) at each. An entry the lens did not report (`0/0`) is null.
 */
data class LensSpecification(
    val shortestFocalLength: Double? = null,
    val longestFocalLength: Double? = null,
    val widestApertureAtShortest: Double? = null,
    val widestApertureAtLongest: Double? = null,
)
