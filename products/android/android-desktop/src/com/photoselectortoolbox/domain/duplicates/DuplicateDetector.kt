package com.photoselectortoolbox.domain.duplicates

import android.content.Context
import android.net.Uri
import com.photoselectortoolbox.data.model.DuplicateGroup
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Progress state emitted during duplicate detection.
 */
data class DuplicateDetectionProgress(
    val processed: Int,
    val total: Int,
    val groups: List<DuplicateGroup>
)

/**
 * Detects duplicate files by first grouping by file size, then computing
 * SHA-256 hashes for files with matching sizes. Same optimization strategy
 * as the desktop version: only hash files when multiple share the same size.
 */
class DuplicateDetector @Inject constructor() {

    companion object {
        private const val HASH_ALGORITHM = "SHA-256"
        private const val BUFFER_SIZE = 8192
    }

    /**
     * Find duplicate files from the given list.
     *
     * @param uris List of pairs: (content URI, file size in bytes).
     * @param context Android context for content resolver access.
     * @return List of duplicate groups, each containing files with identical content.
     */
    suspend fun findDuplicates(
        uris: List<Pair<Uri, Long>>,
        context: Context
    ): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val sizeGroups = groupBySize(uris)
        val duplicateGroups = mutableListOf<DuplicateGroup>()

        for ((_, group) in sizeGroups) {
            ensureActive()
            if (group.size < 2) continue

            val hashGroups = hashAndGroup(group, context)
            duplicateGroups.addAll(hashGroups)
        }

        duplicateGroups
    }

    /**
     * Find duplicates with progress reporting via Flow.
     *
     * @param uris List of pairs: (content URI, file size in bytes).
     * @param context Android context for content resolver access.
     * @return Flow emitting progress updates with discovered duplicate groups.
     */
    fun findDuplicatesWithProgress(
        uris: List<Pair<Uri, Long>>,
        context: Context
    ): Flow<DuplicateDetectionProgress> = flow {
        val sizeGroups = groupBySize(uris)

        // Count only files that need hashing (in groups of 2+)
        val candidateGroups = sizeGroups.filter { it.value.size >= 2 }
        val totalToHash = candidateGroups.values.sumOf { it.size }
        var processed = 0
        val duplicateGroups = mutableListOf<DuplicateGroup>()

        emit(DuplicateDetectionProgress(0, totalToHash, emptyList()))

        for ((_, group) in candidateGroups) {
            val hashGroups = hashAndGroup(group, context) { hashesCompleted ->
                processed += hashesCompleted
                // Emit progress inside the hashing loop
            }

            duplicateGroups.addAll(hashGroups)

            emit(
                DuplicateDetectionProgress(
                    processed = processed,
                    total = totalToHash,
                    groups = duplicateGroups.toList()
                )
            )
        }

        // Final emission with all groups
        emit(
            DuplicateDetectionProgress(
                processed = totalToHash,
                total = totalToHash,
                groups = duplicateGroups.toList()
            )
        )
    }.flowOn(Dispatchers.IO)

    /**
     * Group URIs by file size. Only groups with 2+ files are potential duplicates.
     */
    private fun groupBySize(uris: List<Pair<Uri, Long>>): Map<Long, List<Uri>> {
        return uris.groupBy(
            keySelector = { it.second },
            valueTransform = { it.first }
        )
    }

    /**
     * Hash files in a same-size group and return duplicate groups.
     */
    private suspend fun hashAndGroup(
        uris: List<Uri>,
        context: Context,
        onFileHashed: ((Int) -> Unit)? = null
    ): List<DuplicateGroup> {
        val hashMap = mutableMapOf<String, MutableList<String>>()

        for (uri in uris) {
            coroutineContext.ensureActive()
            val hash = computeHash(uri, context)
            if (hash != null) {
                hashMap.getOrPut(hash) { mutableListOf() }.add(uri.toString())
            }
            onFileHashed?.invoke(1)
        }

        return hashMap
            .filter { it.value.size > 1 }
            .map { (hash, files) -> DuplicateGroup(hash = hash, files = files) }
    }

    /**
     * Compute SHA-256 hash of a file via streaming DigestInputStream.
     * Returns null if the file cannot be read.
     */
    private suspend fun computeHash(uri: Uri, context: Context): String? =
        withContext(Dispatchers.IO) {
            try {
                val digest = MessageDigest.getInstance(HASH_ALGORITHM)
                // A stream that does not open is an unreadable file, not an empty one: hashing
                // nothing would make every unreadable file of a size a "duplicate" (FD-D-17).
                val stream = context.contentResolver.openInputStream(uri) ?: return@withContext null
                stream.use { inputStream ->
                    val digestStream = DigestInputStream(inputStream, digest)
                    val buffer = ByteArray(BUFFER_SIZE)
                    @Suppress("ControlFlowWithEmptyBody")
                    while (digestStream.read(buffer) != -1) {
                        coroutineContext.ensureActive()
                        // Reading drives the digest computation
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
}
