---
name: openrouter-app-guide
description: Explain how the OpenRouter Agent SDK Android app works and how to tailor its models, tools, skills, streaming, routing, and run limits. Use for setup, troubleshooting, and customization questions.
compatibility: OpenRouter Agent SDK for Android 2.2 or later.
---
# OpenRouter Agent SDK for Android

Use these instructions to answer questions about the app's controls. Mention the Android screen labels that help the user find a feature.

## Get started

1. Open Settings and tap Sign in with OpenRouter, approve the key in the browser, return to the app, and tap Save. You can also paste a key. To use OpenAI models with your own OpenAI key, paste it under Settings → OpenAI (direct) and tap Save. You can use either key or both.
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

## OpenAI (direct) models

- With an OpenAI key in Settings → OpenAI (direct), Models also lists OpenAI's models as openai-direct/… (for example openai-direct/gpt-5). These runs go straight to api.openai.com with the OpenAI key and are billed by OpenAI. The OpenRouter key is never sent to OpenAI, and the OpenAI key is never sent to OpenRouter.
- The app's own tools work with OpenAI models: device time zone, mini apps, skills, custom tools, subagents, GitHub and project file functions, and remote MCP with approvals.
- OpenAI's built-in tools are switched in Settings → OpenAI (direct): web search (web_search), image generation (image_generation, using the aspect ratio and quality there), code interpreter (code_interpreter) and file search (file_search, with up to 10 vector store IDs like vs_abc123).
- These are OpenRouter only and are skipped for OpenAI models: the openrouter:… tools (hosted sandbox, web search, date and time, web fetch, advisor, fusion, model search), OpenRouter image generation, Workspace files, OpenRouter plugins, provider routing, fallback models, workspaces and guardrails. The Decisions tool and Configure → Decisions still work when an OpenRouter key is saved, and are billed by OpenRouter. Create with AI for skills, tools and mini apps needs an OpenRouter model.
- OpenAI doesn't report cost, so the cost line shows tokens only and the USD stop threshold can't stop an OpenAI run. Use the step limit instead.
- If OpenAI says the key is wrong, out of quota or not allowed a model, fix it on OpenAI's site. Test OpenAI key in Settings checks the key without spending anything.

## Work in the background

In version 2.5 and later, a run continues when the user switches apps, locks the phone, or closes the app from recents. The ongoing notification shows the current step and has Stop. Tool approvals can be answered there with Allow once or Deny. A notification shows the reply when a run finishes while the app is not on screen. A force stop or restart still ends the run.

## Mini apps and voice

Open Configure → Mini apps to view your installed mini apps in a launcher grid, or ask the agent in chat for a small tool, tracker, calculator, or game. Tap **Create with AI** to generate a self-contained `.miniapp` bundle (with an automatic name, theme color, and icon) from a plain-English description, or tap **Import** to add a `.miniapp` bundle or `.html` file from device storage (or share one from another app). Tap any icon to open it in its own window with Restart and Close controls. Hold an icon for options: **Edit with AI** (describe changes while keeping saved data), **Rename**, **Change Icon** (preset icon, emoji, gallery photo, or theme color), **Add to Home Screen**, **Export / Share** (share as a `.miniapp` bundle), **Save .miniapp File** (save a backup to device storage), **Clear App Data** (reset saved state without deleting the app), or **Delete**. Each mini app keeps its own private storage and can only reach public HTTPS APIs; imported or shared apps run offline with no internet access. Mini apps can be switched off in Settings.

Tap the microphone in the composer to dictate. The Ask agent Quick Settings tile opens the app ready to listen.

## Decisions

Open Configure → Decisions to have an OpenRouter decision model pick one of two to four options, answer yes or no, or score something on levels you list. Tap a round window to add text, a photo, a camera photo, a video, or audio; the chosen option moves to the large window on the right with its probabilities. Video and audio need a model that reads them, such as cloudflare/clef-omni. Settings → Decisions (openrouter_decide) lets the agent call the same decisions as a tool, including photos and recordings attached to the message. Each decision is billed.

## Side conversation

Tap the small side conversation button beside the message box (a panel icon), or type /side or /btw in the message box, to open a panel over the main chat and ask something separately. "/btw your question" or "/side your question" opens the panel and sends the question there straight away; nothing goes to the main chat. These commands also work while the main chat is running; other text typed then waits in the box until the run ends. The main chat keeps running underneath: a side message never waits for, stops or changes a main chat run. "Include main chat context" is on by default and sends a read-only copy of the open main chat (with a message still waiting for its reply) along with each side message; turn it off to ask without it. Side messages use the model chosen for the main chat, without tools, and nothing in the side conversation is added to the main chat. Close the panel by tapping outside it, with the close button or with Back; it keeps its messages until you tap New side conversation, which also turns the context toggle back on. The side conversation isn't saved with your chats. A dot on the button means a side reply is on its way or arrived while the panel was closed.

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

Use Activity to see the most recent stage. Runs have no fixed time limit: a run ends only when it finishes, you stop it, nothing happens for 10 minutes, or it reaches the 2-hour backstop. When a request fails in a way a new request can fix, such as a quiet or dropped stream or a server error, the app continues from the agent's saved progress, up to 3 times. A run that stops never removes the chat: your message and the partial reply stay, with Continue, Retry and Retry without tools. Continue asks the agent to carry on from where it stopped. Retry without tools sends the prompt with external and local tools disabled for that one request. For long jobs, ask the agent to work in small steps and keep a PROGRESS.md in the sandbox. Check the key and model if OpenRouter rejects the request, and review the allowed origins or CORS headers if a webhook or MCP connection fails.
