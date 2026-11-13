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
import kotlinx.coroutines.flow.StateFlow

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

    /** See [LearnSession.showFolder]. */
    fun showFolder(folderUri: String?, photos: List<ImageItem>, listingSettled: Boolean, lastCompletedScan: CompletedScan?) =
        session.showFolder(folderUri, photos, listingSettled, lastCompletedScan)

    /** Learn is opened: progress is read again, so a *Reset Learn* in Setup shows here. */
    fun onEnter() = session.refreshProgress()

    fun completeLesson(progress: LessonProgressEntity) = session.completeLesson(progress)

    fun setGoal(goal: PracticeGoalEntity) = session.setGoal(goal)

    fun clearGoal() = session.clearGoal()
}
