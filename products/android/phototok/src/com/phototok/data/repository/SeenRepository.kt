package com.phototok.data.repository

import android.content.Context
import com.phototok.data.model.ImageItem
import com.phototok.domain.SeenStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hilt singleton wrapper around [SeenStore] (`U-15`).
 */
@Singleton
class SeenRepository(
    internal val store: SeenStore = SeenStore(logFile = null),
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(
        store = SeenStore(
            logFile = File(context.filesDir, "seen_photos.log"),
            ioExecutor = SeenStore.backgroundWriter("jackpix-seen-writer"),
        ),
    )

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    fun isSeen(item: ImageItem, sourceUri: String? = null): Boolean =
        store.isSeen(item, sourceUri)

    fun markSeen(item: ImageItem, sourceUri: String): Boolean {
        val added = store.markSeen(item, sourceUri)
        if (added) {
            _version.value++
        }
        return added
    }

    fun filterSeenUris(items: List<ImageItem>, sourceUri: String? = null): Set<String> =
        store.filterSeenUris(items, sourceUri)

    fun countSeen(items: List<ImageItem>, sourceUri: String? = null): Int =
        store.countSeen(items, sourceUri)

    fun resetSource(sourceUri: String) {
        store.resetSource(sourceUri)
        _version.value++
    }

    fun resetAll() {
        store.resetAll()
        _version.value++
    }
}
