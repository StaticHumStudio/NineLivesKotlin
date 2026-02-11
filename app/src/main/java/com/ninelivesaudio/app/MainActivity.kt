package com.ninelivesaudio.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.ninelivesaudio.app.service.PlaybackManager
import com.ninelivesaudio.app.ui.components.CosmicBackgroundGradient
import com.ninelivesaudio.app.ui.components.MiniPlayer
import com.ninelivesaudio.app.ui.navigation.LeftNavRail
import com.ninelivesaudio.app.ui.navigation.NineLivesNavHost
import com.ninelivesaudio.app.ui.navigation.Routes
import com.ninelivesaudio.app.ui.theme.NineLivesAudioTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var playbackManager: PlaybackManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NineLivesAudioTheme {
                val navController = rememberNavController()

                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        // Left navigation rail (72.dp wide)
                        LeftNavRail(
                            navController = navController,
                            modifier = Modifier.width(72.dp)
                        )

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
                                // MiniPlayer at bottom (was in bottomBar)
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
