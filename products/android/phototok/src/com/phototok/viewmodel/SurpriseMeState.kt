package com.phototok.viewmodel

import com.phototok.domain.SurpriseMe

/**
 * Whether the feed shows Casual's "surprise me" control and its state: null where it is not
 * offered (Pro, an SD card or camera, an empty feed), and while the folder is still being read,
 * because a shuffle needs the whole folder (it is dealt once, then fixed).
 */
val PhoneModeUiState.surpriseMeShown: Boolean?
    get() = if (SurpriseMe.offered(proMode, sourceKind) && images.isNotEmpty() && !isDiscovering) surpriseMe else null
