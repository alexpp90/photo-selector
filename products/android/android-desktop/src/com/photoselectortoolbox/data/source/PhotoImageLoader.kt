package com.photoselectortoolbox.data.source

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.memory.MemoryCache
import coil.request.Options
import coil.size.pxOrElse
import com.photoselectortoolbox.domain.format.ImageFormats

/**
 * Coil fetcher for the formats Android's image decoders cannot handle: camera
 * RAWs (CR3 has no platform codec at all; others vary by device) and TIFF.
 * It hands them to [PhotoBitmapDecoder], which serves the embedded camera
 * JPEG of a RAW — never a full RAW decode on the first-paint path.
 *
 * Every other URI is declined and goes through Coil's default pipeline
 * unchanged. Returning null from [fetch] (nothing decodable) also falls back
 * to the default pipeline.
 */
class PhotoFetcher(
    private val uri: Uri,
    private val fileName: String,
    private val options: Options,
    private val decoder: PhotoBitmapDecoder,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val requested = maxOf(
            options.size.width.pxOrElse { 0 },
            options.size.height.pxOrElse { 0 },
        )
        val target = if (requested > 0) requested else UNSIZED_LONG_EDGE
        val bitmap = decoder.decodeForDisplay(uri, fileName, target) ?: return null
        return DrawableResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val decoder: PhotoBitmapDecoder) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != "content" && data.scheme != "file") return null
            // SAF document ids end in the display name; no I/O to decide.
            val name = data.lastPathSegment ?: return null
            if (!ImageFormats.isRaw(name) && !ImageFormats.isTiff(name)) return null
            return PhotoFetcher(data, name, options, decoder)
        }
    }

    companion object {
        /** Long edge used when a request has no size (e.g. a wrap-content image). */
        const val UNSIZED_LONG_EDGE = 2048
    }
}

/**
 * The app's single Coil [ImageLoader]. See `PhotoSelectorApp.initCoil` for
 * why the memory cache size is what it is.
 *
 * **No disk cache.** Coil 2 writes its disk cache only from network fetches
 * (`HttpUriFetcher`, the one fetcher that references `coil.disk.DiskCache`);
 * decoded frames are never written to disk, and a `content://` photo is read
 * from its provider whenever it is not in the memory cache. This product has
 * no network access, so the 512 MB disk cache it used to configure stayed
 * empty (scale-contract 2026-11-15, `ImageLoaderDiskCacheTest`). `null` also
 * keeps Coil from creating its default singleton disk cache.
 */
object PhotoImageLoader {
    fun build(context: Context): ImageLoader {
        val app = context.applicationContext
        return ImageLoader.Builder(app)
            .components { add(PhotoFetcher.Factory(PhotoBitmapDecoder(app))) }
            .memoryCache {
                MemoryCache.Builder(app)
                    .maxSizePercent(0.30)
                    .build()
            }
            .diskCache(null)
            .build()
    }
}
