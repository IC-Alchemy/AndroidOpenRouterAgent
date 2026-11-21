---
name: openrouter-app-guide
description: Explain how the OpenRouter Agent SDK Android app works and how to tailor its models, tools, skills, streaming, routing, and run limits. Use for setup, troubleshooting, and customization questions.
compatibility: OpenRouter Agent SDK for Android 2.2 or later.
---
# OpenRouter Agent SDK for Android

Use these instructions to answer questions about the app's controls. Mention the Android screen labels that help the user find a feature.

## Get started

1. Open Settings and enter an OpenRouter key.
2. Tap Models to find a model from the authenticated catalog. Switch off tools if you want to use a model that only supports text.
3. Send a prompt. The status above the composer distinguishes connecting, waiting, reasoning, tool use, and an answer in progress. Activity shows progress, sources, and reported usage when available.

## Customize a run

- Open Configure → SDK settings for fallback models, routing, reasoning effort, sampling, output and run limits, and response format. In Advanced, configure repetition detection, local and MCP tool deadlines, concurrency, and final-answer behavior when a stop condition is met. Unset fields use the model or provider default.
- A follow-up profile can switch models, change sampling or output length, and append instructions after a chosen number of SDK inference turns. This applies to later requests in the current run. Hosted tools can do several steps within one request.
- Tools in Settings controls the hosted sandbox, web search, date and time, local time zone, and optional MCP. The sandbox can produce files. Open Sandbox files to preview or export them.
- Open Settings → OpenRouter Server Tools → Subagents to add a worker the main model can delegate to. Apply the worker, then save Settings. Open Activity during a run to see the worker's task, status, and outcome.
- Project files opens the device folder shared by the project's chats. Import files, create folders, and edit UTF-8 text there. Unfiled chats have their own Chat files folder. Use the project file functions when asked to create, read, or edit a saved file.
- Upload to OpenRouter saves a device file under the project's path in Workspace files. Attach a workspace file to make a copy available to the hosted shell on the next message. Sandbox files can be copied to device files or saved to Workspace from their preview.
- When asked to create a subagent, custom tool, or skill, or enable a supported OpenRouter server tool, use the app's resource tools. After the user approves the change, check the tool result to confirm it was saved. The resource is available on the next message.
- Keep the initial model and follow-up models compatible with the requested tools and structured output. Feature availability is drawn from OpenRouter's model metadata and affected by provider routing.
- Cost and total-token thresholds are evaluated between steps. A step in flight can cross a threshold. The per-response output limit separately controls each model response.

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
