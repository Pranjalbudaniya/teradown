package com.pranjalbudaniya.teradown.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pranjalbudaniya.teradown.ui.browse.BrowseShareScreen
import com.pranjalbudaniya.teradown.ui.downloads.DownloadsScreen
import com.pranjalbudaniya.teradown.ui.home.HomeScreen
import com.pranjalbudaniya.teradown.ui.library.LibraryScreen
import com.pranjalbudaniya.teradown.ui.settings.SettingsScreen
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@Composable
fun TeledownMainScreen(
    navController: NavHostController = rememberNavController()
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Show bottom navigation on primary tabs
    val isBottomNavVisible = bottomNavItems.any { it.screen.route == currentRoute }

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = isBottomNavVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    bottomNavItems.forEach { item ->
                        val selected = currentRoute == item.screen.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (currentRoute != item.screen.route) {
                                    navController.navigate(item.screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.title
                                )
                            },
                            label = { Text(item.title) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                indicatorColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Home Destination
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateToBrowse = { url, pwd ->
                        navController.navigate(Screen.BrowseShare.createRoute(url, pwd))
                    },
                    onNavigateToDownloads = {
                        navController.navigate(Screen.Downloads.route)
                    },
                    onNavigateToLibrary = {
                        navController.navigate(Screen.Library.route)
                    },
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route)
                    }
                )
            }

            // Browse Share Destination (Reachable from Home via Inspect Link)
            composable(
                route = Screen.BrowseShare.route,
                arguments = listOf(
                    navArgument("url") { type = NavType.StringType; defaultValue = "" },
                    navArgument("pwd") { type = NavType.StringType; defaultValue = "" }
                )
            ) { backStackEntry ->
                val rawUrl = backStackEntry.arguments?.getString("url").orEmpty()
                val rawPwd = backStackEntry.arguments?.getString("pwd").orEmpty()

                val decodedUrl = try {
                    URLDecoder.decode(rawUrl, StandardCharsets.UTF_8.toString())
                } catch (e: Exception) {
                    rawUrl
                }

                val decodedPwd = try {
                    if (rawPwd.isNotBlank()) URLDecoder.decode(rawPwd, StandardCharsets.UTF_8.toString()) else null
                } catch (e: Exception) {
                    rawPwd.ifBlank { null }
                }

                BrowseShareScreen(
                    url = decodedUrl,
                    pwd = decodedPwd,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToDownloads = {
                        navController.navigate(Screen.Downloads.route)
                    }
                )
            }

            // Downloads Destination
            composable(Screen.Downloads.route) {
                DownloadsScreen()
            }

            // Library Destination
            composable(Screen.Library.route) {
                LibraryScreen(
                    onNavigateToHome = {
                        navController.navigate(Screen.Home.route)
                    }
                )
            }

            // Settings Destination
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
        }
    }
}
