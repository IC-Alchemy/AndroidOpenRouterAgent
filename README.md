# OpenRouter Agent SDK for Android

The app embeds OpenRouter’s official TypeScript Agent SDK in a bundled, isolated Android WebView. Agent requests go directly from the phone to OpenRouter. Kotlin handles Android UI, model discovery, credential storage, and sandbox file downloads.

## Run the app

1. Install the signed debug APK on Android 8.0 or later with a current Android System WebView. This build appears as OpenRouter Agent Studio and upgrades Studio 2.3 and imports its locally saved conversations. Enter your API key in Studio to connect it.
2. Tap **Connect OpenRouter**, enter an [OpenRouter API key](https://openrouter.ai/settings/keys), and save. The app loads the key’s allowed text models from `GET /api/v1/models/user`. Tap **Models** to search or refresh the list. When tools are enabled, the picker shows models that advertise tool calling.
3. Try “Use the sandbox to save the Python version to outputs/version.txt.” Follow the live response and tool activity. Open **Sandbox files** to preview and export the result.

SQLite stores saved chats on the phone, including OpenRouter agent state and tool results for follow-up turns. Android Keystore protects provider keys and the optional MCP token. Shell commands run in OpenRouter’s hosted container. Shell egress is off by default, and changing the allowlist starts a fresh container. OpenRouter’s [container retention](https://openrouter.ai/docs/guides/features/containers) applies to hosted files.

## Chats and projects

Open **Chats** in the header. Use **New chat** to start another conversation, or **New project** to create a group. Select a project before starting a chat to file it there automatically. Use the menu beside a chat to rename it, move it to a project, or delete it. Removing a project moves its chats to **Unfiled**.

Chats are scoped to the selected provider and credential. Reopening an OpenRouter chat restores its SDK conversation state, tool results, and hosted sandbox reference. The app imports the old saved conversation for each matching provider when you first open that provider. Storage stays on the device.

## Providers

Open **Configure → Providers** or tap the provider name in the header. The **OpenUncensored** preset uses `https://api.openuncensored.com/v1`, the model `deepseek-v4.1-flash-1017-uncensored`, and quality routing. Tap **Use**, enter your OpenUncensored API key, and tap **Save and use**.

Use **Add provider** to connect another HTTPS API that implements OpenAI-compatible Chat Completions. Enter the base URL or the full `/chat/completions` URL. **Load models** discovers models when the server exposes `GET /models`. Enter a model ID directly if discovery is unavailable. Each profile also has a streaming toggle, system instructions, and optional JSON request fields for provider-specific settings.

Custom providers support text chat and pinned skills. Select OpenRouter to use the Agent SDK, hosted sandbox, SDK tools, and on-demand skill discovery.

## Agent controls

- **Settings** controls hosted shell, search, date and time, web fetch, and the local device time-zone tool. The SDK dispatches local tools and feeds their results back to the model.
- **Advanced agent settings** includes a step limit, an optional USD stop threshold, and temperature for models that advertise that parameter. The SDK and provider check the threshold between steps. A step in progress can take the total above the threshold.
- The app supports one HTTPS remote MCP server with no authentication or a separate bearer token. The server must accept cross-origin requests from `https://agent-runtime.invalid`. The app shows the tool name, endpoint, and arguments before each call and offers **Allow once** or **Decline**. Tool discovery occurs on connection.
- **Additional server tools** accepts an array of SDK configuration objects using camelCase fields. Supported types include `openrouter:web_fetch`, `openrouter:image_generation`, `openrouter:experimental__search_models`, `openrouter:advisor`, `openrouter:subagent`, and `openrouter:fusion`. For example, `[{"type":"openrouter:advisor","parameters":{"model":"<allowed-model-id>"}}]`. Some tools require model-specific parameters or workspace permissions.
- **Stop waiting** aborts the local agent run and restores the draft. A submitted hosted tool may continue on OpenRouter.

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

The SDK reads the OpenRouter Responses stream through a native Android HTTP connection. The live status distinguishes connecting, waiting for the model, reasoning, tool execution, and streaming a reply. **Activity** shows provider reasoning summaries, tool status and results, citations, sandbox files, and reported token and cost totals as events arrive. Available events depend on the selected model and tools.

The stream connects within 10 seconds and fails after 90 seconds without any bytes. SSE keepalive comments count as activity. Change the idle limit (30–600 s) under **SDK settings → Stream idle timeout**. Connection failures and HTTP 408, 429 and 5xx responses that arrive before the stream starts are retried up to three times with exponential backoff and jitter. Retry-After is honoured up to 60 seconds. A stream that drops midway is not retried, because hosted tools may already have run. Errors include the request, model, HTTP status, OpenRouter request ID, bytes received and the last SSE event. The same line is logged under the `AgentHttp` logcat tag. The API key is never logged. Requests that ask OpenRouter itself to download or generate video are rejected before any network call. OpenRouter routes LLM requests, so name a video provider or a direct URL for that step.

The transcript follows the newest answer until you scroll back. Tap **Latest** to resume following. If you stop a run or the provider disconnects, the app keeps the partial reply in view. You can retry normally or retry with tools disabled for that request. Tool settings remain available for your next run.

## Customize your agent

Open the **Configure** gear. **Custom tools** registers validated SDK functions with a JSON object schema. Supported handlers are templates, questions answered in the app, and HTTPS JSON webhooks. Webhooks ask for approval before a request and publish a live progress event. They need a CORS-enabled endpoint. Tool results become part of the agent conversation.

**Skills & app guide** saves text instructions with a name and description. Import or export the Markdown `SKILL.md` form. The import uses the name, description, and Markdown body, and opens the skill disabled for review. A tool-capable model sees enabled skills by name and description and can load their full text on demand. Pin a skill to include its instructions on each run. The built-in app guide is readable offline.

**SDK settings** separates generation, routing, follow-up behavior, advanced fields, and display.

## OpenRouter Agent SDK coverage

OpenRouter chat runs start with `callModel` from the bundled official Agent SDK. The app uses its full-response and item streams together for live text, reasoning summaries, tool activity, sources, usage, and available sandbox files. Each chat commits the SDK's versioned conversation state and visible transcript together. New requests are appended by the SDK when a chat is reopened. A cancelled or failed turn keeps its partial reply for review and retry.

Advanced settings include SDK repetition detection, final-turn behavior, tool deadlines and concurrency, and stopping after a named function tool. Follow-up profiles use the SDK's dynamic model, instruction, and generation callbacks. Enabled skills can be discovered or pinned, and MCP and webhook calls request approval inside the app.

The Android app handles approvals during an open run. It does not currently expose detached background jobs, approval resumption after process death, SDK telemetry, or native image previews. Server tool availability depends on the selected model and OpenRouter workspace.

