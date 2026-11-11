package com.photoselectortoolbox.viewmodel

import androidx.lifecycle.viewModelScope
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.model.CompositionResult
import com.photoselectortoolbox.domain.usecase.AnalyzeCompositionUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Learn runs the composition analysis of the photo it shows, one at a time (finding B-AD-4). */
@OptIn(ExperimentalCoroutinesApi::class)
class LearnCompositionViewModelTest {

    private fun image(name: String) = ImageItem("content://test/folder/$name", name, 1L, 1L, "image/jpeg")

    private fun result(x: Float) = CompositionResult(emptyList(), x, 0.5f, List(9) { 1f / 9 })

    private val pending = mutableMapOf<String, CompletableDeferred<CompositionResult?>>()
    private val cancelled = mutableListOf<String>()

    private val analyze = mockk<AnalyzeCompositionUseCase> {
        coEvery { this@mockk.invoke(any()) } coAnswers {
            val uri = firstArg<ImageItem>().uri
            val gate = pending.getOrPut(uri) { CompletableDeferred() }
            try {
                gate.await()
            } catch (e: CancellationException) {
                cancelled += uri
                throw e
            }
        }
    }

    private lateinit var viewModel: LearnCompositionViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = LearnCompositionViewModel(analyze)
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `opening Learn on a photo runs one analysis and publishes its result`() = runTest {
        val a = image("a.jpg")

        viewModel.show(a)
        assertEquals(LearnComposition.Analysing(a.uri), viewModel.composition.value)
        viewModel.show(a)
        pending.getValue(a.uri).complete(result(0.3f))

        assertEquals(LearnComposition.Ready(a.uri, result(0.3f)), viewModel.composition.value)
        assertEquals(result(0.3f), viewModel.composition.value.resultFor(a.uri))
        coVerify(exactly = 1) { analyze(a) }
    }

    @Test
    fun `showing another photo cancels the running analysis and its result never lands`() = runTest {
        val a = image("a.jpg")
        val b = image("b.jpg")

        viewModel.show(a)
        viewModel.show(b)

        assertEquals(listOf(a.uri), cancelled)
        assertEquals(LearnComposition.Analysing(b.uri), viewModel.composition.value)
        pending.getValue(b.uri).complete(result(0.7f))
        assertEquals(LearnComposition.Ready(b.uri, result(0.7f)), viewModel.composition.value)
        assertNull("a result is never drawn on another photo", viewModel.composition.value.resultFor(a.uri))
    }

    @Test
    fun `a photo that cannot be analysed is unavailable, and no photo is none`() = runTest {
        val a = image("a.jpg")
        viewModel.show(a)
        pending.getValue(a.uri).complete(null)
        assertEquals(LearnComposition.Unavailable(a.uri), viewModel.composition.value)

        viewModel.show(null)
        assertEquals(LearnComposition.None, viewModel.composition.value)
        assertTrue(cancelled.isEmpty())
    }
}
