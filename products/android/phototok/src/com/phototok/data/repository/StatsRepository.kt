package com.phototok.data.repository

import android.content.Context
import com.photoselector.core.storage.SourceKind
import com.phototok.domain.Milestones
import com.phototok.domain.SeenStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Session and lifetime culling statistics split by phone vs removable card (`U-19`).
 */
data class CullingStats(
    val lifetimeBytesFreedPhone: Long = 0L,
    val lifetimeBytesFreedCard: Long = 0L,
    val lifetimePhotosReviewedPhone: Int = 0,
    val lifetimePhotosReviewedCard: Int = 0,
    val lifetimeKeepersPhone: Int = 0,
    val lifetimeKeepersCard: Int = 0,
    val sessionBytesFreedPhone: Long = 0L,
    val sessionBytesFreedCard: Long = 0L,
    val sessionPhotosReviewed: Int = 0,
    val sessionKeepers: Int = 0,
    val sessionDeletesQueuedCount: Int = 0,
    val sessionDeletesQueuedBytes: Long = 0L,
    val sessionDeletesCommittedCount: Int = 0,
    val sessionDeletesCommittedBytes: Long = 0L,
) {
    val totalLifetimeBytesFreed: Long
        get() = lifetimeBytesFreedPhone + lifetimeBytesFreedCard

    val totalLifetimePhotosReviewed: Int
        get() = lifetimePhotosReviewedPhone + lifetimePhotosReviewedCard

    val totalSessionBytesFreed: Long
        get() = sessionBytesFreedPhone + sessionBytesFreedCard + sessionDeletesQueuedBytes
}

class StatsStore(
    private val statsFile: File? = null,
    /** Runs the file write; production uses a background thread so a swipe never waits for the disk. */
    private val ioExecutor: Executor = Executor { it.run() },
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val _stats = MutableStateFlow(CullingStats())
    val stats: StateFlow<CullingStats> = _stats.asStateFlow()

    private var lastCelebrationAtMs: Long = 0L
    private val firedByteThresholds = LinkedHashSet<Long>()
    private val firedSeenThresholds = LinkedHashSet<Int>()

    init {
        loadFromDisk()
    }

    private fun SourceKind.isCardLike(): Boolean =
        this == SourceKind.REMOVABLE || this == SourceKind.CAMERA_MTP

    @Synchronized
    fun recordPhotoReviewed(
        kind: SourceKind,
        celebrationsEnabled: Boolean = true,
        nowMs: Long = System.currentTimeMillis(),
    ): Milestones.MilestoneEvent? {
        val prev = _stats.value
        val prevTotalSeen = prev.totalLifetimePhotosReviewed
        val updated = if (kind.isCardLike()) {
            prev.copy(
                lifetimePhotosReviewedCard = prev.lifetimePhotosReviewedCard + 1,
                sessionPhotosReviewed = prev.sessionPhotosReviewed + 1,
            )
        } else {
            prev.copy(
                lifetimePhotosReviewedPhone = prev.lifetimePhotosReviewedPhone + 1,
                sessionPhotosReviewed = prev.sessionPhotosReviewed + 1,
            )
        }
        _stats.value = updated
        persistLocked(updated)

        val event = Milestones.evaluate(
            previousBytes = prev.totalLifetimeBytesFreed,
            currentBytes = updated.totalLifetimeBytesFreed,
            previousSeen = prevTotalSeen,
            currentSeen = updated.totalLifetimePhotosReviewed,
            lastCelebrationAtMs = lastCelebrationAtMs,
            nowMs = nowMs,
            celebrationsEnabled = celebrationsEnabled,
            alreadyFiredByteThresholds = firedByteThresholds,
            alreadyFiredSeenThresholds = firedSeenThresholds,
        )
        if (event is Milestones.MilestoneEvent.PhotosReviewed) {
            firedSeenThresholds.add(event.thresholdCount)
            lastCelebrationAtMs = nowMs
        }
        return event
    }

    @Synchronized
    fun recordKeeper(kind: SourceKind, count: Int = 1) {
        if (count <= 0) return
        val prev = _stats.value
        val updated = if (kind.isCardLike()) {
            prev.copy(
                lifetimeKeepersCard = prev.lifetimeKeepersCard + count,
                sessionKeepers = prev.sessionKeepers + count,
            )
        } else {
            prev.copy(
                lifetimeKeepersPhone = prev.lifetimeKeepersPhone + count,
                sessionKeepers = prev.sessionKeepers + count,
            )
        }
        _stats.value = updated
        persistLocked(updated)
    }

    @Synchronized
    fun updateQueuedDeletes(count: Int, bytes: Long) {
        val prev = _stats.value
        _stats.value = prev.copy(
            sessionDeletesQueuedCount = count.coerceAtLeast(0),
            sessionDeletesQueuedBytes = bytes.coerceAtLeast(0L),
        )
    }

    @Synchronized
    fun recordCommittedDelete(
        kind: SourceKind,
        bytesFreed: Long,
        fileCount: Int = 1,
        celebrationsEnabled: Boolean = true,
        nowMs: Long = System.currentTimeMillis(),
    ): Milestones.MilestoneEvent? {
        val safeBytes = bytesFreed.coerceAtLeast(0L)
        val prev = _stats.value
        val prevTotalBytes = prev.totalLifetimeBytesFreed
        val updated = if (kind.isCardLike()) {
            prev.copy(
                lifetimeBytesFreedCard = prev.lifetimeBytesFreedCard + safeBytes,
                sessionBytesFreedCard = prev.sessionBytesFreedCard + safeBytes,
                sessionDeletesCommittedCount = prev.sessionDeletesCommittedCount + fileCount.coerceAtLeast(0),
                sessionDeletesCommittedBytes = prev.sessionDeletesCommittedBytes + safeBytes,
            )
        } else {
            prev.copy(
                lifetimeBytesFreedPhone = prev.lifetimeBytesFreedPhone + safeBytes,
                sessionBytesFreedPhone = prev.sessionBytesFreedPhone + safeBytes,
                sessionDeletesCommittedCount = prev.sessionDeletesCommittedCount + fileCount.coerceAtLeast(0),
                sessionDeletesCommittedBytes = prev.sessionDeletesCommittedBytes + safeBytes,
            )
        }
        _stats.value = updated
        persistLocked(updated)

        val event = Milestones.evaluate(
            previousBytes = prevTotalBytes,
            currentBytes = updated.totalLifetimeBytesFreed,
            previousSeen = prev.totalLifetimePhotosReviewed,
            currentSeen = updated.totalLifetimePhotosReviewed,
            lastCelebrationAtMs = lastCelebrationAtMs,
            nowMs = nowMs,
            celebrationsEnabled = celebrationsEnabled,
            alreadyFiredByteThresholds = firedByteThresholds,
            alreadyFiredSeenThresholds = firedSeenThresholds,
        )
        if (event is Milestones.MilestoneEvent.BytesFreed) {
            firedByteThresholds.add(event.thresholdBytes)
            lastCelebrationAtMs = nowMs
        }
        return event
    }

    @Synchronized
    fun resetSessionStats() {
        val prev = _stats.value
        _stats.value = prev.copy(
            sessionBytesFreedPhone = 0L,
            sessionBytesFreedCard = 0L,
            sessionPhotosReviewed = 0,
            sessionKeepers = 0,
            sessionDeletesQueuedCount = 0,
            sessionDeletesQueuedBytes = 0L,
            sessionDeletesCommittedCount = 0,
            sessionDeletesCommittedBytes = 0L,
        )
    }

    private fun loadFromDisk() {
        val file = statsFile ?: return
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            val loaded = CullingStats(
                lifetimeBytesFreedPhone = root.optLong("lifetimeBytesFreedPhone", 0L),
                lifetimeBytesFreedCard = root.optLong("lifetimeBytesFreedCard", 0L),
                lifetimePhotosReviewedPhone = root.optInt("lifetimePhotosReviewedPhone", 0),
                lifetimePhotosReviewedCard = root.optInt("lifetimePhotosReviewedCard", 0),
                lifetimeKeepersPhone = root.optInt("lifetimeKeepersPhone", 0),
                lifetimeKeepersCard = root.optInt("lifetimeKeepersCard", 0),
            )
            _stats.value = loaded
            Milestones.BYTE_THRESHOLDS.filter { it <= loaded.totalLifetimeBytesFreed }
                .forEach { firedByteThresholds.add(it) }
            Milestones.SEEN_THRESHOLDS.filter { it <= loaded.totalLifetimePhotosReviewed }
                .forEach { firedSeenThresholds.add(it) }
        } catch (e: Exception) {
            onError("Could not read ${file.name}", e) // ui-text: ignore log message
        }
    }

    private fun persistLocked(current: CullingStats) {
        val file = statsFile ?: return
        val json = JSONObject().apply {
            put("lifetimeBytesFreedPhone", current.lifetimeBytesFreedPhone)
            put("lifetimeBytesFreedCard", current.lifetimeBytesFreedCard)
            put("lifetimePhotosReviewedPhone", current.lifetimePhotosReviewedPhone)
            put("lifetimePhotosReviewedCard", current.lifetimePhotosReviewedCard)
            put("lifetimeKeepersPhone", current.lifetimeKeepersPhone)
            put("lifetimeKeepersCard", current.lifetimeKeepersCard)
        }.toString()
        ioExecutor.execute { writeAtomically(file, json) }
    }

    /**
     * Write aside, then rename: a kill mid-write leaves the previous file, never a truncated one
     * (a truncated JSON would restart the lifetime totals at 0). Failures are reported, not hidden.
     */
    private fun writeAtomically(file: File, text: String) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        } catch (e: java.io.IOException) {
            onError("Could not write ${file.name}", e) // ui-text: ignore log message
        }
    }
}

@Singleton
class StatsRepository(
    internal val store: StatsStore = StatsStore(statsFile = null),
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(
        store = StatsStore(
            statsFile = File(context.filesDir, "culling_stats.json"),
            ioExecutor = SeenStore.backgroundWriter("jackpix-stats-writer"),
            onError = { message, e -> android.util.Log.w("Stats", message, e) },
        ),
    )

    val stats: StateFlow<CullingStats> = store.stats

    fun recordPhotoReviewed(
        kind: SourceKind,
        celebrationsEnabled: Boolean = true,
        nowMs: Long = System.currentTimeMillis(),
    ): Milestones.MilestoneEvent? = store.recordPhotoReviewed(kind, celebrationsEnabled, nowMs)

    fun recordKeeper(kind: SourceKind, count: Int = 1) = store.recordKeeper(kind, count)

    fun updateQueuedDeletes(count: Int, bytes: Long) = store.updateQueuedDeletes(count, bytes)

    fun recordCommittedDelete(
        kind: SourceKind,
        bytesFreed: Long,
        fileCount: Int = 1,
        celebrationsEnabled: Boolean = true,
        nowMs: Long = System.currentTimeMillis(),
    ): Milestones.MilestoneEvent? =
        store.recordCommittedDelete(kind, bytesFreed, fileCount, celebrationsEnabled, nowMs)

    fun resetSessionStats() = store.resetSessionStats()
}
