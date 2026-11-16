package com.photoselectortoolbox.data.source

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the app's Coil loader caches for the photos it shows (REQ-AND-PERF.09).
 *
 * Coil 2.7 writes its disk cache only from `HttpUriFetcher`: the only fetcher among its classes
 * that references `coil.disk.DiskCache` (checked in the coil-base 2.7.0 jar, 2026-11-15). Decoded
 * frames are never written to disk, and a `content://` photo is read from its provider every time
 * it is not in the memory cache. This product has no network access, so a disk cache would only
 * ever stay empty; the loader is built without one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageLoaderDiskCacheTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    // REQ-AND-PERF.09
    @Test
    fun `a content photo is read from its provider again when it is not in memory, and nothing goes to disk`() = runBlocking {
        val jpeg = ByteArrayOutputStream().also {
            Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        val uri = Uri.parse("content://scale.photos/document/IMG_0001.jpg")
        val opens = AtomicInteger()
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            opens.incrementAndGet()
            ByteArrayInputStream(jpeg)
        }
        val loader = PhotoImageLoader.build(context)
        fun request() = ImageRequest.Builder(context).data(uri).size(32)
            .memoryCachePolicy(CachePolicy.DISABLED).build()

        val first = loader.execute(request())
        val second = loader.execute(request())

        assertTrue("decoded: $first", first is SuccessResult && second is SuccessResult)
        assertEquals("each load outside the memory cache reads the provider", 2, opens.get())
        assertNull("no disk cache: Coil 2 fills one only from network fetches", loader.diskCache)
        assertTrue(
            "nothing is written under cacheDir/image_cache",
            context.cacheDir.resolve("image_cache").listFiles().isNullOrEmpty(),
        )
    }
}
