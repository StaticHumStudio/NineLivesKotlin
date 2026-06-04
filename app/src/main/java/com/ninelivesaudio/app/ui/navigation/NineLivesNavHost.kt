package com.ninelivesaudio.app.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.hilt.navigation.compose.hiltViewModel
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.ui.bookdetail.BookDetailScreen
import com.ninelivesaudio.app.ui.dossier.NightwatchDossierScreen
import com.ninelivesaudio.app.ui.downloads.DownloadsScreen
import com.ninelivesaudio.app.ui.home.HomeScreen
import com.ninelivesaudio.app.ui.library.LibraryScreen
import com.ninelivesaudio.app.ui.onboarding.WelcomeScreen
import com.ninelivesaudio.app.ui.onboarding.WelcomeViewModel
import com.ninelivesaudio.app.ui.player.PlayerScreen
import com.ninelivesaudio.app.ui.settings.LicensesScreen
import com.ninelivesaudio.app.ui.settings.SettingsScreen

/**
 * Navigation route constants.
 */
object Routes {
    const val HOME = "home"
    const val WELCOME = "welcome"
    const val LIBRARY = "library"
    const val PLAYER = "player"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"
    const val DOSSIER = "dossier"
    const val LICENSES = "licenses"
    const val BOOK_DETAIL = "book_detail/{bookId}"

    fun bookDetail(bookId: String) = "book_detail/${Uri.encode(bookId)}"
}

@Composable
fun NineLivesNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: String = Routes.HOME,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable(Routes.WELCOME) {
            val welcomeViewModel: WelcomeViewModel = hiltViewModel()
            fun goToSettings() {
                navController.navigate(Routes.SETTINGS) {
                    popUpTo(Routes.WELCOME) { inclusive = true }
                }
            }
            WelcomeScreen(
                onChooseLocal = {
                    welcomeViewModel.choose(AppMode.LOCAL)
                    goToSettings()
                },
                onChooseServer = {
                    welcomeViewModel.choose(AppMode.AUDIOBOOKSHELF)
                    goToSettings()
                },
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                onNavigateToLibrary = {
                    navController.navigate(Routes.LIBRARY) {
                        launchSingleTop = true
                    }
                },
                onNavigateToBookDetail = { bookId ->
                    navController.navigate(Routes.bookDetail(bookId))
                },
                onNavigateToDossier = {
                    navController.navigate(Routes.DOSSIER) {
                        launchSingleTop = true
                    }
                },
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS) {
                        launchSingleTop = true
                    }
                },
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
            SettingsScreen(
                onNavigateToDossier = {
                    navController.navigate(Routes.DOSSIER) {
                        launchSingleTop = true
                    }
                },
                onNavigateToLicenses = {
                    navController.navigate(Routes.LICENSES) {
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(Routes.LICENSES) {
            LicensesScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.DOSSIER) {
            NightwatchDossierScreen(
                onNavigateBack = { navController.popBackStack() }
            )
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
                        popUpTo(navController.graph.findStartDestination().id) {
                            inclusive = false
                        }
                        launchSingleTop = true
                    }
                }
            )
        }
    }
}
