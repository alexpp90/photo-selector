package com.phototok.image

import android.net.Uri
import coil.size.Dimension
import coil.size.Precision
import coil.size.Scale
import coil.size.Size
import com.photoselector.core.Requirement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The neighbour prefetch decodes for the viewer's page, never at the photo's full resolution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FeedImageRequestsTest {

    private val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADCIM%2FCamera%2FIMG_1.jpg")

    @Test
    @Requirement("REQ-TOK-ARCH.23")
    fun `a prefetch is sized to the page, inexact and fitted`() = runTest {
        val request = FeedImageRequests.prefetch(RuntimeEnvironment.getApplication(), uri, 1080, 2340)

        // Without a size a request with no target decodes at Size.ORIGINAL (48 MP: ~190 MB).
        assertEquals(Size(Dimension(1080), Dimension(2340)), request.sizeResolver.size())
        assertEquals(Precision.INEXACT, request.precision)
        assertEquals(Scale.FIT, request.scale)
        assertEquals(uri, request.data)
    }
}
