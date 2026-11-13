package com.photoselectortoolbox.domain.learn

import android.util.Log
import com.photoselectortoolbox.data.cache.LessonProgressEntity
import com.photoselectortoolbox.data.cache.PracticeGoalEntity
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.repository.LearnRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What Learn shows from what the scan stored and what the user did (shoot debrief SD-1b). */
data class LearnState(
    /** The folder [factsByUri] belongs to; null before the first one. */
    val folderUri: String? = null,
    val factsByUri: Map<String, List<TechniqueFactEntity>> = emptyMap(),
    val completedLessons: Map<String, LessonProgressEntity> = emptyMap(),
    val activeGoal: PracticeGoalEntity? = null,
)

/**
 * Learn's stored state (shoot debrief SD-1b, findings S1 and S2): the facts the scan stored for
 * the open folder, the completed lessons and the practice goal, the last two read from and
 * written through [LearnRepository], so they outlive the screen and the process. The Learn view
 * model owns one, running in its own scope ([scope]); it is kept out of `SelectorViewModel`,
 * which is oversized and is not Learn's.
 *
 * Learn's view model is scoped to the Learn destination and sees no scan that ends while Learn is
 * closed, so the screen forwards the open folder, its photos, whether the listing has settled and
 * the cull screen's `lastCompletedScan` through [showFolder]. The facts load once per folder when
 * its photo list has settled, and again when that folder's completed scan changes — not on every
 * batch the listing delivers — and a result for a folder that is no longer open, or for an older
 * request, is dropped.
 */
class LearnSession(
    private val repository: LearnRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(LearnState())
    val state: StateFlow<LearnState> = _state.asStateFlow()

    /** The folder and scan the facts were last asked for: a repeat asks nothing. */
    private var factsKey: Pair<String, Long?>? = null
    private var factsLoad: Job? = null

    private companion object {
        const val TAG = "LearnSession"
    }

    init {
        scope.launch {
            try {
                val lessons = repository.lessonProgress()
                val goal = repository.practiceGoal()
                // Anything the user did while this read ran wins over what it read.
                _state.update { it.copy(completedLessons = lessons + it.completedLessons, activeGoal = it.activeGoal ?: goal) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not read Learn progress", e)
            }
        }
    }

    /**
     * Learn shows [folderUri] with [photos]. [listingSettled]: the folder listing has delivered
     * its last batch. [lastCompletedScan]: the cull screen's last scan that ran to its end, of
     * any folder; only one of [folderUri] reloads the facts.
     */
    fun showFolder(
        folderUri: String?,
        photos: List<ImageItem>,
        listingSettled: Boolean,
        lastCompletedScan: CompletedScan?,
    ) {
        if (folderUri == null) {
            factsLoad?.cancel()
            factsKey = null
            _state.update { it.copy(folderUri = null, factsByUri = emptyMap()) }
            return
        }
        if (_state.value.folderUri != folderUri) {
            // Another folder: nothing of the last one stays on screen while this one loads.
            factsLoad?.cancel()
            factsKey = null
            _state.update { it.copy(folderUri = folderUri, factsByUri = emptyMap()) }
        }
        if (!listingSettled) return
        val scanOfThisFolder = lastCompletedScan?.takeIf { it.folderUri == folderUri }?.finishedAtEpochMs
        val key = folderUri to scanOfThisFolder
        if (key == factsKey) return
        factsKey = key
        factsLoad?.cancel()
        val snapshot = photos.toList()
        factsLoad = scope.launch {
            val facts = try {
                repository.factsFor(snapshot)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the stored facts", e)
                return@launch
            }
            _state.update { current ->
                if (current.folderUri != folderUri || factsKey != key) current else current.copy(factsByUri = facts)
            }
        }
    }

    /** The user finished [progress]'s lesson: shown at once, and kept. */
    fun completeLesson(progress: LessonProgressEntity) {
        _state.update { it.copy(completedLessons = it.completedLessons + (progress.lessonId to progress)) }
        persist("lesson progress") { repository.saveLessonProgress(progress) }
    }

    /** [goal] becomes the one practice goal: shown at once, and kept. */
    fun setGoal(goal: PracticeGoalEntity) {
        _state.update { it.copy(activeGoal = goal) }
        persist("the practice goal") { repository.setPracticeGoal(goal) }
    }

    fun clearGoal() {
        _state.update { it.copy(activeGoal = null) }
        persist("the cleared practice goal") { repository.clearPracticeGoal() }
    }

    private fun persist(what: String, write: suspend () -> Unit) {
        scope.launch {
            try {
                write()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not keep $what", e)
            }
        }
    }
}
