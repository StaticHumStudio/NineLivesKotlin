package com.ninelivesaudio.app.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ninelivesaudio.app.ui.theme.NineLivesAudioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryGroupHeaderSemanticsInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun headerIsAHeadingWithItsStateAndClickLabel() {
        var expanded by mutableStateOf(false)

        composeTestRule.setContent {
            NineLivesAudioTheme {
                GroupHeaderRow(title = "Saga", count = 3, isExpanded = expanded, onClick = { expanded = !expanded })
            }
        }

        val header = composeTestRule.onNodeWithText("Saga", substring = true)
        header
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        assertEquals(
            "Expand group",
            header.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label,
        )

        header.performClick()

        composeTestRule.onNodeWithText("Saga", substring = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    }
}
