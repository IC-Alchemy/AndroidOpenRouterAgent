# OpenRouter Agent SDK for Android

The app embeds OpenRouter’s official TypeScript Agent SDK in a bundled, isolated Android WebView. Agent requests go directly from the phone to OpenRouter. Kotlin handles Android UI, model discovery, credential storage, and sandbox file downloads.

## Run the app

1. Install the signed debug APK on Android 8.0 or later with a current Android System WebView. This build appears as OpenRouter Agent Studio and upgrades Studio 2.3 and imports its locally saved conversations. Enter your API key in Studio to connect it.
2. Open **Settings** and tap **Sign in with OpenRouter**. Approve the key on the OpenRouter page, return to the app, and tap **Save**. You can also paste an [OpenRouter API key](https://openrouter.ai/settings/keys) instead. The app loads the key’s allowed text models from `GET /api/v1/models/user`. Tap **Models** to search or refresh the list. When tools are enabled, the picker shows models that advertise tool calling.
3. Try “Use the sandbox to save the Python version to outputs/version.txt.” Follow the live response and tool activity. Open **Sandbox files** to preview and export the result.

SQLite stores saved chats on the phone, including OpenRouter agent state and tool results for follow-up turns. Android Keystore protects provider keys and the optional MCP token. Shell commands run in OpenRouter’s hosted container. Shell egress is off by default, and changing the allowlist starts a fresh container. OpenRouter’s [container retention](https://openrouter.ai/docs/guides/features/containers) applies to sandbox files.

## Sign in with OpenRouter

**Sign in with OpenRouter** uses OpenRouter's [OAuth PKCE flow](https://openrouter.ai/docs/guides/overview/auth/oauth). The app opens the OpenRouter authorization page in your browser. After you approve, the browser returns to a one-time listener on `127.0.0.1`, which closes after one sign-in or 10 minutes. The app checks the random `state`, exchanges the code with its PKCE verifier, and fills in the new key. Tap **Return to the app** on the success page, then **Save**. The key is stored with Android Keystore like a pasted key. Manage or revoke it in your OpenRouter key settings.

## Background runs

A run keeps going when you switch apps, lock the phone, or close the app from recents. While a run is active, a foreground service keeps the app from being frozen. The ongoing notification shows the current step and has a **Stop** button. When a tool needs approval, the notification offers **Allow once** and **Deny**. Questions from the agent open the app. When a run finishes while the app is not on screen, a notification shows the start of the reply. The app asks for notification permission when your first run starts.

A run still ends if Android stops the app, for example after a force stop or a restart. Its partial reply is not kept in that case. For a Google Play release, declare the `dataSync` foreground service type in Play Console.

## Mini apps

Ask for a small app, for example “make a habit tracker with a streak counter”. The agent builds one HTML file and saves it with the `mini_app_save` tool. The app checks the file before saving it. It needs a title and a viewport tag, all CSS and JavaScript must be inline, and it may not use external scripts, stylesheets, frames, form actions or redirects. Problems go back to the model so it can fix them.

Open **Configure → Mini apps** to open, update, delete or share an app, or add it to your home screen. Each app runs in its own isolated WebView with no access to the agent, your files, the camera, the microphone or your location. It keeps its own data in `localStorage` and can call public HTTPS APIs. To change an app, ask the agent; it reads the app and saves a new version under the same id, so the app's data stays. Apps shared with you, opened from a `.html` file, are saved without internet access and cannot be changed by the agent. Switch the tools off under **Settings → Mini apps**. They are skipped for models without tool calling.

## Voice

Tap the microphone in the composer to dictate a message. Android's speech recognizer is used, preferring on-device recognition. Add the **Ask agent** tile from Quick Settings to open the app straight into voice input.

## Chats and projects

Open **Chats** in the header. Use **New chat** to start another conversation, or **New project** to create a group. Select a project before starting a chat to file it there automatically. Use the menu beside a chat to rename it, move it to a project, or delete it. Removing a project moves its chats to **Unfiled**.

Chats are scoped to the selected provider and credential. Reopening an OpenRouter chat restores its SDK conversation state, tool results, and hosted sandbox reference. The app imports the old saved conversation for each matching provider when you first open that provider. Storage stays on the device.

### Work with files

- **Project files** opens an editable device folder shared by the project's chats. You can also open it from the selected project in **Chats**. Create folders and text files, import files from Android, and preview or export them. Unfiled chats have their own **Chat files** folder. **Configure → Device files** can reopen folders retained after a project or chat is removed.
- Ask a tool-capable OpenRouter model to list, read, create, or edit files in the current folder. For example, “Create `notes/plan.md` with a three-step plan,” then “Change the deadline in `notes/plan.md` to Friday.” The app writes the file and confirms the path. Text editing supports complete UTF-8 files up to 64 KiB.
- **Upload to OpenRouter** publishes a device file through the Files API. Uploaded project files appear under `projects/<project-id>/` with their relative folders preserved. **Workspace files** lists those files within the project. **Configure → All workspace files** opens the account's full list from a project. Open a workspace file to preview, export, copy, delete, or edit it as a new revision. OpenRouter makes server-generated files downloadable. The app retains a local copy of each upload made from this device. [OpenRouter Files API](https://openrouter.ai/docs/guides/features/files-api)
- Use **Attach to chat** on a workspace file to give the hosted shell a writable copy on the next message. Up to 20 files may be attached per message. **Sandbox files** opens the hosted container. Each project has a separate sandbox shared by its chats. From a sandbox preview, use **Copy to device files** to edit a local copy, or **Save to Workspace** to keep it through the Files API. Ask in chat to edit the original sandbox file. [OpenRouter containers](https://openrouter.ai/docs/guides/features/containers)

**Workspace files tool** in Settings gives unfiled OpenRouter chats the hosted `openrouter:files` tool. OpenRouter scopes that tool to the entire workspace. Project chats use app file functions scoped to their project folder. [OpenRouter Files server tool](https://openrouter.ai/docs/guides/features/server-tools/files)

## Providers

Open **Configure → Providers** or tap the provider name in the header. The **OpenUncensored** preset uses `https://api.openuncensored.com/v1`, the model `deepseek-v4.1-flash-1017-uncensored`, and quality routing. Tap **Use**, enter your OpenUncensored API key, and tap **Save and use**.

With OpenUncensored selected, tap **Generate images** above the message field. Choose a model from the live catalog, enter a prompt, and select a size and 1–4 images. The dialog shows the estimated cost before you generate. Preview the results and tap **Save image** to keep a copy on your device.

Use **Add provider** to connect another HTTPS API that implements OpenAI-compatible Chat Completions. Enter the base URL or the full `/chat/completions` URL. **Load models** discovers models when the server exposes `GET /models`. Enter a model ID directly if discovery is unavailable. Each profile also has a streaming toggle, system instructions, and optional JSON request fields for provider-specific settings.

Custom providers support text chat and pinned skills. Select OpenRouter to use the Agent SDK, hosted sandbox, SDK tools, and on-demand skill discovery.

## Agent controls

- **Settings** controls hosted shell, search, date and time, web fetch, and the local device time-zone tool. The SDK dispatches local tools and feeds their results back to the model.
- **Advanced agent settings** includes a step limit, an optional USD stop threshold, and temperature for models that advertise that parameter. The SDK and provider check the threshold between steps. A step in progress can take the total above the threshold.
- The app supports one HTTPS remote MCP server with no authentication or a separate bearer token. The server must accept cross-origin requests from `https://agent-runtime.invalid`. The app shows the tool name, endpoint, and arguments before each call and offers **Allow once** or **Decline**. Tool discovery occurs on connection.
- **Additional server tools** accepts an array of SDK configuration objects using camelCase fields. Supported types include `openrouter:web_fetch`, `openrouter:image_generation`, `openrouter:experimental__search_models`, `openrouter:advisor`, `openrouter:subagent`, and `openrouter:fusion`. For example, `[{"type":"openrouter:advisor","parameters":{"model":"<allowed-model-id>"}}]`. Existing subagent entries in this field also appear in the Subagents editor.
- **Stop waiting** aborts the local agent run and restores the draft. A submitted hosted tool may continue on OpenRouter.

### Delegate work to subagents

1. Select OpenRouter as the provider. Open **Settings → OpenRouter Server Tools → Subagents → Add subagent**.
2. Enter an optional name, worker model ID, and instructions. You can add several uniquely named workers and one unnamed default worker. If you leave the model blank, OpenRouter uses the main model.
3. Tap **Apply**, then **Save** in Settings. In chat, try asking the main model to delegate a focused task, such as extracting action items from meeting notes and returning them as a checklist. The model decides when to delegate. **Activity** shows each worker's task, status, result or error, and any inherited function calls.

Use **Advanced worker settings** to set `maxToolCalls` (1–25), `maxCompletionTokens`, `temperature` (0–2), or `reasoning` (`effort` and `maxTokens`). Add hosted tools in `tools`, for example `{"tools":[{"type":"openrouter:web_search"}],"maxToolCalls":3}`. Parameters nested inside those tool definitions use their own API field names.

The experimental `inheritedFunctionNames` list gives the worker access to specific enabled Android function tools by name. For example, use `{"inheritedFunctionNames":["device_time_zone"]}` with the local time-zone tool enabled. Use `"inheritFunctions":true` to inherit all the functions available in the request. Inherited webhook and MCP calls still ask for approval in the app. OpenRouter resumes the worker after Android returns the tool result.

The worker sees the task description supplied by the main model. Worker usage is included in the run's reported totals and billed at the worker model's rates. Workspace tool permissions apply. [OpenRouter Subagent documentation](https://openrouter.ai/docs/guides/features/server-tools/subagent)

## Build and test

Use the latest signed Studio APK as the baseline for changes. Compare its bundled `assets/agent/agent.js` with the source package and run the bundled SDK bridge smoke test before revising the runtime.

Open the project in Android Studio with JDK 17 or later and Android SDK 35. The prebuilt Agent SDK assets are checked in so a normal Android Studio build needs no Node step.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

To rebuild the bundled runtime or run its tests, use Node.js 22 or later.

```sh
cd agent-runtime
npm ci
npm run build
npm test
```

The APK builds to `app/build/outputs/apk/debug/app-debug.apk`. The mock tests cover account-filtered model discovery, streamed text, hosted shell and file handling, local tool execution, MCP approval and denial, and tool history across turns. Live verification requires an OpenRouter API key.

API references [Agent SDK](https://openrouter.ai/docs/agent-sdk/call-model/overview) · [Account-filtered models](https://openrouter.ai/docs/api/api-reference/models/list-models-filtered-by-user-provider-preferences-privacy-settings-and-guardrails) · [Remote MCP tools](https://openrouter.ai/docs/agent-sdk/call-model/mcp-tools) · [Hosted shell](https://openrouter.ai/docs/guides/features/server-tools/shell)

## Streaming and recovery

The SDK reads the OpenRouter Responses stream through a native Android HTTP connection. The live status distinguishes connecting, waiting for the model, reasoning, tool execution, and streaming a reply, and counts output tokens and tokens per second as they arrive. Before the provider reports usage, the count is an estimate marked with `~`.

The reply streams as a timeline in the order the model works. **Thinking** blocks show the model's reasoning as it streams. This is the raw reasoning text when the provider sends it, otherwise the reasoning summary. A thinking block collapses once the model moves on. Tap it to read the whole thing. Each tool call gets a row with a spinner while it runs. Its input streams into the row while the model writes the arguments, and the result replaces the input when the tool finishes. Tap a row to see everything. **Activity** also lists tool status and results, citations, sandbox files, and reported token and cost totals. Available events depend on the selected model and tools.

Turn reasoning on or off with **Show reasoning** in the settings menu, or with **Hide** and **Show reasoning** on a live thinking block. The switch takes effect at once, including during a run, and also applies to saved replies. Hidden reasoning still shows a *Thinking…* line so you can see the model working. While reasoning is shown, the app asks reasoning models for an `auto` reasoning summary, because some models (OpenAI's) stream nothing without one. That request can make some models reason where they would not by default, which uses more tokens. Choose a summary level under **SDK settings → Request reasoning summaries**, or set **Reasoning effort** to `off` to stop reasoning altogether. Models that reason privately show *Thinking privately* and a reasoning token count. Reasoning is saved with each reply, up to 48,000 characters, so turning it back on shows it for past replies. Fusion runs show one row per panel model, with that panel's streamed answer. OpenAI-compatible providers that stream `reasoning_content` or `reasoning` deltas are shown the same way.

The stream connects within 10 seconds and fails after 90 seconds without any bytes. SSE keepalive comments count as activity. Change the idle limit (30–600 s) under **SDK settings → Stream idle timeout**. Connection failures and HTTP 408, 429 and 5xx responses that arrive before the stream starts are retried up to three times with exponential backoff and jitter. Retry-After is honoured up to 60 seconds. A stream that drops midway is not retried, because hosted tools may already have run. Errors include the request, model, HTTP status, OpenRouter request ID, bytes received and the last SSE event. The same line is logged under the `AgentHttp` logcat tag. The API key is never logged. Requests that ask OpenRouter itself to download or generate video are rejected before any network call. OpenRouter routes LLM requests, so name a video provider or a direct URL for that step.

The transcript follows the newest answer until you scroll back. Tap **Latest** to resume following. If you stop a run or the provider disconnects, the app keeps the partial reply in view. You can retry normally or retry with tools disabled for that request. Tool settings remain available for your next run.

Touch and hold to select text in chat messages, tool output, file previews, or the message you’re composing. Adjust the Android selection handles and the selected text copies automatically. Use **Copy reply** to copy the entire response at once.

## Customize your agent

Open the **Configure** gear. **Custom tools** registers validated SDK functions with a JSON object schema. Supported handlers are templates, questions answered in the app, and HTTPS JSON webhooks. Webhooks ask for approval before a request and publish a live progress event. They need a CORS-enabled endpoint. Tool results become part of the agent conversation.

**Skills & app guide** saves text instructions with a name and description. Import or export the Markdown `SKILL.md` form. The import uses the name, description, and Markdown body, and opens the skill disabled for review. A tool-capable model sees enabled skills by name and description and can load their full text on demand. Pin a skill to include its instructions on each run. The built-in app guide is readable offline.

You can also ask in chat to create a subagent, custom tool, or skill, or to enable a supported OpenRouter server tool. For example, “Create a subagent named Editor to review Markdown for clarity.” Review the proposed settings and tap **Save change**. The app validates and saves the resource to the same settings used by the configuration screens. It becomes available on the next message. Custom tools can use templates, ask for an answer, or POST to a fixed HTTPS webhook that requires approval when called.

**SDK settings** separates generation, routing, follow-up behavior, advanced fields, and display.

## OpenRouter Agent SDK coverage

OpenRouter chat runs start with `callModel` from the bundled official Agent SDK. The app uses its full-response and item streams together for live text, reasoning summaries, tool activity, sources, usage, and available sandbox files. Each chat commits the SDK's versioned conversation state and visible transcript together. New requests are appended by the SDK when a chat is reopened. A cancelled or failed turn keeps its partial reply for review and retry.

Advanced settings include SDK repetition detection, final-turn behavior, tool deadlines and concurrency, and stopping after a named function tool. Follow-up profiles use the SDK's dynamic model, instruction, and generation callbacks. Enabled skills can be discovered or pinned, and MCP and webhook calls request approval inside the app.

The Android app handles approvals during a run, including from the background notification. It does not currently resume a run after the process dies. SDK telemetry and previews of images from OpenRouter server tools are unavailable. Server tool availability depends on the selected model and OpenRouter workspace.

