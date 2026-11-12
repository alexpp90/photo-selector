package com.photoselectortoolbox.data.source

import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.photoselectortoolbox.data.model.ImageDimensions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * A frame's shape comes from the photo as it is shown (REQ-AND-PERF.05, REQ-AND-LAYOUT.11): a
 * camera stores a portrait JPEG as landscape pixels with an EXIF orientation of 90° or 270°, and
 * the header alone said landscape, so the frame solver drew a landscape frame for a portrait
 * photo (full review 2026-11-09, archived fix re-applied).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DimensionOrientationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val source = LocalImageSourceImpl(RuntimeEnvironment.getApplication(), Dispatchers.IO)

    private fun jpeg(name: String, orientation: Int?): String {
        val file = File(tmp.root, name)
        FileOutputStream(file).use { Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (orientation != null) {
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
        return Uri.fromFile(file).toString()
    }

    @Test
    fun `a JPEG turned by its EXIF orientation is sized as it is shown`() = runBlocking {
        val upright = jpeg("upright.jpg", ExifInterface.ORIENTATION_NORMAL)
        val turned = jpeg("turned.jpg", ExifInterface.ORIENTATION_ROTATE_90)
        val turnedBack = jpeg("turned270.jpg", ExifInterface.ORIENTATION_ROTATE_270)
        val untagged = jpeg("untagged.jpg", null)

        val sizes = source.resolveDimensions(listOf(upright, turned, turnedBack, untagged))

        assertEquals(ImageDimensions.of(60, 40), sizes[upright])
        assertEquals(ImageDimensions.of(40, 60), sizes[turned])
        assertEquals(ImageDimensions.of(40, 60), sizes[turnedBack])
        assertEquals(ImageDimensions.of(60, 40), sizes[untagged])
    }
}
