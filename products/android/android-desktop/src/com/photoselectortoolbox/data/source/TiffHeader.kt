package com.photoselectortoolbox.data.source

import com.photoselectortoolbox.data.model.ImageDimensions
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A TIFF's size as it is shown, from the first IFD of its header only: `ImageWidth` (256),
 * `ImageLength` (257), each SHORT or LONG, and `Orientation` (274), whose values 5 to 8 turn the
 * frame by 90° (finding FD-C-23, full review 2026-11-09). Android has no TIFF codec, so the
 * platform's bounds read finds nothing. Reads at most [MAX_HEADER_BYTES]; a header that is
 * cut short, has no size, or puts IFD0 further in reads as [ImageDimensions.UNKNOWN].
 */
internal object TiffHeader {

    /** Scanners and exporters write IFD0 right after the 8-byte header; this covers far more. */
    const val MAX_HEADER_BYTES = 64 * 1024

    private const val TAG_WIDTH = 256
    private const val TAG_LENGTH = 257
    private const val TAG_ORIENTATION = 274
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4

    fun dimensions(stream: InputStream): ImageDimensions {
        val bytes = stream.readNBytesCompat(MAX_HEADER_BYTES)
        if (bytes.size < 8) return ImageDimensions.UNKNOWN
        val order = when {
            bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> return ImageDimensions.UNKNOWN
        }
        val buffer = ByteBuffer.wrap(bytes).order(order)
        if (buffer.getShort(2).toInt() != 42) return ImageDimensions.UNKNOWN
        val ifd = buffer.getInt(4).toLong() and 0xFFFFFFFFL
        if (ifd < 8 || ifd + 2 > bytes.size) return ImageDimensions.UNKNOWN
        val count = buffer.getShort(ifd.toInt()).toInt() and 0xFFFF

        var width = 0L
        var length = 0L
        var orientation = 1L
        for (i in 0 until count) {
            val entry = ifd.toInt() + 2 + i * 12
            if (entry + 12 > bytes.size) break
            val tag = buffer.getShort(entry).toInt() and 0xFFFF
            val value = when (buffer.getShort(entry + 2).toInt() and 0xFFFF) {
                TYPE_SHORT -> (buffer.getShort(entry + 8).toLong() and 0xFFFFL)
                TYPE_LONG -> (buffer.getInt(entry + 8).toLong() and 0xFFFFFFFFL)
                else -> continue
            }
            when (tag) {
                TAG_WIDTH -> width = value
                TAG_LENGTH -> length = value
                TAG_ORIENTATION -> orientation = value
            }
        }
        if (width <= 0 || length <= 0 || width > Int.MAX_VALUE || length > Int.MAX_VALUE) {
            return ImageDimensions.UNKNOWN
        }
        return if (orientation in 5..8) {
            ImageDimensions.of(length.toInt(), width.toInt())
        } else {
            ImageDimensions.of(width.toInt(), length.toInt())
        }
    }

    /** Up to [limit] bytes (`InputStream.readNBytes` is API 33+). */
    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = ByteArray(limit)
        var read = 0
        while (read < limit) {
            val n = read(out, read, limit - read)
            if (n < 0) break
            read += n
        }
        return out.copyOf(read)
    }
}
