# OpenAI vs OpenRouter tools

How tool use differs when a chat runs on an OpenAI (direct) model with your own OpenAI key versus an
OpenRouter model. Checked against OpenAI's Responses API docs and OpenRouter's Responses API and
server-tool docs, November 2026.

## Same on both

| | OpenRouter | OpenAI |
|---|---|---|
| Function tools | `{type: "function", name, description, parameters, strict}` on `/api/v1/responses` | Same shape on `/v1/responses`, but `strict` defaults to true, which rejects optional fields; the app sends `strict: false` unless a tool asks for it |
| Tool calls and results | `function_call` items, answered with `function_call_output` | Same |
| Tool choice | `auto`, `none`, `required`, `{type:"function", name}` | Same |
| Parallel tool calls | `parallel_tool_calls` | Same |
| Streaming | SSE `response.*` events | Same event names |

Because the shapes match, every app tool that runs on the phone works unchanged with OpenAI models:
device time zone, mini apps, `load_skill`, custom tools, subagents, GitHub and project file functions,
and remote MCP (still asks before each call).

## Hosted (server-side) tools differ

| Job | OpenRouter | OpenAI |
|---|---|---|
| Web search | `openrouter:web_search` (any model; also `:online`) | `web_search` (supported OpenAI models only) |
| Code / shell | `openrouter:shell` hosted Linux sandbox, optional network allowlist | `code_interpreter` Python container (`container: {type:"auto"}`); no network allowlist |
| Images | `openrouter:image_generation` (pick any image model) | `image_generation` (OpenAI's image model; `size`, `quality`) |
| Read a page | `openrouter:web_fetch` | No equivalent; web search only |
| Date and time | `openrouter:datetime` | No equivalent; the app's device time zone tool covers it |
| Files you uploaded | OpenRouter workspace files (`openrouter:files`) | `file_search` over OpenAI vector stores (`vector_store_ids`) |
| Ask other models | `openrouter:advisor`, `openrouter:fusion`, model search, Decisions | None. The app's Decisions tool still runs on OpenAI chats through OpenRouter when an OpenRouter key is saved |
| Remote MCP | Run by the app (client-side, with approvals) | OpenAI can call MCP servers itself (`{type:"mcp"}`); not used here so the app keeps its approvals |
| Computer use | None | `computer_use_preview`; not added (needs a screen to drive) |

## Request options that are OpenRouter only

`provider` routing (order/only/ignore/sort/zdr/max price…), `models` fallbacks, `plugins`
(response healing, context compression), `session_id`, `stop_server_tools_when`, `top_k`, repetition
and frequency penalties, `image_config`, workspaces and guardrails. The app drops these from OpenAI
requests and marks the matching settings "OpenRouter only".

## Other differences that show in the app

- **Cost.** OpenRouter returns the cost of each response; OpenAI returns tokens only. Cost shows as
  unknown on OpenAI runs and the USD stop threshold can't trigger. Use the step limit.
- **Reasoning models.** OpenAI's o-series and GPT-5 models reject `temperature` and `top_p`; the app
  leaves them out for those models. Reasoning effort and summaries still apply.
- **History.** Requests use `store: false`. Hosted tool call items (searches, images, code runs) and
  encrypted reasoning items from earlier turns are not replayed to OpenAI; text, function calls and
  their results are. Switching a chat between OpenRouter and OpenAI models mid-conversation works.
- **Code interpreter files.** Files the container makes stay on OpenAI; the app doesn't download them yet.
- **Errors.** 401 means the OpenAI key is wrong, 429 means rate limit or no quota on the OpenAI account.

## How the Android app does it

`OpenAiDirect.kt` holds the translation. The bundled agent runtime builds its usual request; for
models with the `openai-direct/` prefix Kotlin rewrites the body (allowlisted fields, prefix removed,
`store: false`, hosted tools added), sends it to `https://api.openai.com/v1/responses` with the OpenAI
key and without OpenRouter's attribution headers, and streams the reply back. The WebView only ever
sees a placeholder key. Models come from `GET https://api.openai.com/v1/models`, filtered to chat models.
