package com.photoselectortoolbox.ui.duplicates

import android.content.Context
import android.net.Uri
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselectortoolbox.data.model.DuplicateGroup
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.repository.HeldDeleteCommitter
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.domain.curation.FileIdentity
import com.photoselectortoolbox.domain.duplicates.DuplicateDetectionProgress
import com.photoselectortoolbox.domain.format.DuplicateLabels
import com.photoselectortoolbox.domain.usecase.FindDuplicatesUseCase
import com.photoselectortoolbox.viewmodel.DuplicatesViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Dupes delete through the screen as it ships (REQ-AND-LAYOUT.24, UX note F9): the dialog says
 * when the files go, the confirmation card offers UNDO, UNDO brings the copy back, and only leaving
 * the screen deletes.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class, ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DuplicatesHeldDeleteScreenTest {

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
        coEvery { repository.deleteImage(any(), any()) } returns true
        coEvery { repository.documentIdentity(any(), any()) } returns FileIdentity(10, 1)
    }

    @After
    fun tearDown() {
        appScope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel(): DuplicatesViewModel {
        val finder = mockk<FindDuplicatesUseCase> {
            every { this@mockk.invoke(any()) } returns
                flowOf(DuplicateDetectionProgress(2, 2, listOf(DuplicateGroup("h", listOf(a.uri, b.uri)))))
        }
        val held = HeldDeleteCommitter(repository, mockk(relaxed = true), mockk(relaxed = true), context)
        return DuplicatesViewModel(finder, repository, held, appScope, context, mockk(relaxed = true)).apply {
            selectFolder(Uri.parse("content://test/folder"))
            startScan()
            toggleSelection(b.uri)
        }
    }

    @Test
    fun `the dialog says when the files go, and UNDO brings the copy back`() {
        val vm = viewModel()
        rule.setContent { DuplicatesScreen(windowSizeClass = windowSize, viewModel = vm) }

        rule.onNodeWithText("Delete Selected (1)").performClick()
        rule.onNodeWithText(DuplicateLabels.confirmTitle(1)).assertIsDisplayed()
        rule.onNodeWithText(DuplicateLabels.CONFIRM_BODY).assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()

        rule.onNodeWithText(DuplicateLabels.deletedMessage(1)).assertIsDisplayed()
        coVerify(exactly = 0) { repository.deleteImage(any(), any()) }

        rule.onNodeWithTag(HELD_DELETE_UNDO_TAG).performClick()
        rule.waitForIdle()

        assertEquals(listOf(a.uri, b.uri), vm.uiState.value.duplicateGroups.single().files)
        rule.onNodeWithTag(HELD_DELETE_UNDO_TAG).assertDoesNotExist()
        coVerify(exactly = 0) { repository.deleteImage(any(), any()) }
    }

    @Test
    fun `leaving the Dupes screen deletes the held copy`() {
        val vm = viewModel()
        var shown by mutableStateOf(true)
        rule.setContent { if (shown) DuplicatesScreen(windowSizeClass = windowSize, viewModel = vm) }

        rule.onNodeWithText("Delete Selected (1)").performClick()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        coVerify(exactly = 0) { repository.deleteImage(any(), any()) }

        shown = false
        rule.waitForIdle()

        coVerify(exactly = 1) { repository.deleteImage(any(), Uri.parse(b.uri)) }
        coVerify(exactly = 0) { repository.deleteImage(any(), Uri.parse(a.uri)) }
    }
}
