package com.phototok.viewmodel

import android.net.Uri
import com.photoselector.core.Requirement
import com.phototok.R
import com.phototok.data.model.ImageItem
import com.phototok.data.model.PhoneSettings
import com.phototok.data.model.SourceSettings
import com.phototok.data.repository.ImageRepository
import com.phototok.data.repository.SettingsRepository
import com.phototok.data.source.FileOperationResult
import com.phototok.data.source.SelectionListing
import com.phototok.de
import com.phototok.domain.FavouritesSnapshot
import com.phototok.domain.FileTypeFilter
import com.phototok.domain.FolderScanInfo
import com.phototok.domain.SwipeAction
import com.phototok.domain.SwipeRightVerb
import com.phototok.domain.UiText
import com.phototok.en
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Class check "feedback after every action" (findings F-TOK-018, F-TOK-037, F-TOK-051, F-TOK-053,
 * F-TOK-062, F-TOK-086; gap analysis 2026-11-09, rank 1).
 *
 * One table: every user action of the feed and the Selection viewer, and what the user sees after
 * it. An action whose result is not on screen (a copy, a move, a delete, a removed favourite, a
 * batch copy) must raise a snackbar that names what happened — the verb is the resource, so a copy
 * never reads like a move — and the destination folder where there is one, in English and German.
 * An action whose result *is* on screen confirms nothing more (REQ-TOK-UX.01, "nothing confirms what
 * the user can already see"): a ★ lights the favourite badge (F-TOK-053), Revert shows the restored
 * photo (REQ-TOK-GESTURE.03), Share opens the system sheet. Each row checks the effect it relies on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionFeedbackClassTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val phoneTree = "content://com.android.externalstorage.documents/tree/primary%3ADCIM"
    private val rejectsTree = "content://com.android.externalstorage.documents/tree/primary%3ARejects"
    private val selectionName = "Keepers"
    private val rejectsName = "Rejects"

    private val settingsRepository: SettingsRepository = mockk(relaxed = true) {
        every { phoneSettings } returns MutableStateFlow(PhoneSettings(proMode = true))
        every { phoneCollectionUri } returns MutableStateFlow<String?>(null)
        every { phoneLeftSwipeUri } returns MutableStateFlow<String?>(null)
        every { lastFolderUri } returns MutableStateFlow<String?>(null)
        every { sortingEnabled } returns MutableStateFlow(true)
        every { phoneGestureTutorialTs } returns MutableStateFlow(System.currentTimeMillis())
        every { lastAppUsedTs } returns MutableStateFlow(System.currentTimeMillis())
        coEvery { getFolderScanInfo(any()) } returns FolderScanInfo(0L, 0)
    }

    private val imageRepository: ImageRepository = mockk(relaxed = true) {
        coEvery { prepareSourceFolder(any()) } returns "DCIM"
        coEvery { resolveFolderName(any()) } returns null
        coEvery { resolveFolderName(Uri.parse(rejectsTree)) } returns rejectsName
        coEvery { getExifData(any()) } returns null
        coEvery { getImageDimensions(any()) } returns Pair(0, 0)
        coEvery { copyImageDetailed(any(), any(), any(), any()) } coAnswers {
            FileOperationResult.success(firstArg<Uri>().toString(), null)
        }
        coEvery { moveImageDetailed(any(), any(), any(), any()) } coAnswers {
            FileOperationResult.success(firstArg<Uri>().toString(), null)
        }
        coEvery { transferBundleDetailed(any(), any(), any(), any(), any()) } coAnswers {
            firstArg<List<Uri>>().map { FileOperationResult.success(it.toString(), null) }
        }
    }

    private fun image(name: String, modified: Long, mime: String = "image/jpeg") = ImageItem(
        uri = "content://photos/$name",
        fileName = name,
        fileSize = 1,
        lastModified = modified,
        mimeType = mime,
        imageWidth = 100,
        imageHeight = 50,
    )

    private val photos = listOf(image("a.jpg", 30), image("b.jpg", 20), image("c.jpg", 10))

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** What the user sees after the action. */
    private sealed interface Seen {
        /** A snackbar with [message]; [names] must appear in its text in every language. */
        data class Snackbar(val message: UiText, val names: String?, val undo: Boolean = false) : Seen

        /** No snackbar: the screen itself shows the result, which [shows] checks. */
        data class OnScreen(val what: String, val shows: (PhoneModeUiState, ImageItem) -> Boolean) : Seen
    }

    private class Row(
        val action: String,
        val settings: SourceSettings,
        val perform: PhoneModeViewModel.() -> Unit,
        val seen: Seen,
    )

    private val copySettings = SourceSettings(
        swipeRightVerb = SwipeRightVerb.COPY,
        swipeLeftVerb = SwipeAction.DELETE,
        directDeleteConfirmEnabled = false,
        selectionSubfolder = selectionName,
    )

    private val feedRows = listOf(
        Row(
            "swipe right (keep) with Copy",
            copySettings,
            { addToCollection() },
            Seen.Snackbar(UiText.of(R.string.snackbar_copied, UiText.raw(selectionName)), selectionName),
        ),
        Row(
            "swipe right (keep) with Move",
            copySettings.copy(swipeRightVerb = SwipeRightVerb.MOVE),
            { addToCollection() },
            Seen.Snackbar(UiText.of(R.string.snackbar_moved, UiText.raw(selectionName)), selectionName),
        ),
        Row(
            "swipe right (keep) with ★ Favourite",
            copySettings.copy(swipeRightVerb = SwipeRightVerb.FAVOURITE),
            { addToCollection() },
            Seen.OnScreen("the favourite badge of the kept photo is lit") { s, acted -> s.favourites.isFavourite(acted.uri) },
        ),
        Row(
            "long-press Copy to Selection",
            copySettings,
            { copyCurrentToSelection() },
            Seen.Snackbar(UiText.of(R.string.snackbar_copied, UiText.raw(selectionName)), selectionName),
        ),
        Row(
            "long-press Move to Selection",
            copySettings,
            { moveCurrentToSelection() },
            Seen.Snackbar(UiText.of(R.string.snackbar_moved, UiText.raw(selectionName)), selectionName),
        ),
        Row(
            "long-press Favourite",
            copySettings,
            { toggleCurrentFavourite() },
            Seen.OnScreen("the favourite badge is lit") { s, acted -> s.favourites.isFavourite(acted.uri) },
        ),
        Row(
            "long-press Remove favourite",
            copySettings,
            { toggleCurrentFavourite(); clearFeedback(); toggleCurrentFavourite() },
            Seen.Snackbar(UiText.of(R.string.snackbar_favourite_removed), names = null),
        ),
        Row(
            "swipe left with Delete (no confirmation)",
            copySettings,
            { onSwipeLeft() },
            Seen.Snackbar(UiText.of(R.string.snackbar_photo_deleted), names = null, undo = true),
        ),
        Row(
            "swipe left with Delete, confirmed in the dialog",
            copySettings.copy(directDeleteConfirmEnabled = true),
            { onSwipeLeft(); confirmDelete(dontAskAgain = false) },
            Seen.Snackbar(UiText.of(R.string.snackbar_photo_deleted), names = null, undo = true),
        ),
        Row(
            "swipe left with Copy",
            copySettings.copy(swipeLeftVerb = SwipeAction.COPY, leftSwipeUri = rejectsTree),
            { onSwipeLeft() },
            Seen.Snackbar(UiText.of(R.string.snackbar_copied, UiText.raw(rejectsName)), rejectsName),
        ),
        Row(
            "swipe left with Move",
            copySettings.copy(swipeLeftVerb = SwipeAction.MOVE, leftSwipeUri = rejectsTree),
            { onSwipeLeft() },
            Seen.Snackbar(UiText.of(R.string.snackbar_moved, UiText.raw(rejectsName)), rejectsName),
        ),
        Row(
            "Revert (bar or snackbar Undo) after a delete",
            copySettings,
            { requestDelete(); clearFeedback(); revertDelete() },
            Seen.OnScreen("the restored photo is the one on screen") { s, acted ->
                s.images.getOrNull(s.currentIndex)?.uri == acted.uri
            },
        ),
        Row(
            "Share the current photo",
            copySettings,
            { assertNotNull("Share opens the system share sheet", buildCurrentShareIntent()) },
            Seen.OnScreen("the system share sheet (no snackbar)") { _, _ -> true },
        ),
    )

    private fun viewModelFor(settings: SourceSettings): PhoneModeViewModel {
        every { settingsRepository.sourceSettingsFlow(any()) } answers {
            flowOf(settings)
        }
        every { imageRepository.discoverImages(any(), any(), any()) } returns flowOf(photos)
        val viewModel = PhoneModeViewModel(
            imageRepository = imageRepository,
            settingsRepository = settingsRepository,
            appScope = CoroutineScope(testDispatcher),
        )
        viewModel.selectSourceFolder(Uri.parse(phoneTree))
        return viewModel
    }

    private fun assertSnackbar(where: String, expected: Seen.Snackbar, feedback: ActionFeedback?) {
        assertNotNull("$where: no feedback at all", feedback)
        feedback!!
        assertEquals("$where: the message names a different outcome", expected.message, feedback.message)
        assertEquals("$where: Undo offered", expected.undo, feedback.offersUndoDelete)
        val en = feedback.message.en()
        val de = feedback.message.de()
        for ((lang, text) in listOf("en" to en, "de" to de)) {
            assertTrue("$where [$lang]: blank message", text.isNotBlank())
            expected.names?.let { assertTrue("$where [$lang]: '$text' does not name '$it'", text.contains(it)) }
        }
        // The German text is a translation, not the English sentence; a bare folder name is data.
        assertNotEquals("$where: German shows the English text '$en'", en, de)
    }

    @Test
    @Requirement("REQ-TOK-GESTURE.04", "REQ-TOK-ARCH.16", "REQ-TOK-FEATURE.18", "REQ-TOK-UX.01")
    fun everyFeedActionShowsWhatHappened() = runTest {
        val failures = mutableListOf<String>()
        for (row in feedRows) {
            val viewModel = viewModelFor(row.settings)
            advanceUntilIdle()
            val before = viewModel.uiState.value
            assertTrue("${row.action}: the feed did not load", before.images.isNotEmpty())
            val acted = before.images[before.currentIndex]
            viewModel.clearFeedback()
            row.perform(viewModel)
            advanceUntilIdle()
            val after = viewModel.uiState.value
            try {
                when (val seen = row.seen) {
                    is Seen.Snackbar -> assertSnackbar(row.action, seen, after.lastActionFeedback)
                    is Seen.OnScreen -> {
                        assertTrue("${row.action}: expected ${seen.what}", seen.shows(after, acted))
                        // Nothing is confirmed twice (REQ-TOK-UX.01), and nothing failed silently.
                        assertEquals("${row.action}: unexpected snackbar", null, after.lastActionFeedback)
                    }
                }
            } catch (e: AssertionError) {
                failures += e.message.orEmpty()
            }
        }
        assertTrue("actions without the right feedback:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /** Distinct outcomes read differently: a copy never says what a move says, in either language. */
    @Test
    @Requirement("REQ-TOK-ARCH.16")
    fun copyMoveAndDeleteMessagesAreDistinctInBothLanguages() {
        val messages = listOf(
            UiText.of(R.string.snackbar_copied, UiText.raw(selectionName)),
            UiText.of(R.string.snackbar_moved, UiText.raw(selectionName)),
            UiText.of(R.string.snackbar_photo_deleted),
            UiText.of(R.string.snackbar_favourite_removed),
        )
        for (lang in listOf("en", "de")) {
            val texts = messages.map { if (lang == "en") it.en() else it.de() }
            assertEquals("[$lang] two outcomes share a message: $texts", texts.size, texts.toSet().size)
        }
    }

    /**
     * F-TOK-018: the delete flow told three stories (the dialog "no trash", then "moved to trash",
     * then Undo). The snackbar after a delete must not claim a bin the dialog just denied.
     */
    @Test
    @Requirement("REQ-TOK-GESTURE.03")
    fun theDeleteSnackbarDoesNotClaimATrash() {
        val dialog = UiText.of(R.string.dialog_body_no_trash)
        val snackbar = UiText.of(R.string.snackbar_photo_deleted)
        for ((lang, pair) in listOf("en" to (dialog.en() to snackbar.en()), "de" to (dialog.de() to snackbar.de()))) {
            val (dialogText, snackText) = pair
            val trashWords = listOf("trash", "bin", "papierkorb")
            assertTrue("[$lang] the dialog '$dialogText' is the one that speaks of a trash", trashWords.any { dialogText.lowercase().contains(it) })
            assertTrue("[$lang] the snackbar '$snackText' claims a trash", trashWords.none { snackText.lowercase().contains(it) })
        }
    }

    /** The Selection viewer: a batch copy names the count and the folder; Share opens the sheet. */
    @Test
    @Requirement("REQ-TOK-FEATURE.17")
    fun everySelectionViewerActionShowsWhatHappened() = runTest {
        val repository: ImageRepository = mockk(relaxed = true) {
            coEvery { listSelectionImages(any(), any()) } returns SelectionListing.Available(emptyList(), "Selection")
            coEvery { copyImageDetailed(any(), any(), any(), any()) } coAnswers {
                FileOperationResult.success(firstArg<Uri>().toString(), "d")
            }
        }
        val viewModel = SelectionViewerViewModel(repository, CoroutineScope(testDispatcher))
        val favs = listOf(image("a.jpg", 30), image("b.jpg", 20))
        viewModel.openWithFavourites(
            targetFolderUri = phoneTree,
            sourceImages = favs,
            favourites = FavouritesSnapshot(favs.map { it.uri }.toSet()),
        )
        advanceUntilIdle()
        assertTrue("the viewer opened", viewModel.uiState.value.isOpen)

        viewModel.copyFavouritesToFolder(Uri.parse(rejectsTree), allSourceImages = favs)
        advanceUntilIdle()
        val copied = viewModel.uiState.value.feedback
        assertNotNull("batch copy: no feedback", copied)
        assertEquals(UiText.of(R.string.snackbar_favourites_copied, 2, "Favourites"), copied!!.message)
        for (text in listOf(copied.message.en(), copied.message.de())) {
            assertTrue("'$text' names the count", text.contains("2"))
            assertTrue("'$text' names the folder", text.contains("Favourites"))
        }
        assertNotEquals(copied.message.en(), copied.message.de())

        viewModel.clearFeedback()
        assertNotNull("Share opens the system share sheet", viewModel.buildBatchShareIntent(favs))
    }

    /**
     * F-TOK-086 (fixed): when the file-type filter hides every photo of a folder, the landing does
     * not say the folder has no photos (backlog 2026-11-06); it says "N hidden by filter".
     */
    @Test
    @Requirement("REQ-TOK-FEATURE.04")
    fun aFilterThatHidesEveryPhotoIsNotReportedAsAnEmptyFolder() = runTest {
        val jpegsOnly = copySettings.copy(fileTypeFilter = FileTypeFilter.RAW)
        val viewModel = viewModelFor(jpegsOnly)
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertTrue("the folder has photos", state.allImages.isNotEmpty())
        assertTrue("the RAW filter hides every JPEG", state.images.isEmpty())
        val message = state.emptyFolderMessage
        assertNotNull("an empty result says something", message)
        val plainEmpty = UiText.of(R.string.empty_folder, state.sourceFolderName)
        assertNotEquals("the landing claims the folder is empty", plainEmpty, message)
        for (text in listOf(message!!.en(), message.de())) {
            assertTrue("'$text' does not name the hidden count", text.contains(state.allImages.size.toString()))
        }
        assertNotEquals("EN and DE differ", message.en(), message.de())
        assertEquals("the landing names the filter", FileTypeFilter.RAW, state.filterHidingEveryPhoto)
    }
}
