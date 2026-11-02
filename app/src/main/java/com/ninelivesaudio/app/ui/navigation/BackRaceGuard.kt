package com.ninelivesaudio.app.ui.navigation

import android.annotation.SuppressLint
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.navigation.NavController

/**
 * Drops a Back that the navigation host has not acted on yet whenever the
 * back stack changes under it (#104).
 *
 * The host in navigation-compose 2.8 does not handle Back on the spot. It
 * queues the work for the next turn of the main thread and, when that runs,
 * animates the page it last drew on top. If the app changes the stack in the
 * gap (Play finishing its load and opening the Player, the toolbar back
 * arrow, a tab tap) that page is already gone, and the host throws
 * "Cannot transition entry that is not in the back stack", which kills the
 * app. Navigation 2.10 reworked this, but it needs a newer build toolchain
 * than this project uses.
 *
 * Every change to the stack, from any caller, reaches this listener before
 * the queued work runs, so cancelling here means the stale Back never runs.
 * The page change that cancelled it already moved the user, so nothing they
 * asked for is lost. A Back still in progress when the user lifts their
 * finger acts on whatever page is now on top.
 *
 * Call it next to the NavHost, with the same controller.
 */
@SuppressLint("VisibleForTests") // the dispatcher's only way to cancel a Back
@Composable
fun CancelPendingBackOnNavigation(navController: NavController) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher ?: return
    DisposableEffect(navController, dispatcher) {
        val listener = NavController.OnDestinationChangedListener { _, _, _ ->
            dispatcher.dispatchOnBackCancelled()
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }
}
