package com.photoselectortoolbox.ui.navigation

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import com.photoselectortoolbox.ui.components.handCursor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.photoselectortoolbox.ui.duplicates.DuplicatesScreen
import com.photoselectortoolbox.ui.inspect.InspectScreen
import com.photoselectortoolbox.ui.selector.SelectorScreen
import com.photoselectortoolbox.ui.settings.SettingsScreen
import com.photoselectortoolbox.ui.statistics.StatisticsScreen
import com.photoselectortoolbox.ui.theme.BrandAccent
import com.photoselectortoolbox.ui.theme.BrandAccentNav
import com.photoselectortoolbox.ui.theme.StudioCanvas
import com.photoselectortoolbox.ui.theme.StudioPanel
import com.photoselectortoolbox.ui.theme.Zinc400

@Composable
fun PhotoSelectorApp(windowSizeClass: WindowSizeClass) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioCanvas)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
            ),
    ) {
        DesktopModeApp(windowSizeClass = windowSizeClass)
    }
}

@Composable
private fun DesktopModeApp(
    windowSizeClass: WindowSizeClass,
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val selectorViewModel: com.photoselectortoolbox.viewmodel.SelectorViewModel =
        androidx.hilt.navigation.compose.hiltViewModel()

    val isExpanded = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

    // The selector draws its own sidebar, which already carries these four
    // destinations at the bottom (see SelectorSidebar). Drawing a
    // NavigationRail beside it too would put the same four controls on screen
    // twice and cost the photographs another 80dp of width — on a layout whose
    // entire premise is that nothing competes with the frames for space.
    val selectorOwnsNavigation = isExpanded &&
        currentDestination?.hierarchy?.any { it.route == Screen.PhotoSelector.route } == true
    val useNavigationRail = isExpanded && !selectorOwnsNavigation

    val navigateToScreen: (Screen) -> Unit = { screen ->
        navController.navigate(screen.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    if (useNavigationRail) {
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail(
                modifier = Modifier.fillMaxHeight(),
                containerColor = StudioPanel,
                contentColor = Zinc400,
            ) {
                NavEntry.all.forEach { entry ->
                    RailCell(
                        entry = entry,
                        currentRoute = currentDestination?.route,
                        onNavigate = navigateToScreen,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f),
            ) {
                AppNavHost(
                    navController = navController,
                    windowSizeClass = windowSizeClass,
                    currentRoute = currentDestination?.route,
                    onNavigate = navigateToScreen,
                    selectorViewModel = selectorViewModel,
                )
            }
        }
    } else if (selectorOwnsNavigation) {
        // Expanded, on the selector: the sidebar is the navigation.
        AppNavHost(
            navController = navController,
            windowSizeClass = windowSizeClass,
            currentRoute = currentDestination?.route,
            onNavigate = navigateToScreen,
            selectorViewModel = selectorViewModel,
        )
    } else {
        Scaffold(
            containerColor = StudioCanvas,
            bottomBar = {
                NavigationBar(
                    containerColor = StudioPanel,
                    contentColor = Zinc400,
                    tonalElevation = 0.dp,
                ) {
                    NavEntry.all.forEach { entry ->
                        BarCell(
                            entry = entry,
                            currentRoute = currentDestination?.route,
                            onNavigate = navigateToScreen,
                        )
                    }
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                AppNavHost(
                    navController = navController,
                    windowSizeClass = windowSizeClass,
                    currentRoute = currentDestination?.route,
                    onNavigate = navigateToScreen,
                    selectorViewModel = selectorViewModel,
                )
            }
        }
    }
}

@Composable
private fun AppNavHost(
    navController: androidx.navigation.NavHostController,
    windowSizeClass: WindowSizeClass,
    currentRoute: String?,
    onNavigate: (Screen) -> Unit,
    selectorViewModel: com.photoselectortoolbox.viewmodel.SelectorViewModel,
    modifier: Modifier = Modifier,
) {
    // Only the folder, so the host does not recompose with every change of the selector's state.
    val selectorFolderUri by remember(selectorViewModel) {
        selectorViewModel.uiState.map { it.folderUri }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = selectorViewModel.uiState.value.folderUri)
    NavHost(
        navController = navController,
        startDestination = Screen.PhotoSelector.route,
        modifier = modifier.fillMaxSize(),
    ) {
        composable(Screen.PhotoSelector.route) {
            SelectorScreen(
                windowSizeClass = windowSizeClass,
                viewModel = selectorViewModel,
                currentRoute = currentRoute,
                onNavigate = onNavigate,
            )
        }
        composable(Screen.Inspect.route) {
            InspectScreen(
                windowSizeClass = windowSizeClass,
                viewModel = selectorViewModel,
                currentRoute = currentRoute,
                onNavigate = onNavigate,
            )
        }
        composable(Screen.Statistics.route) {
            StatisticsScreen(windowSizeClass = windowSizeClass, seedFolderUri = selectorFolderUri)
        }
        composable(Screen.DuplicateFinder.route) {
            DuplicatesScreen(windowSizeClass = windowSizeClass, seedFolderUri = selectorFolderUri)
        }
        composable(Screen.Settings.route) {
            SettingsScreen()
        }
    }
}

/**
 * One rail cell. Tagged like the sidebar's (`nav_<route>`, `nav_tools`), so "go to screen X" is
 * one selector whichever navigation surface the window size chose. Tools opens its menu right of
 * the cell instead of navigating.
 */
@Composable
internal fun ColumnScope.RailCell(entry: NavEntry, currentRoute: String?, onNavigate: (Screen) -> Unit) {
    val colors = NavigationRailItemDefaults.colors(
        selectedIconColor = BrandAccent,
        selectedTextColor = BrandAccent,
        unselectedIconColor = Zinc400,
        unselectedTextColor = Zinc400,
        indicatorColor = BrandAccentNav,
    )
    when (entry) {
        is NavEntry.Destination -> NavigationRailItem(
            modifier = Modifier.testTag(entry.testTag).handCursor(),
            icon = { Icon(imageVector = entry.icon, contentDescription = entry.spoken(currentRoute)) },
            label = { Text(text = entry.label) },
            selected = entry.isSelected(currentRoute),
            onClick = { onNavigate(entry.screen) },
            colors = colors,
        )
        NavEntry.Tools -> {
            val menu = rememberToolsMenuState()
            val density = LocalDensity.current
            var cell by remember { mutableStateOf(IntSize.Zero) }
            Box(modifier = Modifier.onSizeChanged { cell = it }) {
                NavigationRailItem(
                    modifier = with(menu) { Modifier.toolsCell() }
                        .semantics { contentDescription = entry.spoken(currentRoute) }
                        .testTag(entry.testTag)
                        .handCursor(),
                    icon = { Icon(imageVector = entry.icon, contentDescription = null) },
                    label = { Text(text = entry.label) },
                    selected = entry.isSelected(currentRoute),
                    onClick = menu::open,
                    colors = colors,
                )
                ToolsMenu(
                    state = menu,
                    currentRoute = currentRoute,
                    onOpen = onNavigate,
                    offset = with(density) { DpOffset(cell.width.toDp(), -cell.height.toDp()) },
                )
            }
        }
    }
}

/** One bottom-bar cell (compact windows); Tools opens its menu above the bar. */
@Composable
internal fun RowScope.BarCell(entry: NavEntry, currentRoute: String?, onNavigate: (Screen) -> Unit) {
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = BrandAccent,
        selectedTextColor = BrandAccent,
        unselectedIconColor = Zinc400,
        unselectedTextColor = Zinc400,
        indicatorColor = BrandAccentNav,
    )
    when (entry) {
        is NavEntry.Destination -> NavigationBarItem(
            modifier = Modifier.testTag(entry.testTag).handCursor(),
            icon = { Icon(imageVector = entry.icon, contentDescription = entry.spoken(currentRoute)) },
            label = { Text(text = entry.label) },
            selected = entry.isSelected(currentRoute),
            onClick = { onNavigate(entry.screen) },
            colors = colors,
        )
        NavEntry.Tools -> {
            val menu = rememberToolsMenuState()
            NavigationBarItem(
                modifier = with(menu) { Modifier.toolsCell() }
                    .semantics { contentDescription = entry.spoken(currentRoute) }
                    .testTag(entry.testTag)
                    .handCursor(),
                // The menu is anchored to the glyph: the popup flips above it, the bar being
                // the window's last row.
                icon = {
                    Box {
                        Icon(imageVector = entry.icon, contentDescription = null)
                        ToolsMenu(state = menu, currentRoute = currentRoute, onOpen = onNavigate)
                    }
                },
                label = { Text(text = entry.label) },
                selected = entry.isSelected(currentRoute),
                onClick = menu::open,
                colors = colors,
            )
        }
    }
}
