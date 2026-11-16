package com.photoselectortoolbox

import android.app.Application
import android.util.Log
import coil.Coil
import com.photoselectortoolbox.data.source.PhotoImageLoader
import dagger.hilt.android.HiltAndroidApp
import org.opencv.android.OpenCVLoader

@HiltAndroidApp
class PhotoSelectorApp : Application() {

    companion object {
        private const val TAG = "PhotoSelectorApp"
    }

    override fun onCreate() {
        super.onCreate()
        initOpenCV()
        initCoil()
    }

    private fun initOpenCV() {
        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully")
        } else {
            Log.e(TAG, "OpenCV initialization failed")
        }
    }

    /**
     * Configure Coil for a screen that holds three large frames at once.
     *
     * The numbers are derived from what this app actually draws, not picked:
     *
     * - Three equal frames of up to 675×450dp render at ~1350×900px on a 2×
     *   density tablet — about 4.9 MB each in `ARGB_8888`, so ~15 MB just for
     *   what is on screen.
     * - A maximised frame is 1211×908dp ≈ 2422×1816px ≈ 17.6 MB on its own.
     * - Neighbour prefetch means several more frames either side are decoded
     *   ahead of the photographer, who typically scrubs back and forth over the
     *   same burst.
     *
     * 30 % of the app's available memory gives roughly 75 MB on a 256 MB heap —
     * enough for the visible three, a maximised frame and a prefetch window,
     * while leaving headroom for the OpenCV analysis bitmaps (2048px, decoded on
     * the analysis path and released immediately). Higher would start competing
     * with the scan; lower would evict a neighbour between two comparisons of
     * the same pair, which is the one thing this screen must never do.
     *
     * There is no disk cache: Coil 2 fills one only from network fetches, never
     * with decoded frames, so for the photos of a SAF folder it stayed empty
     * (see [PhotoImageLoader]). A frame evicted from memory is decoded again from
     * its source.
     */
    private fun initCoil() {
        // Built in PhotoImageLoader so the format conformance test exercises
        // the exact loader — and the RAW/TIFF fetcher — the frames use.
        val imageLoader = PhotoImageLoader.build(this)
        Coil.setImageLoader(imageLoader)
    }
}
