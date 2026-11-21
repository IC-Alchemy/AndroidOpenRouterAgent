import { z } from 'zod';

export const subagentType = 'openrouter:subagent';
export const hostedToolTypes = [
  'openrouter:shell', 'openrouter:web_search', 'openrouter:datetime', 'openrouter:web_fetch',
  'openrouter:files', 'openrouter:image_generation', 'openrouter:experimental__search_models',
  'openrouter:advisor', 'openrouter:fusion',
];

const additionalWorkerFields = z.record(z.string(), z.unknown());
const workerToolFields = ['type', 'parameters', 'additionalProperties'];
const parameterSchema = (object, workerType, workerExtensions = additionalWorkerFields) => object({
  name: z.string().trim().regex(/^[a-zA-Z0-9 _-]{1,64}$/).optional(),
  model: z.string().trim().min(1).refine(model => !/\s/.test(model) && model !== subagentType).optional(),
  instructions: z.string().optional(),
  maxCompletionTokens: z.int().positive().optional(),
  maxToolCalls: z.int().min(1).max(25).optional(),
  temperature: z.number().min(0).max(2).optional(),
  reasoning: object({
    effort: z.enum(['max', 'xhigh', 'high', 'medium', 'low', 'minimal', 'none']).optional(),
    maxTokens: z.int().positive().optional(),
  }).optional(),
  tools: z.array(object({
    type: workerType,
    parameters: z.record(z.string(), z.unknown()).optional(),
    additionalProperties: workerExtensions.optional(),
  })).optional(),
  inheritFunctions: z.boolean().optional(),
  inheritedFunctionNames: z.array(z.string().trim().min(1)).optional(),
});
const subagentParameters = parameterSchema(z.object,
  z.string().regex(/^openrouter:[a-z][a-z0-9_]*$/).refine(type => type !== subagentType));
const strictSubagentParameters = parameterSchema(z.strictObject, z.enum(hostedToolTypes),
  additionalWorkerFields.refine(fields => workerToolFields.every(key => !Object.hasOwn(fields, key))));

export function subagentConfig(item, seenNames, { strict = false } = {}) {
  const schema = strict ? strictSubagentParameters : subagentParameters;
  const parameters = schema.safeParse(item.parameters === undefined ? {} : item.parameters);
  if (!parameters.success) {
    const issue = parameters.error.issues[0];
    const field = [...issue.path, ...(issue.keys ?? [])].join('.');
    const description = field.startsWith('tools') ? 'worker tools' : field || 'parameters';
    throw new Error('Check the subagent ' + description + ' setting.');
  }
  const name = parameters.data.name;
  if (seenNames.has(name)) {
    throw new Error(name === undefined ? 'Use at most one default subagent.' : 'Subagent names must be unique.');
  }
  seenNames.add(name);
  return item.parameters === undefined ? { type: subagentType } : { type: subagentType, parameters: parameters.data };
}

export function discardUnfinishedSubagents(state) {
  const unfinished = new Map();
  for (const item of state.messages) {
    if (item.type === subagentType && item.callId) {
      unfinished.set(item.callId, item.status === 'in_progress');
    }
  }
  if (![...unfinished.values()].some(Boolean)) return state;

  // OpenRouter requires a result for every projected call in a suspended worker's replay.
  // The app starts a fresh turn after a stop, so drop the unfinished worker and its calls together.
  const childCalls = new Set(state.messages
    .filter(item => item.type === 'function_call' && unfinished.get(item.subagentId))
    .map(item => item.callId));
  const messages = state.messages.filter(item =>
    !(item.type === subagentType && unfinished.get(item.callId)) &&
    !((item.type === 'function_call' || item.type === 'function_call_output') && childCalls.has(item.callId)));
  const pendingToolCalls = state.pendingToolCalls?.filter(call => !childCalls.has(call.id));
  if (state.status === 'awaiting_client_tools' && pendingToolCalls?.length === 0) {
    return { ...state, messages, pendingToolCalls: undefined, status: 'complete' };
  }
  return { ...state, messages, pendingToolCalls };
}
