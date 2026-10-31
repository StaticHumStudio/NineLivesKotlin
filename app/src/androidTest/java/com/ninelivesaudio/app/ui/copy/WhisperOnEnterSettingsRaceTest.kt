package com.ninelivesaudio.app.ui.copy

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ninelivesaudio.app.settings.unhinged.UnhingedSettings
import com.ninelivesaudio.app.ui.components.unhinged.LocalUnhingedSettings
import com.ninelivesaudio.app.ui.copy.unhinged.catalog.WhisperContext
import com.ninelivesaudio.app.ui.copy.unhinged.catalog.WhisperOnEnter
import com.ninelivesaudio.app.ui.copy.unhinged.catalog.WhisperService
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhisperOnEnterSettingsRaceTest {
    @get:Rule val composeRule = createComposeRule()

    /** The stored settings arrive after the first frame. The first frame carries the defaults (whispers on). */
    private fun run(stored: UnhingedSettings): WhisperService {
        val service = WhisperService()
        var settings by mutableStateOf(UnhingedSettings.Default)
        composeRule.setContent {
            CompositionLocalProvider(LocalUnhingedSettings provides settings) {
                WhisperOnEnter(WhisperContext.APP_OPENED, service)
            }
        }
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { settings = stored }
        composeRule.mainClock.advanceTimeBy(2_000)
        return service
    }

    @Test fun whispersOffInStorageMeansNoLaunchWhisper() {
        assertNull(run(UnhingedSettings.Default.copy(whispersEnabled = false)).currentWhisper.value)
    }

    @Test fun reducedMotionInStorageMeansNoLaunchWhisper() {
        assertNull(run(UnhingedSettings.Default.copy(reduceMotionRequested = true)).currentWhisper.value)
    }

    @Test fun whispersOnStillShowsTheLaunchWhisper() {
        assertNotNull(run(UnhingedSettings.Default).currentWhisper.value)
    }
}
