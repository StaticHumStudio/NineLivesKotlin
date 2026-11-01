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
 * An invisible activity a widget tap lands on. Open taps go straight to
 * `MainActivity`. Play taps decide here, through [WidgetPlayback], and only
 * open a screen when Play is refused (locked, or the book cannot play now).
 * An activity rather than a broadcast, because a foreground service start is
 * allowed from an activity a user just tapped.
 */
@AndroidEntryPoint
class WidgetActionActivity : ComponentActivity() {

    @Inject lateinit var widgetPlayback: WidgetPlayback

    @Inject lateinit var playbackManager: PlaybackManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launch = intent.widgetLaunchOrNull()
        if (launch == null) {
            finish()
            return
        }
        if (launch !is WidgetLaunch.PlayBook) {
            startActivity(widgetMainIntent(this, launch))
            finish()
            return
        }
        lifecycleScope.launch {
            try {
                when (widgetPlayback.playFromBinder(launch.bookId)) {
                    WidgetPlayPlan.OPEN_BOOK_PAGE ->
                        startActivity(widgetMainIntent(this@WidgetActionActivity, WidgetLaunch.OpenBook(launch.bookId)))
                    WidgetPlayPlan.OPEN_UNLOCK ->
                        startActivity(widgetMainIntent(this@WidgetActionActivity, WidgetLaunch.OpenUnlock))
                    WidgetPlayPlan.LOAD_BOOK, WidgetPlayPlan.RESUME_CURRENT -> if (TRAMPOLINE_HOLDS_UNTIL_PLAYING) {
                        withTimeoutOrNull(8.seconds) {
                            playbackManager.playbackState.first { it != PlaybackState.LOADING }
                        }
                    }
                    else -> Unit
                }
            } finally {
                finish()
            }
        }
    }
}
