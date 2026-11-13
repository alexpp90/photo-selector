package com.photoselectortoolbox.domain.learn

import com.photoselector.saf.FolderAccessManager
import java.security.MessageDigest

/**
 * The key a shoot summary stores for its folder (shoot debrief SD-3): SHA-256 of the folder's
 * canonical URI ([FolderAccessManager.canonicalUri]), so two spellings of one tree URI — a
 * re-grant can spell it differently — give one key, and the path, which can be a place name, is
 * never stored (C3).
 */
object FolderKey {
    fun of(folderUri: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(FolderAccessManager.canonicalUri(folderUri).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
