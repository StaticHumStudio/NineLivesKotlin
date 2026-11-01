package com.ninelivesaudio.app.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.ninelivesaudio.app.service.PlaybackManager
import com.ninelivesaudio.app.service.PlaybackState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps the trampoline on screen until the player leaves LOADING, so the app
 * is in the foreground while Media3 starts its service. Off unless the plain
 * path is refused a foreground start (plan Task 4 Step 6, decided in Task 8).
 */
internal const val TRAMPOLINE_HOLDS_UNTIL_PLAYING = false

/**
 * An invisible activity a widget tap lands on. Open app taps go straight to
 * `MainActivity`. Play and Open its page taps decide here, through
 * [WidgetPlayback], with the same lock and active library checks, and only
 * open a screen for an Open or a refusal (locked, or the book cannot play
 * now). It never sits over the home screen longer than [WIDGET_HANDOFF_CAP]:
 * a slower decision carries on in [WidgetPlayback]'s own scope. An activity
 * rather than a broadcast, because a foreground service start is allowed
 * from an activity a user just tapped.
 */
@AndroidEntryPoint
class WidgetActionActivity : ComponentActivity() {

    @Inject lateinit var widgetPlayback: WidgetPlayback

    @Inject lateinit var playbackManager: PlaybackManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launch = intent.widgetLaunchOrNull()
        val (bookId, decision) = when (launch) {
            is WidgetLaunch.PlayBook -> launch.bookId to widgetPlayback.binderTap(launch)
            is WidgetLaunch.OpenBook -> launch.bookId to widgetPlayback.binderTap(launch)
            null -> {
                finish()
                return
            }
            else -> {
                startActivity(widgetMainIntent(this, launch))
                finish()
                return
            }
        }
        lifecycleScope.launch {
            handOff(
                decision = decision,
                cap = WIDGET_HANDOFF_CAP,
                onPlan = { plan ->
                    refusalLaunch(plan, bookId)?.let { startActivity(widgetMainIntent(this@WidgetActionActivity, it)) }
                    val loading = plan == WidgetPlayPlan.LOAD_BOOK || plan == WidgetPlayPlan.RESUME_CURRENT
                    if (TRAMPOLINE_HOLDS_UNTIL_PLAYING && loading) {
                        withTimeoutOrNull(8.seconds) {
                            playbackManager.playbackState.first { it != PlaybackState.LOADING }
                        }
                    }
                },
                onLatePlan = { late -> widgetPlayback.openWhenDecided(this@WidgetActionActivity, bookId, late) },
                finish = ::finish,
            )
        }
    }
}
