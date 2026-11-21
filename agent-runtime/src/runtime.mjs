import { OpenRouter, deserializeConversationState, serializeConversationState,
  hasToolCall, maxCost, maxTokensUsed, serverTool, stepCountIs, tool } from '@openrouter/agent';
import { HTTPClient } from '@openrouter/sdk/lib/http';
import { customTool, sdkParameters, skillLoader } from './customization.mjs';
import { streamObserver } from './stream-events.mjs';
import { phoneTools } from './phone.mjs';
import { createMCPTools } from '@openrouter/mcp';
import { z } from 'zod';

const allowedServerTools = new Set([
  'openrouter:web_fetch',
  'openrouter:image_generation',
  'openrouter:experimental__search_models',
  'openrouter:advisor',
  'openrouter:subagent',
  'openrouter:fusion',
]);

export function buildTools(options, events, signal) {
  const tools = [];
  if (options.sdk?.toolChoice === 'none') return tools;
  if (options.shell) {
    const environment = options.containerId
      ? { type: 'container_reference', containerId: options.containerId }
      : { type: 'container_auto' };
    if (!options.containerId && options.domains?.length) {
      environment.networkPolicy = { type: 'allowlist', allowedDomains: options.domains };
    }
    tools.push(serverTool({ type: 'openrouter:shell', parameters: { engine: 'openrouter', environment } }));
  }
  if (options.webSearch) tools.push(serverTool({ type: 'openrouter:web_search' }));
  if (options.dateTime) tools.push(serverTool({ type: 'openrouter:datetime' }));
  if (options.webFetch) tools.push(serverTool({ type: 'openrouter:web_fetch' }));
  if (options.deviceTools) {
    tools.push(tool({
      name: 'device_time_zone',
      description: 'Read the Android device time zone and UTC offset. Use when the user asks for local time or scheduling.',
      inputSchema: z.object({}),
      execute: async () => ({
        timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
        utcOffsetMinutes: -new Date().getTimezoneOffset(),
      }),
    }));
  }
  if (options.phoneControl && events?.phone) tools.push(...phoneTools(events.phone));
  const seen = new Set(tools.map(t => t.config?.type));
  for (const item of options.extraTools ?? []) {
    if (!item || !allowedServerTools.has(item.type)) throw new Error('Unsupported additional server tool.');
    if (seen.has(item.type)) throw new Error('Each additional server tool can appear once.');
    seen.add(item.type);
    tools.push(serverTool(item));
  }
  const skill = skillLoader(options);
  if (skill) tools.push(skill);
  for (const spec of options.customTools ?? []) tools.push(customTool(spec, events, signal));
  return tools;
}

export function buildCall(options, tools, approval, signal, observe) {
  const stopWhen = [stepCountIs(options.maxSteps)];
  const stopServerToolsWhen = [{ type: 'step_count_is', stepCount: options.maxSteps }];
  if (options.maxCost != null) {
    stopWhen.push(maxCost(options.maxCost));
    stopServerToolsWhen.push({ type: 'max_cost', maxCostInDollars: options.maxCost });
  }
  if (options.sdk?.maxRunTokens) {
    stopWhen.push(maxTokensUsed(options.sdk.maxRunTokens));
    stopServerToolsWhen.push({ type: 'max_tokens_used', maxTokens: options.sdk.maxRunTokens });
  }
  if (options.sdk?.stopOnTool) stopWhen.push(hasToolCall(options.sdk.stopOnTool));
  const finalResponse = options.sdk?.finalResponse;
  const webhooks = new Map((options.customTools ?? []).filter(tool => tool.handler === 'webhook')
    .map(tool => [tool.name, tool.value]));
  return {
    ...sdkParameters(options, tools.length > 0),
    input: [...(options.state ? [] : options.history), { type: 'message', role: 'user', content: options.prompt }],
    tools,
    signal,
    doomLoop: options.sdk?.detectRepetition !== false,
    allowFinalResponse: finalResponse === 'off' ? false :
      finalResponse === 'custom' ? options.sdk.finalDirective ?? '' : undefined,
    strictFinalResponse: options.sdk?.strictFinalResponse === true,
    toolTimeoutMs: options.sdk?.toolTimeoutSeconds ? options.sdk.toolTimeoutSeconds * 1000 : undefined,
    toolConcurrency: options.sdk?.toolConcurrency || undefined,
    stopWhen,
    stopServerToolsWhen,
    sessionId: options.sessionId,
    store: false,
    hooks: {
      PreToolUse: [{ handler: async ({ toolName, toolInput }) => {
        if (signal.aborted) return { block: 'The run was cancelled.' };
        const isMcp = toolName.startsWith('remote_');
        const endpoint = isMcp ? options.mcpUrl : webhooks.get(toolName);
        if (!endpoint) {
          observe?.toolStarted(toolName);
          return {};
        }
        const accepted = await approval(isMcp ? toolName.slice('remote_'.length) : toolName, toolInput, endpoint);
        if (accepted && !signal.aborted) {
          observe?.toolStarted(toolName);
          return {};
        }
        observe?.toolStarted(toolName, 'declined');
        return { block: 'The user declined this tool call.' };
      } }],
      PostToolUseFailure: [{ handler: ({ toolName, error }) => {
        observe?.toolStarted(toolName, 'failed', error?.message ?? String(error ?? 'Tool failed.'));
      } }],
      DoomLoopDetected: [{ handler: ({ message }) => { observe?.phase('repetition', message); } }],
    },
  };
}

function normalize(value) {
  if (Array.isArray(value)) return value.map(normalize);
  if (value == null || typeof value !== 'object') return value;
  return Object.fromEntries(Object.entries(value).map(([key, val]) => [
    key.replace(/[A-Z]/g, char => '_' + char.toLowerCase()), normalize(val),
  ]));
}

function eventLabel(item) {
  if (item.type === 'function_call') return item.name;
  if (item.type?.startsWith('openrouter:')) return item.type.slice('openrouter:'.length);
  return undefined;
}

export async function runAgent(options, events, signal, createClient = opts => new OpenRouter(opts)) {
  const tools = buildTools(options, events, signal);
  const observe = streamObserver(events);
  let mcp;
  let result;
  try {
    if (options.mcpUrl && options.sdk?.toolChoice !== 'none') {
      observe.phase('mcp');
      events.activity?.('Connecting to MCP');
      mcp = await createMCPTools({
        url: options.mcpUrl,
        auth: options.mcpToken ? { kind: 'bearer', token: options.mcpToken } : undefined,
        toolNamePrefix: 'remote_',
        resources: false,
        signal,
        fetch: (input, init) => fetch(input, { ...init, credentials: 'omit', redirect: 'error' }),
      });
      tools.push(...mcp.tools);
    }
    const client = createClient({
      apiKey: options.apiKey,
      appTitle: 'OpenRouter Sandbox Agent',
      ...(events.fetcher ? { httpClient: new HTTPClient({ fetcher: events.fetcher }) } : {}),
    });
    const loadedState = options.state ? deserializeConversationState(options.state) : null;
    let savedState = loadedState;
    const state = {
      load: async () => savedState,
      save: async value => { savedState = value; },
    };
    observe.phase('requesting');
    result = client.callModel({
      ...buildCall(options, tools, events.approval, signal, observe),
      state,
    }, { signal, retries: { strategy: 'none' } });
    events.register?.(result);
    const items = new Map();
    let itemNumber = 0;
    // Let the live consumers own the SDK run. Read the completed response after they drain.
    await Promise.all([
      (async () => {
        for await (const event of result.getFullResponsesStream()) observe.observe(event);
      })(),
      (async () => {
        for await (const item of result.getItemsStream()) {
          items.set(item.id ?? (item.callId ? item.type + ':' + item.callId : ++itemNumber), item);
          const name = eventLabel(item);
          if (name) events.activity?.(name);
        }
      })(),
    ]);
    observe.phase('finishing');
    const response = await result.getResponse();
    const [usage, finalState, repetition] = await Promise.all([
      result.getUsage(), result.getState(), result.getDoomLoopVerdict(),
    ]);
    for (const item of response.output ?? []) {
      const key = item.id ?? (item.callId ? item.type + ':' + item.callId : ++itemNumber);
      items.set(key, item);
    }
    const previousMessages = Array.isArray(loadedState?.messages) ? loadedState.messages : options.history;
    const history = Array.isArray(finalState.messages) && finalState.messages.length >= previousMessages.length + 1
      ? finalState.messages
      : [...previousMessages,
          { type: 'message', role: 'user', content: options.prompt },
          ...Array.from(items.values()).filter(item => item.type === 'message')];
    return {
      response: normalize({
        status: response.status,
        error: response.error,
        incompleteDetails: response.incompleteDetails,
        stoppedForRepetition: repetition != null,
        model: response.model,
        outputText: response.outputText,
        output: Array.from(items.values()),
        usage: {
          inputTokens: usage.inputTokens,
          outputTokens: usage.outputTokens,
          totalTokens: usage.totalTokens ?? usage.inputTokens + usage.outputTokens,
          cost: usage.cost,
          cachedTokens: usage.cachedTokens,
        },
      }),
      history,
      state: serializeConversationState({ ...finalState, messages: history }),
    };
  } finally {
    if (signal.aborted) await result?.cancel().catch(() => {});
    if (mcp) await mcp.close().catch(() => {});
  }
}
