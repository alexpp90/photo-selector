package com.phototok.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.photoselector.core.storage.SafTreeDisplayPath
import com.phototok.R

/**
 * A custom destination folder as the path a person recognises, for display only: "Handy › DCIM ›
 * Keepers", "SD / USB › DCIM › Keepers". The volumes are named in Jackpix's own words, the ones its
 * source badges use, so the path is German in a German UI (REQ-TOK-UX.05); the folders are the
 * folders' own names. A provider with opaque ids (cloud, camera) reads as [providerName], the name it
 * reports for the folder, else as "Folder".
 */
@Composable
internal fun folderDisplayPath(uri: String, providerName: String?): String {
    val removable = stringResource(R.string.label_kind_sd)
    val labels = SafTreeDisplayPath.Labels(
        internalStorage = stringResource(R.string.label_kind_phone),
        removableVolume = { _ -> removable },
        unknownFolder = stringResource(R.string.label_folder),
    )
    return SafTreeDisplayPath.format(uri, providerTreeName = providerName, labels = labels)
}
