package com.example.openrouteragent

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPreflightTest {
    @Test
    fun rejectsTheReportedVideoCommandBeforeAnyNetworkCall() {
        val error = commandProblem(
            "create a tool that uses the open router API to download videos from open router and use the llm to create them")
        assertNotNull(error)
        assertTrue(error!!, error.startsWith("OpenRouter does not provide video download/generation. Use a video provider."))
    }

    @Test
    fun rejectsOtherVideoDownloadAndGenerationRequestsAimedAtOpenRouter() {
        listOf(
            "Download the latest videos from OpenRouter",
            "use openrouter to generate a video of a cat",
            "Have the OpenRouter model render an mp4 clip for me",
            "openrouter: make me a video",
        ).forEach { assertNotNull(it, commandProblem(it)) }
    }

    @Test
    fun allowsLlmOnlyVideoWorkAndRealVideoProviders() {
        listOf(
            "Use OpenRouter to write five video prompts and save them to outputs/prompts.json",
            "Create a tool that uses OpenRouter for video scripts and Runway to generate the videos",
            "Write a Python tool that calls fal.ai to generate videos from prompts an OpenRouter LLM writes",
            "Download https://example.com/clip.mp4 in the sandbox",
            "Summarise this video transcript",
            "What models does OpenRouter offer?",
            "Use the sandbox to save the Python version to outputs/version.txt.",
        ).forEach { assertNull(it, commandProblem(it)) }
    }
}
