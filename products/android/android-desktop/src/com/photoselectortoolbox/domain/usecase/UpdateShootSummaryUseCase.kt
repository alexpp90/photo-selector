package com.photoselectortoolbox.domain.usecase

import android.util.Log
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.repository.LearnRepository
import com.photoselectortoolbox.domain.learn.CameraClassifier
import com.photoselectortoolbox.domain.learn.CompletedScan
import com.photoselectortoolbox.domain.learn.DebriefGroup
import com.photoselectortoolbox.domain.learn.ShootDebriefEngine
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/**
 * After a scan that ran to its end (shoot debrief SD-3): the folder's debrief from the facts the
 * scan stored, kept as its shoot summaries, so a later shoot with the same kind of camera has a
 * previous value even after *Clear cache*. The cull screen's view model calls it in the success
 * branch of its scan, beside setting `lastCompletedScan`; it never fails that scan.
 */
class UpdateShootSummaryUseCase @Inject constructor(
    private val learn: LearnRepository,
) {
    private companion object {
        const val TAG = "UpdateShootSummary"
    }

    /**
     * [photos] (the list [scan] was run on) summarised and stored for [CompletedScan.folderUri],
     * `partial` as [scan] says. Returns the groups, or null when they could not be stored.
     */
    suspend operator fun invoke(
        scan: CompletedScan,
        photos: List<ImageItem>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): List<DebriefGroup>? = try {
        val facts = learn.factsFor(photos)
        val classifier = CameraClassifier.withOverrides(learn.cameraOverrides())
        val groups = ShootDebriefEngine.groups(photos, facts, classifier)
        learn.saveShoot(scan.folderUri, groups, scan.partial, nowEpochMs)
        groups
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Could not store the shoot summary", e)
        null
    }
}
