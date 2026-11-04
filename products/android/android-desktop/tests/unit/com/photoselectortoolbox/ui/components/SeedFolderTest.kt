package com.photoselectortoolbox.ui.components

import android.net.Uri
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselectortoolbox.ui.duplicates.DuplicatesScreen
import com.photoselectortoolbox.ui.statistics.StatisticsScreen
import com.photoselectortoolbox.viewmodel.DuplicatesUiState
import com.photoselectortoolbox.viewmodel.DuplicatesViewModel
import com.photoselectortoolbox.viewmodel.StatisticsUiState
import com.photoselectortoolbox.viewmodel.StatisticsViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Statistics and Duplicates open the folder the selector has open (FD-UX-3), through the screens as
 * they ship, with their own folder winning once they have one.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SeedFolderTest {

    @get:Rule
    val rule = createComposeRule()

    private val windowSize = WindowSizeClass.calculateFromSize(DpSize(1200.dp, 800.dp))
    private val seed = "content://tree/shoot"

    @Test
    fun `Statistics opens the selector's folder when it has none`() {
        val viewModel = mockk<StatisticsViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(StatisticsUiState())

        rule.setContent { StatisticsScreen(windowSizeClass = windowSize, seedFolderUri = seed, viewModel = viewModel) }
        rule.waitForIdle()

        verify(exactly = 1) { viewModel.selectFolder(Uri.parse(seed)) }
    }

    @Test
    fun `Statistics keeps a folder of its own`() {
        val viewModel = mockk<StatisticsViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(StatisticsUiState(folderUri = "content://tree/other"))

        rule.setContent { StatisticsScreen(windowSizeClass = windowSize, seedFolderUri = seed, viewModel = viewModel) }
        rule.waitForIdle()

        verify(exactly = 0) { viewModel.selectFolder(any()) }
    }

    @Test
    fun `Duplicates opens the selector's folder when it has none`() {
        val viewModel = mockk<DuplicatesViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(DuplicatesUiState())

        rule.setContent { DuplicatesScreen(windowSizeClass = windowSize, seedFolderUri = seed, viewModel = viewModel) }
        rule.waitForIdle()

        verify(exactly = 1) { viewModel.selectFolder(Uri.parse(seed)) }
    }

    @Test
    fun `no selector folder seeds nothing`() {
        val viewModel = mockk<DuplicatesViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(DuplicatesUiState())

        rule.setContent { DuplicatesScreen(windowSizeClass = windowSize, seedFolderUri = null, viewModel = viewModel) }
        rule.waitForIdle()

        verify(exactly = 0) { viewModel.selectFolder(any()) }
    }
}
