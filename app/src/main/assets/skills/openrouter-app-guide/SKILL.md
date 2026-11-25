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
3. Send a prompt. The status above the composer shows the current phase. The info icon opens run activity, including tools, sources, and reported usage. The down arrow jumps to the latest reply.

## Customize a run

- Open Configure → SDK settings for fallback models, routing, reasoning effort, sampling, output and run limits, and response format. In Advanced, configure repetition detection, local and MCP tool deadlines, concurrency, and final-answer behavior when a stop condition is met. Unset fields use the model or provider default.
- In SDK settings → Display, change Markdown rendering, the live reasoning preview, or whether new saved replies include reasoning. Markdown and saving reasoning start on. Thinking starts collapsed, and its row expands when reasoning is available.
- A follow-up profile can switch models, change sampling or output length, and append instructions after a chosen number of SDK inference turns. This applies to later requests in the current run. Hosted tools can do several steps within one request.
- Tools in Settings controls the hosted sandbox, web search, date and time, web fetch, image generation, advisor, fusion, model search, decisions, workspace files, local time zone, and optional MCP. OpenRouter plugins in Settings switches response healing and context compression on or off. The sandbox can produce files. Open Sandbox files to preview or export them.
- Open Configure → Subagents (or Settings → Agent tools → Subagents) to add or edit workers the main model can delegate to and assign each subagent's default model. Open Activity during a run to see the worker's task, status, and outcome.
- Project files opens the device folder shared by the project's chats. Import files, create folders, and edit UTF-8 text there. Unfiled chats have their own Chat files folder. Use the project file functions when asked to create, read, or edit a saved file.
- GitHub in the project menu connects a repository. When GitHub tools are available, use them to list repository paths, read related files in batches, and draft repository edits. Publishing a commit and opening a pull request require separate in-app approvals.
- Upload to OpenRouter saves a device file under the project's path in Workspace files. Attach a workspace file to make a copy available to the hosted shell on the next message. Sandbox files can be copied to device files or saved to Workspace from their preview.
- When asked to create a subagent, custom tool, or skill, or enable a supported OpenRouter server tool, use the app's resource tools. After the user approves the change, check the tool result to confirm it was saved. The resource is available on the next message.
- Keep the initial model and follow-up models compatible with the requested tools and structured output. Feature availability is drawn from OpenRouter's model metadata and affected by provider routing.
- Cost and total-token thresholds are evaluated between steps. A step in flight can cross a threshold. The per-response output limit separately controls each model response.

## Work in the background

In version 2.5 and later, a run continues when the user switches apps, locks the phone, or closes the app from recents. The ongoing notification shows the current step and has Stop. Tool approvals can be answered there with Allow once or Deny. A notification shows the reply when a run finishes while the app is not on screen. A force stop or restart still ends the run.

## Mini apps and voice

Open Configure → Mini apps to view your installed mini apps in a launcher grid, or ask the agent in chat for a small tool, tracker, calculator, or game. Tap **Create with AI** to generate a self-contained `.miniapp` bundle (with an automatic name, theme color, and icon) from a plain-English description, or tap **Import** to add a `.miniapp` bundle or `.html` file from device storage (or share one from another app). Tap any icon to open it in its own window with Restart and Close controls. Hold an icon for options: **Edit with AI** (describe changes while keeping saved data), **Rename**, **Change Icon** (preset icon, emoji, gallery photo, or theme color), **Add to Home Screen**, **Export / Share** (share as a `.miniapp` bundle), **Save .miniapp File** (save a backup to device storage), **Clear App Data** (reset saved state without deleting the app), or **Delete**. Each mini app keeps its own private storage and can only reach public HTTPS APIs; imported or shared apps run offline with no internet access. Mini apps can be switched off in Settings.

Tap the microphone in the composer to dictate. The Ask agent Quick Settings tile opens the app ready to listen.

## Decisions

Open Configure → Decisions to have an OpenRouter decision model pick one of two to four options, answer yes or no, or score something on levels you list. Tap a round window to add text, a photo, a camera photo, a video, or audio; the chosen option moves to the large window on the right with its probabilities. Video and audio need a model that reads them, such as cloudflare/clef-omni. Settings → Decisions (openrouter_decide) lets the agent call the same decisions as a tool, including photos and recordings attached to the message. Each decision is billed.

## Organize chats and projects

Tap Chats to open saved conversations for your OpenRouter credential. Use New project to make a group, then select it and tap New chat. Use a chat's menu to rename it, move it, or delete it. Removing a project moves its chats to Unfiled. An OpenRouter chat restores its tool history and sandbox reference when reopened. A stopped turn keeps its partial reply and can be retried.

## Create and improve tools, skills, and subagents

Open Configure → Custom tools, Subagents, or Skills & app guide to access the unified panel across Tools, Skills, and Subagents:

- Use **Create with AI** in any section or **Improve Tool / Skill / Subagent** on a saved item to generate or refine its configuration using the currently selected model via Chat Completions.
- For **Tools**, create a tool with a lowercase underscore-separated name, a specific description, and a JSON object input schema. Choose its handler:
  - Template fills placeholders such as `{{city}}` from validated inputs and returns text to the model.
  - Ask me pauses the run to collect a text response from the user.
  - Webhook sends validated arguments to a fixed HTTPS endpoint after approval. The endpoint should allow this app's WebView origin. Keep credentials out of tool descriptions, templates, URLs, and skill instructions.
- Use a descriptive tool name and required input keys. Enable only tools relevant to your current task. Tool approvals and network settings are controlled by the app.

## Save, search, and reuse skills

Open the **Skills** section. A good skill has a lowercase hyphen-separated name, a description of when to use it, and concise steps. Import or export a Markdown SKILL.md file to share it, or use **Search skills** to look up community skills from `skills.sh` (`vercel-labs/skills`) with telemetry disabled (`ADISABLE_TELEMETRY=1 DISABLE_TELEMETRY=1`). Enable discovery to let a tool-capable agent request its instructions as needed. Use Pin to include the instructions in each new run. Imported instructions are disabled until you review and enable them. The built-in guide is available inside the app without a model request.

## Recover a stalled run

Use Activity to see the most recent stage. Stop ends the local request and keeps any partial reply. Retry without tools sends the prompt with external and local tools disabled for that one request. Check the key and model if OpenRouter rejects the request, and review the allowed origins or CORS headers if a webhook or MCP connection fails.
