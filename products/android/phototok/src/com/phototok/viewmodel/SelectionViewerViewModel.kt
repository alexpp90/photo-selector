package com.phototok.viewmodel

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phototok.R
import com.phototok.data.model.ImageItem
import com.phototok.data.repository.FavouritesRepository
import com.phototok.domain.FavouritesSnapshot
import com.phototok.data.repository.ImageRepository
import com.phototok.data.source.SelectionListing
import com.phototok.domain.PhotoFolders
import com.phototok.domain.ShareIntentBuilder
import com.phototok.domain.SwipeRightVerb
import com.phototok.domain.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Name of the folder favourites are copied into (a folder name on disk, not wording). */
private const val FAVOURITES_FOLDER = "Favourites"

enum class SelectionViewerTab {
    FAVOURITES,
    SELECTION,
}

/**
 * Which list the Selection viewer opens on and whether it offers a choice at all (`REQ-TOK-FEATURE.17`).
 * Pure, so the decisions are unit-testable.
 */
object SelectionViewerLogic {
    /**
     * The tab the user most likely wants: the one their swipe right fills. A favouriting swipe
     * feeds Favourites; a copying or moving swipe feeds the Selection folder.
     */
    fun preferredTab(verb: SwipeRightVerb): SelectionViewerTab = when (verb) {
        SwipeRightVerb.FAVOURITE -> SelectionViewerTab.FAVOURITES
        SwipeRightVerb.COPY, SwipeRightVerb.MOVE -> SelectionViewerTab.SELECTION
    }

    /**
     * The tab to open on: [preferred] unless its list is empty, then the other one. When both are
     * empty it stays on [preferred] (the caller does not open the viewer in that case).
     */
    fun initialTab(preferred: SelectionViewerTab, favouriteCount: Int, selectionCount: Int): SelectionViewerTab {
        val preferredCount = if (preferred == SelectionViewerTab.FAVOURITES) favouriteCount else selectionCount
        val otherCount = if (preferred == SelectionViewerTab.FAVOURITES) selectionCount else favouriteCount
        if (preferredCount > 0 || otherCount == 0) return preferred
        return if (preferred == SelectionViewerTab.FAVOURITES) {
            SelectionViewerTab.SELECTION
        } else {
            SelectionViewerTab.FAVOURITES
        }
    }

    /** Tabs are offered only when both lists have photos; with one list there is nothing to switch to. */
    fun showTabs(favouriteCount: Int, selectionCount: Int): Boolean = favouriteCount > 0 && selectionCount > 0
}

data class SelectionViewerUiState(
    /** Whether the read-only selection viewer is currently shown. */
    val isOpen: Boolean = false,
    val activeTab: SelectionViewerTab = SelectionViewerTab.SELECTION,
    val images: List<ImageItem> = emptyList(),
    val selectionImages: List<ImageItem> = emptyList(),
    val favouriteImages: List<ImageItem> = emptyList(),
    val folderName: String = "",
    /** Name of the Selection tab's folder (the chip label); `folderName` follows the active tab. */
    val selectionFolderName: String = "",
    val currentIndex: Int = 0,
    val feedback: ActionFeedback? = null,
) {
    /** Whether the top bar shows two switchable tabs (both lists non-empty) or a single title. */
    val showTabs: Boolean
        get() = SelectionViewerLogic.showTabs(favouriteImages.size, selectionImages.size)
}

/**
 * Read-only viewer for the Selection folder and in-place Favourites (`U-13`, `U-17`).
 */
@HiltViewModel
class SelectionViewerViewModel @Inject constructor(
    private val imageRepository: ImageRepository,
    private val favouritesRepository: FavouritesRepository,
) : ViewModel() {

    constructor(
        imageRepository: ImageRepository,
    ) : this(imageRepository, FavouritesRepository())

    private val _uiState = MutableStateFlow(SelectionViewerUiState())
    val uiState: StateFlow<SelectionViewerUiState> = _uiState.asStateFlow()

    /**
     * Open the selection folder inside [targetFolderUri] (the collection target,
     * falling back to the source folder). Local folders only.
     */
    fun open(targetFolderUri: String?) {
        if (targetFolderUri == null) {
            _uiState.update {
                it.copy(feedback = ActionFeedback(UiText.of(R.string.snackbar_selection_none), isError = false))
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isOpen = true,
                    activeTab = SelectionViewerTab.SELECTION,
                    currentIndex = 0,
                )
            }
            try {
                when (val listing = imageRepository.listSelectionImages(Uri.parse(targetFolderUri))) {
                    is SelectionListing.Available -> _uiState.update {
                        it.copy(
                            images = listing.images,
                            selectionImages = listing.images,
                            folderName = listing.folderName,
                            selectionFolderName = listing.folderName,
                        )
                    }
                    SelectionListing.NotSupported -> _uiState.update {
                        it.copy(
                            isOpen = false,
                            feedback = ActionFeedback(UiText.of(R.string.error_selection_local), isError = true),
                        )
                    }
                    SelectionListing.Missing -> _uiState.update {
                        it.copy(
                            isOpen = false,
                            feedback = ActionFeedback(UiText.of(R.string.snackbar_selection_empty), isError = false),
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isOpen = false,
                        feedback = ActionFeedback(UiText.of(R.string.error_selection_open), isError = true),
                    )
                }
            }
        }
    }

    /**
     * Open the viewer with both in-place [sourceImages] filtered by [favourites] and
     * the Selection subfolder inside [targetFolderUri] (`U-17`). It opens on [preferredTab]
     * (see [SelectionViewerLogic.preferredTab]) unless that list is empty.
     */
    fun openWithFavourites(
        targetFolderUri: String?,
        sourceImages: List<ImageItem>,
        favourites: FavouritesSnapshot = favouritesRepository.snapshot.value,
        preferredTab: SelectionViewerTab = SelectionViewerTab.FAVOURITES,
    ) {
        val favImages = sourceImages.filter { favourites.isFavourite(it.uri) }
        if (targetFolderUri == null && favImages.isEmpty()) {
            _uiState.update {
                it.copy(feedback = ActionFeedback(UiText.of(R.string.snackbar_nothing_yet), isError = false))
            }
            return
        }

        viewModelScope.launch {
            var selImages: List<ImageItem> = emptyList()
            var selFolderName = PhotoFolders.SELECTION
            if (targetFolderUri != null) {
                try {
                    when (val listing = imageRepository.listSelectionImages(Uri.parse(targetFolderUri))) {
                        is SelectionListing.Available -> {
                            selImages = listing.images
                            selFolderName = listing.folderName
                        }
                        else -> Unit
                    }
                } catch (_: Exception) {
                }
            }

            if (favImages.isEmpty() && selImages.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isOpen = false,
                        feedback = ActionFeedback(UiText.of(R.string.snackbar_nothing_yet), isError = false),
                    )
                }
                return@launch
            }

            val initialTab = SelectionViewerLogic.initialTab(preferredTab, favImages.size, selImages.size)
            val activeList = if (initialTab == SelectionViewerTab.FAVOURITES) favImages else selImages

            _uiState.update {
                it.copy(
                    isOpen = true,
                    activeTab = initialTab,
                    images = activeList,
                    selectionImages = selImages,
                    favouriteImages = favImages,
                    folderName = if (initialTab == SelectionViewerTab.FAVOURITES) FAVOURITES_FOLDER else selFolderName,
                    selectionFolderName = selFolderName,
                    currentIndex = 0,
                )
            }
        }
    }

    fun selectTab(tab: SelectionViewerTab) {
        _uiState.update { state ->
            val list = if (tab == SelectionViewerTab.FAVOURITES) {
                state.favouriteImages
            } else {
                state.selectionImages
            }
            state.copy(
                activeTab = tab,
                images = list,
                folderName = if (tab == SelectionViewerTab.FAVOURITES) {
                    FAVOURITES_FOLDER
                } else {
                    state.selectionFolderName.ifEmpty { PhotoFolders.SELECTION }
                },
                currentIndex = 0,
            )
        }
    }

    /**
     * Copy all favourited photos to a user-chosen destination folder (`U-17`).
     */
    fun copyFavouritesToFolder(destFolderUri: Uri, subfolderName: String = FAVOURITES_FOLDER) {
        val favs = _uiState.value.favouriteImages.ifEmpty { _uiState.value.images }
        if (favs.isEmpty()) return
        viewModelScope.launch {
            var copied = 0
            for (img in favs) {
                val ok = try {
                    imageRepository.copyImage(
                        sourceUri = Uri.parse(img.uri),
                        destFolderUri = destFolderUri,
                        sorting = false,
                        subfolderName = subfolderName,
                    )
                } catch (_: Exception) {
                    false
                }
                if (ok) copied++
            }
            val msg = if (copied == favs.size) {
                UiText.of(R.string.snackbar_favourites_copied, copied, subfolderName)
            } else {
                UiText.of(R.string.error_favourites_partial, copied, favs.size)
            }
            _uiState.update {
                it.copy(feedback = ActionFeedback(msg, isError = copied < favs.size))
            }
        }
    }

    /**
     * Build a batch `ACTION_SEND_MULTIPLE` chooser for the currently active tab's photos (`U-13`).
     */
    fun buildBatchShareIntent(allSourceImages: List<ImageItem> = emptyList()): Intent? {
        val items = _uiState.value.images
        if (items.isEmpty()) return null
        val pool = if (allSourceImages.isNotEmpty()) allSourceImages else items
        val resolution = ShareIntentBuilder.resolveShareBatch(
            items = items,
            allImages = pool,
            moveRelatedFiles = true,
            fileTypeFilter = com.phototok.domain.FileTypeFilter.ONE_PER_SHOT,
        )
        if (resolution.truncatedCount > 0) {
            val totalRequested = resolution.targets.size + resolution.truncatedCount
            _uiState.update {
                it.copy(
                    feedback = ActionFeedback(
                        UiText.of(R.string.snackbar_share_capped, ShareIntentBuilder.MAX_SHARE_ITEMS, totalRequested),
                        isError = false,
                    )
                )
            }
        }
        return ShareIntentBuilder.buildBatchShareChooser(resolution.targets)
    }

    fun close() {
        _uiState.update {
            it.copy(isOpen = false, images = emptyList(), currentIndex = 0)
        }
    }

    fun navigateTo(index: Int) {
        if (index in _uiState.value.images.indices) {
            _uiState.update { it.copy(currentIndex = index) }
        }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(feedback = null) }
    }
}
