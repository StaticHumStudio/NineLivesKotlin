package com.ninelivesaudio.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ninelivesaudio.app.service.ConnectivityMonitor
import com.ninelivesaudio.app.service.PlaybackManager
import com.ninelivesaudio.app.entitlement.EffectiveSettingsRepository
import com.ninelivesaudio.app.review.InAppReviewManager
import com.ninelivesaudio.app.entitlement.FreeTier
import com.ninelivesaudio.app.service.SettingsManager
import com.ninelivesaudio.app.settings.unhinged.UnhingedSettings
import com.ninelivesaudio.app.settings.unhinged.UnhingedSettingsRepository
import com.ninelivesaudio.app.ui.components.CosmicBackgroundGradient
import com.ninelivesaudio.app.ui.components.MiniPlayer
import com.ninelivesaudio.app.ui.copy.unhinged.catalog.WhisperContext
import com.ninelivesaudio.app.ui.copy.unhinged.catalog.WhisperHost
import com.ninelivesaudio.app.ui.copy.unhinged.catalog.WhisperOnEnter
import com.ninelivesaudio.app.ui.navigation.BottomNavBar
import com.ninelivesaudio.app.ui.navigation.startDestinationFor
import com.ninelivesaudio.app.ui.navigation.LeftNavRail
import com.ninelivesaudio.app.ui.navigation.NineLivesNavHost
import com.ninelivesaudio.app.ui.navigation.Routes
import com.ninelivesaudio.app.ui.theme.NineLivesAudioTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "MainActivity"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var playbackManager: PlaybackManager

    @Inject
    lateinit var settingsManager: SettingsManager

    /**
     * The theme is a gated feature, so it is applied from the
     * entitlement-clamped copy rather than from raw storage. The Settings picker
     * keeps showing the user's stored choice, greyed, so a downgrade reads as
     * "locked" rather than as "your choice was erased".
     */
    @Inject
    lateinit var effectiveSettings: EffectiveSettingsRepository

    /**
     * The Play review flow has to launch from an Activity, so the trigger lives
     * here rather than in PlaybackManager, which outlives every Activity.
     */
    @Inject
    lateinit var inAppReviewManager: InAppReviewManager

    @Inject
    lateinit var connectivityMonitor: ConnectivityMonitor

    @Inject
    lateinit var unhingedRepository: UnhingedSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: MainActivity starting")
        enableEdgeToEdge()

        // Request POST_NOTIFICATIONS permission (required on Android 13+ / API 33)
        // Without this, Media3 cannot post the foreground service notification.
        requestNotificationPermission()

        // A finished book is the success moment the review prompt hangs off.
        // Never a payment, a permission result, or any failure state, and never
        // a button: Play's quota is silent, so a control that sometimes does
        // nothing reads as broken. The manual path is the Settings row, which
        // deep-links to the listing and always does something visible.
        lifecycleScope.launch {
            // RESUMED, not the bare lifecycleScope. A plain collector runs while
            // the Activity is stopped, so finishing a book with the screen off or
            // the app backgrounded would fire a dialog at a user who is not
            // looking, wasting the one prompt Play might honour.
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                playbackManager.bookCompleted.collect {
                    inAppReviewManager.maybeRequestReview(this@MainActivity)
                }
            }
        }

        // Settings are already loaded by NineLivesApp.onCreate()
        lifecycleScope.launch {
            unhingedRepository.incrementSession()
            Log.d(TAG, "onCreate: Session incremented")
        }

        setContent {
            // Observe settings for Unhinged Mode
            // Raw settings drive navigation. Routing off the normalized copy
            // was wrong: `effective` is derived independently, so settingsLoaded
            // could flip true while it still held construction-time defaults, and
            // a returning user would briefly be routed to onboarding.
            val appSettings by settingsManager.settings.collectAsStateWithLifecycle()
            // Only the theme is entitlement-gated, so only the theme is clamped.
            val isUnlocked by effectiveSettings.isUnlocked.collectAsStateWithLifecycle()
            val settingsLoaded by settingsManager.isLoaded.collectAsStateWithLifecycle()
            val storageUnavailable by settingsManager.storageUnavailable.collectAsStateWithLifecycle()

            // Detect system reduce motion preference
            val systemReduceMotion = try {
                Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            } catch (_: Exception) { false }

            // Observe DataStore-persisted unhinged settings (includes user's reduce motion toggle)
            val dataStoreSettings by unhingedRepository.settingsFlow
                .collectAsStateWithLifecycle(initialValue = UnhingedSettings.Default)

            // Merge: use DataStore reduce motion OR system reduce motion
            val reduceMotion = dataStoreSettings.reduceMotionRequested || systemReduceMotion

            // Convert to UnhingedSettings
            val unhingedSettings = UnhingedSettings.fromAppSettings(
                unhingedThemeEnabled = appSettings.unhingedThemeEnabled,
                anomaliesEnabled = appSettings.anomaliesEnabled,
                whispersEnabled = appSettings.whispersEnabled,
                copyModeString = appSettings.copyMode,
                reduceMotionRequested = reduceMotion
            )

            Log.d(TAG, "Recomposing with unhingedSettings: " +
                    "anomalies=${unhingedSettings.anomaliesEnabled}, " +
                    "whispers=${unhingedSettings.whispersEnabled}, " +
                    "copyMode=${unhingedSettings.copyMode}, " +
                    "reduceMotion=${unhingedSettings.reduceMotionRequested}")

            NineLivesAudioTheme(
                // The stored choice stays untouched and the picker keeps showing
                // it, greyed. Only what gets APPLIED is clamped.
                themeMode = if (isUnlocked) appSettings.themeMode else FreeTier.THEME,
                unhingedSettings = unhingedSettings,
            ) {
                if (!settingsLoaded) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        CosmicBackgroundGradient()
                    }
                    return@NineLivesAudioTheme
                }

                // Storage failure is a destructive/critical-adjacent state, so it
                // stays plain-language rather than in the app's usual voice.
                if (storageUnavailable) {
                    SettingsStorageUnavailableScreen(
                        onRetry = {
                            lifecycleScope.launch { settingsManager.loadSettings() }
                        },
                    )
                    return@NineLivesAudioTheme
                }

                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                val screenWidthDp = LocalConfiguration.current.screenWidthDp
                val useRailNavigation = screenWidthDp >= 720
                val startDestination = startDestinationFor(appSettings.onboardingComplete)

                // WhisperHost wraps all content to show atmospheric whisper overlays
                WhisperHost(modifier = Modifier.fillMaxSize()) {
                    // Trigger a whisper on app open
                    WhisperOnEnter(WhisperContext.APP_OPENED)

                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        bottomBar = {
                            if (!useRailNavigation && currentRoute != Routes.WELCOME) {
                                BottomNavBar(
                                    navController = navController,
                                    appMode = appSettings.appMode,
                                )
                            }
                        }
                    ) { innerPadding ->
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            if (useRailNavigation && currentRoute != Routes.WELCOME) {
                                LeftNavRail(
                                    navController = navController,
                                    modifier = Modifier.width(72.dp),
                                    appMode = appSettings.appMode,
                                )
                            }

                            // Main content area with background + screens
                            Box(modifier = Modifier.weight(1f)) {
                                // Cosmic gradient background (behind all content)
                                CosmicBackgroundGradient()

                                // Content stack: NavHost + MiniPlayer overlay
                                Column(modifier = Modifier.fillMaxSize()) {
                                    NineLivesNavHost(
                                        navController = navController,
                                        startDestination = startDestination,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (currentRoute != Routes.PLAYER && currentRoute != Routes.WELCOME) {
                                        MiniPlayer(
                                            playbackManager = playbackManager,
                                            onNavigateToPlayer = {
                                                navController.navigate(Routes.PLAYER) {
                                                    popUpTo(navController.graph.findStartDestination().id) {
                                                        saveState = true
                                                    }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // App is visible — evict stale connections and recover playback session
        connectivityMonitor.onAppForegrounded()
    }

    override fun onStop() {
        super.onStop()
        // App going to background — record timestamp for debouncing
        connectivityMonitor.onAppBackgrounded()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_CODE,
                )
            }
        }
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_CODE = 1001
    }
}

/**
 * Full-screen fallback when [SettingsManager] cannot read encrypted storage.
 * Deliberately plain language, no lore voice — destructive/critical flows stay
 * literal per repo convention. Retry re-runs the same load rather than the app
 * silently falling back to defaults, which would risk clobbering retained
 * server, source, and library selections.
 */
@Composable
private fun SettingsStorageUnavailableScreen(onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Settings storage is unavailable",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Nine Lives could not read its settings. Your data has not been changed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}
