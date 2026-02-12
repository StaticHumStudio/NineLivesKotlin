package com.ninelivesaudio.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ninelivesaudio.app.service.PlaybackManager
import com.ninelivesaudio.app.service.SettingsManager
import com.ninelivesaudio.app.settings.unhinged.UnhingedSettings
import com.ninelivesaudio.app.settings.unhinged.UnhingedSettingsRepository
import com.ninelivesaudio.app.ui.components.CosmicBackgroundGradient
import com.ninelivesaudio.app.ui.components.MiniPlayer
import com.ninelivesaudio.app.ui.navigation.BottomNavBar
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

    @Inject
    lateinit var unhingedRepository: UnhingedSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: MainActivity starting")
        enableEdgeToEdge()

        // Request POST_NOTIFICATIONS permission (required on Android 13+ / API 33)
        // Without this, Media3 cannot post the foreground service notification.
        requestNotificationPermission()

        // Settings are already loaded by NineLivesApp.onCreate()
        lifecycleScope.launch {
            unhingedRepository.incrementSession()
            Log.d(TAG, "onCreate: Session incremented")
        }

        setContent {
            // Observe settings for Unhinged Mode
            val appSettings by settingsManager.settings.collectAsState()

            // Convert to UnhingedSettings
            val unhingedSettings = UnhingedSettings.fromAppSettings(
                unhingedThemeEnabled = appSettings.unhingedThemeEnabled,
                anomaliesEnabled = appSettings.anomaliesEnabled,
                whispersEnabled = appSettings.whispersEnabled,
                copyModeString = appSettings.copyMode,
                reduceMotionRequested = false
            )

            Log.d(TAG, "Recomposing with unhingedSettings: " +
                    "themeEnabled=${unhingedSettings.unhingedThemeEnabled}, " +
                    "anomalies=${unhingedSettings.anomaliesEnabled}, " +
                    "whispers=${unhingedSettings.whispersEnabled}, " +
                    "copyMode=${unhingedSettings.copyMode}")

            NineLivesAudioTheme(unhingedSettings = unhingedSettings) {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                val screenWidthDp = LocalConfiguration.current.screenWidthDp
                val useRailNavigation = screenWidthDp >= 720

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        if (!useRailNavigation) {
                            BottomNavBar(navController = navController)
                        }
                    }
                ) { innerPadding ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (useRailNavigation) {
                            LeftNavRail(
                                navController = navController,
                                modifier = Modifier.width(72.dp)
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
                                    modifier = Modifier.weight(1f)
                                )
                                if (currentRoute != Routes.PLAYER) {
                                    MiniPlayer(
                                        playbackManager = playbackManager,
                                        onNavigateToPlayer = {
                                            navController.navigate(Routes.PLAYER) {
                                                launchSingleTop = true
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
