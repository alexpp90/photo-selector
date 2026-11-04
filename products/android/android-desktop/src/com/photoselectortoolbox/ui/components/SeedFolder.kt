package com.photoselectortoolbox.ui.components

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * Statistics and Duplicates have their own ViewModels, so a photographer who had a folder open in
 * Cull was sent to "No Folder Selected" on both (FD-UX-3). This opens the selector's folder in the
 * screen's own ViewModel when it has none; once it has one (picked there, or seeded) it never
 * overrides it.
 */
@Composable
fun SeedFolderEffect(
    seedFolderUri: String?,
    ownFolderUri: String?,
    onSeed: (Uri) -> Unit,
) {
    val latestOnSeed by rememberUpdatedState(onSeed)
    LaunchedEffect(seedFolderUri, ownFolderUri) {
        if (ownFolderUri == null && seedFolderUri != null) latestOnSeed(Uri.parse(seedFolderUri))
    }
}
