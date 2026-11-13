package com.photoselectortoolbox.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.domain.usecase.ScanImagesUseCase
import com.photoselectortoolbox.domain.usecase.ScanProgress
import com.photoselectortoolbox.domain.usecase.UpdateShootSummaryUseCase
import com.photoselectortoolbox.testing.InMemoryLearnRepository
import com.photoselectortoolbox.ui.fit.fitPhoto
import com.photoselectortoolbox.ui.fit.fitSelectorViewModel
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A scan that ran to its end tells Learn (shoot debrief SD-1a step 4) and keeps the folder's shoot
 * summary (SD-3 step 3), through the cull screen's real view model; a cancelled or failed scan does
 * neither.
 */
@Requirement("REQ-AND-LEARN.01", "REQ-AND-LEARN.04")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanCompletionTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(dispatcher)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val repo = InMemoryLearnRepository()
    private val photos = listOf(fitPhoto("A.JPG", 3_000), fitPhoto("B.JPG", 2_000))
    private val made = mutableListOf<SelectorViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        made.forEach { it.viewModelScope.cancel() }
        appScope.cancel()
        Dispatchers.resetMain()
    }

    private fun open(scan: ScanImagesUseCase) = fitSelectorViewModel(
        context, appScope, photos = photos, scanImagesUseCase = scan, updateShootSummary = UpdateShootSummaryUseCase(repo),
    ).also { made += it }

    private fun scanning(progress: kotlinx.coroutines.flow.Flow<ScanProgress>): ScanImagesUseCase =
        mockk(relaxed = true) { every { this@mockk(any(), any()) } returns progress }

    @Test
    fun `a finished scan is the last completed one and keeps the shoot`() {
        val results = mapOf(photos[0].uri to ScanResult(photos[0].uri, sharpnessScore = 80.0))
        val vm = open(scanning(flowOf(ScanProgress(2, 2, "", results))))
        vm.startScan()

        val completed = vm.uiState.value.lastCompletedScan
        assertNotNull(completed)
        assertEquals("content://test/folder", completed!!.folderUri)
        assertEquals(2, completed.photoCount)
        assertEquals("one photo had no result", 1, completed.withoutResult)
        assertTrue(completed.partial)
        assertTrue("the shoot summary is stored", "content://test/folder" in repo.shoots)
        assertEquals(true, repo.shoots["content://test/folder"]?.second)
    }

    @Test
    fun `a failed scan sets none and keeps no shoot`() {
        val vm = open(scanning(flow { throw IOException("decoder") }))
        vm.startScan()
        assertNull(vm.uiState.value.lastCompletedScan)
        assertTrue(repo.shoots.isEmpty())
    }

    @Test
    fun `a cancelled scan sets none and keeps no shoot`() {
        val vm = open(scanning(MutableSharedFlow()))
        vm.startScan()
        vm.cancelScan()
        assertFalse(vm.uiState.value.isScanRunning)
        assertNull(vm.uiState.value.lastCompletedScan)
        assertTrue(repo.shoots.isEmpty())
    }
}
