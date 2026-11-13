package com.photoselectortoolbox.data.repository

import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * A screen that opens a folder of its own besides the cull (REQ-AND-PLATFORM.06). Each one's
 * folder is a durable grant holder ([SettingsRepository.setToolFolderUri]): held only in memory,
 * the grant was released by the next reference sync — the cull opening another folder, or the
 * startup reconcile — while the screen still worked on it (backlog 2026-11-12, Dupes).
 */
enum class ToolFolder(storedName: String) {
    DUPLICATES("duplicates_folder_uri"),
    STATISTICS("statistics_folder_uri"),
    ;

    internal val key = stringPreferencesKey(storedName)
}
