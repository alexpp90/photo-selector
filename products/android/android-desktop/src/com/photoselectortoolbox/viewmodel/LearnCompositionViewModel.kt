package com.photoselectortoolbox.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.model.CompositionResult
import com.photoselectortoolbox.domain.usecase.AnalyzeCompositionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The composition analysis of the photo Learn shows (REQ-AND-COMPOSITION.01). */
sealed interface LearnComposition {
    /** The photo it is about; null for [None]. */
    val uri: String?

    /** No photo. */
    data object None : LearnComposition {
        override val uri: String? = null
    }

    /** Being analysed: the composition overlays wait, the drawn guides do not. */
    data class Analysing(override val uri: String) : LearnComposition

    data class Ready(override val uri: String, val result: CompositionResult) : LearnComposition

    /** Could not be decoded or analysed: Learn shows what it has without it. */
    data class Unavailable(override val uri: String) : LearnComposition

    /** The result to draw, only when it belongs to [forUri]. */
    fun resultFor(forUri: String?): CompositionResult? = (this as? Ready)?.takeIf { it.uri == forUri }?.result
}

/**
 * Runs the composition analysis on demand for the photo Learn shows (finding B-AD-4): one
 * analysis per photo, cancelled when another photo is shown. Kept out of `SelectorViewModel`
 * on purpose: Learn is its only reader.
 */
@HiltViewModel
class LearnCompositionViewModel @Inject constructor(
    private val analyzeComposition: AnalyzeCompositionUseCase,
) : ViewModel() {

    private val _composition = MutableStateFlow<LearnComposition>(LearnComposition.None)
    val composition: StateFlow<LearnComposition> = _composition.asStateFlow()

    private var analysis: Job? = null

    /**
     * Learn now shows [image]. The same photo again starts nothing (its analysis runs or has
     * run); another photo cancels the running analysis and starts its own.
     */
    fun show(image: ImageItem?) {
        if (image == null) {
            analysis?.cancel()
            analysis = null
            _composition.value = LearnComposition.None
            return
        }
        if (_composition.value.uri == image.uri) return
        analysis?.cancel()
        _composition.value = LearnComposition.Analysing(image.uri)
        analysis = viewModelScope.launch {
            val result = analyzeComposition(image)
            _composition.update { current ->
                when {
                    current.uri != image.uri -> current
                    result != null -> LearnComposition.Ready(image.uri, result)
                    else -> LearnComposition.Unavailable(image.uri)
                }
            }
        }
    }
}
