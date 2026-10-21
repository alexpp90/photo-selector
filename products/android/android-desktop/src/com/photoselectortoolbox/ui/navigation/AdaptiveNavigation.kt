package com.photoselectortoolbox.ui.navigation

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
                Screen.all.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any {
                        it.route == screen.route
                    } == true

                    NavigationRailItem(
                        // Same tag the selector sidebar puts on its own
                        // destination items, so "go to screen X" is one
                        // selector regardless of which of the two navigation
                        // surfaces is currently drawn.
                        modifier = Modifier.testTag("nav_${screen.route}"),
                        icon = {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = screen.label,
                            )
                        },
                        label = { Text(text = screen.label) },
                        selected = selected,
                        onClick = { navigateToScreen(screen) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = BrandAccent,
                            selectedTextColor = BrandAccent,
                            unselectedIconColor = Zinc400,
                            unselectedTextColor = Zinc400,
                            indicatorColor = BrandAccentNav,
                        ),
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
                    Screen.all.forEach { screen ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == screen.route
                        } == true

                        NavigationBarItem(
                            // The third navigation surface, tagged like the
                            // other two. All of the sidebar (expanded selector),
                            // the rail (expanded elsewhere) and this bar
                            // (compact) answer to `nav_<route>`, so a test does
                            // not have to know which one the window size chose.
                            modifier = Modifier.testTag("nav_${screen.route}"),
                            icon = {
                                Icon(
                                    imageVector = screen.icon,
                                    contentDescription = screen.label,
                                )
                            },
                            label = { Text(text = screen.label) },
                            selected = selected,
                            onClick = { navigateToScreen(screen) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = BrandAccent,
                                selectedTextColor = BrandAccent,
                                unselectedIconColor = Zinc400,
                                unselectedTextColor = Zinc400,
                                indicatorColor = BrandAccentNav,
                            ),
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
            StatisticsScreen(windowSizeClass = windowSizeClass)
        }
        composable(Screen.DuplicateFinder.route) {
            DuplicatesScreen(windowSizeClass = windowSizeClass)
        }
        composable(Screen.Settings.route) {
            SettingsScreen()
        }
    }
}

