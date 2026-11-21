---
name: openrouter-app-guide
description: Explain how the OpenRouter Agent SDK Android app works and how to tailor its models, tools, skills, streaming, routing, and run limits. Use for setup, troubleshooting, and customization questions.
compatibility: OpenRouter Agent SDK for Android 2.2 or later.
---
# OpenRouter Agent SDK for Android

Use these instructions to answer questions about the app's controls. Mention the Android screen labels that help the user find a feature.

## Get started

1. Open Settings and tap Sign in with OpenRouter, approve the key in the browser, return to the app, and tap Save. You can also paste a key.
2. Tap Models to find a model from the authenticated catalog. Switch off tools if you want to use a model that only supports text.
3. Send a prompt. The status above the composer distinguishes connecting, waiting, reasoning, tool use, and an answer in progress. Activity shows progress, sources, and reported usage when available.

## Customize a run

- Open Configure → SDK settings for fallback models, routing, reasoning effort, sampling, output and run limits, and response format. In Advanced, configure repetition detection, local and MCP tool deadlines, concurrency, and final-answer behavior when a stop condition is met. Unset fields use the model or provider default.
- A follow-up profile can switch models, change sampling or output length, and append instructions after a chosen number of SDK inference turns. This applies to later requests in the current run. Hosted tools can do several steps within one request.
- Tools in Settings controls the hosted sandbox, web search, date and time, local time zone, and optional MCP. The sandbox can produce files. Open Sandbox files to preview or export them.
- Keep the initial model and follow-up models compatible with the requested tools and structured output. Feature availability is drawn from OpenRouter's model metadata and affected by provider routing.
- Cost and total-token thresholds are evaluated between steps. A step in flight can cross a threshold. The per-response output limit separately controls each model response.

## Work in the background

In version 2.5 and later, a run continues when the user switches apps, locks the phone, or closes the app from recents. The ongoing notification shows the current step and has Stop. Tool approvals can be answered there with Allow once or Deny. A notification shows the reply when a run finishes while the app is not on screen. A force stop or restart still ends the run.

## Mini apps and voice

Ask the agent for a small app, such as a tracker, calculator or game. It saves a single HTML file that the user opens from Configure → Mini apps. There the user can add it to the home screen, share it as a file, or delete it. Each app runs on its own, keeps its own data, and can only reach public HTTPS APIs. Apps shared by someone else have no internet access. To change an app, ask the agent; it keeps the app's data. Mini apps can be switched off in Settings.

Tap the microphone in the composer to dictate. The Ask agent Quick Settings tile opens the app ready to listen.

## Organize chats and projects

Tap Chats to open saved conversations for the active provider. Use New project to make a group, then select it and tap New chat. Use a chat's menu to rename it, move it, or delete it. Removing a project moves its chats to Unfiled. An OpenRouter chat restores its tool history and sandbox reference when reopened. A stopped turn keeps its partial reply and can be retried.

## Switch providers

In version 2.3 and later, open Configure → Providers or tap the provider name in the header. Use the OpenUncensored preset with its own API key, or add an HTTPS provider that supports OpenAI-compatible Chat Completions. Load models from the provider or enter an exact model ID. Profiles have separate conversations and can enable streaming or add provider-specific JSON request fields. Select OpenRouter for hosted tools, custom SDK tools, and on-demand skill discovery.

## Create custom tools

Open Configure → Tools and create a tool with a lowercase underscore-separated name, a specific description, and a JSON object input schema. Choose its handler:

- Template fills placeholders such as `{{city}}` from validated inputs and returns text to the model.
- Ask me pauses the run to collect a text response from the user.
- Webhook sends validated arguments to a fixed HTTPS endpoint after approval. The endpoint should allow this app's WebView origin. Keep credentials out of tool descriptions, templates, URLs, and skill instructions.

Use a descriptive tool name and required input keys. Enable only tools relevant to your current task. Tool approvals and network settings are controlled by the app.

## Save and reuse skills

Open Configure → Skills. A good skill has a lowercase hyphen-separated name, a description of when to use it, and concise steps. Import or export a Markdown SKILL.md file to share it. Enable discovery to let a tool-capable agent request its instructions as needed. Use Pin to include the instructions in each new run. Imported instructions are disabled until you review and enable them. The built-in guide is available inside the app without a model request.

## Recover a stalled run

Use Activity to see the most recent stage. Stop ends the local request and keeps any partial reply. Retry without tools sends the prompt with external and local tools disabled for that one request. Check the key and model if OpenRouter rejects the request, and review the allowed origins or CORS headers if a webhook or MCP connection fails.
