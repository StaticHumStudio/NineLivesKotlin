package com.ninelivesaudio.app.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ninelivesaudio.app.ui.theme.NineLivesAudioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryErrorBannerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun bannerShowsTheLineAndOffersRetryAndDismiss() {
        var retries by mutableIntStateOf(0)
        var dismissals by mutableIntStateOf(0)

        composeTestRule.setContent {
            NineLivesAudioTheme {
                LibraryErrorBanner(
                    message = libraryErrorMessage(LibraryLoadFailure.SHELF),
                    onRetry = { retries++ },
                    onDismiss = { dismissals++ },
                )
            }
        }

        composeTestRule.onNodeWithText("Saved books could not be read.").assertExists()
        composeTestRule.onNodeWithText("Retry").performClick()
        composeTestRule.onNodeWithContentDescription("Dismiss").performClick()

        assertEquals(1, retries)
        assertEquals(1, dismissals)
    }
}
