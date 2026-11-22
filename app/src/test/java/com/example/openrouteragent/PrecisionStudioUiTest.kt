package com.example.openrouteragent

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrecisionStudioUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun studioThemeAppliesDarkAndLightPalettesTypographyAndShapes() {
        var darkPrimary = Color.Unspecified
        var darkBackground = Color.Unspecified
        var lightPrimary = Color.Unspecified
        var lightBackground = Color.Unspecified
        var mediumShape = RoundedCornerShape(0.dp)

        composeRule.setContent {
            OpenRouterAgentTheme(darkTheme = true) {
                darkPrimary = MaterialTheme.colorScheme.primary
                darkBackground = MaterialTheme.colorScheme.background
                mediumShape = MaterialTheme.shapes.medium as RoundedCornerShape
            }
            OpenRouterAgentTheme(darkTheme = false) {
                lightPrimary = MaterialTheme.colorScheme.primary
                lightBackground = MaterialTheme.colorScheme.background
            }
        }

        assertEquals(Color(0xFF38BDF8), darkPrimary)
        assertEquals(Color(0xFF050608), darkBackground)
        assertEquals(Color(0xFF0284C7), lightPrimary)
        assertEquals(Color(0xFFF7F8FA), lightBackground)
        assertEquals(RoundedCornerShape(14.dp), mediumShape)
        assertEquals(0.75.dp, StudioHairlineWidth)
    }

    @Test
    fun outlinedSectionAndStudioRailRenderTitledUntitledAndHighlightedStates() {
        composeRule.setContent {
            OpenRouterAgentTheme(darkTheme = true) {
                androidx.compose.foundation.layout.Column {
                    OutlinedSection(title = "Connection", highlighted = true) {
                        Text("Section body")
                    }
                    OutlinedSection(title = null, highlighted = false) {
                        Text("Untitled body")
                    }
                    StudioRailBlock {
                        Text("Rail content")
                    }
                }
            }
        }

        composeRule.onNodeWithText("Connection").assertIsDisplayed()
        composeRule.onNodeWithText("Section body").assertIsDisplayed()
        composeRule.onNodeWithText("Untitled body").assertIsDisplayed()
        composeRule.onNodeWithText("Rail content").assertIsDisplayed()
    }
}
