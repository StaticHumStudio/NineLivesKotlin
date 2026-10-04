package com.ninelivesaudio.app.ui.navigation

import androidx.navigation.NavController

/**
 * The screen every tab sits on top of, on every launch. The tabs used to pop
 * to the graph's start destination, but on first run that is Welcome, which
 * onboarding removes from the back stack. Navigation ignores a popUpTo whose
 * target is not on the stack, so every tab tap pushed a new screen (#58).
 * Home is always on the stack (onboarding puts it there), so popping to it
 * keeps one entry per tab under Home on first run and every launch after.
 */
const val TAB_ROOT_ROUTE = Routes.HOME

/**
 * The back stack onboarding leaves behind when it lands on [target]: Home
 * first, so Back from the landing tab goes Home and then out of the app, the
 * same as a normal launch.
 */
fun postOnboardingRoutes(target: String): List<String> =
    if (target == TAB_ROOT_ROUTE) listOf(TAB_ROOT_ROUTE) else listOf(TAB_ROOT_ROUTE, target)

/**
 * Switch to the tab at [route]. Each tab keeps its screen, scroll, and loaded
 * shelf while another tab is open. Without saveState and restoreState,
 * leaving the Library threw it away and a big library re-downloaded on every
 * return.
 */
fun NavController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(TAB_ROOT_ROUTE) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

/** Leave Welcome for [target] with the stack a normal launch would have. */
fun NavController.finishOnboarding(target: String) {
    val routes = postOnboardingRoutes(target)
    navigate(routes.first()) {
        popUpTo(Routes.WELCOME) { inclusive = true }
        launchSingleTop = true
    }
    routes.drop(1).forEach { navigateToTab(it) }
}
