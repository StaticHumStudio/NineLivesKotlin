package com.ninelivesaudio.app.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.ui.theme.NineLivesAudioTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BottomNavSelectionTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun downloadsLooksUnselectedOnTheFrameItAppears() {
        var appMode by mutableStateOf(AppMode.LOCAL)
        composeRule.setContent {
            NineLivesAudioTheme {
                val nav = rememberNavController()
                Column {
                    NavHost(nav, startDestination = Routes.SETTINGS, modifier = Modifier.weight(1f)) {
                        listOf(Routes.HOME, Routes.LIBRARY, Routes.PLAYER, Routes.DOWNLOADS, Routes.SETTINGS).forEach { composable(it) {} }
                    }
                    BottomNavBar(nav, appMode)
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        appMode = AppMode.AUDIOBOOKSHELF
        composeRule.mainClock.advanceTimeByFrame()
        val firstFrame = composeRule.onNode(hasText("Downloads")).captureToImage().asAndroidBitmap()
        composeRule.mainClock.advanceTimeBy(2_000)
        val settled = composeRule.onNode(hasText("Downloads")).captureToImage().asAndroidBitmap()
        assertTrue("Downloads drew a selection on its first frame", firstFrame.sameAs(settled))
    }
}
