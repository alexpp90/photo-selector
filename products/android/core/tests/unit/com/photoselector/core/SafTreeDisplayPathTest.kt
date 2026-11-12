package com.photoselector.core

import com.photoselector.core.storage.SafTreeDisplayPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A SAF tree URI as a readable path — display only, never throwing. Plain JVM: no Android. */
class SafTreeDisplayPathTest {

    private val storage = "content://com.android.externalstorage.documents"

    // The module has no default words; this test speaks English like Frameduel does.
    private val english = SafTreeDisplayPath.Labels(
        internalStorage = "Internal storage",
        documents = "Documents",
        removableVolume = { "SD card ($it)" },
        unknownFolder = "Folder",
    )

    private fun segments(treeUri: String?, volumeLabel: String? = null, providerTreeName: String? = null) =
        SafTreeDisplayPath.segments(treeUri, volumeLabel, providerTreeName, english)

    private fun format(treeUri: String?) = SafTreeDisplayPath.format(treeUri, labels = english)

    @Test
    fun `internal storage reads as Internal storage and the folders below it`() {
        assertEquals(
            listOf("Internal storage", "DCIM", "Trip"),
            segments("$storage/tree/primary%3ADCIM%2FTrip"),
        )
        assertEquals(
            "Internal storage › DCIM › Trip",
            format("$storage/tree/primary%3ADCIM%2FTrip"),
        )
    }

    @Test
    fun `the root of internal storage is just Internal storage`() {
        assertEquals(listOf("Internal storage"), segments("$storage/tree/primary%3A"))
    }

    @Test
    fun `the home root is the Documents folder of internal storage`() {
        assertEquals(
            listOf("Internal storage", "Documents", "Scans"),
            segments("$storage/tree/home%3AScans"),
        )
    }

    @Test
    fun `a folder inside a tree reads by its document id`() {
        assertEquals(
            listOf("Internal storage", "DCIM", "Trip", "Selection"),
            segments(
                "$storage/tree/primary%3ADCIM%2FTrip/document/primary%3ADCIM%2FTrip%2FSelection",
            ),
        )
    }

    @Test
    fun `a removable volume reads with the label the caller supplies`() {
        assertEquals(
            listOf("SanDisk SD", "Pictures"),
            segments("$storage/tree/1234-ABCD%3APictures", volumeLabel = "SanDisk SD"),
        )
    }

    @Test
    fun `a removable volume without a label reads as SD card with its uuid`() {
        assertEquals(
            listOf("SD card (1234-ABCD)", "Pictures"),
            segments("$storage/tree/1234-ABCD%3APictures"),
        )
        // A blank label is no label.
        assertEquals(
            listOf("SD card (1234-ABCD)"),
            segments("$storage/tree/1234-ABCD%3A", volumeLabel = "  "),
        )
    }

    @Test
    fun `the uuid of a removable volume is offered for the label lookup`() {
        assertEquals("1234-ABCD", SafTreeDisplayPath.removableVolumeId("$storage/tree/1234-ABCD%3APictures"))
        assertNull(SafTreeDisplayPath.removableVolumeId("$storage/tree/primary%3ADCIM"))
        assertNull(SafTreeDisplayPath.removableVolumeId("$storage/tree/home%3AScans"))
        assertNull(SafTreeDisplayPath.removableVolumeId("content://com.google.android.apps.docs.storage/tree/abc"))
        assertNull(SafTreeDisplayPath.removableVolumeId(null))
        assertNull(SafTreeDisplayPath.removableVolumeId("not a uri"))
    }

    @Test
    fun `another provider reads as the tree name it reports`() {
        assertEquals(
            listOf("Holiday 2026"),
            segments(
                "content://com.google.android.apps.docs.storage/tree/acc%3D1%3Bdoc%3Dencoded%3D0xAB",
                providerTreeName = "Holiday 2026",
            ),
        )
    }

    @Test
    fun `another provider without a name reads as the last segment of its id`() {
        assertEquals(
            listOf("Camera"),
            segments(
                "content://com.android.providers.downloads.documents/tree/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FCamera",
            ),
        )
        assertEquals(
            listOf("downloads"),
            segments("content://com.android.providers.downloads.documents/tree/downloads"),
        )
    }

    @Test
    fun `a plus in a folder name stays a plus and spaces decode`() {
        assertEquals(
            listOf("Internal storage", "DCIM", "Trip + Friends", "Day 1"),
            segments("$storage/tree/primary%3ADCIM%2FTrip%20%2B%20Friends%2FDay%201"),
        )
        assertEquals(
            listOf("Internal storage", "A+B"),
            segments("$storage/tree/primary%3AA+B"),
        )
    }

    @Test
    fun `non-ASCII names decode as UTF-8`() {
        assertEquals(
            listOf("Internal storage", "Fotos", "Größe"),
            segments("$storage/tree/primary%3AFotos%2FGr%C3%B6%C3%9Fe"),
        )
    }

    @Test
    fun `malformed input falls back and never throws`() {
        listOf(
            null,
            "",
            "   ",
            "not a uri",
            "content://",
            "content://com.android.externalstorage.documents",
            "content://com.android.externalstorage.documents/tree/",
            "content://com.android.externalstorage.documents/tree/%3ADCIM",
            "content://com.android.externalstorage.documents/tree/noColonHere",
            "content://com.android.externalstorage.documents/tree/primary%ZZ",
            "content://other.provider/tree/%2F%2F",
        ).forEach { input ->
            val segments = segments(input)
            assertTrue("empty for $input", segments.isNotEmpty())
            assertTrue("blank segment for $input: $segments", segments.none { it.isBlank() })
        }
        assertEquals(listOf("Folder"), segments("not a uri"))
        assertEquals(listOf("Folder"), segments(null))
        // A name the caller supplies is the better fallback.
        assertEquals(listOf("Trip"), segments("garbage", providerTreeName = "Trip"))
        // A malformed escape is kept as written rather than failing.
        assertEquals(
            listOf("Internal storage", "100%ZZ"),
            segments("$storage/tree/primary%3A100%ZZ"),
        )
    }

    @Test
    fun `labels are the caller's, so each product keeps its own words`() {
        val german = SafTreeDisplayPath.Labels(
            internalStorage = "Interner Speicher",
            documents = "Dokumente",
            removableVolume = { "SD-Karte ($it)" },
            unknownFolder = "Ordner",
        )
        assertEquals(
            "Interner Speicher › DCIM",
            SafTreeDisplayPath.format("$storage/tree/primary%3ADCIM", labels = german),
        )
        assertEquals(
            "SD-Karte (1234-ABCD)",
            SafTreeDisplayPath.format("$storage/tree/1234-ABCD%3A", labels = german),
        )
        assertEquals("Ordner", SafTreeDisplayPath.format(null, labels = german))
    }
}
