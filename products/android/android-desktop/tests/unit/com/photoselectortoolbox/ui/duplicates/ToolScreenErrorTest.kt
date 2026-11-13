package com.photoselectortoolbox.ui.duplicates

import android.content.Context
import android.net.Uri
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselectortoolbox.data.model.DuplicateGroup
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.repository.HeldDeleteCommitter
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.domain.duplicates.DuplicateDetectionProgress
import com.photoselectortoolbox.domain.usecase.FindDuplicatesUseCase
import com.photoselectortoolbox.ui.statistics.StatisticsScreen
import com.photoselectortoolbox.viewmodel.DuplicatesViewModel
import com.photoselectortoolbox.viewmodel.StatisticsUiState
import com.photoselectortoolbox.viewmodel.StatisticsViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Dupes and Stats show their errors (review 2026-11-09 finding FD-UI-5): both view models set
 * `error`, and neither screen drew it, so "Keep one copy" (a refused tick on the last copy), a
 * failed duplicate scan and a failed Stats analysis said nothing. Each now reaches the screen as a
 * snackbar, through the shipped screens; each emission has its own sequence, so a second identical
 * message is a new card. A revoked folder is said once, by the empty state, not twice.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class, ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ToolScreenErrorTest {

    @get:Rule
    val rule = createComposeRule()

    private val dispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val windowSize = WindowSizeClass.calculateFromSize(DpSize(1200.dp, 800.dp))
    private val repository = mockk<ImageRepository>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    private fun photo(name: String) =
        ImageItem(uri = "content://test/$name", fileName = name, fileSize = 10, lastModified = 1, mimeType = "image/jpeg")

    private val a = photo("a.jpg")
    private val b = photo("b.jpg")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.openFolder(any(), any()) } returns "Trip"
        coEvery { repository.discoverAllImages(any()) } returns listOf(a, b)
    }

    @After
    fun tearDown() {
        appScope.cancel()
        Dispatchers.resetMain()
    }

    private fun dupes(finder: FindDuplicatesUseCase): DuplicatesViewModel {
        val held = HeldDeleteCommitter(repository, mockk(relaxed = true), mockk(relaxed = true), context)
        return DuplicatesViewModel(finder, repository, held, appScope, context, mockk(relaxed = true)).apply {
            selectFolder(Uri.parse("content://test/folder"))
            startScan()
        }
    }

    private fun oneGroup() = mockk<FindDuplicatesUseCase> {
        every { this@mockk.invoke(any()) } returns
            flowOf(DuplicateDetectionProgress(2, 2, listOf(DuplicateGroup("h", listOf(a.uri, b.uri)))))
    }

    @Test
    fun `ticking the last copy says Keep one copy on the Dupes screen, and again the second time`() {
        val vm = dupes(oneGroup())
        rule.setContent { DuplicatesScreen(windowSizeClass = windowSize, viewModel = vm) }
        vm.toggleSelection(b.uri)
        vm.toggleSelection(a.uri)
        rule.waitForIdle()
        rule.onNodeWithText(KEEP_ONE_COPY).assertExists()
        val first = vm.uiState.value.errorSequence

        vm.toggleSelection(a.uri)
        rule.waitForIdle()
        assertTrue("a second identical refusal is a new card", vm.uiState.value.errorSequence > first)
        assertEquals(1, rule.onAllNodesWithText(KEEP_ONE_COPY).fetchSemanticsNodes().size)
    }

    @Test
    fun `a failed duplicate scan says so`() {
        val failing = mockk<FindDuplicatesUseCase> {
            every { this@mockk.invoke(any()) } returns flow { throw IllegalStateException("unreadable") }
        }
        val vm = dupes(failing)
        rule.setContent { DuplicatesScreen(windowSizeClass = windowSize, viewModel = vm) }
        rule.waitForIdle()
        rule.onNodeWithText("Scan failed").assertExists()
    }

    @Test
    fun `a failed Stats analysis says so`() {
        val vm = mockk<StatisticsViewModel>(relaxed = true)
        every { vm.uiState } returns MutableStateFlow(
            StatisticsUiState(folderUri = "content://tree/trip", error = "Analysis failed", errorSequence = 1),
        )
        rule.setContent { StatisticsScreen(windowSizeClass = windowSize, viewModel = vm) }
        rule.waitForIdle()
        rule.onNodeWithText("Analysis failed").assertExists()
    }

    @Test
    fun `a revoked folder is said once, by the empty state`() {
        val vm = mockk<StatisticsViewModel>(relaxed = true)
        every { vm.uiState } returns MutableStateFlow(
            StatisticsUiState(missingGrantPrompt = true, error = REVOKED, errorSequence = 1),
        )
        rule.setContent { StatisticsScreen(windowSizeClass = windowSize, viewModel = vm) }
        rule.waitForIdle()
        rule.onNodeWithText("Folder Access Needed").assertExists()
        assertEquals(0, rule.onAllNodesWithText(REVOKED).fetchSemanticsNodes().size)
    }

    private companion object {
        /** As the view model words its refusal (`DuplicatesViewModel.KEEP_ONE_COPY`, private there). */
        const val KEEP_ONE_COPY = "Keep one copy"
        const val REVOKED = "Failed to load folder: permission revoked or directory deleted."
    }
}
