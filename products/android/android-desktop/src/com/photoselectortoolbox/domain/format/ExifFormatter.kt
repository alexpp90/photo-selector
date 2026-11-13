package com.photoselectortoolbox.domain.format

import com.photoselector.core.model.ExifData
import java.util.Locale
import com.photoselectortoolbox.domain.text.text
import com.photoselectortoolbox.domain.text.raw
import com.photoselectortoolbox.domain.text.UiText
import com.photoselectortoolbox.R

/**
 * Turns EXIF values into the strings the selector shows.
 *
 * Kept free of Compose and Android types so the wording, the ordering and —
 * importantly — the decimal separator are unit-testable. Every number is
 * formatted with an explicit [Locale.US]: a photographer reading "f/2,8" on a
 * German device has to stop and re-parse it, and the desktop app writes the
 * same values with a dot.
 */
object ExifFormatter {

    /** `1/250s` for fractional exposures, `2.0s` for long ones. */
    fun shutterSpeed(speed: Double): String = when {
        speed <= 0.0 -> "—"
        // Rounded, not truncated: 0.004000000000000001 s is 1/250 s, not 1/249 s.
        speed < 1.0 -> "1/${kotlin.math.round(1.0 / speed).toInt()}s"
        else -> String.format(Locale.US, "%.1fs", speed)
    }

    /** `f/2.8` */
    fun aperture(value: Double): String = String.format(Locale.US, "f/%.1f", value)

    /** `35mm` */
    fun focalLength(value: Double): String = "${value.toInt()}mm"

    /** `ISO 400` */
    fun iso(value: Int): String = "ISO $value"

    /**
     * The single-line EXIF summary under a frame:
     * `1/250s · f/2.8 · 35mm · ISO 400`.
     *
     * Missing values are dropped rather than shown as placeholders — a line of
     * em-dashes is noise, and the details panel is where absence is explicit.
     * Returns null when nothing at all is known, so callers can omit the row.
     */
    fun summaryLine(exif: ExifData?): String? {
        if (exif == null) return null
        val parts = buildList {
            exif.shutterSpeed?.let { add(shutterSpeed(it)) }
            exif.aperture?.let { add(aperture(it)) }
            exif.focalLength?.let { add(focalLength(it)) }
            exif.iso?.let { add(iso(it)) }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
    }

    /**
     * The exposure block for a neighbour frame's value overlay: at most two
     * short lines, e.g. `1/250s · f/2.8` then `35mm · ISO 400`.
     *
     * Two lines rather than one truncated line, and always split at the same
     * point, because the comparison being made is *between* Previous and Next:
     * shutter speed sits above shutter speed and ISO above ISO, so a difference
     * is a difference in one place rather than a re-read of two strings. A
     * single line ellipsised at 148 dp would drop ISO — the field most likely
     * to differ inside a burst — which is the opposite of useful.
     *
     * The lens is not part of this block: a lens name has no fixed length and
     * cannot be split at a token, so it is its own line ([lensLine]) that the
     * overlay draws beneath these two and ellipsises.
     *
     * Returns an empty list when nothing is known, so the caller omits the
     * block rather than drawing an empty one over a photograph.
     */
    fun overlayLines(exif: ExifData?): List<String> {
        if (exif == null) return emptyList()
        val exposure = buildList {
            exif.shutterSpeed?.let { add(shutterSpeed(it)) }
            exif.aperture?.let { add(aperture(it)) }
        }
        val optics = buildList {
            exif.focalLength?.let { add(focalLength(it)) }
            exif.iso?.let { add(iso(it)) }
        }
        return buildList {
            exposure.takeIf { it.isNotEmpty() }?.let { add(it.joinToString(" · ")) }
            optics.takeIf { it.isNotEmpty() }?.let { add(it.joinToString(" · ")) }
        }
    }

    /**
     * The lens name for a single line under the exposure, or null when there is
     * nothing worth a line: no EXIF, a blank string, or [UNKNOWN] (what the readers
     * store when the file carried no lens). Unlike [detailRows], where "Unknown"
     * is information, a line reading "Unknown" under a photograph is noise.
     */
    fun lensLine(exif: ExifData?): String? =
        exif?.lens?.trim()?.takeIf { it.isNotEmpty() && !it.equals(UNKNOWN, ignoreCase = true) }

    /**
     * The label/value pairs for the details panel, in display order.
     *
     * Unlike [summaryLine] this keeps every field and renders unknowns as
     * "Unknown": in the panel, "we could not read the lens" is information the
     * photographer wants, not clutter.
     */
    fun detailRows(exif: ExifData?): List<Pair<UiText, UiText>> {
        if (exif == null) return emptyList()
        val unknown = text(R.string.label_unknown)
        fun value(v: String?): UiText = v?.let(::raw) ?: unknown
        return listOf(
            text(R.string.label_exif_shutter_speed) to value(exif.shutterSpeed?.let { shutterSpeed(it) }),
            text(R.string.label_exif_aperture) to value(exif.aperture?.let { aperture(it) }),
            text(R.string.label_exif_focal_length) to value(exif.focalLength?.let { focalLength(it) }),
            text(R.string.label_exif_focal_35mm) to value(exif.focalLength35mm?.let { focalLength(it) }),
            text(R.string.label_exif_iso) to value(exif.iso?.toString()),
            // The model's own "Unknown" for a file without a lens tag reads as the UI's word.
            text(R.string.label_exif_lens) to (if (exif.lens == UNKNOWN) unknown else raw(exif.lens)),
        )
    }

    const val UNKNOWN = "Unknown"
}
