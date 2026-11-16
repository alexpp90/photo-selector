package com.photoselectortoolbox.domain.session

import com.photoselector.core.storage.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Test

class DimensionBackfillTest {

    // REQ-AND-PERF.05
    @Test
    fun `the folder-wide backfill runs on phone storage and cards, never on MTP or a cloud provider`() {
        val runs = SourceKind.entries.associateWith(DimensionBackfill::runsFor)

        assertEquals(
            mapOf(
                SourceKind.PHONE_LIBRARY to true,
                SourceKind.REMOVABLE to true,
                SourceKind.CAMERA_MTP to false,
                SourceKind.CLOUD_OTHER to false,
            ),
            runs,
        )
    }
}
