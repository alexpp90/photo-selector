package com.photoselectortoolbox.domain.learn

import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.analysis.PhotoCoachEngineImpl
import com.photoselectortoolbox.domain.format.ExifFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

data class ComparableScene(
    val sceneId: String,
    val title: String,
    val photos: List<ImageItem>,
    val primaryPhoto: ImageItem,
    val comparisonPhoto: ImageItem,
    val diffExplanation: String,
    val differenceBullets: List<String>,
)

/**
 * Groups near-identical scenes/bursts and generates plain-language explanations
 * of why one frame worked better than another (`U-27`).
 */
object SceneComparator {

    private const val MAX_TIME_GAP_MS = 90_000L
    private const val MIN_SHARPNESS_REL_DIFF = 0.15

    fun findComparableScenes(images: List<ImageItem>): List<ComparableScene> {
        if (images.size < 2) return emptyList()

        // Group by burst groupId first when available; otherwise cluster by timestamp proximity (<= 90s)
        val rawClusters = mutableListOf<List<ImageItem>>()
        val groupedByBurst = images.filter { it.groupId != null }.groupBy { it.groupId }
        if (groupedByBurst.isNotEmpty()) {
            groupedByBurst.values.filter { it.size >= 2 }.forEach { rawClusters.add(it) }
        }

        val ungrouped = if (groupedByBurst.isNotEmpty()) {
            images.filter { it.groupId == null }
        } else {
            images
        }

        if (ungrouped.size >= 2) {
            val sorted = ungrouped.sortedBy { it.lastModified }
            var currentCluster = mutableListOf(sorted.first())
            for (i in 1 until sorted.size) {
                val prev = currentCluster.last()
                val curr = sorted[i]
                val dt = abs(curr.lastModified - prev.lastModified)
                val focalPrev = prev.exifData?.focalLength35mm ?: prev.exifData?.focalLength
                val focalCurr = curr.exifData?.focalLength35mm ?: curr.exifData?.focalLength
                val focalClose = focalPrev == null || focalCurr == null || abs(focalPrev - focalCurr) <= 15.0
                if (dt <= MAX_TIME_GAP_MS && focalClose) {
                    currentCluster.add(curr)
                } else {
                    if (currentCluster.size >= 2) rawClusters.add(currentCluster)
                    currentCluster = mutableListOf(curr)
                }
            }
            if (currentCluster.size >= 2) rawClusters.add(currentCluster)
        }

        // Fallback: if no clusters met timestamp proximity but folder has >= 2 scanned images with different settings/scores,
        // offer the best vs weakest pair in the folder so the photographer can still compare scenes.
        if (rawClusters.isEmpty() && images.size >= 2) {
            rawClusters.add(images)
        }

        val scenes = mutableListOf<ComparableScene>()
        rawClusters.forEachIndexed { idx, cluster ->
            val sortedByQuality = cluster.sortedByDescending { item ->
                val s = item.scanResult?.sharpnessScore ?: 0.0
                val clipPenalty = (item.scanResult?.highlightClipping ?: 0.0) * 3.0
                s - clipPenalty
            }
            val best = sortedByQuality.first()
            val other = sortedByQuality.last()
            if (best.uri == other.uri) return@forEachIndexed

            val sBest = best.scanResult?.sharpnessScore
            val sOther = other.scanResult?.sharpnessScore
            val hasSharpnessDiff = if (sBest != null && sOther != null && max(sBest, sOther) > 0.0) {
                abs(sBest - sOther) / max(sBest, sOther) >= MIN_SHARPNESS_REL_DIFF
            } else {
                false
            }
            val hasExifDiff = hasMeaningfulExifDifference(best, other)
            if (hasSharpnessDiff || hasExifDiff || rawClusters.size == 1) {
                val (summary, bullets) = explainDifference(best, other)
                scenes.add(
                    ComparableScene(
                        sceneId = "scene_${idx + 1}",
                        title = "Scene ${idx + 1} · ${best.fileName} vs ${other.fileName}",
                        photos = cluster,
                        primaryPhoto = best,
                        comparisonPhoto = other,
                        diffExplanation = summary,
                        differenceBullets = bullets,
                    )
                )
            }
        }
        return scenes
    }

    fun explainDifference(photoA: ImageItem, photoB: ImageItem): Pair<String, List<String>> {
        val bullets = mutableListOf<String>()
        val exifA = photoA.exifData
        val exifB = photoB.exifData
        val scanA = photoA.scanResult
        val scanB = photoB.scanResult

        val shutterA = exifA?.shutterSpeed
        val shutterB = exifB?.shutterSpeed
        if (shutterA != null && shutterB != null && abs(shutterA - shutterB) / max(shutterA, shutterB) > 0.15) {
            val fEff = PhotoCoachEngineImpl.computeEffectiveFocalLength(exifA)
                ?: PhotoCoachEngineImpl.computeEffectiveFocalLength(exifB)
            val guidelineNote = if (fEff != null) " (1/${fEff.toInt()}s hand-holding guideline)" else ""
            bullets.add(
                "Shutter speed: ${photoA.fileName} used ${ExifFormatter.shutterSpeed(shutterA)} vs " +
                    "${ExifFormatter.shutterSpeed(shutterB)} in ${photoB.fileName}$guidelineNote."
            )
        }

        val apA = exifA?.aperture
        val apB = exifB?.aperture
        if (apA != null && apB != null && abs(apA - apB) >= 0.3) {
            bullets.add(
                "Aperture: ${photoA.fileName} shot at ${ExifFormatter.aperture(apA)} vs " +
                    "${ExifFormatter.aperture(apB)} in ${photoB.fileName}, changing depth of field and light intake."
            )
        }

        val isoA = exifA?.iso
        val isoB = exifB?.iso
        if (isoA != null && isoB != null && isoA != isoB) {
            val noiseNote = if (scanA?.noiseLevel != null && scanB?.noiseLevel != null) {
                String.format(Locale.US, " (measured noise %.1f vs %.1f)", scanA.noiseLevel, scanB.noiseLevel)
            } else {
                ""
            }
            bullets.add("ISO sensitivity: ISO $isoA vs ISO $isoB$noiseNote.")
        }

        val sharpA = scanA?.sharpnessScore
        val sharpB = scanB?.sharpnessScore
        if (sharpA != null && sharpB != null && abs(sharpA - sharpB) >= 1.0) {
            bullets.add(
                String.format(
                    Locale.US,
                    "Measured sharpness: %.0f on %s vs %.0f on %s.",
                    sharpA,
                    photoA.fileName,
                    sharpB,
                    photoB.fileName,
                )
            )
        }

        val clipA = scanA?.highlightClipping
        val clipB = scanB?.highlightClipping
        if (clipA != null && clipB != null && abs(clipA - clipB) >= 1.0) {
            bullets.add(
                String.format(
                    Locale.US,
                    "Highlight clipping: %.1f%% on %s vs %.1f%% on %s.",
                    clipA,
                    photoA.fileName,
                    clipB,
                    photoB.fileName,
                )
            )
        }

        if (bullets.isEmpty()) {
            bullets.add("${photoA.fileName} and ${photoB.fileName} were captured with similar camera settings; compare micro-expressions and framing.")
        }

        val summary = bullets.joinToString(" ")
        return summary to bullets
    }

    private fun hasMeaningfulExifDifference(a: ImageItem, b: ImageItem): Boolean {
        val ea = a.exifData ?: return false
        val eb = b.exifData ?: return false
        val sa = ea.shutterSpeed
        val sb = eb.shutterSpeed
        val aa = ea.aperture
        val ab = eb.aperture
        val shutterDiff = sa != null && sb != null && abs(sa - sb) / max(sa, sb) > 0.15
        val apertureDiff = aa != null && ab != null && abs(aa - ab) >= 0.3
        val isoDiff = ea.iso != null && eb.iso != null && ea.iso != eb.iso
        return shutterDiff || apertureDiff || isoDiff
    }
}
