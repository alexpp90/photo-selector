package com.photoselectortoolbox.domain.workflow

import com.photoselector.core.model.ExifData
import com.photoselector.core.storage.SourceKind
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.data.repository.ImageRepositoryImpl
import com.photoselectortoolbox.data.source.LocalImageSourceImpl
import com.photoselectortoolbox.domain.format.SelectorLabels
import com.photoselectortoolbox.ui.theme.computeEffectiveFontScale
import com.photoselectortoolbox.viewmodel.SelectorUiState
import com.photoselectortoolbox.viewmodel.StatisticsViewModel
import com.photoselectortoolbox.viewmodel.isCloudProvider
import com.photoselectortoolbox.viewmodel.providerLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.photoselectortoolbox.testing.en

class Phase5and6EnhancementsTest {

    // --- U-30: Cloud / OTG Source Kind & Safeguards ---

    @Test
    fun `deleteConfirmationMessage appends cloud provider warning only for CLOUD_OTHER`() {
        val localMsg = SelectorLabels.deleteConfirmationMessage(
            fileName = "IMG_001.JPG",
            isCloudProvider = SourceKind.PHONE_LIBRARY.isCloudProvider,
            providerLabel = SourceKind.PHONE_LIBRARY.providerLabel,
        ).en()
        assertFalse(localMsg.contains("Warning:"))

        val cloudMsg = SelectorLabels.deleteConfirmationMessage(
            fileName = "IMG_001.JPG",
            isCloudProvider = SourceKind.CLOUD_OTHER.isCloudProvider,
            providerLabel = SourceKind.CLOUD_OTHER.providerLabel,
        ).en()
        assertTrue(cloudMsg.contains("cloud storage"))
        assertTrue(cloudMsg.contains("synced devices"))
    }

    @Test
    fun `cloudScanConfirmationMessage explains cloud download impact`() {
        val msg = SelectorLabels.cloudScanConfirmationMessage(SourceKind.CLOUD_OTHER.providerLabel, 150).en()
        assertTrue(msg.contains("150"))
        assertTrue(msg.contains("cloud storage"))
        assertTrue(msg.contains("network"))
    }

    // --- U-32: Multi-Pass Culling ---

    @Test
    fun `shouldSkipDirectory skips primary and secondary selection folders and marker directories`() {
        assertTrue(
            LocalImageSourceImpl.shouldSkipDirectory(
                dirName = "Selection",
                isRootDirectory = false,
                hasMarkerFile = false,
                customExcludedNames = setOf("Selection", "Final"),
            ),
        )
        assertTrue(
            LocalImageSourceImpl.shouldSkipDirectory(
                dirName = "Final",
                isRootDirectory = false,
                hasMarkerFile = false,
                customExcludedNames = setOf("Selection", "Final"),
            ),
        )
        assertTrue(
            LocalImageSourceImpl.shouldSkipDirectory(
                dirName = "CustomKeepers",
                isRootDirectory = false,
                hasMarkerFile = true,
                customExcludedNames = setOf("Selection", "Final"),
            ),
        )
        assertFalse(
            LocalImageSourceImpl.shouldSkipDirectory(
                dirName = "Selection",
                isRootDirectory = true,
                hasMarkerFile = true,
                customExcludedNames = setOf("Selection", "Final"),
            ),
        )
        assertFalse(
            LocalImageSourceImpl.shouldSkipDirectory(
                dirName = "Day1_Shoot",
                isRootDirectory = false,
                hasMarkerFile = false,
                customExcludedNames = setOf("Selection", "Final"),
            ),
        )
        assertEquals(".photoselector-selection", LocalImageSourceImpl.SELECTION_MARKER_FILE_NAME)
    }

    // --- U-33: Safe to Format Verification ---

    @Test
    fun `verifySafeToFormat succeeds only when every kept file arrived with its exact size`() {
        val kept = mapOf("DSC0001.ARW" to 24_000_000L, "DSC0002.JPG" to 8_500_000L)
        val matchingActual = mapOf(
            "DSC0001.ARW" to 24_000_000L,
            "DSC0002.JPG" to 8_500_000L,
            ".photoselector-selection" to 0L,
        )
        val okResult = ImageRepositoryImpl.verifySafeToFormat(kept, matchingActual)
        assertTrue(okResult.isSafe)
        assertEquals(2, okResult.verifiedCount)
        assertTrue(okResult.missingOrMismatchedFiles.isEmpty())

        val emptyCopy = mapOf("DSC0001.ARW" to 24_000_000L, "DSC0002.JPG" to 0L)
        val badResult = ImageRepositoryImpl.verifySafeToFormat(kept, emptyCopy)
        assertFalse(badResult.isSafe)
        assertEquals(listOf("DSC0002.JPG"), badResult.missingOrMismatchedFiles)

        // A copy cut short is not the file (FD-C-11): only equal sizes verify.
        val shortCopy = mapOf("DSC0001.ARW" to 23_999_999L, "DSC0002.JPG" to 8_500_000L)
        assertEquals(listOf("DSC0001.ARW"), ImageRepositoryImpl.verifySafeToFormat(kept, shortCopy).missingOrMismatchedFiles)
    }

    @Test
    fun `verifySafeToFormat does not count a kept RAW as safe when only its JPEG arrived`() {
        val kept = mapOf("DSC0001.ARW" to 24_000_000L, "DSC0001.JPG" to 8_000_000L)
        val onlyJpeg = mapOf("DSC0001.JPG" to 8_000_000L)

        val result = ImageRepositoryImpl.verifySafeToFormat(kept, onlyJpeg)

        assertFalse(result.isSafe)
        assertEquals(listOf("DSC0001.ARW"), result.missingOrMismatchedFiles)
    }

    // --- U-34: Worst-First Reject Triage ---

    @Test
    fun `hasReachedSharpnessThreshold triggers when worst-first sorting crosses stop threshold`() {
        val sharpImage = ImageItem(
            uri = "content://test/sharp.jpg",
            fileName = "sharp.jpg",
            fileSize = 1000L,
            lastModified = 1L,
            mimeType = "image/jpeg",
            scanResult = ScanResult(
                filePath = "content://test/sharp.jpg",
                sharpnessScore = 22.5,
                noiseLevel = 1.0,
                highlightClipping = 0.0,
                shadowClipping = 0.0,
            ),
        )
        val blurryImage = ImageItem(
            uri = "content://test/blurry.jpg",
            fileName = "blurry.jpg",
            fileSize = 1000L,
            lastModified = 1L,
            mimeType = "image/jpeg",
            scanResult = ScanResult(
                filePath = "content://test/blurry.jpg",
                sharpnessScore = 8.0,
                noiseLevel = 1.0,
                highlightClipping = 0.0,
                shadowClipping = 0.0,
            ),
        )

        val stateAtBlurry = SelectorUiState(
            images = listOf(blurryImage, sharpImage),
            currentIndex = 0,
            sortWorstFirst = true,
            sharpnessStopThreshold = 15.0,
        )
        assertFalse(stateAtBlurry.hasReachedSharpnessThreshold)

        val stateAtSharp = stateAtBlurry.copy(currentIndex = 1)
        assertTrue(stateAtSharp.hasReachedSharpnessThreshold)
        assertTrue(SelectorLabels.worstFirstStopLineMessage(15.0).en().contains("15"))
    }

    // --- U-35: Camera / Lens Quality Analytics ---

    @Test
    fun `computeExifSharpnessBreakdowns aggregates mean and median sharpness by shutter, aperture, and focal length`() {
        val entries = listOf(
            ExifData(shutterSpeed = 1.0 / 500.0, aperture = 2.8, focalLength = 35.0, lens = "RF 24-70mm F2.8") to 40.0,
            ExifData(shutterSpeed = 1.0 / 500.0, aperture = 2.8, focalLength = 35.0, lens = "RF 24-70mm F2.8") to 60.0,
            ExifData(shutterSpeed = 1.0 / 30.0, aperture = 5.6, focalLength = 400.0, lens = "RF 100-500mm") to 18.0,
        )

        val breakdowns = StatisticsViewModel.computeExifSharpnessBreakdowns(entries)
        val f28 = breakdowns.byAperture["f/2.8"]!!
        assertEquals(2, f28.sampleCount)
        assertEquals(50.0, f28.medianSharpness, 0.01)

        val focal35 = breakdowns.byFocalLength["35mm"]!!
        assertEquals(2, focal35.sampleCount)
        assertEquals(50.0, focal35.meanSharpness, 0.01)

        val shutter500 = breakdowns.byShutterSpeed["1/500s"]!!
        assertEquals(2, shutter500.sampleCount)
        assertEquals(50.0, shutter500.medianSharpness, 0.01)
    }

    // --- U-36: TV / 10-Foot Display Scale ---

    @Test
    fun `computeEffectiveFontScale multiplies fontScale by 1_25x when TV scale is enabled`() {
        assertEquals(1.0f, computeEffectiveFontScale(1.0f, tvDisplayScaleEnabled = false), 0.001f)
        assertEquals(1.25f, computeEffectiveFontScale(1.0f, tvDisplayScaleEnabled = true), 0.001f)
    }
}
