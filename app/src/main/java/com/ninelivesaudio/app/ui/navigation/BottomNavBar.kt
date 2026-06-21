package com.ninelivesaudio.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.ui.theme.NineLivesTheme

data class BottomNavItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val homeItem = BottomNavItem(
    route = Routes.HOME,
    label = "Home",
    selectedIcon = Icons.Filled.Home,
    unselectedIcon = Icons.Outlined.Home,
)
private val libraryItem = BottomNavItem(
    route = Routes.LIBRARY,
    label = "Library",
    selectedIcon = Icons.Filled.LibraryMusic,
    unselectedIcon = Icons.Outlined.LibraryMusic,
)
private val playerItem = BottomNavItem(
    route = Routes.PLAYER,
    label = "Player",
    selectedIcon = Icons.Filled.PlayCircle,
    unselectedIcon = Icons.Outlined.PlayCircle,
)
private val downloadsItem = BottomNavItem(
    route = Routes.DOWNLOADS,
    label = "Downloads",
    selectedIcon = Icons.Filled.Download,
    unselectedIcon = Icons.Outlined.Download,
)
private val settingsItem = BottomNavItem(
    route = Routes.SETTINGS,
    label = "Settings",
    selectedIcon = Icons.Filled.Settings,
    unselectedIcon = Icons.Outlined.Settings,
)

/**
 * Nav items for the current mode. LOCAL mode omits Downloads — scanned-local
 * audio is already on-device and the Downloads queue is ABS-only.
 */
fun bottomNavItemsFor(appMode: AppMode): List<BottomNavItem> =
    if (appMode == AppMode.LOCAL) {
        listOf(homeItem, libraryItem, playerItem, settingsItem)
    } else {
        listOf(homeItem, libraryItem, playerItem, downloadsItem, settingsItem)
    }

@Composable
fun BottomNavBar(
    navController: NavController,
    appMode: AppMode = AppMode.AUDIOBOOKSHELF,
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val colors = NineLivesTheme.colors
    NavigationBar(
        containerColor = colors.archiveVoidBase,
        contentColor = colors.goldFilament
    ) {
        bottomNavItemsFor(appMode).forEach { item ->
            val isSelected = currentDestination?.hierarchy?.any { it.route == item.route } == true

            NavigationBarItem(
                selected = isSelected,
                onClick = {
                    navController.navigate(item.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            inclusive = false
                        }
                        launchSingleTop = true
                    }
                },
                icon = {
                    Icon(
                        imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = item.label
                    )
                },
                label = {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colors.goldFilament,
                    selectedTextColor = colors.goldFilament,
                    unselectedIconColor = colors.archiveTextMuted,
                    unselectedTextColor = colors.archiveTextMuted,
                    indicatorColor = colors.goldFilament.copy(alpha = 0.12f)
                )
            )
        }
    }
}
