package com.photoselectortoolbox.data.metadata

/**
 * The backup document a JPEG rating write keeps beside the photo while it overwrites it
 * (finding FD-D-11). Embedding the rating rewrites the user's original in place; a kill, a crash
 * or a card pulled out between the truncate and the last byte would otherwise leave a cut-off
 * photo and no copy anywhere but in the memory of a process that is gone.
 *
 * Order of a write ([SafRatingWriter]): the backup is created, written and read back *before* the
 * photo is opened for writing, and deleted only after the photo read back as written (or was
 * restored and read back). So whenever a backup exists, one of three things is true, and the next
 * folder open ([SafRatingWriter.recover]) tells them apart:
 *
 * 1. The photo starts with every byte of the backup (equal, or the backup was cut off while being
 *    written): the photo was never touched. The backup is deleted.
 * 2. The photo is a complete JPEG ([isCompleteJpeg]) that differs from the backup: the write
 *    finished and only the backup's delete did not. The backup is kept and reported, because
 *    nothing here can prove which of the two the photographer wants.
 * 3. Anything else (cut off, empty, missing an end marker): the backup is written over the photo,
 *    read back, and deleted. This is the safe direction: at worst a rating is lost, never a photo.
 *
 * The name is the photo's name plus [SUFFIX], visible in a file manager, never an image or
 * sidecar extension: discovery does not list it and no bundle moves it.
 */
object RatingBackups {
    const val SUFFIX = ".frameduel-backup"

    fun backupNameFor(photoName: String): String = photoName + SUFFIX

    /** The photo a backup belongs to, or null when [name] is not a backup. */
    fun photoNameOf(name: String): String? =
        if (name.endsWith(SUFFIX) && name.length > SUFFIX.length) name.dropLast(SUFFIX.length) else null

    /** True when [photo] begins with every byte of [backup]: the photo was never overwritten. */
    fun photoUntouched(photo: ByteArray, backup: ByteArray): Boolean {
        if (photo.size < backup.size) return false
        for (i in backup.indices) if (photo[i] != backup[i]) return false
        return true
    }

    /**
     * A JPEG that starts with SOI, ends with EOI and whose header [decodesBounds]. A write cut
     * off half way keeps its header but loses the end marker, which is why the end is checked.
     */
    fun isCompleteJpeg(bytes: ByteArray, decodesBounds: (ByteArray) -> Boolean): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[bytes.size - 2] == 0xFF.toByte() && bytes[bytes.size - 1] == 0xD9.toByte() &&
            decodesBounds(bytes)
}

/** A backup [RatingBackups] could not resolve on its own; it stays in the folder. */
data class KeptRatingBackup(val photoName: String, val backupName: String)

/** What a folder open found of interrupted JPEG rating writes. */
data class RatingRecovery(
    /** Photos written back from their backup. */
    val restored: List<String> = emptyList(),
    /** Backups left in place for the photographer to check. */
    val kept: List<KeptRatingBackup> = emptyList(),
) {
    val isEmpty: Boolean get() = restored.isEmpty() && kept.isEmpty()

    companion object {
        val NONE = RatingRecovery()
    }
}
