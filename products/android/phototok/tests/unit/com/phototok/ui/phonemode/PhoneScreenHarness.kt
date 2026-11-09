package com.phototok.ui.phonemode

import android.net.Uri
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.photoselector.core.model.ExifData
import com.phototok.data.model.ImageItem
import com.phototok.data.model.PhoneSettings
import com.phototok.data.model.SourceSettings
import com.phototok.data.repository.ImageRepository
import com.phototok.data.repository.SettingsRepository
import com.phototok.data.source.FileOperationResult
import com.phototok.data.source.SelectionListing
import com.phototok.domain.FolderScanInfo
import com.phototok.domain.SwipeAction
import com.phototok.domain.SwipeRightVerb
import com.phototok.ui.theme.PhotoTokTheme
import com.phototok.viewmodel.PhoneModeViewModel
import com.phototok.viewmodel.SelectionViewerViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * The real [PhoneModeScreen] with real view models over mocked repositories, for screen-level
 * Robolectric checks (System Back, overlays). Everything runs on the main looper: the repositories
 * answer at once, so `rule.waitForIdle()` settles the screen.
 *
 * Settings cannot be opened through the screen here: `SettingsScreen` gets its view model from
 * Hilt, which a plain test activity does not provide; tests drive [SettingsSheet] directly instead.
 */
class PhoneScreenHarness(
    private val rule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>,
) {
    val phoneTree = "content://com.android.externalstorage.documents/tree/primary%3ADCIM"
    val sdTree = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3ADCIM"

    var phoneSettings = PhoneSettings(proMode = true)
    var sourceSettings = SourceSettings(
        swipeRightVerb = SwipeRightVerb.COPY,
        swipeLeftVerb = SwipeAction.DELETE,
        directDeleteConfirmEnabled = true,
    )

    /** 0 = never seen: the first-run tutorial opens with the feed. */
    var gestureTutorialTs: Long = System.currentTimeMillis()

    val settingsRepository: SettingsRepository = mockk(relaxed = true) {
        every { phoneSettings } answers { MutableStateFlow(this@PhoneScreenHarness.phoneSettings) }
        every { sourceSettingsFlow(any()) } answers { flowOf(this@PhoneScreenHarness.sourceSettings) }
        every { phoneCollectionUri } returns MutableStateFlow<String?>(null)
        every { phoneLeftSwipeUri } returns MutableStateFlow<String?>(null)
        every { lastFolderUri } returns MutableStateFlow<String?>(null)
        every { sortingEnabled } returns MutableStateFlow(true)
        every { selectionFolderName } returns MutableStateFlow("Selection")
        every { phoneGestureTutorialTs } answers { MutableStateFlow(gestureTutorialTs) }
        every { lastAppUsedTs } returns MutableStateFlow(System.currentTimeMillis())
        coEvery { getFolderScanInfo(any()) } returns FolderScanInfo(0L, 0)
    }

    val imageRepository: ImageRepository = mockk(relaxed = true) {
        coEvery { prepareSourceFolder(any()) } returns "DCIM"
        coEvery { resolveFolderName(any()) } returns null
        coEvery { getExifData(any()) } returns EXIF
        coEvery { getImageDimensions(any()) } returns Pair(4000, 3000)
        coEvery { listSelectionImages(any(), any()) } returns SelectionListing.Available(emptyList(), "Selection")
        coEvery { copyImageDetailed(any(), any(), any(), any()) } coAnswers {
            FileOperationResult.success(firstArg<Uri>().toString(), null)
        }
        coEvery { moveImageDetailed(any(), any(), any(), any()) } coAnswers {
            FileOperationResult.success(firstArg<Uri>().toString(), null)
        }
    }

    lateinit var viewModel: PhoneModeViewModel
        private set
    lateinit var selectionViewModel: SelectionViewerViewModel
        private set

    /** Back presses that no layer of the app consumed: on a device the app would close. */
    var backPressesThatLeftTheApp = 0
        private set

    /** Composes the screen (and registers the "app left" sentinel below every app handler). */
    fun launch(wrap: @Composable (@Composable () -> Unit) -> Unit = { it() }) {
        prepare()
        rule.setContent {
            PhotoTokTheme {
                wrap { Screen() }
            }
        }
        rule.waitForIdle()
    }

    /** The screen under test, for a host that sets the content itself (the text-load matrix). */
    @Composable
    fun Screen() {
        PhoneModeScreen(viewModel = viewModel, selectionViewModel = selectionViewModel)
    }

    /** Creates the view models and the "app left" sentinel, without composing anything. */
    fun prepare() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        viewModel = PhoneModeViewModel(imageRepository, settingsRepository, scope)
        selectionViewModel = SelectionViewerViewModel(imageRepository, scope)
        rule.runOnUiThread {
            // Registered first, so every handler the screen adds later takes precedence over it.
            rule.activity.onBackPressedDispatcher.addCallback(
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        backPressesThatLeftTheApp++
                    }
                },
            )
        }
    }

    /** Opens [folder] with [images] and waits until the feed shows them. */
    fun openFolder(images: List<ImageItem>, folder: String = phoneTree) {
        every { imageRepository.discoverImages(any(), any(), any()) } returns flowOf(images)
        rule.runOnUiThread { viewModel.selectSourceFolder(Uri.parse(folder)) }
        rule.waitUntil(5_000) { viewModel.uiState.value.images.isNotEmpty() }
        rule.waitForIdle()
    }

    /**
     * System Back as the platform delivers it: a KEYCODE_BACK down/up pair to the topmost window
     * (a dialog, a sheet or a popup when one is shown, otherwise the activity).
     */
    fun pressSystemBack() {
        rule.runOnUiThread {
            val root = topWindowRoot()
            root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        rule.waitForIdle()
        // Let a sheet or a dialog finish its exit animation.
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    /** The number of windows the app shows (1 = the activity alone). */
    fun windowCount(): Int = attachedRoots().size

    private fun attachedRoots(): List<View> {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val field = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val views = ArrayList(field.get(global) as List<View>)
        return views.filter { it.isAttachedToWindow && it.windowVisibility == View.VISIBLE }
    }

    private fun topWindowRoot(): View = attachedRoots().last()

    companion object {
        val EXIF = ExifData(
            shutterSpeed = 1.0 / 250,
            aperture = 2.8,
            focalLength = 50.0,
            iso = 400,
            lens = "PSTLab 50mm F1.8 DG HSM Art", // ui-text: ignore test fixture
            dateTimeOriginal = "2026:10:24 14:05:09",
        )

        fun photo(name: String, modified: Long, mime: String = "image/jpeg") = ImageItem(
            uri = "content://photos/$name",
            fileName = name,
            fileSize = 1,
            lastModified = modified,
            mimeType = mime,
            imageWidth = 4000,
            imageHeight = 3000,
            exifData = EXIF,
        )

        val PHOTOS = listOf(
            photo("IMG_20261024_140509_000.JPG", 30), // ui-text: ignore test fixture
            photo("IMG_20261024_140510_000.JPG", 20), // ui-text: ignore test fixture
            photo("IMG_20261024_140511_000.JPG", 10), // ui-text: ignore test fixture
        )
    }
}
