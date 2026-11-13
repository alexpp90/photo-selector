package com.photoselectortoolbox.viewmodel

import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselector.saf.FileOperationResult
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.ui.fit.FitSelectorSettings
import com.photoselectortoolbox.ui.fit.fitImageRepository
import com.photoselectortoolbox.ui.fit.fitPhoto
import com.photoselectortoolbox.ui.fit.fitSelectorViewModel
import io.mockk.coEvery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The session tally counts photos, not actions (`[USER 2026-11-12]` decision #29, UX note F-29):
 * each photo counts once, under its latest decision; Remaining is the photos listed with no
 * decision; undoing a decision returns the photo to the one before it. The 12-photo case — copy
 * 5, then Move on 5 of which 3 were copied (refused: "Already copied"), then delete a copied one —
 * keeps Moved + Copied + Deleted + Remaining at 12 after every step, through the shipped
 * [SelectorViewModel].
 */
@Requirement("REQ-AND-LAYOUT.08", "REQ-AND-LAYOUT.21")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SessionTallyTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(dispatcher)
    private lateinit var viewModel: SelectorViewModel
    private lateinit var repo: ImageRepository
    private val photos = (1..12).map { fitPhoto("P%02d.JPG".format(it), 100_000L - it) }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = fitImageRepository().also { r ->
            coEvery { r.copyBundle(any(), any(), any(), any()) } answers {
                val item = secondArg<ImageItem>()
                FileOperationResult.success(item.uri, "content://test/sel/${item.fileName}")
            }
            coEvery { r.moveBundle(any(), any(), any(), any()) } answers {
                val item = secondArg<ImageItem>()
                FileOperationResult.success(item.uri, "content://test/sel/${item.fileName}")
            }
            coEvery { r.undoMoveBundle(any(), any(), any(), any(), any()) } answers {
                FileOperationResult.success(secondArg<List<String>>().first(), "content://test/folder/back")
            }
        }
        viewModel = fitSelectorViewModel(
            ApplicationProvider.getApplicationContext(), appScope,
            FitSelectorSettings(filingAction = FilingAction.DEFAULT), photos, imageRepository = repo,
        )
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        appScope.cancel()
        Dispatchers.resetMain()
    }

    private val state get() = viewModel.uiState.value

    private fun at(name: String) {
        val index = state.images.indexOfFirst { it.fileName == name }
        check(index >= 0) { "$name is not listed" }
        viewModel.navigateToImage(index)
    }

    private fun assertTwelve(step: String) {
        val sum = state.sessionMovedCount + state.sessionCopiedCount + state.sessionDeletedCount +
            state.sessionRemainingCount
        assertEquals("after $step: moved ${state.sessionMovedCount}, copied ${state.sessionCopiedCount}, " +
            "deleted ${state.sessionDeletedCount}, remaining ${state.sessionRemainingCount}", 12, sum)
    }

    private fun assertTally(moved: Int, copied: Int, deleted: Int, remaining: Int) {
        assertEquals("moved", moved, state.sessionMovedCount)
        assertEquals("copied", copied, state.sessionCopiedCount)
        assertEquals("deleted", deleted, state.sessionDeletedCount)
        assertEquals("remaining", remaining, state.sessionRemainingCount)
    }

    @Test
    fun `the twelve-photo case adds up after every step, and an undo returns to the decision before`() {
        assertTally(0, 0, 0, 12)
        listOf("P01.JPG", "P02.JPG", "P03.JPG", "P04.JPG", "P05.JPG").forEach {
            at(it); viewModel.copyToSelection(); assertTwelve("copy $it")
        }
        assertTally(moved = 0, copied = 5, deleted = 0, remaining = 7)

        // Move on five photos, three of them copied: those three file nothing and stay Copied.
        listOf("P01.JPG", "P02.JPG", "P03.JPG", "P06.JPG", "P07.JPG").forEach {
            at(it); viewModel.moveToSelection(); assertTwelve("move $it")
        }
        assertTally(moved = 2, copied = 5, deleted = 0, remaining = 5)

        // Delete a copied photo: its latest decision is Deleted, and it is counted once.
        at("P04.JPG"); viewModel.deleteCurrentImage(); assertTwelve("delete P04")
        assertTally(moved = 2, copied = 4, deleted = 1, remaining = 5)

        viewModel.undoLastOperation(); assertTwelve("undo the delete")
        assertTally(moved = 2, copied = 5, deleted = 0, remaining = 5)

        viewModel.undoLastOperation(); assertTwelve("undo the last move")
        assertTally(moved = 1, copied = 5, deleted = 0, remaining = 6)
    }

    @Test
    fun `deleting an undecided photo moves it from Remaining to Deleted`() {
        at("P09.JPG"); viewModel.deleteCurrentImage()
        assertTally(moved = 0, copied = 0, deleted = 1, remaining = 11)
    }
}
