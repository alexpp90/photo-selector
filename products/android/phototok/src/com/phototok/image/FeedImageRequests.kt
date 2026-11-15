package com.phototok.image

import android.content.Context
import android.net.Uri
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale

/**
 * Requests that warm a feed photo into Coil's memory cache before the pager reaches it.
 *
 * A request without a target and without a size decodes at `Size.ORIGINAL`: a 12 MP camera photo
 * becomes a ~48 MB bitmap (48 MP: ~190 MB, computed), too large for the memory cache to keep
 * several, so prefetches were full decodes thrown away while the page decoded again on arrival
 * (reported on a Poco F3; measured on Pixel 4a and Moto G77, 2026-11-15). A prefetch is
 * sized to the viewer's page instead: what it caches fits the page the pager draws (Coil's
 * compose image asks for the page's constraints, inexact, fit), so the page is served from the
 * cache under the same key.
 */
object FeedImageRequests {

    fun prefetch(context: Context, uri: Uri, pageWidthPx: Int, pageHeightPx: Int): ImageRequest =
        ImageRequest.Builder(context)
            .data(uri)
            .size(pageWidthPx, pageHeightPx)
            .precision(Precision.INEXACT)
            .scale(Scale.FIT)
            .build()
}
