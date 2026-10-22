package com.photoselectortoolbox.ui.inspect

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Grid3x3
import androidx.compose.material.icons.filled.Grid4x4
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.photoselectortoolbox.domain.format.ImageFormats
import com.photoselectortoolbox.data.cache.LessonProgressEntity
import com.photoselectortoolbox.data.cache.PracticeGoalEntity
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.analysis.PhotoCoachEngine
import com.photoselectortoolbox.domain.analysis.PhotoCoachEngineImpl
import com.photoselectortoolbox.domain.format.ExifFormatter
import com.photoselectortoolbox.domain.learn.CardGenerator
import com.photoselectortoolbox.domain.learn.CropAspectRatio
import com.photoselectortoolbox.domain.learn.CropSuggester
import com.photoselectortoolbox.domain.learn.LearnCard
import com.photoselectortoolbox.domain.learn.LessonCatalog
import com.photoselectortoolbox.domain.learn.MicroLesson
import com.photoselectortoolbox.domain.learn.PracticeAndInsightsEngine
import com.photoselectortoolbox.domain.learn.SceneComparator
import com.photoselectortoolbox.domain.learn.SubjectBoundingBox
import com.photoselectortoolbox.domain.learn.SuggestedCrop
import com.photoselectortoolbox.domain.learn.TechniqueFactDetector
import com.photoselectortoolbox.domain.model.CoachingTip
import com.photoselectortoolbox.domain.model.CompositionGuideType
import com.photoselectortoolbox.domain.model.CompositionResult
import com.photoselectortoolbox.domain.model.PhotoCoachReport
import com.photoselectortoolbox.domain.model.RatingFilter
import com.photoselectortoolbox.domain.model.SpiralOrientation
import com.photoselectortoolbox.domain.model.TipSeverity
import com.photoselectortoolbox.ui.components.CompositionGeometry
import com.photoselectortoolbox.ui.components.CompositionOverlay
import com.photoselectortoolbox.ui.components.EmptyStateCard
import com.photoselectortoolbox.ui.components.StarRatingBar
import com.photoselectortoolbox.ui.navigation.Screen
import com.photoselectortoolbox.ui.selector.RatingFilterSelector
import com.photoselectortoolbox.ui.components.MinTouchTarget
import com.photoselectortoolbox.ui.components.TouchTarget
import androidx.compose.ui.semantics.Role
import com.photoselectortoolbox.ui.theme.BrandAccent
import com.photoselectortoolbox.ui.theme.BrandAccentDeep
import com.photoselectortoolbox.ui.theme.StudioPanel
import com.photoselectortoolbox.ui.theme.ScoreBad
import com.photoselectortoolbox.ui.theme.ScoreGood
import com.photoselectortoolbox.ui.theme.ScoreWarn
import com.photoselectortoolbox.ui.theme.Zinc200
import com.photoselectortoolbox.ui.theme.Zinc400
import com.photoselectortoolbox.ui.theme.Zinc500
import com.photoselectortoolbox.ui.theme.Zinc700
import com.photoselectortoolbox.ui.theme.Zinc800
import com.photoselectortoolbox.ui.theme.Zinc900
import com.photoselectortoolbox.ui.theme.Zinc950
import com.photoselectortoolbox.viewmodel.SelectorViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LearnTab(val label: String, val tag: String) {
    EXPLORE("Explore", "learn_tab_explore"),
    LESSONS("Lessons", "learn_tab_lessons"),
    COMPARE("Compare", "learn_tab_compare"),
    PRACTICE("Practice", "learn_tab_practice"),
}

/**
 * Cycles the 4-way focal eye orientation for the Fibonacci Golden Spiral in order:
 * TOP_LEFT -> TOP_RIGHT -> BOTTOM_RIGHT -> BOTTOM_LEFT -> TOP_LEFT.
 */
fun cycleSpiralOrientation(current: SpiralOrientation): SpiralOrientation = when (current) {
    SpiralOrientation.TOP_LEFT -> SpiralOrientation.TOP_RIGHT
    SpiralOrientation.TOP_RIGHT -> SpiralOrientation.BOTTOM_RIGHT
    SpiralOrientation.BOTTOM_RIGHT -> SpiralOrientation.BOTTOM_LEFT
    SpiralOrientation.BOTTOM_LEFT -> SpiralOrientation.TOP_LEFT
}

/**
 * Pure hardware keyboard hotkey handler for the Learn workstation (`U-21`).
 *
 * Mappings:
 * - `L`, `I`, `Escape`: Return to Culling view (`onNavigateToSelector`).
 * - `Left Arrow`: Navigate to previous image.
 * - `Right Arrow`: Navigate to next image.
 * - `0`–`5` (and NumPad equivalents): Set rating (0 = clear/unrated, 1..5 = stars).
 * - `X`: Set reject rating (-1).
 */
internal fun handleInspectKey(
    key: Key,
    onNavigateToSelector: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSetRating: (Int) -> Unit,
): Boolean {
    return when (key) {
        Key.L, Key.I, Key.Escape -> {
            onNavigateToSelector()
            true
        }
        Key.DirectionLeft -> {
            onPrevious()
            true
        }
        Key.DirectionRight -> {
            onNext()
            true
        }
        Key.Zero, Key.NumPad0 -> {
            onSetRating(0)
            true
        }
        Key.One, Key.NumPad1 -> {
            onSetRating(1)
            true
        }
        Key.Two, Key.NumPad2 -> {
            onSetRating(2)
            true
        }
        Key.Three, Key.NumPad3 -> {
            onSetRating(3)
            true
        }
        Key.Four, Key.NumPad4 -> {
            onSetRating(4)
            true
        }
        Key.Five, Key.NumPad5 -> {
            onSetRating(5)
            true
        }
        Key.X -> {
            onSetRating(-1)
            true
        }
        else -> false
    }
}

@Composable
fun InspectScreen(
    windowSizeClass: WindowSizeClass,
    @Suppress("UNUSED_PARAMETER") currentRoute: String? = null,
    onNavigate: (Screen) -> Unit = {},
    viewModel: SelectorViewModel = hiltViewModel(),
    photoCoachEngine: PhotoCoachEngine = remember { PhotoCoachEngineImpl() },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentImage = uiState.currentImage

    InspectScreenContent(
        currentImage = currentImage,
        images = uiState.filteredImages,
        currentIndex = uiState.filteredPosition.let { if (it > 0) it - 1 else uiState.currentIndex },
        windowSizeClass = windowSizeClass,
        onNavigateToSelector = { onNavigate(Screen.PhotoSelector) },
        onSelectImage = { filteredIdx ->
            val target = uiState.filteredImages.getOrNull(filteredIdx)
            if (target != null) {
                val globalIdx = uiState.images.indexOfFirst { it.uri == target.uri }
                if (globalIdx >= 0) {
                    viewModel.navigateToImage(globalIdx)
                }
            }
        },
        onPrevious = { viewModel.navigatePrevious() },
        onNext = { viewModel.navigateNext() },
        onSetRating = { item, rating -> viewModel.setRating(item, rating) },
        ratingFilter = uiState.ratingFilter,
        onRatingFilterSelected = { filter -> viewModel.setRatingFilter(filter) },
        photoCoachEngine = photoCoachEngine,
    )
}

@Composable
internal fun InspectScreenContent(
    currentImage: ImageItem?,
    images: List<ImageItem>,
    currentIndex: Int,
    windowSizeClass: WindowSizeClass,
    onNavigateToSelector: () -> Unit,
    onSelectImage: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSetRating: (ImageItem, Int) -> Unit,
    modifier: Modifier = Modifier,
    ratingFilter: RatingFilter = RatingFilter.All,
    onRatingFilterSelected: (RatingFilter) -> Unit = {},
    photoCoachEngine: PhotoCoachEngine = remember { PhotoCoachEngineImpl() },
    compositionResult: CompositionResult? = null,
    factsByUri: Map<String, List<TechniqueFactEntity>> = emptyMap(),
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    var activeGuides by remember {
        mutableStateOf<Set<CompositionGuideType>>(setOf(CompositionGuideType.RuleOfThirds))
    }
    var spiralOrientation by remember { mutableStateOf(SpiralOrientation.TOP_LEFT) }
    var selectedTab by remember { mutableStateOf(LearnTab.EXPLORE) }
    var selectedLessonId by remember { mutableStateOf<String?>(null) }
    var guessFirstEnabled by remember { mutableStateOf(false) }
    var guessRevealedForUri by remember { mutableStateOf<String?>(null) }
    var whatIfCropActive by remember { mutableStateOf(false) }
    var selectedCropAspect by remember { mutableStateOf<CropAspectRatio?>(null) }
    var completedLessons by remember { mutableStateOf<Map<String, LessonProgressEntity>>(emptyMap()) }
    var activePracticeGoal by remember { mutableStateOf<PracticeGoalEntity?>(null) }

    val isCompact = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Zinc950)
            .testTag("inspect_screen")
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                handleInspectKey(
                    key = event.key,
                    onNavigateToSelector = onNavigateToSelector,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onSetRating = { rating -> currentImage?.let { onSetRating(it, rating) } },
                )
            },
    ) {
        if (images.isEmpty() || currentImage == null) {
            EmptyStateCard(
                icon = Icons.Default.CenterFocusStrong,
                title = "No Photos to Inspect",
                description = "Choose a shoot folder in Culling to evaluate composition, exposure technique, and coaching guidance.",
                actionLabel = "Return to Culling",
                onAction = onNavigateToSelector,
                modifier = Modifier
                    .align(Alignment.Center)
                    .testTag("inspect_empty_state"),
            )
            return@Box
        }

        val isRaw = remember(currentImage) {
            currentImage.hasRawCompanion || ImageFormats.isRaw(currentImage.fileName)
        }

        val coachReport = remember(currentImage, isRaw) {
            photoCoachEngine.evaluate(
                exif = currentImage.exifData,
                scanResult = currentImage.scanResult,
                isRawOrHasRawCompanion = isRaw,
                imageWidth = currentImage.imageWidth,
                imageHeight = currentImage.imageHeight,
            )
        }

        val detector = remember { TechniqueFactDetector() }
        val currentFacts = remember(currentImage, factsByUri) {
            val cached = factsByUri[currentImage.uri]
            if (!cached.isNullOrEmpty()) {
                cached
            } else {
                detector.detectAll(
                    uri = currentImage.uri,
                    fileSize = currentImage.fileSize,
                    lastModified = currentImage.lastModified,
                    exif = currentImage.exifData,
                    width = currentImage.imageWidth ?: 0,
                    height = currentImage.imageHeight ?: 0,
                    sharpnessScore = currentImage.scanResult?.sharpnessScore,
                    noiseLevel = currentImage.scanResult?.noiseLevel,
                    highlightClipping = currentImage.scanResult?.highlightClipping,
                    shadowClipping = currentImage.scanResult?.shadowClipping,
                    isRawOrHasRawCompanion = isRaw,
                )
            }
        }

        val cardResult = remember(currentImage, currentFacts, isRaw) {
            CardGenerator.generateFromEntities(
                entities = currentFacts,
                exif = currentImage.exifData,
                scanResult = currentImage.scanResult,
                isRawOrHasRawCompanion = isRaw,
            )
        }

        val defaultSubjectBox = remember(currentImage) {
            SubjectBoundingBox(
                xMin = 0.36,
                yMin = 0.36,
                xMax = 0.64,
                yMax = 0.64,
                centroidX = 0.48,
                centroidY = 0.48,
                confidence = 0.70,
            )
        }

        val suggestedCrop = remember(currentImage, selectedCropAspect) {
            CropSuggester.suggestCrop(
                subjectBox = defaultSubjectBox,
                imageWidth = currentImage.imageWidth?.takeIf { it > 0 } ?: 1500,
                imageHeight = currentImage.imageHeight?.takeIf { it > 0 } ?: 1000,
                requestedAspect = selectedCropAspect,
            )
        }

        val onToggleGuideThirds = {
            activeGuides = if (activeGuides.any { it is CompositionGuideType.RuleOfThirds }) {
                activeGuides.filterNot { it is CompositionGuideType.RuleOfThirds }.toSet()
            } else {
                activeGuides + CompositionGuideType.RuleOfThirds
            }
        }
        val onToggleGuideGolden = {
            activeGuides = if (activeGuides.any { it is CompositionGuideType.GoldenRatio }) {
                activeGuides.filterNot { it is CompositionGuideType.GoldenRatio }.toSet()
            } else {
                activeGuides + CompositionGuideType.GoldenRatio
            }
        }
        val onToggleGuideSpiral = {
            activeGuides = if (activeGuides.any { it is CompositionGuideType.FibonacciSpiral }) {
                activeGuides.filterNot { it is CompositionGuideType.FibonacciSpiral }.toSet()
            } else {
                activeGuides + CompositionGuideType.FibonacciSpiral(spiralOrientation)
            }
        }
        val onFlipGuideSpiral = {
            val newOrientation = cycleSpiralOrientation(spiralOrientation)
            spiralOrientation = newOrientation
            if (activeGuides.any { it is CompositionGuideType.FibonacciSpiral }) {
                activeGuides = activeGuides.filterNot { it is CompositionGuideType.FibonacciSpiral }.toSet() +
                    CompositionGuideType.FibonacciSpiral(newOrientation)
            }
        }
        val onToggleGuideDiagonals = {
            activeGuides = if (activeGuides.any { it is CompositionGuideType.BaroqueSinister }) {
                activeGuides.filterNot { it is CompositionGuideType.BaroqueSinister }.toSet()
            } else {
                activeGuides + CompositionGuideType.BaroqueSinister
            }
        }
        val onToggleGuideHorizon = {
            activeGuides = if (activeGuides.any { it is CompositionGuideType.CrosshairHorizon }) {
                activeGuides.filterNot { it is CompositionGuideType.CrosshairHorizon }.toSet()
            } else {
                activeGuides + CompositionGuideType.CrosshairHorizon
            }
        }

        val sidePanelContent: @Composable () -> Unit = {
            LearnTabSelector(
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it },
            )

            when (selectedTab) {
                LearnTab.EXPLORE -> {
                    InspectRatingCard(
                        rating = currentImage.rating,
                        onRatingChanged = { rating -> onSetRating(currentImage, rating) },
                        ratingFilter = ratingFilter,
                        onRatingFilterSelected = onRatingFilterSelected,
                    )

                    ExploreCardsSection(
                        currentImage = currentImage,
                        cardResult = cardResult,
                        guessFirstEnabled = guessFirstEnabled,
                        isGuessRevealed = !guessFirstEnabled || guessRevealedForUri == currentImage.uri,
                        onToggleGuessFirst = {
                            guessFirstEnabled = !guessFirstEnabled
                            if (!guessFirstEnabled) guessRevealedForUri = currentImage.uri
                        },
                        onRevealGuess = { guessRevealedForUri = currentImage.uri },
                        onOpenLesson = { lessonId ->
                            selectedLessonId = lessonId
                            selectedTab = LearnTab.LESSONS
                        },
                        whatIfCropActive = whatIfCropActive,
                        onToggleWhatIfCrop = { whatIfCropActive = !whatIfCropActive },
                        selectedCropAspect = selectedCropAspect,
                        onSelectCropAspect = { selectedCropAspect = it },
                        suggestedCrop = suggestedCrop,
                    )

                    PhotoCoachCard(
                        currentImage = currentImage,
                        coachReport = coachReport,
                    )

                    ExifTechnicalReadoutCard(currentImage = currentImage)
                }

                LearnTab.LESSONS -> {
                    LessonsTabContent(
                        selectedLessonId = selectedLessonId,
                        onSelectLesson = { selectedLessonId = it },
                        factsByUri = factsByUri,
                        completedLessons = completedLessons,
                        onLessonCompleted = { progress ->
                            completedLessons = completedLessons + (progress.lessonId to progress)
                        },
                        onSetPracticeGoal = { lessonId ->
                            activePracticeGoal = PracticeAndInsightsEngine.createPracticeGoal(
                                lessonId = lessonId,
                                images = images,
                                factsByUri = factsByUri,
                            )
                            selectedTab = LearnTab.PRACTICE
                        },
                    )
                }

                LearnTab.COMPARE -> {
                    CompareTabContent(images = images)
                }

                LearnTab.PRACTICE -> {
                    PracticeTabContent(
                        images = images,
                        factsByUri = factsByUri,
                        activePracticeGoal = activePracticeGoal,
                        onSelectLessonForGoal = { lessonId ->
                            activePracticeGoal = PracticeAndInsightsEngine.createPracticeGoal(
                                lessonId = lessonId,
                                images = images,
                                factsByUri = factsByUri,
                            )
                        },
                        onOpenLesson = { lessonId ->
                            selectedLessonId = lessonId
                            selectedTab = LearnTab.LESSONS
                        },
                    )
                }
            }
        }

        if (isCompact) {
            Column(modifier = Modifier.fillMaxSize()) {
                InspectControlBar(
                    activeGuides = activeGuides,
                    spiralOrientation = spiralOrientation,
                    onToggleThirds = onToggleGuideThirds,
                    onToggleGoldenRatio = onToggleGuideGolden,
                    onToggleSpiral = onToggleGuideSpiral,
                    onFlipSpiral = onFlipGuideSpiral,
                    onToggleDiagonals = onToggleGuideDiagonals,
                    onToggleHorizon = onToggleGuideHorizon,
                    onNavigateToSelector = onNavigateToSelector,
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = currentImage.uri,
                        contentDescription = currentImage.fileName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    CompositionOverlay(
                        modifier = Modifier.fillMaxSize(),
                        imageAspectRatio = currentImage.aspectRatio ?: (3f / 2f),
                        activeGuides = activeGuides,
                        compositionResult = compositionResult,
                        showLeadingLines = false,
                        showMassDistribution = false,
                    )
                    if (whatIfCropActive && suggestedCrop != null) {
                        WhatIfCropOverlay(
                            modifier = Modifier.fillMaxSize(),
                            imageAspectRatio = currentImage.aspectRatio ?: (3f / 2f),
                            crop = suggestedCrop,
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(290.dp)
                        .background(StudioPanel)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    sidePanelContent()
                }

                InspectFilmstrip(
                    images = images,
                    currentIndex = currentIndex,
                    onSelectImage = onSelectImage,
                )
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    InspectControlBar(
                        activeGuides = activeGuides,
                        spiralOrientation = spiralOrientation,
                        onToggleThirds = onToggleGuideThirds,
                        onToggleGoldenRatio = onToggleGuideGolden,
                        onToggleSpiral = onToggleGuideSpiral,
                        onFlipSpiral = onFlipGuideSpiral,
                        onToggleDiagonals = onToggleGuideDiagonals,
                        onToggleHorizon = onToggleGuideHorizon,
                        onNavigateToSelector = onNavigateToSelector,
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = currentImage.uri,
                            contentDescription = currentImage.fileName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        CompositionOverlay(
                            modifier = Modifier.fillMaxSize(),
                            imageAspectRatio = currentImage.aspectRatio ?: (3f / 2f),
                            activeGuides = activeGuides,
                            compositionResult = compositionResult,
                            showLeadingLines = false,
                            showMassDistribution = false,
                        )
                        if (whatIfCropActive && suggestedCrop != null) {
                            WhatIfCropOverlay(
                                modifier = Modifier.fillMaxSize(),
                                imageAspectRatio = currentImage.aspectRatio ?: (3f / 2f),
                                crop = suggestedCrop,
                            )
                        }
                    }

                    InspectFilmstrip(
                        images = images,
                        currentIndex = currentIndex,
                        onSelectImage = onSelectImage,
                    )
                }

                Column(
                    modifier = Modifier
                        .width(400.dp)
                        .fillMaxHeight()
                        .background(StudioPanel)
                        .border(1.dp, Zinc800, RoundedCornerShape(0.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    sidePanelContent()
                }
            }
        }
    }
}

// ── Control Bar (U-20: dead Lines and Mass toggles removed) ───────────────────

@Composable
private fun InspectControlBar(
    activeGuides: Set<CompositionGuideType>,
    spiralOrientation: SpiralOrientation,
    onToggleThirds: () -> Unit,
    onToggleGoldenRatio: () -> Unit,
    onToggleSpiral: () -> Unit,
    onFlipSpiral: () -> Unit,
    onToggleDiagonals: () -> Unit,
    onToggleHorizon: () -> Unit,
    onNavigateToSelector: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isThirdsActive = activeGuides.any { it is CompositionGuideType.RuleOfThirds }
    val isGoldenActive = activeGuides.any { it is CompositionGuideType.GoldenRatio }
    val isSpiralActive = activeGuides.any { it is CompositionGuideType.FibonacciSpiral }
    val isDiagonalsActive = activeGuides.any { it is CompositionGuideType.BaroqueSinister }
    val isHorizonActive = activeGuides.any { it is CompositionGuideType.CrosshairHorizon }

    val spiralOrientationLabel = when (spiralOrientation) {
        SpiralOrientation.TOP_LEFT -> "TL"
        SpiralOrientation.TOP_RIGHT -> "TR"
        SpiralOrientation.BOTTOM_RIGHT -> "BR"
        SpiralOrientation.BOTTOM_LEFT -> "BL"
    }

    Surface(
        color = Zinc900,
        contentColor = Zinc200,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Zinc800, RoundedCornerShape(0.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(
                onClick = onNavigateToSelector,
                modifier = Modifier
                    .size(MinTouchTarget)
                    .testTag("back_to_cull_button")
                    .pointerHoverIcon(PointerIcon.Hand),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Return to Culling (L / I / Escape)",
                    tint = Zinc400,
                )
            }

            Text(
                text = "Guides:",
                style = MaterialTheme.typography.labelSmall,
                color = Zinc500,
                modifier = Modifier.padding(end = 2.dp),
            )

            GuideToggleButton(
                label = "Thirds",
                icon = Icons.Default.Grid3x3,
                active = isThirdsActive,
                onClick = onToggleThirds,
                testTag = "toggle_thirds",
            )

            GuideToggleButton(
                label = "Phi",
                icon = Icons.Default.Grid4x4,
                active = isGoldenActive,
                onClick = onToggleGoldenRatio,
                testTag = "toggle_golden_ratio",
            )

            GuideToggleButton(
                label = "Spiral",
                icon = Icons.AutoMirrored.Filled.RotateRight,
                active = isSpiralActive,
                onClick = onToggleSpiral,
                testTag = "toggle_spiral",
            )

            TouchTarget(onClick = onFlipSpiral, testTag = "toggle_spiral_flip") {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isSpiralActive) Zinc800 else Zinc900,
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isSpiralActive) BrandAccentDeep else Zinc700),
                ) {
                    Text(
                        text = "Flip ($spiralOrientationLabel)",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSpiralActive) Color.White else Zinc400,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }

            GuideToggleButton(
                label = "Diagonals",
                icon = Icons.Default.Timeline,
                active = isDiagonalsActive,
                onClick = onToggleDiagonals,
                testTag = "toggle_diagonals",
            )

            GuideToggleButton(
                label = "Horizon",
                icon = Icons.Default.Tune,
                active = isHorizonActive,
                onClick = onToggleHorizon,
                testTag = "toggle_horizon",
            )

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun GuideToggleButton(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    TouchTarget(
        onClick = onClick,
        testTag = testTag,
        modifier = modifier,
    ) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (active) BrandAccentDeep else Zinc900,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (active) BrandAccent else Zinc700),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (active) Color.White else Zinc400,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) Color.White else Zinc400,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
    }
}

// ── Learn 4-Tab Bar (U-24 .. U-28) ────────────────────────────────────────────

@Composable
private fun LearnTabSelector(
    selectedTab: LearnTab,
    onSelectTab: (LearnTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("learn_tab_bar"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LearnTab.entries.forEach { tab ->
            val active = tab == selectedTab
            TouchTarget(
                onClick = { onSelectTab(tab) },
                testTag = tab.tag,
                modifier = Modifier.weight(1f),
                role = Role.Tab,
                shape = RoundedCornerShape(8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (active) BrandAccentDeep else Zinc900,
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (active) BrandAccent else Zinc800),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) Color.White else Zinc400,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

// ── Explore Section (U-24, U-25) ──────────────────────────────────────────────

@Composable
private fun ExploreCardsSection(
    @Suppress("UNUSED_PARAMETER") currentImage: ImageItem,
    cardResult: com.photoselectortoolbox.domain.learn.CardGenerationResult,
    guessFirstEnabled: Boolean,
    isGuessRevealed: Boolean,
    onToggleGuessFirst: () -> Unit,
    onRevealGuess: () -> Unit,
    onOpenLesson: (String) -> Unit,
    whatIfCropActive: Boolean,
    onToggleWhatIfCrop: () -> Unit,
    selectedCropAspect: CropAspectRatio?,
    onSelectCropAspect: (CropAspectRatio?) -> Unit,
    suggestedCrop: SuggestedCrop?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TouchTarget(onClick = onToggleGuessFirst, testTag = "guess_first_toggle") {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (guessFirstEnabled) BrandAccentDeep else Zinc900,
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (guessFirstEnabled) BrandAccent else Zinc800),
                ) {
                    Text(
                        text = if (guessFirstEnabled) "Guess first: ON" else "Guess first: OFF",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (guessFirstEnabled) Color.White else Zinc400,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            }

            if (suggestedCrop != null) {
                TouchTarget(onClick = onToggleWhatIfCrop, testTag = "what_if_crop_toggle") {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (whatIfCropActive) BrandAccentDeep else Zinc900,
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (whatIfCropActive) BrandAccent else Zinc800),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Crop,
                            contentDescription = null,
                            tint = if (whatIfCropActive) Color.White else Zinc400,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = if (whatIfCropActive) "Hide 'What if?' Crop" else "What if? Crop",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (whatIfCropActive) Color.White else Zinc400,
                        )
                    }
                }
                }
            }
        }

        if (whatIfCropActive && suggestedCrop != null) {
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Zinc900),
                border = androidx.compose.foundation.BorderStroke(1.dp, BrandAccent.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth().testTag("what_if_crop_card"),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Non-Destructive Crop Preview",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = suggestedCrop.rationale,
                        style = MaterialTheme.typography.bodySmall,
                        color = Zinc400,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        CropAspectRatio.entries.forEach { ratio ->
                            val isChosen = (selectedCropAspect ?: suggestedCrop.aspectRatio) == ratio
                            TouchTarget(
                                onClick = { onSelectCropAspect(ratio) },
                                testTag = "crop_aspect_${ratio.name}",
                                shape = RoundedCornerShape(4.dp),
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isChosen) BrandAccentDeep else Zinc950,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Zinc700),
                                ) {
                                    Text(
                                        text = ratio.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isChosen) Color.White else Zinc400,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!isGuessRevealed) {
            val challenge = cardResult.guessFirstChallenge
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Zinc900),
                border = androidx.compose.foundation.BorderStroke(1.dp, BrandAccent),
                modifier = Modifier.fillMaxWidth().testTag("guess_first_card"),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = challenge.prompt,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    challenge.options.forEachIndexed { idx, option ->
                        TouchTarget(
                            onClick = onRevealGuess,
                            testTag = "guess_option_$idx",
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Zinc950,
                                border = androidx.compose.foundation.BorderStroke(1.dp, Zinc800),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = option,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Zinc200,
                                    modifier = Modifier.padding(10.dp),
                                )
                            }
                        }
                    }
                }
            }
        } else {
            cardResult.didWellCards.forEach { card ->
                LearnObservationCard(
                    card = card,
                    onOpenLesson = onOpenLesson,
                    testTag = "did_well_card",
                )
            }
            cardResult.tryNextCard?.let { card ->
                LearnObservationCard(
                    card = card,
                    onOpenLesson = onOpenLesson,
                    testTag = "try_next_card",
                )
            }
        }
    }
}

@Composable
private fun LearnObservationCard(
    card: LearnCard,
    onOpenLesson: (String) -> Unit,
    testTag: String,
) {
    val isPositive = card.kind == com.photoselectortoolbox.domain.learn.CardKind.DID_WELL
    val accent = if (isPositive) ScoreGood else ScoreWarn
    val badgeText = if (isPositive) "What you did well" else "What to try next"

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Zinc900),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().testTag(testTag),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = badgeText.uppercase(Locale.US),
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = card.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = card.whatHappened,
                style = MaterialTheme.typography.bodySmall,
                color = Zinc200,
            )
            Text(
                text = card.whyItMatters,
                style = MaterialTheme.typography.bodySmall,
                color = Zinc400,
            )
            Text(
                text = "Next time: ${card.tryNextTime}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
            card.lessonId?.let { lessonId ->
                TouchTarget(onClick = { onOpenLesson(lessonId) }, testTag = "open_lesson_link") {
                    Text(
                        text = "Why? → Open Lesson",
                        style = MaterialTheme.typography.labelSmall,
                        color = BrandAccent,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WhatIfCropOverlay(
    modifier: Modifier,
    imageAspectRatio: Float,
    crop: SuggestedCrop,
) {
    Canvas(modifier = modifier.testTag("what_if_crop_overlay")) {
        val bounds = CompositionGeometry.calculateDisplayedBounds(
            containerWidth = size.width,
            containerHeight = size.height,
            aspectRatio = imageAspectRatio,
        )
        val cropLeft = bounds.left + (crop.xMin.toFloat() * bounds.width)
        val cropTop = bounds.top + (crop.yMin.toFloat() * bounds.height)
        val cropWidth = (crop.xMax - crop.xMin).toFloat() * bounds.width
        val cropHeight = (crop.yMax - crop.yMin).toFloat() * bounds.height

        drawRect(
            color = BrandAccent,
            topLeft = Offset(cropLeft, cropTop),
            size = Size(cropWidth, cropHeight),
            style = Stroke(width = 2.5.dp.toPx()),
        )
    }
}

// ── Lessons Tab (U-26) ────────────────────────────────────────────────────────

@Composable
private fun LessonsTabContent(
    selectedLessonId: String?,
    onSelectLesson: (String?) -> Unit,
    factsByUri: Map<String, List<TechniqueFactEntity>>,
    completedLessons: Map<String, LessonProgressEntity>,
    onLessonCompleted: (LessonProgressEntity) -> Unit,
    onSetPracticeGoal: (String) -> Unit,
) {
    val activeLesson: MicroLesson? = selectedLessonId?.let { LessonCatalog.getById(it) }
    if (activeLesson != null) {
        val questions = remember(activeLesson, factsByUri) {
            LessonCatalog.buildSpotItQuiz(activeLesson, factsByUri)
        }
        var answers by remember(activeLesson.id) { mutableStateOf<Map<Int, Int>>(emptyMap()) }

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Zinc900),
            border = androidx.compose.foundation.BorderStroke(1.dp, Zinc800),
            modifier = Modifier.fillMaxWidth().testTag("lesson_detail_card"),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TouchTarget(onClick = { onSelectLesson(null) }, testTag = "all_lessons_link") {
                    Text(
                        text = "← All Lessons (${activeLesson.track.title})",
                        style = MaterialTheme.typography.labelSmall,
                        color = BrandAccent,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                Text(
                    text = activeLesson.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    text = activeLesson.conceptSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = Zinc200,
                )
                Text(
                    text = "Spot It Quiz (2 Questions)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = BrandAccent,
                )
                questions.forEachIndexed { qIdx, q ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "${qIdx + 1}. ${q.prompt}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                        q.options.forEachIndexed { optIdx, optText ->
                            val chosen = answers[qIdx] == optIdx
                            val isCorrect = optIdx == q.correctIndex
                            val borderCol = when {
                                !chosen -> Zinc800
                                isCorrect -> ScoreGood
                                else -> ScoreBad
                            }
                            TouchTarget(
                                onClick = {
                                    val updated = answers + (qIdx to optIdx)
                                    answers = updated
                                    if (updated.size == questions.size) {
                                        val correctCount = questions.indices.count { i ->
                                            updated[i] == questions[i].correctIndex
                                        }
                                        onLessonCompleted(
                                            LessonProgressEntity(
                                                lessonId = activeLesson.id,
                                                completedAt = System.currentTimeMillis(),
                                                spotItCorrect = correctCount,
                                                spotItTotal = questions.size,
                                            )
                                        )
                                    }
                                },
                                testTag = "quiz_option_${qIdx}_$optIdx",
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Zinc950,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, borderCol),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = optText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Zinc200,
                                        modifier = Modifier.padding(8.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                TouchTarget(
                    onClick = { onSetPracticeGoal(activeLesson.id) },
                    testTag = "set_practice_goal_button",
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = BrandAccentDeep,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Practice this next shoot",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        }
        return
    }

    LessonCatalog.lessonsByTrack().forEach { (track, lessons) ->
        Text(
            text = track.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Zinc400,
        )
        lessons.forEach { lesson ->
            val done = completedLessons.containsKey(lesson.id)
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Zinc900),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (done) ScoreGood.copy(alpha = 0.5f) else Zinc800),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectLesson(lesson.id) }
                    .testTag("lesson_item_${lesson.id}"),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = lesson.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                        Text(
                            text = lesson.tryThisPrompt,
                            style = MaterialTheme.typography.labelSmall,
                            color = Zinc400,
                            maxLines = 2,
                        )
                    }
                    if (done) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Completed",
                            tint = ScoreGood,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

// ── Compare Tab (U-27) ────────────────────────────────────────────────────────

@Composable
private fun CompareTabContent(images: List<ImageItem>) {
    val scenes = remember(images) { SceneComparator.findComparableScenes(images) }
    if (scenes.isEmpty()) {
        Text(
            text = "Load at least 2 photos in a shoot folder to compare burst/scene pairs.",
            style = MaterialTheme.typography.bodySmall,
            color = Zinc400,
        )
        return
    }

    scenes.forEach { scene ->
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Zinc900),
            border = androidx.compose.foundation.BorderStroke(1.dp, Zinc800),
            modifier = Modifier.fillMaxWidth().testTag("scene_compare_card"),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = scene.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    text = "Why one worked better:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = BrandAccent,
                )
                scene.differenceBullets.forEach { bullet ->
                    Text(
                        text = "• $bullet",
                        style = MaterialTheme.typography.bodySmall,
                        color = Zinc200,
                    )
                }
            }
        }
    }
}

// ── Practice & Insights Tab (U-28) ────────────────────────────────────────────

@Composable
private fun PracticeTabContent(
    images: List<ImageItem>,
    factsByUri: Map<String, List<TechniqueFactEntity>>,
    activePracticeGoal: PracticeGoalEntity?,
    onSelectLessonForGoal: (String) -> Unit,
    onOpenLesson: (String) -> Unit,
) {
    val goalStatus = remember(activePracticeGoal, images, factsByUri) {
        activePracticeGoal?.let { PracticeAndInsightsEngine.evaluateGoalProgress(it, images, factsByUri) }
    }
    val insights = remember(images, factsByUri) {
        PracticeAndInsightsEngine.computeInsights(images, factsByUri)
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Zinc900),
        border = androidx.compose.foundation.BorderStroke(1.dp, BrandAccent.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().testTag("practice_goal_card"),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Active Practice Goal",
                style = MaterialTheme.typography.labelSmall,
                color = BrandAccent,
                fontWeight = FontWeight.Bold,
            )
            if (goalStatus != null) {
                Text(
                    text = goalStatus.lessonTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    text = goalStatus.tryThisPrompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = Zinc200,
                )
                Text(
                    text = goalStatus.summaryText,
                    style = MaterialTheme.typography.labelSmall,
                    color = ScoreGood,
                )
            } else {
                Text(
                    text = "Choose one technique to focus on during your next shoot:",
                    style = MaterialTheme.typography.bodySmall,
                    color = Zinc400,
                )
                LessonCatalog.ALL_LESSONS.take(3).forEach { lesson ->
                    TouchTarget(
                        onClick = { onSelectLessonForGoal(lesson.id) },
                        testTag = "set_goal_${lesson.id}",
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Zinc950,
                            border = androidx.compose.foundation.BorderStroke(1.dp, Zinc800),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "Set Goal: ${lesson.title}",
                                style = MaterialTheme.typography.labelSmall,
                                color = BrandAccent,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    Text(
        text = "Personal Pattern Insights",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = Zinc400,
    )

    if (insights.isEmpty()) {
        Text(
            text = "Scan at least ${PracticeAndInsightsEngine.MIN_SAMPLE_SIZE} photos in a shoot folder to unlock statistically grounded personal pattern insights.",
            style = MaterialTheme.typography.bodySmall,
            color = Zinc500,
        )
    } else {
        insights.forEach { insight ->
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Zinc900),
                border = androidx.compose.foundation.BorderStroke(1.dp, Zinc800),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenLesson(insight.recommendedLessonId) }
                    .testTag("pattern_insight_card"),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = insight.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = insight.observation,
                        style = MaterialTheme.typography.bodySmall,
                        color = Zinc200,
                    )
                }
            }
        }
    }
}

// ── Photo Coach Card (U-20: Hand-Holding Guideline wording, no 'shake') ───────

@Composable
private fun PhotoCoachCard(
    currentImage: ImageItem,
    coachReport: PhotoCoachReport,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Zinc900),
        border = androidx.compose.foundation.BorderStroke(1.dp, Zinc800),
        modifier = modifier
            .fillMaxWidth()
            .testTag("photo_coach_card"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.School,
                    contentDescription = null,
                    tint = BrandAccent,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "Photo Coach",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }

            val risk = coachReport.cameraShakeRisk
            val riskPct = (risk * 100).toInt()
            val (riskLabel, riskColor) = when {
                risk >= 0.7f -> "High Blur Risk ($riskPct%)" to ScoreBad
                risk >= 0.3f -> "Moderate Blur Risk ($riskPct%)" to ScoreWarn
                else -> "Low Blur Risk ($riskPct%)" to ScoreGood
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("camera_shake_badge")
                    .testTag("camera_shake_risk"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Hand-Holding Guideline:",
                    style = MaterialTheme.typography.bodySmall,
                    color = Zinc400,
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = riskColor.copy(alpha = 0.2f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, riskColor.copy(alpha = 0.6f)),
                ) {
                    Text(
                        text = riskLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = riskColor,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            val safeSpeed = coachReport.safeShutterSpeed
            val actualSpeed = currentImage.exifData?.shutterSpeed
            val safeSpeedFormatted = safeSpeed?.let { ExifFormatter.shutterSpeed(it) } ?: "—"
            val actualSpeedFormatted = actualSpeed?.let { ExifFormatter.shutterSpeed(it) } ?: "—"

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("shutter_speed_comparison"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "1/f_eff Guideline:",
                    style = MaterialTheme.typography.bodySmall,
                    color = Zinc400,
                )
                Text(
                    text = "$safeSpeedFormatted (Actual: $actualSpeedFormatted)",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                )
            }

            coachReport.recommendedEvCompensation?.let { ev ->
                val evFormatted = if (ev > 0.0) "+$ev" else "$ev"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("recommended_ev"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Suggested EV Correction:",
                        style = MaterialTheme.typography.bodySmall,
                        color = Zinc400,
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = ScoreWarn.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ScoreWarn.copy(alpha = 0.5f)),
                    ) {
                        Text(
                            text = "$evFormatted EV",
                            style = MaterialTheme.typography.labelSmall,
                            color = ScoreWarn,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            Text(
                text = "Technique Guidance:",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Zinc400,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("coaching_tips_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (coachReport.tips.isEmpty()) {
                    Text(
                        text = "Clean capture: exposure parameters and sharpness are within optimal thresholds.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Zinc500,
                    )
                } else {
                    for (tip in coachReport.tips) {
                        CoachingTipItem(tip = tip)
                    }
                }
            }
        }
    }
}

@Composable
private fun CoachingTipItem(
    tip: CoachingTip,
    modifier: Modifier = Modifier,
) {
    val (chipColor, chipBg) = when (tip.severity) {
        TipSeverity.CRITICAL -> ScoreBad to ScoreBad.copy(alpha = 0.15f)
        TipSeverity.WARNING -> ScoreWarn to ScoreWarn.copy(alpha = 0.15f)
        TipSeverity.INFO -> BrandAccent to BrandAccent.copy(alpha = 0.15f)
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Zinc950,
        border = androidx.compose.foundation.BorderStroke(1.dp, Zinc800),
        modifier = modifier
            .fillMaxWidth()
            .testTag("coaching_tip_item"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = tip.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tip_title"),
                )

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = chipBg,
                    border = androidx.compose.foundation.BorderStroke(1.dp, chipColor.copy(alpha = 0.5f)),
                    modifier = Modifier.testTag("tip_severity_chip"),
                ) {
                    Text(
                        text = tip.severity.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = chipColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            Text(
                text = tip.description,
                style = MaterialTheme.typography.bodySmall,
                color = Zinc400,
                modifier = Modifier.testTag("tip_description"),
            )

            Text(
                text = "💡 ${tip.recommendation}",
                style = MaterialTheme.typography.bodySmall,
                color = Zinc200,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.testTag("tip_recommendation"),
            )
        }
    }
}

// ── EXIF Technical Readout ────────────────────────────────────────────────────

@Composable
private fun ExifTechnicalReadoutCard(
    currentImage: ImageItem,
    modifier: Modifier = Modifier,
) {
    val exif = currentImage.exifData
    val rows = ExifFormatter.detailRows(exif)

    val formattedDate = remember(currentImage.lastModified) {
        if (currentImage.lastModified > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(currentImage.lastModified))
        } else {
            "Unknown"
        }
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Zinc900),
        border = androidx.compose.foundation.BorderStroke(1.dp, Zinc800),
        modifier = modifier
            .fillMaxWidth()
            .testTag("exif_readout"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = BrandAccent,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "EXIF Technical Readout",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }

            for ((label, value) in rows) {
                ReadoutRow(label = label, value = value)
            }

            ReadoutRow(label = "Dimensions", value = "${currentImage.imageWidth} × ${currentImage.imageHeight}")
            ReadoutRow(label = "Timestamp", value = formattedDate)
        }
    }
}

@Composable
private fun ReadoutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Zinc400,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ── Rating Card (U-21: owns StarRatingBar + RatingFilterSelector) ─────────────

@Composable
private fun InspectRatingCard(
    rating: Int,
    onRatingChanged: (Int) -> Unit,
    ratingFilter: RatingFilter = RatingFilter.All,
    onRatingFilterSelected: (RatingFilter) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Zinc900),
        border = androidx.compose.foundation.BorderStroke(1.dp, Zinc800),
        modifier = modifier
            .fillMaxWidth()
            .testTag("star_rating_card"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Rating & Evaluation",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    text = "Hotkeys 0–5, X",
                    style = MaterialTheme.typography.labelSmall,
                    color = Zinc500,
                )
            }

            StarRatingBar(
                rating = rating,
                onRatingChanged = onRatingChanged,
                starSize = 28.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )

            RatingFilterSelector(
                currentFilter = ratingFilter,
                onFilterSelected = onRatingFilterSelected,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

// ── Bottom Filmstrip ──────────────────────────────────────────────────────────

@Composable
private fun InspectFilmstrip(
    images: List<ImageItem>,
    currentIndex: Int,
    onSelectImage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(currentIndex) {
        if (currentIndex in images.indices) {
            listState.animateScrollToItem((currentIndex - 2).coerceAtLeast(0))
        }
    }

    Surface(
        color = Zinc950,
        contentColor = Zinc200,
        modifier = modifier
            .fillMaxWidth()
            .height(84.dp)
            .border(1.dp, Zinc800, RoundedCornerShape(0.dp))
            .testTag("inspect_filmstrip"),
    ) {
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(images, key = { _, item -> item.uri }) { index, item ->
                val isSelected = index == currentIndex

                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .height(68.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Zinc900)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) BrandAccent else Zinc800,
                            shape = RoundedCornerShape(6.dp),
                        )
                        .clickable { onSelectImage(index) }
                        .testTag("filmstrip_thumb_$index")
                        .pointerHoverIcon(PointerIcon.Hand),
                ) {
                    AsyncImage(
                        model = item.uri,
                        contentDescription = item.fileName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )

                    if (item.rating != 0) {
                        Surface(
                            shape = RoundedCornerShape(topEnd = 4.dp),
                            color = Color.Black.copy(alpha = 0.75f),
                            modifier = Modifier.align(Alignment.BottomStart),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                if (item.rating > 0) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = Color(0xFFFBBF24),
                                        modifier = Modifier.size(10.dp),
                                    )
                                    Text(
                                        text = "${item.rating}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                    )
                                } else {
                                    Text(
                                        text = "✕",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                        color = ScoreBad,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
