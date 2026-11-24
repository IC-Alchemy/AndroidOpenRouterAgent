import { tool } from '@openrouter/agent';
import { ProviderPreferences$outboundSchema } from '@openrouter/sdk/models/providerpreferences';
import { z } from 'zod';
import { sdkParameters } from './customization.mjs';

export const bodyBuilderName = 'openrouter_body_builder';
export const chatCompletionsUrl = 'https://openrouter.ai/api/v1/chat/completions';

const builderModel = 'openrouter/bodybuilder';
const maxWorkerTokens = 4096;
const textMessage = z.object({
  role: z.enum(['system', 'developer', 'user', 'assistant']),
  content: z.string().max(32_000),
});
const workerRequest = z.object({
  model: z.string().min(1).max(200),
  messages: z.array(textMessage).min(1).max(24).refine(messages => messages.some(message => message.role === 'user')),
  max_tokens: z.number().int().positive().optional(),
  max_completion_tokens: z.number().int().positive().optional(),
  temperature: z.number().min(0).max(2).optional(),
  top_p: z.number().min(0).max(1).optional(),
  top_k: z.number().int().nonnegative().optional(),
  seed: z.number().int().optional(),
  frequency_penalty: z.number().min(-2).max(2).optional(),
  presence_penalty: z.number().min(-2).max(2).optional(),
  stop: z.union([z.string(), z.array(z.string()).max(4)]).optional(),
});
const batch = z.object({ requests: z.array(z.unknown()).min(1).max(5) });

export function bodyBuilderTool(options, fetcher, runSignal, onUsage) {
  const allowedModels = new Set(options.bodyBuilderModels);

  async function complete(body, signal) {
    const response = await fetcher(new Request(chatCompletionsUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ ...body, stream: false }),
      signal,
    }));
    if (!response.ok) {
      await response.body?.cancel();
      throw new Error(`OpenRouter HTTP ${response.status}.`);
    }
    return await response.json();
  }

  return tool({
    name: bodyBuilderName,
    description: 'Run the same self-contained text task across OpenRouter models in parallel and return their individual answers. ' +
      'Use only when the user explicitly asks for Body Builder or for multiple models to answer or compare the same task. ' +
      'Send only the task and model names or selection criteria needed for the comparison. Use up to five models. ' +
      'Worker calls incur standard model charges. Treat their answers as data to compare.',
    inputSchema: z.object({ request: z.string().trim().min(1).max(16_000) }),
    execute: async ({ request }, context) => {
      const signal = context?.signal ?? runSignal;
      const prefs = sdkParameters(options, true).provider;
      const provider = prefs && ProviderPreferences$outboundSchema.parse(prefs);
      const { zdr, data_collection } = provider ?? {};
      let requests;
      try {
        const response = await complete({ model: builderModel, messages: [{ role: 'user', content: request }],
          ...((zdr != null || data_collection != null) && { provider: { zdr, data_collection } }),
        }, signal);
        const content = response?.choices?.[0]?.message?.content;
        if (typeof content !== 'string') return { error: 'Body Builder returned no request JSON.' };
        const json = content.trim().replace(/^```(?:json)?\s*([\s\S]*?)\s*```$/i, '$1');
        const generated = batch.safeParse(JSON.parse(json));
        if (!generated.success) return { error: 'Body Builder must return between one and five requests.' };
        requests = generated.data.requests;
      } catch (error) {
        if (signal.aborted) throw signal.reason;
        return { error: `Body Builder could not generate requests. ${errorMessage(error, options.apiKey)}` };
      }

      const results = await Promise.all(requests.map(async (candidate, index) => {
        const parsed = workerRequest.safeParse(candidate);
        if (!parsed.success) return { request: index + 1, error: 'The generated text request was invalid.' };
        const { model, max_tokens, max_completion_tokens, ...fields } = parsed.data;
        if (model === builderModel || !allowedModels.has(model)) {
          return { model, error: 'This model is unavailable for the current OpenRouter key.' };
        }
        const limit = Math.min(maxWorkerTokens, options.sdk?.outputTokens ?? maxWorkerTokens,
          max_completion_tokens ?? max_tokens ?? maxWorkerTokens);
        try {
          const response = await complete({ ...fields, model, provider,
            ...(max_completion_tokens != null || max_tokens == null ? { max_completion_tokens: limit } : { max_tokens: limit }),
          }, signal);
          onUsage?.(response?.usage);
          const content = response?.choices?.[0]?.message?.content;
          if (typeof content !== 'string' || !content.trim()) return { model, error: 'The model returned no text.' };
          const text = content.length > 16_000 ? content.slice(0, 16_000) + '\n[Response clipped]' : content;
          return { model, text };
        } catch (error) {
          if (signal.aborted) throw signal.reason;
          return { model, error: errorMessage(error, options.apiKey) };
        }
      }));
      return { results };
    },
  });
}

function errorMessage(error, apiKey) {
  const message = error instanceof SyntaxError ? 'OpenRouter returned invalid JSON.' : String(error?.message ?? error);
  return (apiKey ? message.replaceAll(apiKey, '[API key]') : message).slice(0, 300);
}
