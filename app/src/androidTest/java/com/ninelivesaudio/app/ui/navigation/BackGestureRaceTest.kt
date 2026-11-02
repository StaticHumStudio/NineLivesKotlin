package com.ninelivesaudio.app.ui.navigation

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * Issue #104. A back gesture or Back key lands, and before the navigation
 * host gets to act on it the app itself changes the back stack (Play
 * finishing its load and opening the Player, a toolbar back arrow, a tab).
 * The host then tried to animate the page that was already gone and threw
 * "Cannot transition entry that is not in the back stack", which killed
 * the app.
 *
 * These run on a real activity with the real main-thread dispatcher, because
 * the crash lives in the gap between two main-thread messages.
 */
@RunWith(AndroidJUnit4::class)
class BackGestureRaceTest {

    private val failure = AtomicReference<Throwable?>(null)
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var nav: NavHostController
    private lateinit var dispatcher: OnBackPressedDispatcher

    @Before fun setUp() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> failure.compareAndSet(null, e) }
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            activity.setContent {
                nav = rememberNavController()
                dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
                // The same pairing NineLivesNavHost uses.
                CancelPendingBackOnNavigation(nav)
                NavHost(nav, startDestination = Routes.HOME) {
                    composable(Routes.HOME) { Text("home") }
                    composable(Routes.LIBRARY) { Text("library") }
                    composable(Routes.PLAYER) { Text("player") }
                    composable(Routes.BOOK_DETAIL) { Text("detail") }
                }
            }
        }
        settle()
        // Library, then a book, the way a person gets to a book page.
        onMain { nav.navigateToTab(Routes.LIBRARY) }
        settle()
        onMain { nav.navigate(Routes.bookDetail("book")) }
        settle()
        onMain { assertEquals(Routes.BOOK_DETAIL, nav.currentDestination?.route) }
    }

    @After fun tearDown() {
        scenario.close()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    @Test fun backKeyThenTheAppOpensThePlayer() {
        onMain {
            dispatcher.onBackPressed()
            // Play finished loading and opened the Player before the host
            // got to the Back.
            nav.navigateToTab(Routes.PLAYER)
        }
        settle()
        assertNoCrash()
        onMain { assertEquals(Routes.PLAYER, nav.currentDestination?.route) }
    }

    @Test fun backKeyThenTheToolbarBackArrow() {
        onMain {
            dispatcher.onBackPressed()
            nav.popBackStack()
        }
        settle()
        assertNoCrash()
    }

    @Test fun backSwipeStartsThenTheAppLeavesThePage() {
        onMain {
            dispatcher.dispatchOnBackStarted(BackEventCompat(5f, 900f, 0f, BackEventCompat.EDGE_LEFT))
            nav.popBackStack()
        }
        settle()
        onMain {
            dispatcher.dispatchOnBackProgressed(BackEventCompat(300f, 900f, 0.5f, BackEventCompat.EDGE_LEFT))
            dispatcher.onBackPressed()
        }
        settle()
        assertNoCrash()
        // The app's own pop already left the book page and cancelled the
        // swipe. Lifting the finger still counts as a Back from the Library,
        // so the user lands on Home.
        assertSettledOn(Routes.HOME)
    }

    @Test fun backSwipeInProgressThenTheAppLeavesThePage() {
        onMain { dispatcher.dispatchOnBackStarted(BackEventCompat(5f, 900f, 0f, BackEventCompat.EDGE_LEFT)) }
        settle()
        onMain { dispatcher.dispatchOnBackProgressed(BackEventCompat(200f, 900f, 0.3f, BackEventCompat.EDGE_LEFT)) }
        settle()
        // The host is mid-swipe on the book page when the app pops it.
        onMain { nav.popBackStack() }
        settle()
        onMain {
            dispatcher.dispatchOnBackProgressed(BackEventCompat(300f, 900f, 0.5f, BackEventCompat.EDGE_LEFT))
            dispatcher.onBackPressed()
        }
        settle()
        assertNoCrash()
        assertSettledOn(Routes.HOME)
    }

    @Test fun aPlainBackStillGoesToTheLibrary() {
        val detail = currentEntry()
        onMain { dispatcher.onBackPressed() }
        settle()
        assertNoCrash()
        assertSettledOn(Routes.LIBRARY)
        assertGone(detail)
    }

    @Test fun aPlainBackSwipeStillGoesToTheLibrary() {
        onMain { dispatcher.dispatchOnBackStarted(BackEventCompat(5f, 900f, 0f, BackEventCompat.EDGE_LEFT)) }
        settle()
        onMain { dispatcher.dispatchOnBackProgressed(BackEventCompat(300f, 900f, 0.5f, BackEventCompat.EDGE_LEFT)) }
        settle()
        val detail = currentEntry()
        onMain { dispatcher.onBackPressed() }
        settle()
        assertNoCrash()
        assertSettledOn(Routes.LIBRARY)
        assertGone(detail)
    }

    @Test fun twoQuickBacksGoToTheLibraryThenHome() {
        onMain { dispatcher.onBackPressed() }
        onMain { dispatcher.onBackPressed() }
        settle()
        assertNoCrash()
        onMain {
            val route = nav.currentDestination?.route
            check(route == Routes.LIBRARY || route == Routes.HOME) { "ended on $route" }
        }
    }

    private fun assertNoCrash() {
        failure.get()?.let { throw AssertionError("app crashed", it) }
    }

    private fun currentEntry(): NavBackStackEntry {
        var entry: NavBackStackEntry? = null
        onMain { entry = nav.currentBackStackEntry }
        return entry!!
    }

    /**
     * On [route], with the page animation finished: the page is RESUMED and
     * it is the only page the host still draws.
     */
    private fun assertSettledOn(route: String) {
        waitUntil("$route never settled") {
            nav.currentDestination?.route == route &&
                nav.currentBackStackEntry!!.lifecycle.currentState == Lifecycle.State.RESUMED &&
                nav.visibleEntries.value.count { it.destination.navigatorName == "composable" } == 1
        }
        onMain { assertEquals(route, nav.currentDestination?.route) }
    }

    /** The popped page finished its exit and was torn down. */
    private fun assertGone(entry: NavBackStackEntry) {
        waitUntil("${entry.destination.route} was never destroyed") {
            entry.lifecycle.currentState == Lifecycle.State.DESTROYED
        }
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            var met = false
            onMain { met = condition() }
            if (met) return
            if (System.currentTimeMillis() > deadline) throw AssertionError(message)
            Thread.sleep(50)
        }
    }

    private fun onMain(block: () -> Unit) = scenario.onActivity { block() }

    private fun settle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(600)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}
