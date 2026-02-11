package com.ninelivesaudio.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.ninelivesaudio.app.ui.bookdetail.BookDetailScreen
import com.ninelivesaudio.app.ui.downloads.DownloadsScreen
import com.ninelivesaudio.app.ui.home.HomeScreen
import com.ninelivesaudio.app.ui.library.LibraryScreen
import com.ninelivesaudio.app.ui.player.PlayerScreen
import com.ninelivesaudio.app.ui.settings.SettingsScreen

/**
 * Navigation route constants.
 */
object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val PLAYER = "player"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"
    const val BOOK_DETAIL = "book_detail/{bookId}"

    fun bookDetail(bookId: String) = "book_detail/$bookId"
}

@Composable
fun NineLivesNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onNavigateToLibrary = {
                    navController.navigate(Routes.LIBRARY) {
                        launchSingleTop = true
                    }
                },
                onNavigateToBookDetail = { bookId ->
                    navController.navigate(Routes.bookDetail(bookId))
                }
            )
        }

        composable(Routes.LIBRARY) {
            LibraryScreen(
                onNavigateToBookDetail = { bookId ->
                    navController.navigate(Routes.bookDetail(bookId))
                }
            )
        }

        composable(Routes.PLAYER) {
            PlayerScreen()
        }

        composable(Routes.DOWNLOADS) {
            DownloadsScreen()
        }

        composable(Routes.SETTINGS) {
            SettingsScreen()
        }

        composable(
            route = Routes.BOOK_DETAIL,
            arguments = listOf(
                navArgument("bookId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString("bookId") ?: return@composable
            BookDetailScreen(
                bookId = bookId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToPlayer = {
                    navController.navigate(Routes.PLAYER) {
                        launchSingleTop = true
                    }
                }
            )
        }
    }
}
