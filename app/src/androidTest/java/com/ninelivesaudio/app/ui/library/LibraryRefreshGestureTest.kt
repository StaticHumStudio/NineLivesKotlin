package com.ninelivesaudio.app.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMaterial3Api::class)
class LibraryRefreshGestureTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun emptyLibraryContentPassesDownwardGestureToPullRefresh() {
        val refreshCount = AtomicInteger(0)

        composeTestRule.setContent {
            PullToRefreshBox(
                isRefreshing = false,
                onRefresh = { refreshCount.incrementAndGet() },
                modifier = Modifier.fillMaxSize(),
            ) {
                RefreshableEmptyLibraryContent {
                    EmptyHostTestContent()
                }
            }
        }

        composeTestRule.onNodeWithTag("empty-library-content")
            .performTouchInput { swipeDown() }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            refreshCount.get() == 1
        }
        assertEquals(1, refreshCount.get())
    }
}

@Composable
private fun EmptyHostTestContent() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("empty-library-content"),
    )
}
