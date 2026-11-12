package com.phototok.domain

import com.photoselector.core.storage.SourceKind
import com.phototok.data.model.PhoneSettings
import com.phototok.data.model.SourceSettings

/**
 * What swipe right does for a source, before the first swipe (landing cards, recent rows): a ★
 * favourite in place, a copy into the phone (a Casual card, REQ-TOK-FEATURE.21), or a copy or move
 * into the folder [folder] (`Selection`, or `<card>/Selection` in Pro). Read from the effective
 * per-source settings, so Casual and Pro both show what really happens.
 */
data class SwipeRightOutcome(val kind: Kind, val folder: String) {

    enum class Kind { FAVOURITE, COPY_TO_PHONE, COPY_TO_FOLDER, MOVE_TO_FOLDER }

    companion object {
        fun of(settings: SourceSettings, sourceKind: SourceKind): SwipeRightOutcome {
            val kind = when {
                settings.swipeRightVerb == SwipeRightVerb.FAVOURITE -> Kind.FAVOURITE
                settings.importToPhoneCamera -> Kind.COPY_TO_PHONE
                // A cloud provider cannot move: the feed copies instead.
                settings.swipeRightVerb == SwipeRightVerb.MOVE && sourceKind != SourceKind.CLOUD_OTHER -> Kind.MOVE_TO_FOLDER
                else -> Kind.COPY_TO_FOLDER
            }
            return SwipeRightOutcome(kind, settings.selectionSubfolder)
        }

        /** The outcome of every source kind, from one settings snapshot. */
        fun perKind(settings: PhoneSettings): Map<SourceKind, SwipeRightOutcome> = mapOf(
            SourceKind.PHONE_LIBRARY to of(settings.phoneSourceSettings, SourceKind.PHONE_LIBRARY),
            SourceKind.REMOVABLE to of(settings.removableSourceSettings, SourceKind.REMOVABLE),
            SourceKind.CAMERA_MTP to of(settings.cameraMtpSourceSettings, SourceKind.CAMERA_MTP),
            SourceKind.CLOUD_OTHER to of(settings.cloudSourceSettings, SourceKind.CLOUD_OTHER),
        )
    }
}
