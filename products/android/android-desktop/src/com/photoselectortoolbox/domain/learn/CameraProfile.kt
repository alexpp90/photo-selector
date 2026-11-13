package com.photoselectortoolbox.domain.learn

import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.domain.analysis.PhotoCoachEngineImpl

/** A setting a camera class lets the photographer change, which advice may name (C2). */
enum class Control {
    APERTURE,
    SHUTTER,
    ISO,
    EXPOSURE_COMPENSATION,
    /** A zoom, or a choice among the phone's lenses: "use a different focal length". */
    FOCAL_LENGTH,
    /** Changing the lens itself: interchangeable-lens cameras only. */
    LENS,
    FOCUS,
    GIMBAL_PITCH,
}

/** Who chose the exposure time: worded "you chose 1/30 s" or "the camera chose 1/30 s". */
enum class DecidedBy(val key: String) {
    PHOTOGRAPHER("photographer"),
    CAMERA("camera"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun ofKey(key: String?): DecidedBy = entries.firstOrNull { it.key == key } ?: UNKNOWN
    }
}

/**
 * What Learn knows about the camera that took a photo (shoot debrief SD-2b, findings S6 and C2):
 * its [cameraClass], the [controls] advice may name, who chose the exposure ([decidedBy]), how sure
 * the rule is ([confidence], 0 for unknown) and the [evidence] it went by.
 */
data class CameraProfile(
    val cameraClass: CameraClass,
    val controls: Set<Control>,
    val decidedBy: DecidedBy,
    val confidence: Double,
    val evidence: String,
) {
    companion object {
        val UNKNOWN = CameraProfile(CameraClass.UNKNOWN, emptySet(), DecidedBy.UNKNOWN, 0.0, "no camera data")

        /** The controls of each class (`SD2B_RESEARCH.md` §2 word lists). */
        fun controlsOf(cameraClass: CameraClass): Set<Control> = when (cameraClass) {
            CameraClass.INTERCHANGEABLE -> setOf(
                Control.APERTURE, Control.SHUTTER, Control.ISO, Control.EXPOSURE_COMPENSATION,
                Control.FOCAL_LENGTH, Control.LENS, Control.FOCUS,
            )
            CameraClass.COMPACT -> setOf(
                Control.APERTURE, Control.SHUTTER, Control.ISO, Control.EXPOSURE_COMPENSATION,
                Control.FOCAL_LENGTH, Control.FOCUS,
            )
            CameraClass.PHONE -> setOf(Control.FOCUS, Control.FOCAL_LENGTH)
            CameraClass.DRONE -> setOf(Control.SHUTTER, Control.ISO, Control.EXPOSURE_COMPENSATION, Control.GIMBAL_PITCH)
            CameraClass.ACTION, CameraClass.UNKNOWN -> emptySet()
        }
    }
}

/**
 * The camera class of a photo from its EXIF, by the photo researcher's rules (`SD2B_RESEARCH.md`
 * §1, 2026-11-13), first match wins: a user's override → no data → drone → action cam → phone →
 * compact → interchangeable → unknown. Never from the 35 mm equivalent focal length alone (phones
 * write 24–27 mm, and the old make-blind rule called an RX100 a phone); keywords match whole words.
 * Points the research marks unverified (DJI Osmo model strings, the gimbal tag in a given drone's
 * files, Insta360's equirectangular flag) are not guessed: they fall through to the class the
 * remaining rules give, else unknown.
 */
object CameraProfiler {

    /** The crop factor that separates a compact (1" sensors and smaller) from MFT and larger. */
    const val COMPACT_CROP = 2.3

    /** A phone's physical focal length is below this (its main camera is 4–9 mm). */
    private const val PHONE_FOCAL_MM = 10.5

    /** A phone's crop factor is above this. */
    private const val PHONE_CROP = 2.5

    private val DRONE_MAKES = listOf("parrot", "autel robotics", "autel", "skydio", "yuneec")
    private val DJI_DRONE_MODEL = Regex(
        """\b(FC\d+|Mavic|Mini|Air|Phantom|Inspire|Avata|Neo|Matrice|M\d\w*|Zenmuse|H20\w*)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val HASSELBLAD_DRONE_MODEL = Regex("""\bL[12]D-20c\b""", RegexOption.IGNORE_CASE)
    private val ACTION_MAKES = listOf("gopro", "insta360", "sjcam", "akaso")
    private val SONY_ACTION_MODEL = Regex("""\b(HDR-AS|FDR-X)""", RegexOption.IGNORE_CASE)
    private val PHONE_WORDS = Regex(
        """\b(apple|iphone|google|pixel|xiaomi|redmi|poco|oneplus|huawei|honor|oppo|vivo|motorola|realme|nothing|fairphone|xperia)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SAMSUNG_PHONE_MODEL = Regex("""(\bSM-[A-Z]\d|\bgalaxy\b)""", RegexOption.IGNORE_CASE)

    /** EXIF ExposureProgram: 1 manual, 4 shutter priority. ExposureMode: 1 manual. */
    private const val PROGRAM_MANUAL = 1
    private const val PROGRAM_SHUTTER_PRIORITY = 4
    private const val MODE_MANUAL = 1

    fun profile(
        exif: ExifData?,
        imageWidth: Int? = null,
        overrides: Map<CameraId, CameraClass> = emptyMap(),
    ): CameraProfile {
        if (exif == null) return CameraProfile.UNKNOWN
        val make = exif.make?.trim().orEmpty()
        val model = exif.model?.trim().orEmpty()
        val names = "$make $model"
        val crop = cropFactor(exif, imageWidth)

        overrides[CameraId(make, model)]?.let { chosen ->
            return of(chosen, exif, 1.0, "set by you for ${CameraId(make, model).displayName}")
        }
        val hasCaptureData = exif.focalLength != null || exif.focalLength35mm != null || crop != null ||
            exif.shutterSpeed != null || exif.iso != null || exif.aperture != null
        if ((make.isEmpty() && model.isEmpty()) || !hasCaptureData) return CameraProfile.UNKNOWN

        val makeLower = make.lowercase()
        val dji = makeLower.startsWith("dji")
        when {
            dji && model.contains("osmo action", ignoreCase = true) -> return of(CameraClass.ACTION, exif, 0.95, "DJI Osmo Action")
            dji && DJI_DRONE_MODEL.containsMatchIn(model) -> return of(CameraClass.DRONE, exif, 0.95, "DJI model $model")
            makeLower.startsWith("hasselblad") && HASSELBLAD_DRONE_MODEL.containsMatchIn(model) ->
                return of(CameraClass.DRONE, exif, 0.95, "Hasselblad drone camera $model")
            DRONE_MAKES.any { makeLower.startsWith(it) } -> return of(CameraClass.DRONE, exif, 0.95, "drone make $make")
            exif.gimbalPitchDegrees != null -> return of(CameraClass.DRONE, exif, 0.9, "gimbal pitch in the photo")
        }
        when {
            ACTION_MAKES.any { makeLower.startsWith(it) } -> return of(CameraClass.ACTION, exif, 0.95, "action cam make $make")
            makeLower.startsWith("garmin") && model.contains("virb", ignoreCase = true) ->
                return of(CameraClass.ACTION, exif, 0.95, "Garmin VIRB")
            makeLower.startsWith("sony") && SONY_ACTION_MODEL.containsMatchIn(model) ->
                return of(CameraClass.ACTION, exif, 0.95, "Sony action cam $model")
        }
        val phoneName = PHONE_WORDS.containsMatchIn(names) ||
            (makeLower.startsWith("samsung") && SAMSUNG_PHONE_MODEL.containsMatchIn(model))
        val smallFocal = exif.focalLength?.let { it.isFinite() && it > 0.0 && it < PHONE_FOCAL_MM } == true
        if (phoneName && (smallFocal || (crop != null && crop > PHONE_CROP))) {
            return of(CameraClass.PHONE, exif, 0.9, "phone $names".trim())
        }
        if (crop != null) {
            val from = if (cropFromFocalLengths(exif) != null) "35 mm focal length" else "focal-plane tags"
            val c = String.format(java.util.Locale.US, "%.1f", crop)
            return if (crop > COMPACT_CROP) {
                of(CameraClass.COMPACT, exif, 0.75, "crop $c from $from")
            } else {
                of(CameraClass.INTERCHANGEABLE, exif, if (from == "focal-plane tags") 0.7 else 0.8, "crop $c from $from")
            }
        }
        return CameraProfile.UNKNOWN
    }

    /**
     * The crop factor: the 35 mm equivalent over the physical focal length, unless a digital zoom
     * was applied (then that ratio is wrong); else the focal-plane tags, only with the photo's real
     * pixel width (the tags are per pixel of the original frame, so an assumed width is no route).
     */
    fun cropFactor(exif: ExifData, imageWidth: Int?): Double? {
        val zoomed = exif.digitalZoomRatio?.let { it.isFinite() && it > 1.0 } == true
        if (!zoomed) cropFromFocalLengths(exif)?.let { return it }
        val width = imageWidth?.takeIf { it > 0 } ?: return null
        return PhotoCoachEngineImpl.computeCropFactor(exif.copy(focalLength35mm = null), width)
    }

    private fun cropFromFocalLengths(exif: ExifData): Double? {
        val f35 = exif.focalLength35mm ?: return null
        val f = exif.focalLength ?: return null
        if (!f35.isFinite() || !f.isFinite() || f35 <= 0.0 || f <= 0.0) return null
        return (f35 / f).takeIf { it.isFinite() && it > 0.0 }
    }

    private fun of(cameraClass: CameraClass, exif: ExifData, confidence: Double, evidence: String) = CameraProfile(
        cameraClass = cameraClass,
        controls = CameraProfile.controlsOf(cameraClass),
        decidedBy = decidedBy(cameraClass, exif),
        confidence = confidence,
        evidence = evidence,
    )

    /**
     * Who chose the exposure time, asked only of cameras with a shutter the photographer sets
     * (never a phone, drone or action cam): manual exposure or shutter priority — the photographer;
     * program, aperture priority and scene modes — the camera; with no program written, a non-zero
     * exposure compensation says an automatic mode was biased, so the camera chose.
     */
    fun decidedBy(cameraClass: CameraClass, exif: ExifData): DecidedBy {
        if (cameraClass != CameraClass.INTERCHANGEABLE && cameraClass != CameraClass.COMPACT) return DecidedBy.UNKNOWN
        val program = exif.exposureProgram
        return when {
            exif.exposureMode == MODE_MANUAL || program == PROGRAM_MANUAL || program == PROGRAM_SHUTTER_PRIORITY -> DecidedBy.PHOTOGRAPHER
            program != null && program in 2..8 -> DecidedBy.CAMERA
            exif.exposureCompensationEv?.let { it.isFinite() && kotlin.math.abs(it) > 0.05 } == true -> DecidedBy.CAMERA
            else -> DecidedBy.UNKNOWN
        }
    }
}
