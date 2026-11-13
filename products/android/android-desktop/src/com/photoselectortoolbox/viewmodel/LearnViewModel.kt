package com.photoselectortoolbox.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photoselectortoolbox.data.cache.LessonProgressEntity
import com.photoselectortoolbox.data.cache.PracticeGoalEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.repository.LearnRepository
import com.photoselectortoolbox.domain.learn.CompletedScan
import com.photoselectortoolbox.domain.learn.LearnSession
import com.photoselectortoolbox.domain.learn.LearnState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.photoselectortoolbox.domain.learn.CameraClass
import com.photoselectortoolbox.domain.learn.CameraId
import com.photoselectortoolbox.domain.learn.DebriefBuilder
import com.photoselectortoolbox.domain.learn.DebriefCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Learn's stored state (shoot debrief SD-1b): the facts the scan stored for the open folder, the
 * completed lessons and the practice goal, kept in a [LearnSession] that runs in this view model's
 * scope. Scoped to the Learn destination; it sees no scan that ends while Learn is closed, so the
 * screen forwards the folder and the cull screen's `lastCompletedScan` through [showFolder]. Kept
 * out of `SelectorViewModel`, which is oversized and is not Learn's.
 */
@HiltViewModel
class LearnViewModel @Inject constructor(
    repository: LearnRepository,
) : ViewModel() {

    private val session = LearnSession(repository, viewModelScope)

    val state: StateFlow<LearnState> = session.state

    private val _tab = MutableStateFlow(LearnTab.EXPLORE)

    /** The tab Learn shows: the one last used, except right after a scan (see [onEnter]). */
    val tab: StateFlow<LearnTab> = _tab.asStateFlow()

    /** The completed scan Learn last opened on Debrief for. */
    private var landedFor: Long? = null

    fun selectTab(tab: LearnTab) {
        _tab.value = tab
    }

    /** See [LearnSession.showFolder]. */
    fun showFolder(folderUri: String?, photos: List<ImageItem>, listingSettled: Boolean, lastCompletedScan: CompletedScan?) =
        session.showFolder(folderUri, photos, listingSettled, lastCompletedScan)

    /**
     * Learn is opened. Progress is read again, so a *Reset Learn* in Setup shows here. The first
     * entry after a scan ran to its end ([lastCompletedScan] new) opens on Debrief, once; every
     * other entry opens on the tab last used (SD-4 step 1).
     */
    fun onEnter(lastCompletedScan: CompletedScan? = null) {
        session.refreshProgress()
        val finished = lastCompletedScan?.finishedAtEpochMs ?: return
        if (finished != landedFor) {
            landedFor = finished
            _tab.value = LearnTab.DEBRIEF
        }
    }

    /** The camera chip: the group's cameras are of [cameraClass]. */
    fun setCameraClass(cameras: List<CameraId>, cameraClass: CameraClass) = session.setCameraClass(cameras, cameraClass)

    /** *Track*: the card's focus becomes the one practice goal. */
    fun track(card: DebriefCard) {
        DebriefBuilder.goalFor(card, System.currentTimeMillis())?.let(session::setGoal)
    }

    fun completeLesson(progress: LessonProgressEntity) = session.completeLesson(progress)

    fun setGoal(goal: PracticeGoalEntity) = session.setGoal(goal)

    fun clearGoal() = session.clearGoal()
}
