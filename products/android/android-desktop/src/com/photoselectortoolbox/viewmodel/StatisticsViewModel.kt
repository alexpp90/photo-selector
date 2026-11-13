package com.photoselectortoolbox.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.repository.CacheRepository
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.data.repository.ToolFolder
import com.photoselectortoolbox.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Mean and median sharpness summary for a single EXIF parameter bucket (`U-35`).
 */
data class ExifSharpnessStats(
    val bucketLabel: String,
    val sampleCount: Int,
    val meanSharpness: Double,
    val medianSharpness: Double,
)

data class ExifSharpnessBreakdowns(
    val byShutterSpeed: Map<String, ExifSharpnessStats> = emptyMap(),
    val byAperture: Map<String, ExifSharpnessStats> = emptyMap(),
    val byFocalLength: Map<String, ExifSharpnessStats> = emptyMap(),
)

data class StatisticsUiState(
    val isLoading: Boolean = false,
    val folderUri: String? = null,
    val folderName: String = "",
    val imageCount: Int = 0,
    val shutterSpeedDistribution: Map<String, Int> = emptyMap(),
    val apertureDistribution: Map<String, Int> = emptyMap(),
    val isoDistribution: Map<String, Int> = emptyMap(),
    val focalLengthDistribution: Map<String, Int> = emptyMap(),
    val lensDistribution: Map<String, Int> = emptyMap(),
    val shutterSpeedSharpness: Map<String, ExifSharpnessStats> = emptyMap(),
    val apertureSharpness: Map<String, ExifSharpnessStats> = emptyMap(),
    val focalLengthSharpness: Map<String, ExifSharpnessStats> = emptyMap(),
    val error: String? = null,
    val scanProgress: Float = 0f,
    val progressText: String = "",
    val missingGrantPrompt: Boolean = false,
)

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val imageRepository: ImageRepository,
    private val cacheRepository: CacheRepository?,
    @ApplicationContext private val context: Context,
    /** Keeps the grant of the folder this screen opened ([ToolFolder]); null in screen tests. */
    private val settingsRepository: SettingsRepository?,
    /** Hilt's IO dispatcher; the default is for the test constructor below. */
    @IoDispatcher injectedIoDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    constructor(
        imageRepository: ImageRepository,
        context: Context,
    ) : this(
        imageRepository = imageRepository,
        cacheRepository = null,
        context = context,
        settingsRepository = null,
    )

    /** The dispatcher EXIF reading runs on; a test swaps it. */
    internal var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = injectedIoDispatcher

    private val _uiState = MutableStateFlow(StatisticsUiState())
    val uiState: StateFlow<StatisticsUiState> = _uiState.asStateFlow()

    fun selectFolder(uri: Uri) {
        viewModelScope.launch {
            val folderName = imageRepository.openFolder(context, uri)
            if (folderName != null) settingsRepository?.setToolFolderUri(ToolFolder.STATISTICS, uri.toString())
            if (folderName == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        folderUri = null,
                        folderName = "",
                        missingGrantPrompt = true,
                        error = "Failed to load folder: permission revoked or directory deleted.",
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    folderUri = uri.toString(),
                    folderName = folderName,
                    missingGrantPrompt = false,
                    error = null,
                )
            }

            analyzeFolder()
        }
    }

    fun analyzeFolder() {
        val folderUri = _uiState.value.folderUri ?: return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    scanProgress = 0f,
                    progressText = "Discovering files...",
                    error = null,
                    shutterSpeedDistribution = emptyMap(),
                    apertureDistribution = emptyMap(),
                    isoDistribution = emptyMap(),
                    focalLengthDistribution = emptyMap(),
                    lensDistribution = emptyMap(),
                    shutterSpeedSharpness = emptyMap(),
                    apertureSharpness = emptyMap(),
                    focalLengthSharpness = emptyMap(),
                )
            }

            try {
                val uri = Uri.parse(folderUri)
                // The whole folder, not the first progressive batch. Discovery
                // emits cumulatively (24, then 250, then the complete snapshot),
                // so `discoverImages(uri).first()` would compute a shoot-wide
                // EXIF distribution from 24 photographs and present it as the
                // answer for 842.
                val images = imageRepository.discoverAllImages(uri)

                _uiState.update {
                    it.copy(
                        imageCount = images.size,
                        progressText = "Preparing analysis..."
                    )
                }

                val processedCount = java.util.concurrent.atomic.AtomicInteger(0)
                val analyzedPairs = withContext(ioDispatcher) {
                    coroutineScope {
                        val semaphore = Semaphore(8)
                        images.map { image ->
                            async {
                                semaphore.withPermit {
                                    val exif = try {
                                        imageRepository.getExifData(context, Uri.parse(image.uri))
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        null
                                    }
                                    val cachedScore = try {
                                        cacheRepository?.getCachedScore(image.uri)?.sharpnessScore
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        null
                                    } ?: image.scanResult?.sharpnessScore
                                    val count = processedCount.incrementAndGet()
                                    val progress = count.toFloat() / images.size.toFloat()
                                    _uiState.update {
                                        it.copy(
                                            scanProgress = progress,
                                            progressText = "Analyzing metadata ($count/${images.size})"
                                        )
                                    }
                                    if (exif != null) exif to cachedScore else null
                                }
                            }
                        }.mapNotNull { it.await() }
                    }
                }

                val exifDataList = analyzedPairs.map { it.first }
                val scoredEntries = analyzedPairs.mapNotNull { (exif, score) ->
                    if (score != null) exif to score else null
                }

                val shutterSpeeds = mutableMapOf<String, Int>()
                val apertures = mutableMapOf<String, Int>()
                val isoValues = mutableMapOf<String, Int>()
                val focalLengths = mutableMapOf<String, Int>()
                val lenses = mutableMapOf<String, Int>()

                for (exif in exifDataList) {
                    exif.shutterSpeed?.let { speed ->
                        val label = formatShutterSpeed(speed)
                        shutterSpeeds[label] = (shutterSpeeds[label] ?: 0) + 1
                    }

                    exif.aperture?.let { aperture ->
                        val label = "f/%.1f".format(java.util.Locale.US, aperture)
                        apertures[label] = (apertures[label] ?: 0) + 1
                    }

                    exif.iso?.let { iso ->
                        val label = "ISO $iso"
                        isoValues[label] = (isoValues[label] ?: 0) + 1
                    }

                    exif.focalLength?.let { focal ->
                        val label = "${focal.toInt()}mm"
                        focalLengths[label] = (focalLengths[label] ?: 0) + 1
                    }

                    val lensName = exif.lens
                    if (lensName != "Unknown") {
                        lenses[lensName] = (lenses[lensName] ?: 0) + 1
                    }
                }

                val sharpnessBreakdowns = computeExifSharpnessBreakdowns(scoredEntries)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        shutterSpeedDistribution = shutterSpeeds.toSortedMap(),
                        apertureDistribution = apertures.toSortedMap(),
                        isoDistribution = isoValues.toSortedMap(),
                        focalLengthDistribution = focalLengths.toSortedMap(),
                        lensDistribution = lenses.toSortedMap(),
                        shutterSpeedSharpness = sharpnessBreakdowns.byShutterSpeed,
                        apertureSharpness = sharpnessBreakdowns.byAperture,
                        focalLengthSharpness = sharpnessBreakdowns.byFocalLength,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Analysis failed"
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    companion object {
        /**
         * Format a shutter speed value (in seconds) as a human-readable string.
         * Values < 1 second are displayed as fractions (e.g., "1/250s").
         * Values >= 1 second are displayed with quotes (e.g., "2\"").
         */
        internal fun formatShutterSpeed(speed: Double): String {
            return if (speed >= 1.0) {
                if (speed == speed.toLong().toDouble()) {
                    "${speed.toLong()}\""
                } else {
                    "%.1f\"".format(java.util.Locale.US, speed)
                }
            } else {
                val denominator = (1.0 / speed).toLong()
                "1/${denominator}s"
            }
        }

        /**
         * Compute mean and median sharpness per shutter speed, aperture, and focal length bucket (`U-35`).
         */
        internal fun computeExifSharpnessBreakdowns(
            entries: List<Pair<ExifData, Double>>,
        ): ExifSharpnessBreakdowns {
            if (entries.isEmpty()) return ExifSharpnessBreakdowns()

            val byShutter = mutableMapOf<String, MutableList<Double>>()
            val byAperture = mutableMapOf<String, MutableList<Double>>()
            val byFocal = mutableMapOf<String, MutableList<Double>>()

            for ((exif, sharpness) in entries) {
                exif.shutterSpeed?.let { s ->
                    byShutter.getOrPut(formatShutterSpeed(s)) { mutableListOf() }.add(sharpness)
                }
                exif.aperture?.let { a ->
                    byAperture.getOrPut("f/%.1f".format(java.util.Locale.US, a)) { mutableListOf() }.add(sharpness)
                }
                exif.focalLength?.let { f ->
                    byFocal.getOrPut("${f.toInt()}mm") { mutableListOf() }.add(sharpness)
                }
            }

            fun summarize(map: Map<String, List<Double>>): Map<String, ExifSharpnessStats> =
                map.toSortedMap().mapValues { (label, values) ->
                    val sorted = values.sorted()
                    val mean = sorted.average()
                    val mid = sorted.size / 2
                    val median = if (sorted.size % 2 == 1) {
                        sorted[mid]
                    } else {
                        (sorted[mid - 1] + sorted[mid]) / 2.0
                    }
                    ExifSharpnessStats(
                        bucketLabel = label,
                        sampleCount = sorted.size,
                        meanSharpness = mean,
                        medianSharpness = median,
                    )
                }

            return ExifSharpnessBreakdowns(
                byShutterSpeed = summarize(byShutter),
                byAperture = summarize(byAperture),
                byFocalLength = summarize(byFocal),
            )
        }
    }
}
