package com.phototok.domain

import com.photoselector.core.storage.SourceKind

/**
 * Casual's one "surprise me" control ([USER 2026-11-12] decision #14, REQ-TOK-FEATURE.20): unseen
 * photos first, shuffled, for this visit of the folder only. Newest first stays the order every
 * open starts with, and a card keeps its order (a camera card is a shoot, UC-2), so it is offered
 * only in Casual and only for the phone and cloud sources. Pro has Feed order in Settings instead.
 */
object SurpriseMe {
    fun offered(proMode: Boolean, kind: SourceKind): Boolean =
        !proMode && (kind == SourceKind.PHONE_LIBRARY || kind == SourceKind.CLOUD_OTHER)

    /** The order to deal when the control turns [on]: shuffle (unseen first), else [configured]. */
    fun order(on: Boolean, configured: FeedOrderMode): FeedOrderMode = if (on) FeedOrderMode.SHUFFLE else configured
}
