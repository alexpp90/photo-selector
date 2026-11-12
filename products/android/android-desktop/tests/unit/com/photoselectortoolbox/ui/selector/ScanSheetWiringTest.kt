package com.photoselectortoolbox.ui.selector

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselectortoolbox.domain.scoring.ScanMeasures
import com.photoselectortoolbox.domain.usecase.ScanImagesUseCase
import com.photoselectortoolbox.ui.fit.FitCell
import com.photoselectortoolbox.ui.fit.fitPhoto
import com.photoselectortoolbox.ui.fit.fitSelectorViewModel
import com.photoselectortoolbox.ui.fit.installNoImageLoader
import com.photoselectortoolbox.ui.fit.removeNoImageLoader
import com.photoselectortoolbox.ui.fit.tagExists
import com.photoselectortoolbox.ui.navigation.Screen
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import com.photoselectortoolbox.viewmodel.SelectorViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Scan sheet's rows decide what the scan computes (REQ-AND-ALGO.07): through the shipped
 * selector screen, its Scan button and sheet, down to the scan use case.
 */
@Requirement("REQ-AND-ALGO.07")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
class ScanSheetWiringTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val dispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(dispatcher)
    private lateinit var viewModel: SelectorViewModel
    private val scan = mockk<ScanImagesUseCase> { every { this@mockk.invoke(any(), any()) } returns emptyFlow() }

    @Before
    fun setUp() {
        installNoImageLoader(ApplicationProvider.getApplicationContext())
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        removeNoImageLoader()
        if (this::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        appScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `an unticked row is not measured`() {
        viewModel = fitSelectorViewModel(
            ApplicationProvider.getApplicationContext(), appScope,
            photos = (1..3).map { fitPhoto("P$it.JPG", 10_000L - it) },
            scanImagesUseCase = scan,
        )
        val cell = FitCell(1280, 800, 1f)
        rule.setContent {
            PhotoSelectorToolboxTheme { SelectorScreen(windowSizeClass = cell.windowSizeClass, viewModel = viewModel) }
        }
        rule.waitUntil(5_000) { rule.tagExists("scan_button") }

        rule.onNodeWithTag("scan_button").performClick()
        rule.onNodeWithText(ScanSheetText.NOISE).performClick()
        rule.onNodeWithText("Start Scan").performClick()
        rule.waitForIdle()

        verify(exactly = 1) { scan.invoke(any(), ScanMeasures(noise = false)) }
    }

    @Test
    fun `Setup promises no measurement choice - the Scan sheet has it`() {
        assertFalse(Screen.Settings.meaning, Screen.Settings.meaning.contains("measurement", ignoreCase = true))
    }
}
