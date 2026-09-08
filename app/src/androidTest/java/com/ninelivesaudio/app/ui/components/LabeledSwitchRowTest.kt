package com.ninelivesaudio.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LabeledSwitchRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun labelAreaIsOneNamedSwitchThatTogglesOnce() {
        var checked by mutableStateOf(false)
        var callbackCount by mutableIntStateOf(0)

        composeTestRule.setContent {
            LabeledSwitchRow(
                title = "Use API Token",
                subtitle = "Login with a pre-generated API token",
                checked = checked,
                onCheckedChange = {
                    callbackCount++
                    checked = it
                },
            )
        }

        val switch = composeTestRule.onNodeWithText("Use API Token")
        switch
            .assertIsToggleable()
            .assertIsOff()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))

        composeTestRule.onNodeWithText("Login with a pre-generated API token")
            .assertIsToggleable()

        composeTestRule.onNodeWithText("Use API Token").performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, callbackCount)
            assertEquals(true, checked)
        }
        switch
            .assertIsOn()
    }

    @Test
    fun disabledLabelIsNamedButCannotActivateTheValue() {
        var callbackCount by mutableIntStateOf(0)

        composeTestRule.setContent {
            LabeledSwitchRow(
                title = "Use API Token",
                subtitle = "Login with a pre-generated API token",
                checked = true,
                enabled = false,
                onCheckedChange = { callbackCount++ },
            )
        }

        composeTestRule.onNodeWithText("Use API Token")
            .assertIsToggleable()
            .assertIsOn()
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))

        composeTestRule.onNodeWithText("Use API Token")
            .performTouchInput { click() }

        composeTestRule.runOnIdle {
            assertEquals(0, callbackCount)
        }
    }
}
