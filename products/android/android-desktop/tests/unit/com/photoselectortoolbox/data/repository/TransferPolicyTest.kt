package com.photoselectortoolbox.data.repository

import android.app.Application
import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Frameduel's configuration of the shared engine: copies written to removable media are
 * fsynced (AS-D-6), the case this product exists for - culling an OTG card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TransferPolicyTest {

    private fun documentOn(volume: String) = Uri.parse(
        "content://com.android.externalstorage.documents/tree/$volume%3ADCIM/document/$volume%3ADCIM%2FSelection%2FIMG_0001.JPG",
    )

    @Test
    fun `a copy written to a removable card is fsynced`() {
        assertTrue(ImageRepositoryImpl.TRANSFER_POLICY.syncDestination(documentOn("1A2B-3C4D")))
    }

    @Test
    fun `a copy written to the phone's own storage is not`() {
        assertFalse(ImageRepositoryImpl.TRANSFER_POLICY.syncDestination(documentOn("primary")))
    }
}
