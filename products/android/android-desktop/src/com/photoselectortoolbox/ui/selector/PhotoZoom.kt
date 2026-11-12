package com.photoselectortoolbox.ui.selector

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * Zoom and pan of one viewport — the fullscreen viewer, or the maximised frame — shared by every
 * photo it shows, so stepping with `←` / `→` at 100 % keeps the zoom and the pan offset (backlog
 * 2026-11-11, `docs/shared/KEYBOARD.md`). Scale 1 is fit; [oneToOne] is one image pixel per device
 * pixel for the photo on screen. The offset is a translation of the scaled photo about the
 * viewport's centre, in pixels.
 */
@Stable
class PhotoZoom {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    /** 1:1 for the photo on screen in this viewport, reported by whoever draws it. */
    var oneToOne by mutableFloatStateOf(FALLBACK_ZOOM)

    /** The viewport's size in pixels, for the 20 % pan steps. */
    var viewport by mutableStateOf(IntSize.Zero)

    val isZoomed: Boolean get() = scale > ZOOMED_THRESHOLD

    /** The zoom as a share of 1:1, for the pill: 100 at one image pixel per device pixel. */
    val percent: Int get() = (scale / oneToOne * 100f).roundToInt()

    /** `Space`, a double tap: fit ↔ 100 %. */
    fun toggle() = if (isZoomed) reset() else set(oneToOne, Offset.Zero)

    fun reset() = set(1f, Offset.Zero)

    /**
     * The wheel: zoom by [factor] keeping the photo point under [focus] (relative to the
     * viewport's centre) where it is. Back at fit, the pan is dropped.
     */
    fun zoomAt(factor: Float, focus: Offset) {
        val next = (scale * factor).coerceIn(1f, MAX_ZOOM)
        if (next <= ZOOMED_THRESHOLD) return reset()
        set(next, focus - (focus - offset) * (next / scale))
    }

    /** A pinch: the same rule as the transform gesture always had. */
    fun transform(zoomChange: Float, pan: Offset) {
        val next = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
        set(next, if (next > 1f) offset + pan else Offset.Zero)
    }

    /** A drag while zoomed. */
    fun panBy(pan: Offset) {
        if (isZoomed) offset += pan
    }

    /**
     * `Shift`+arrow: show 20 % more of the photo towards [dx] / [dy] (−1, 0, 1). Returns false
     * when not zoomed, so the key keeps its unzoomed meaning (stepping).
     */
    fun panStep(dx: Int, dy: Int): Boolean {
        if (!isZoomed) return false
        offset -= Offset(viewport.width * PAN_STEP * dx, viewport.height * PAN_STEP * dy)
        return true
    }

    private fun set(nextScale: Float, nextOffset: Offset) {
        scale = nextScale
        offset = nextOffset
    }

    companion object {
        /** Beyond this the photo counts as zoomed: the pager stops, the navigator shows. */
        const val ZOOMED_THRESHOLD = 1.05f

        /** Hard ceiling on magnification, pinch, wheel or key. */
        const val MAX_ZOOM = 8f

        /** One wheel notch. */
        const val WHEEL_STEP = 1.25f

        /** One `Shift`+arrow press, as a share of the viewport. */
        const val PAN_STEP = 0.2f

        /** A photo already shown at or above 1:1 still gets a visible step. */
        const val MIN_MEANINGFUL_ZOOM = 1.6f

        /** When the photo's pixel size was never read. */
        const val FALLBACK_ZOOM = 2.5f

        /**
         * The scale at which a [pixelWidth] × [pixelHeight] photo, fitted (`ContentScale.Fit`)
         * into a [boxWidth] × [boxHeight] px box, shows one image pixel per device pixel.
         */
        fun oneToOneScale(pixelWidth: Int, pixelHeight: Int, boxWidth: Float, boxHeight: Float): Float {
            if (pixelWidth <= 0 || pixelHeight <= 0) return FALLBACK_ZOOM
            val displayedWidth = minOf(boxWidth, boxHeight * pixelWidth.toFloat() / pixelHeight.toFloat())
            if (displayedWidth <= 0f) return FALLBACK_ZOOM
            return (pixelWidth / displayedWidth).coerceIn(MIN_MEANINGFUL_ZOOM, MAX_ZOOM)
        }
    }
}
