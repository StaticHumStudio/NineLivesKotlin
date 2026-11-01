package com.ninelivesaudio.app.widget

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.ninelivesaudio.app.service.PlaybackForegroundStarts
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * An invisible activity a widget tap lands on. Open app taps go straight to
 * `MainActivity`. Play and Open its page taps decide here, through
 * [WidgetPlayback], with the same lock and active library checks, and only
 * open a screen for an Open or a refusal (locked, or the book cannot play now).
 *
 * A Play keeps this activity up until the playback service has started in the
 * foreground (see [handOff]): Android 12 and later refuse that start once the
 * app has no screen up, and a slow cold start outlasts the tap's grace window.
 * So the window is fully transparent and passes every touch and key through,
 * and the home screen stays usable the whole time. [WIDGET_HANDOFF_CAP] bounds it.
 */
@AndroidEntryPoint
class WidgetActionActivity : ComponentActivity() {

    @Inject lateinit var widgetPlayback: WidgetPlayback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        )
        // Fully transparent, so Android lets touches pass through to the home screen.
        window.attributes = window.attributes.apply { alpha = 0f }
        // Taken before the tap is decided, so only a start for this tap counts.
        val startsBefore = PlaybackForegroundStarts.count.value
        val launch = intent.widgetLaunchOrNull()
        val (bookId, tap) = when (launch) {
            is WidgetLaunch.PlayBook -> launch.bookId to widgetPlayback.binderTap(launch)
            is WidgetLaunch.OpenBook -> launch.bookId to widgetPlayback.binderTap(launch)
            is WidgetLaunch.TogglePlay -> launch.bookId to widgetPlayback.toggleTap(launch)
            null, WidgetLaunch.Nothing -> {
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
                decision = tap.plan,
                cap = WIDGET_HANDOFF_CAP,
                onPlan = { plan ->
                    refusalLaunch(plan, bookId)?.let { startActivity(widgetMainIntent(this@WidgetActionActivity, it)) }
                },
                awaitStarted = {
                    coroutineScope {
                        val started = async { PlaybackForegroundStarts.count.first { it > startsBefore } }
                        awaitServiceStart(async { started.await(); Unit }, tap.act, grace = 3.seconds)
                        started.cancel()
                    }
                },
                onLatePlan = { late -> widgetPlayback.openWhenDecided(this@WidgetActionActivity, bookId, late) },
                finish = ::finish,
            )
        }
    }
}
