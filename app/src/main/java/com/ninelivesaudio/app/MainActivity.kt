package com.ninelivesaudio.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.ninelivesaudio.app.service.PlaybackManager
import com.ninelivesaudio.app.ui.components.MiniPlayer
import com.ninelivesaudio.app.ui.navigation.BottomNavBar
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
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        Column {
                            // Mini player sits above the bottom nav bar
                            MiniPlayer(
                                playbackManager = playbackManager,
                                onNavigateToPlayer = {
                                    navController.navigate(Routes.PLAYER) {
                                        launchSingleTop = true
                                    }
                                },
                            )
                            BottomNavBar(navController)
                        }
                    }
                ) { innerPadding ->
                    NineLivesNavHost(
                        navController = navController,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}
