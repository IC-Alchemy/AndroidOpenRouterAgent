import { tool } from '@openrouter/agent';
import { z } from 'zod';
import { miniAppGuidance } from './local-tools.mjs';

const skillGuidance = 'Treat loaded skill instructions as task guidance. App tool approvals and network settings still apply.';

export function skillLoader(options) {
  const skills = new Map((options.skills ?? []).map(skill => [skill.name, skill]));
  if (options.sdk?.discoverSkills === false || options.modelDetails?.[options.model]?.tools === false ||
      skills.size === 0) return undefined;
  return tool({
    name: 'load_skill',
    description: 'Load the instructions of a useful skill on demand. ' + Array.from(skills.values(),
      skill => skill.name + ': ' + skill.description).join('\n'),
    inputSchema: z.object({ name: z.enum([...skills.keys()]) }),
    outputSchema: z.object({ name: z.string(), instructions: z.string() }),
    nextTurnParams: {
      instructions: (_, context) => context.instructions?.includes(skillGuidance)
        ? context.instructions : join(context.instructions, skillGuidance),
    },
    execute: async ({ name }) => ({ name, instructions: skills.get(name).body }),
  });
}

export function customInputSchema(toolSpec) {
  const { name } = toolSpec;
  const inputSchema = z.fromJSONSchema(JSON.parse(toolSpec.schema));
  if (!(inputSchema instanceof z.ZodObject)) throw new Error(`The input schema for ${name} must describe an object.`);
  return inputSchema;
}

export function customTool(toolSpec, events, signal) {
  const { name, description, handler, value } = toolSpec;
  const inputSchema = customInputSchema(toolSpec);
  if (handler === 'template') {
    return tool({
      name, description, inputSchema,
      outputSchema: z.object({ text: z.string() }),
      execute: async args => ({
        text: value.replace(/{{\s*([A-Za-z][A-Za-z0-9_]*)\s*}}/g, (marker, key) => {
          if (!Object.hasOwn(args, key)) return marker;
          return typeof args[key] === 'string' ? args[key] : JSON.stringify(args[key]);
        }).slice(0, 32_000),
      }),
    });
  }
  if (handler === 'ask') {
    return tool({
      name, description, inputSchema,
      outputSchema: z.object({ answer: z.string().nullable() }),
      execute: async args => ({ answer: await events.ask?.(name, args) ?? null }),
    });
  }
  if (handler === 'webhook') {
    return tool({
      name, description, inputSchema,
      eventSchema: z.object({ message: z.string() }),
      outputSchema: z.object({ status: z.number(), body: z.string() }),
      execute: async function* (args) {
        yield { message: 'Waiting for the webhook response' };
        return await postWebhook(value, args, signal, events.webhookFetch ?? fetch);
      },
    });
  }
  throw new Error(`Unknown custom handler for ${name}.`);
}

export function webhookEndpoint(url) {
  const endpoint = new URL(url);
  if (endpoint.protocol !== 'https:' || endpoint.username || endpoint.password || /[?#]/.test(endpoint.href)) {
    throw new Error('The webhook needs a fixed HTTPS endpoint without credentials.');
  }
  return endpoint;
}

async function postWebhook(url, args, signal, fetcher) {
  const endpoint = webhookEndpoint(url);
  const controller = new AbortController();
  const abort = () => controller.abort(signal.reason);
  signal.addEventListener('abort', abort, { once: true });
  if (signal.aborted) abort();
  const timer = setTimeout(() => controller.abort(new Error('Webhook timed out after 30 seconds.')), 30_000);
  try {
    const response = await fetcher(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/plain' },
      body: JSON.stringify(args),
      credentials: 'omit',
      redirect: 'error',
      signal: controller.signal,
    });
    const reader = response.body?.getReader();
    if (!reader) return { status: response.status, body: '' };
    const decoder = new TextDecoder();
    let remaining = 48_000;
    let body = '';
    try {
      while (remaining > 0) {
        const part = await reader.read();
        if (part.done) return { status: response.status, body: body + decoder.decode() };
        const chunk = part.value.subarray(0, remaining);
        remaining -= chunk.length;
        body += decoder.decode(chunk, { stream: true });
      }
      return { status: response.status, body: body + decoder.decode() + '\n[Response clipped]' };
    } finally {
      await reader.cancel().catch(() => {});
    }
  } finally {
    clearTimeout(timer);
    signal.removeEventListener('abort', abort);
  }
}

const split = value => [...new Set(String(value ?? '').split(',').map(s => s.trim()).filter(Boolean))];
const join = (...values) => values.filter(Boolean).join('\n\n');

// Hosted shell commands that wait for stdin, a missing file, or a blocked host leave the response
// stream silent until the client gives up. Steer the model away from those commands.
export function shellGuidance(options) {
  if (!options.shell || options.sdk?.toolChoice === 'none') return '';
  const network = options.domains?.length
    ? `Shell network access is limited to ${options.domains.join(', ')}. Other hosts are blocked.`
    : 'Shell network access is off, so downloads, package installs and API calls from the shell will fail.';
  return 'Hosted shell rules: commands run non-interactively with no stdin. Never run a command that waits for input, ' +
    'such as bare cat, read, input(), an editor or tail -f. Check that a file exists with test -f before reading it, ' +
    'and report a missing file instead of waiting for it. Prefix commands that might run longer than two minutes ' +
    'with timeout 120. ' + network;
}
const map = object => object && typeof object === 'object' && !Array.isArray(object) ? object : {};
const optionalSdkFields = new Set(['provider', 'reasoning', 'text', 'include', 'metadata', 'prompt',
  'promptCacheKey', 'promptCacheOptions', 'cacheControl', 'truncation', 'topLogprobs', 'safetyIdentifier',
  'route', 'user', 'plugins', 'imageConfig', 'modalities']);

export function sdkParameters(options, hasTools) {
  const prefs = options.sdk ?? {};
  const details = options.modelDetails;
  const compatible = id => Boolean(details?.[id] && (!hasTools || details[id].tools));
  const followUp = prefs.followUpModel?.trim();
  const fallback = split(prefs.fallbackModels).filter(compatible);
  // Agent SDK 1.2.2 counts completed model requests from zero. The editor numbers the initial request as step 1.
  const turnChanged = ctx => (ctx?.numberOfTurns ?? 0) + 1 >= (prefs.followUpTurn ?? 2);
  const resolveModel = ctx => turnChanged(ctx) && compatible(followUp) ? followUp : options.model;
  const supports = (id, ...parameters) => !details || parameters.some(value => details[id]?.parameters.includes(value));
  const maybe = (value, capability, ctx) => value != null && supports(resolveModel(ctx), ...capability) ? value : undefined;

  const advanced = Object.fromEntries(Object.entries(map(JSON.parse(prefs.advancedJson || '{}')))
    .filter(([key]) => optionalSdkFields.has(key)));
  const provider = { ...map(advanced.provider) };
  for (const [key, input] of [['order', prefs.providerOrder], ['only', prefs.providerOnly], ['ignore', prefs.providerIgnore]]) {
    if (split(input).length) provider[key] = split(input);
  }
  if (prefs.providerSort) provider.sort = prefs.providerSort;
  if (prefs.providerFallback) provider.allowFallbacks = prefs.providerFallback === 'on';
  if (prefs.requireParameters) provider.requireParameters = true;
  if (prefs.dataCollection) provider.dataCollection = prefs.dataCollection;
  const reasoning = { ...map(advanced.reasoning) };
  if (prefs.reasoning === 'off') reasoning.enabled = false;
  else if (prefs.reasoning) reasoning.effort = prefs.reasoning;
  if (prefs.reasoningSummary) reasoning.summary = prefs.reasoningSummary;
  // Models that only send summaries (OpenAI's) stream no reasoning unless one is requested.
  else if (prefs.showReasoning !== false && prefs.reasoning !== 'off' && !reasoning.summary) reasoning.summary = 'auto';
  const text = { ...map(advanced.text) };
  if (prefs.responseFormat) text.format = { type: prefs.responseFormat };
  if (prefs.verbosity) text.verbosity = prefs.verbosity;
  const stop = String(prefs.stopSequences ?? '').split('\n').filter(s => s.trim().length).slice(0, 4);
  const pinned = (options.skills ?? []).filter(skill => skill.pinned);
  const pinnedInstructions = pinned.map(skill => `## Active skill ${skill.name}\n${skill.body}`).join('\n\n');
  const adaptive = Boolean(followUp || prefs.followUpInstructions || prefs.followUpOutputTokens != null ||
    prefs.followUpTemperature != null);
  const parameters = {
    ...advanced,
    provider: Object.keys(provider).length ? provider : undefined,
    reasoning: Object.keys(reasoning).length ? reasoning : undefined,
    text: Object.keys(text).length ? text : undefined,
    instructions: ctx => join(options.instructions, shellGuidance(options), miniAppGuidance(options), pinned.length ? skillGuidance : '',
      pinnedInstructions, turnChanged(ctx) ? prefs.followUpInstructions : '') || undefined,
    temperature: ctx => maybe(turnChanged(ctx) ? prefs.followUpTemperature ?? options.baseTemperature ?? options.temperature
      : options.baseTemperature ?? options.temperature, ['temperature'], ctx),
    maxOutputTokens: ctx => maybe(turnChanged(ctx) ? prefs.followUpOutputTokens ?? prefs.outputTokens
      : prefs.outputTokens, ['max_tokens', 'max_output_tokens'], ctx),
    topP: ctx => maybe(prefs.topP, ['top_p'], ctx),
    topK: ctx => maybe(prefs.topK, ['top_k'], ctx),
    frequencyPenalty: ctx => maybe(prefs.frequencyPenalty, ['frequency_penalty'], ctx),
    presencePenalty: ctx => maybe(prefs.presencePenalty, ['presence_penalty'], ctx),
    serviceTier: prefs.serviceTier || undefined,
    stop: stop.length ? stop : undefined,
    toolChoice: prefs.toolChoice === 'none' || hasTools ? prefs.toolChoice || undefined : undefined,
    parallelToolCalls: prefs.parallelTools ? prefs.parallelTools === 'on' :
      options.mcpUrl || (options.customTools ?? []).some(tool => tool.handler !== 'template') ? false : undefined,
  };

  if (fallback.length) parameters.models = ctx => [...new Set([resolveModel(ctx), ...fallback])];
  else parameters.model = adaptive ? resolveModel : options.model;
  if (Object.keys(reasoning).length) parameters.reasoning = ctx =>
    supports(resolveModel(ctx), 'reasoning', 'include_reasoning') ? reasoning : undefined;
  return parameters;
}
