package com.photoselectortoolbox.viewmodel

import android.content.Context
import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.repository.CacheRepository
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.domain.grouping.GroupingLevel
import com.photoselectortoolbox.domain.interaction.FilingAction
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The Settings screen's state, through the shipped ViewModel. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val cache = mockk<CacheRepository>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { settings.selectionFolderName } returns flowOf("Selection")
        every { settings.sortingEnabled } returns flowOf(true)
        every { settings.groupingEnabled } returns flowOf(false)
        every { settings.groupingLevel } returns flowOf(GroupingLevel.TIME_FILENAME)
        every { settings.analysisThreadCount } returns flowOf(4)
        every { settings.fullscreenButtonsEnabled } returns flowOf(true)
        every { settings.filingAction } returns flowOf(FilingAction.DEFAULT)
        every { settings.lastFolderUri } returns flowOf(null)
        every { settings.secondarySelectionFolderName } returns flowOf("Second")
        every { settings.customSelectionDestinationUri } returns flowOf(null)
        every { settings.tvDisplayScaleEnabled } returns flowOf(false)
    }

    private fun viewModel() = SettingsViewModel(
        settingsRepository = settings,
        cacheRepository = cache,
        imageRepository = mockk<ImageRepository>(relaxed = true),
        context = mockk<Context>(relaxed = true),
    )

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    @Requirement("REQ-AND-CACHE.02")
    fun `the cache row states how many results are stored`() {
        coEvery { cache.count() } returns 3

        val viewModel = viewModel()

        assertEquals(3, viewModel.uiState.value.cachedScoreCount)
    }

    @Test
    fun `clearing the cache reads zero`() {
        coEvery { cache.count() } returns 3
        val viewModel = viewModel()

        viewModel.clearCache()

        assertEquals(0, viewModel.uiState.value.cachedScoreCount)
    }

    @Test
    fun `the settings flows do not overwrite the stored count`() {
        coEvery { cache.count() } returns 7

        val viewModel = viewModel()

        assertEquals("Second", viewModel.uiState.value.secondarySelectionFolderName)
        assertEquals(7, viewModel.uiState.value.cachedScoreCount)
    }
}
