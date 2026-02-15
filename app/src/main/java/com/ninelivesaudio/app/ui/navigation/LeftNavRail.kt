package com.ninelivesaudio.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import com.ninelivesaudio.app.ui.theme.AccentGold
import com.ninelivesaudio.app.ui.theme.MistFaint
import com.ninelivesaudio.app.ui.theme.NavRailBackground
import com.ninelivesaudio.app.ui.theme.SigilGold

data class NavItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

@Composable
fun LeftNavRail(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(NavRailBackground)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        bottomNavItems.forEach { item ->
            val isSelected = currentDestination?.hierarchy?.any { it.route == item.route } == true

            LeftNavItem(
                item = item,
                isSelected = isSelected,
                onClick = {
                    navController.navigate(item.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            inclusive = false
                        }
                        launchSingleTop = true
                    }
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun LeftNavItem(
    item: BottomNavItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier.size(56.dp),
        contentAlignment = Alignment.Center
    ) {
        // Pill background (only when selected)
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(48.dp, 40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(SigilGold.copy(alpha = 0.12f))
            )
        }

        // Gold vertical indicator bar (3.dp wide, left edge)
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                    .background(SigilGold)
                    .shadow(
                        elevation = 3.dp,
                        shape = RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp),
                        spotColor = AccentGold.copy(alpha = 0.25f)
                    )
            )
        }

        // Icon button
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                contentDescription = item.label,
                tint = if (isSelected) SigilGold else MistFaint,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
