package com.photoselectortoolbox.data.source

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Analysis bitmaps are capped at 2048 px on the long edge (REQ-AND-PERF.09, FD-P-11). The
 * sample size used to floor, so anything from 2049 to 4095 px was analysed at full size.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnalysisBitmapSizeTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `the analysis size keeps the aspect and never exceeds 2048 on the long edge`() {
        assertEquals(2048 to 1536, PhotoBitmapDecoder.sizeWithin(4000, 3000, 2048))
        assertEquals(2048 to 1365, PhotoBitmapDecoder.sizeWithin(6000, 4000, 2048))
        assertEquals(1024 to 2048, PhotoBitmapDecoder.sizeWithin(3000, 6000, 2048))
        assertEquals(2048 to 2048, PhotoBitmapDecoder.sizeWithin(2049, 2049, 2048))
        assertNull(PhotoBitmapDecoder.sizeWithin(2048, 1365, 2048))
        assertNull(PhotoBitmapDecoder.sizeWithin(0, 0, 2048))
    }

    @Test
    fun `a 3000 px JPEG is decoded for analysis at 2048 px`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = tmp.newFile("wide.jpg")
        file.outputStream().use { Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) }

        val bitmap = PhotoBitmapDecoder(context).decodeForAnalysis(Uri.fromFile(file), "wide.jpg")

        assertNotNull(bitmap)
        assertEquals(2048, maxOf(bitmap!!.width, bitmap.height))
        assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
    }

    @Test
    fun `a bitmap that came back above the cap, as a RAW preview does, is scaled down`() {
        val capped = PhotoBitmapDecoder.capLongEdge(Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888), 2048)
        assertEquals(2048, capped.width)
        assertEquals(1365, capped.height)
    }
}
