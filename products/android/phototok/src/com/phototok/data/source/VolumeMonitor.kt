package com.phototok.data.source

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.photoselector.core.storage.ExternalStorageDetector
import com.photoselector.core.storage.ExternalVolume
import com.photoselector.core.storage.SourceKind
import com.phototok.data.model.RecentPath
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Monitors mounted removable storage volumes and media eject/unmount events (`U-14a`, `U-14b`).
 */
@Singleton
class VolumeMonitor @Inject constructor(
    @ApplicationContext private val context: Context?,
    private val externalStorageDetector: ExternalStorageDetector?,
) {
    constructor() : this(null, null)

    private val _mountedVolumes = MutableStateFlow<List<ExternalVolume>>(emptyList())
    val mountedVolumes: StateFlow<List<ExternalVolume>> = _mountedVolumes.asStateFlow()

    /** Emits the unmounted volume UUID (or null when unknown) on surprise eject/unmount. */
    private val _unmountEvents = MutableSharedFlow<String?>(extraBufferCapacity = 8)
    val unmountEvents: SharedFlow<String?> = _unmountEvents.asSharedFlow()

    private var registered = false

    companion object {
        /**
         * Pure check of whether [path] is currently accessible given [mountedVolumes].
         * - Non-removable paths (`PHONE_LIBRARY`, `CAMERA_MTP`, `CLOUD_OTHER`) are treated as mounted.
         * - `REMOVABLE` paths with a recorded [RecentPath.volumeUuid] are mounted iff a volume
         *   with that UUID is present in [mountedVolumes].
         * - `REMOVABLE` paths without a UUID are mounted iff at least one removable volume is mounted.
         */
        fun isRecentPathMounted(
            path: RecentPath,
            mountedVolumes: List<ExternalVolume>,
        ): Boolean {
            if (path.kind != SourceKind.REMOVABLE) return true
            val uuid = path.volumeUuid
            return if (!uuid.isNullOrBlank()) {
                mountedVolumes.any { it.uuid.equals(uuid, ignoreCase = true) }
            } else {
                mountedVolumes.isNotEmpty()
            }
        }
    }

    init {
        refreshVolumes()
        registerListeners()
    }

    fun refreshVolumes(): List<ExternalVolume> {
        val detector = externalStorageDetector ?: return _mountedVolumes.value
        val current = try {
            detector.detectRemovableVolumes()
        } catch (_: Exception) {
            emptyList()
        }
        // Init, the storage callback and the receiver call this from different threads. The state
        // is swapped in one step, so each unmount is diffed against exactly the state it replaced
        // and announced once, never against a stale list read before a slower detection finished.
        val previous = _mountedVolumes.getAndUpdate { current }

        val currentUuids = current.mapNotNull { it.uuid?.lowercase() }.toSet()
        for (old in previous) {
            val oldUuid = old.uuid?.lowercase()
            if (oldUuid != null && oldUuid !in currentUuids) {
                _unmountEvents.tryEmit(old.uuid)
            }
        }
        return current
    }

    /** Trigger for a surprise unmount of [volumeUuid]; used by tests. */
    @VisibleForTesting
    internal fun notifyVolumeUnmounted(volumeUuid: String?) {
        _unmountEvents.tryEmit(volumeUuid)
        refreshVolumes()
    }

    private fun registerListeners() {
        val ctx = context ?: return
        if (registered) return
        registered = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val storageManager = ctx.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
                val directExecutor = Executor { it.run() }
                storageManager?.registerStorageVolumeCallback(
                    directExecutor,
                    object : StorageManager.StorageVolumeCallback() {
                        override fun onStateChanged(volume: StorageVolume) {
                            refreshVolumes()
                        }
                    },
                )
            }
        } catch (_: Exception) {
        }

        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_MOUNTED)
                addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_EJECT)
                addAction(Intent.ACTION_MEDIA_REMOVED)
                addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
                addDataScheme("file")
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val action = intent?.action
                    val previous = _mountedVolumes.value
                    val current = refreshVolumes()
                    if (
                        action == Intent.ACTION_MEDIA_EJECT ||
                        action == Intent.ACTION_MEDIA_UNMOUNTED ||
                        action == Intent.ACTION_MEDIA_REMOVED ||
                        action == Intent.ACTION_MEDIA_BAD_REMOVAL
                    ) {
                        if (previous.size == current.size) {
                            _unmountEvents.tryEmit(null)
                        }
                    }
                }
            }
            ContextCompat.registerReceiver(
                ctx,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (_: Exception) {
        }
    }
}
