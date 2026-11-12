package com.phototok.viewmodel

import androidx.lifecycle.ViewModel
import com.phototok.data.repository.SystemTrashRepository
import com.phototok.data.repository.SystemTrashRequest
import com.phototok.data.repository.SystemTrashUiState
import com.phototok.data.repository.TrashNotice
import com.phototok.di.ApplicationScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The Activity's side of the system bin ([USER 2026-11-12] decision #13): it launches the consent
 * requests [SystemTrashRepository] holds and reports the answer. The answer is settled on the
 * application scope, so a finishing Activity cannot cut the read-back short.
 */
@HiltViewModel
class SystemTrashViewModel @Inject constructor(
    private val repository: SystemTrashRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    val state: StateFlow<SystemTrashUiState> = repository.state

    fun onLaunched(request: SystemTrashRequest) = repository.onLaunched(request)

    fun onResult(accepted: Boolean) {
        appScope.launch { repository.onResult(accepted) }
    }

    fun retry(notice: TrashNotice) = repository.retry(notice)

    fun clearNotice(notice: TrashNotice) = repository.clearNotice(notice)
}
