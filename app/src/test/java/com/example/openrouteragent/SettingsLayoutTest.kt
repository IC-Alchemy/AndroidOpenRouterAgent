package com.example.openrouteragent

import android.app.Application
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class SettingsOrderTest {
    @Test
    fun keysComeFirstThenToolsAndRouting() {
        assertEquals(listOf("keys", "tools", "routing"), SETTINGS_SECTIONS.take(3).map { it.first })
        assertEquals("keys", firstSettingsSection(AgentConfig()))
        assertEquals("tools", firstSettingsSection(AgentConfig(apiKey = "sk-or-v1-abc")))
    }

    @Test
    fun modelListsAreBuiltFromTappedModels() {
        assertEquals("a/one", addModelId("", "a/one"))
        assertEquals("a/one, b/two", addModelId("a/one", "b/two"))
        assertEquals("a/one, b/two", addModelId("a/one, b/two", "a/one"))
        assertEquals("b/two", removeModelId("a/one,b/two", "a/one"))
        assertEquals(listOf("a/one", "b/two"), modelIds(" a/one ,, b/two,a/one"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun viewModel(): AgentViewModel {
        val vm = AgentViewModel(
            application = ApplicationProvider.getApplicationContext<Application>(),
            textModerator = null,
            reportClient = ModerationReportClient(endpointUrl = ""),
        )
        val deadline = System.currentTimeMillis() + 5_000L
        while (vm.isLoading && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        return vm
    }

    @Test
    fun openAiSettingsShowOnlyWithAnOpenAiKey() {
        val vm = viewModel()
        var initial by mutableStateOf(AgentConfig(apiKey = "sk-or-v1-abc"))
        composeRule.setContent {
            OpenRouterAgentTheme {
                androidx.compose.runtime.key(initial) {
                    SettingsBottomSheet(initial, {}, {}, {}, vm)
                }
            }
        }

        // With an OpenRouter key, Settings opens on Tools.
        composeRule.onNodeWithText("Agent tools").assertIsDisplayed()
        composeRule.onNodeWithText("OpenAI tools").assertDoesNotExist()
        composeRule.onNodeWithText(OPENROUTER_ONLY_NOTE).assertDoesNotExist()
        composeRule.onNodeWithText("Keys").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Test OpenAI key").assertDoesNotExist()

        initial = AgentConfig(apiKey = "sk-or-v1-abc", openAiKey = "sk-openai-abc")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("OpenAI tools").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Keys").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Test OpenAI key").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun samplingFieldsComeLastWithANote() {
        val vm = viewModel()
        composeRule.setContent {
            OpenRouterAgentTheme { SettingsBottomSheet(AgentConfig(apiKey = "sk-or-v1-abc"), {}, {}, {}, vm) }
        }
        composeRule.onNodeWithText("Model").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Reasoning effort").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Sampling (rarely supported)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(SAMPLING_NOTE).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Top K").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun fallbackModelsAreAddedByTappingThem() {
        var value by mutableStateOf("")
        val choices = ModelChoices(
            models = listOf(
                CatalogModel("anthropic/claude-sonnet-4.6", "Anthropic: Claude Sonnet 4.6", 200_000, setOf("tools")),
                CatalogModel("google/gemini-3-flash", "Google: Gemini 3 Flash", 1_000_000, setOf("tools")),
                CatalogModel("${OPENAI_MODEL_PREFIX}gpt-5", "GPT-5 (direct)", 400_000, setOf("tools")),
            ),
            loading = false, error = null, onRefresh = {},
        )
        composeRule.setContent {
            OpenRouterAgentTheme {
                androidx.compose.foundation.layout.Column {
                    ModelListField("Fallback models", value, { value = it }, choices)
                }
            }
        }

        composeRule.onNodeWithText("None").assertIsDisplayed()
        composeRule.onNodeWithText("Add model").performClick()
        // OpenAI (direct) models can't be OpenRouter fallbacks, so the list leaves them out.
        composeRule.onNodeWithText("Search models").performTextInput("e")
        composeRule.onNodeWithText("GPT-5 (direct)").assertDoesNotExist()
        // The test screen is small, so search narrows the list to the model to tap.
        composeRule.onNodeWithText("Search models").performTextInput("mini")
        composeRule.onNodeWithText("Google: Gemini 3 Flash").performClick()
        composeRule.onNodeWithText("Add model").performClick()
        composeRule.onNodeWithText("Anthropic: Claude Sonnet 4.6").performClick()
        assertEquals("google/gemini-3-flash, anthropic/claude-sonnet-4.6", value)
        composeRule.onNodeWithText("1. Google: Gemini 3 Flash").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Remove Google: Gemini 3 Flash").performClick()
        assertEquals("anthropic/claude-sonnet-4.6", value)
    }
}
