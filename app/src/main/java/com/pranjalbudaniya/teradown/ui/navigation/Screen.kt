package com.pranjalbudaniya.teradown.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Downloads : Screen("downloads")
    object Library : Screen("library")
    object Settings : Screen("settings")
    object BrowseShare : Screen("browse?url={url}&pwd={pwd}") {
        fun createRoute(url: String, pwd: String? = null): String {
            val encodedUrl = URLEncoder.encode(url, StandardCharsets.UTF_8.toString())
            val encodedPwd = if (!pwd.isNullOrBlank()) {
                URLEncoder.encode(pwd, StandardCharsets.UTF_8.toString())
            } else ""
            return "browse?url=$encodedUrl&pwd=$encodedPwd"
        }
    }
}

data class BottomNavItem(
    val screen: Screen,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

val bottomNavItems = listOf(
    BottomNavItem(
        screen = Screen.Home,
        title = "Home",
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home
    ),
    BottomNavItem(
        screen = Screen.Downloads,
        title = "Downloads",
        selectedIcon = Icons.Filled.Download,
        unselectedIcon = Icons.Outlined.Download
    ),
    BottomNavItem(
        screen = Screen.Library,
        title = "Library",
        selectedIcon = Icons.Filled.VideoLibrary,
        unselectedIcon = Icons.Outlined.VideoLibrary
    ),
    BottomNavItem(
        screen = Screen.Settings,
        title = "Settings",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings
    )
)
