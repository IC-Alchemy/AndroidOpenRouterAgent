import assert from 'node:assert/strict';
import test from 'node:test';
import { OpenRouter } from '@openrouter/agent';
import { HTTPClient } from '@openrouter/sdk/lib/http';
import { buildCall, buildTools } from '../src/runtime.mjs';
import { resourceActionTools } from '../src/resource-actions.mjs';

const signal = new AbortController().signal;
const subagent = { name: ' Researcher ', model: 'example/model', instructions: 'Collect sources.',
  advancedJson: '{"maxToolCalls":3,"reasoning":{"effort":"low"},"inheritedFunctionNames":[" lookup_note "]}' };
const custom = { name: 'lookup_note', description: 'Find a note', handler: 'template', value: 'Note {{id}}',
  schema: '{"type":"object","properties":{"id":{"type":"number"}},"required":["id"]}' };

function getActions(events, abortSignal = signal) {
  return Object.fromEntries(resourceActionTools(events, abortSignal)
    .map(({ function: action }) => [action.name, action]));
}

test('resource changes are saved after approval and return the app result', async () => {
  const calls = [];
  const actions = getActions({
    approval: async (...args) => { calls.push(['approval', ...args]); return true; },
    appAction: async (name, args) => {
      calls.push(['save', name, args]);
      return { type: 'subagent', name: args.name, disposition: 'saved' };
    },
  });
  const args = { ...subagent, name: 'Researcher' };
  assert.deepEqual(await actions.create_subagent.execute(subagent),
    { type: 'subagent', name: args.name, disposition: 'saved' });
  assert.deepEqual(calls, [['approval', 'Create subagent', args, ''], ['save', 'create_subagent', args]]);
});

test('an unnamed subagent is passed to the app as the default', async () => {
  const calls = [];
  const { create_subagent: action } = getActions({
    approval: async (...args) => { calls.push(args); return true; },
    appAction: async (name, args) => {
      calls.push([name, args]);
      return { type: 'subagent' };
    },
  });
  const args = { instructions: 'Summarize findings.' };
  assert.deepEqual(await action.execute(args), { type: 'subagent' });
  assert.deepEqual(calls, [['Create subagent', args, ''], ['create_subagent', args]]);
});

test('declining approval or stopping a run prevents resource changes', async () => {
  const stop = new AbortController();
  for (const approve of [false, true]) {
    const { create_custom_tool: action } = getActions({
      approval: async () => {
        if (approve) stop.abort();
        return approve;
      },
      appAction: async () => assert.fail('Resource change must not be saved'),
    }, stop.signal);
    await assert.rejects(action.execute(custom), approve ? /cancelled/i : /declined/i);
  }
});

test('invalid resource arguments are rejected before approval', async () => {
  const actions = getActions({
    approval: async () => assert.fail('Invalid resource must not prompt'),
    appAction: async () => assert.fail('Invalid resource must not be saved'),
  });
  for (const advancedJson of ['{', '[]', '{"max_tool_calls":2}', '{"instructions":"Override"}',
    '{"reasoning":{"max_tokens":10}}', '{"maxToolCalls":26}', '{"tools":[{"type":"function"}]}',
    '{"tools":[{"type":"openrouter:typo"}]}', '{"tools":[{"type":"openrouter:subagent"}]}',
    '{"tools":[{"type":"openrouter:mcp"}]}', '{"tools":[{"type":"openrouter:web_search","parameters":[]}]}',
    '{"tools":[{"type":"openrouter:web_search","additionalProperties":{"parameters":[]}}]}']) {
    await assert.rejects(actions.create_subagent.execute({ ...subagent, advancedJson }), /subagent/i);
  }
  assert.equal(actions.create_subagent.inputSchema.safeParse({ name: ' ' }).success, false);
  for (const args of [
    { ...custom, schema: '{"type":"array","items":{"type":"number"}}' },
    { ...custom, schema: '{"type":"object","properties":{},"required":"missing"}' },
    { ...custom, schema: '{"type":"object","properties":{},"required":["missing"]}' },
    { ...custom, schema: '{"type":"object","properties":{"id":{"type":"number","minimum":"zero"}}}' },
    { ...custom, schema: '{"type":"object","properties":{"id":{"type":"number","description":42}}}' },
    { ...custom, schema: '{"type":"object","properties":{},"additionalProperties":1}' },
    { ...custom, handler: 'webhook', value: 'http://example.com' },
    { ...custom, handler: 'webhook', value: 'https://example.com?' },
    { ...custom, handler: 'webhook', value: 'https://example.com#' },
    { ...custom, name: 'create_skill' },
    { ...custom, name: 'mini_app_save' },
    { ...custom, name: 'mini_app_custom' },
    ...['list_project_files', 'read_project_file', 'write_project_file', 'edit_project_file']
      .map(name => ({ ...custom, name })),
  ]) {
    await assert.rejects(actions.create_custom_tool.execute(args), /schema|webhook|tool name/i);
  }
  assert.equal(actions.create_skill.inputSchema.safeParse({
    name: 'notes', description: 'Take notes', body: 'Keep a log', unexpected: true,
  }).success, false);
  assert.equal(actions.enable_server_tool.inputSchema.safeParse({ type: 'openrouter:subagent' }).success, false);
});

test('chat creation accepts supported worker tools and existing manual workers retain provider extensions', async () => {
  const events = {
    approval: async () => true,
    appAction: async (_, args) => args,
  };
  const args = { ...subagent, name: 'Researcher',
    advancedJson: '{"tools":[{"type":"openrouter:web_search","parameters":{"max_results":2}},{"type":"openrouter:files"}]}' };
  assert.deepEqual(await getActions(events).create_subagent.execute(args), args);

  const legacy = { type: 'openrouter:subagent', parameters: { tools: [{ type: 'openrouter:provider_extension' }] } };
  assert.deepEqual(buildTools({ extraTools: [legacy] }, {}, signal)[0].config, legacy);
});

test('the official SDK sends usable resource definitions and runs saved configurations', async () => {
  const requests = [];
  const enabled = [];
  const fetcher = async request => {
    requests.push(await request.json());
    const step = requests.length;
    const output = step === 1 ? { type: 'function_call', id: 'fc_create', call_id: 'call_create',
      name: 'create_custom_tool', arguments: JSON.stringify(custom), status: 'completed' } :
      step === 3 ? { type: 'function_call', id: 'fc_use', call_id: 'call_use',
        name: 'lookup_note', arguments: '{"id":8}', status: 'completed' } :
        { type: 'message', id: 'msg_' + step, role: 'assistant', status: 'completed',
          content: [{ type: 'output_text', text: 'Done.', annotations: [] }] };
    const response = { id: 'gen-' + step, object: 'response', model: 'mock/agent',
      created_at: 1700000000, completed_at: 1700000001, error: null, frequency_penalty: null,
      incomplete_details: null, instructions: null, metadata: null, output: [output], parallel_tool_calls: false,
      presence_penalty: null, status: 'completed', tool_choice: 'auto', temperature: null, top_p: null,
      tools: [], usage: { input_tokens: 8, input_tokens_details: { cached_tokens: 0 }, output_tokens: 3,
        output_tokens_details: { reasoning_tokens: 0 }, total_tokens: 11, cost: 0.01 } };
    const events = [
      { type: 'response.output_item.added', output_index: 0, item: output },
      { type: 'response.output_item.done', output_index: 0, item: output },
      { type: 'response.completed', response },
    ];
    return new Response(events.map(({ type, ...data }, sequence_number) =>
      'event: ' + type + '\ndata: ' + JSON.stringify({ ...data, type, sequence_number }) + '\n\n').join('') +
      'data: [DONE]\n\n', { headers: { 'Content-Type': 'text/event-stream' } });
  };
  const client = new OpenRouter({ apiKey: 'mock-key', httpClient: new HTTPClient({ fetcher }) });
  const options = { model: 'mock/agent', history: [], prompt: 'Set up a notes tool', maxSteps: 4 };
  const events = {
    approval: async () => true,
    appAction: async (name, args) => {
      assert.equal(name, 'create_custom_tool');
      enabled.push(args);
      return { type: 'custom_tool', name: args.name };
    },
  };
  const send = (opts, tools) => client.callModel(buildCall(opts, tools, events.approval, signal)).getResponse();
  await send(options, resourceActionTools(events, signal));
  const definitions = requests[0].tools;
  assert.deepEqual(definitions.map(({ name }) => name),
    ['create_subagent', 'create_custom_tool', 'create_skill', 'enable_server_tool']);
  for (const definition of definitions) {
    assert.equal(definition.type, 'function');
    assert.equal(definition.strict, false);
    assert.equal(definition.parameters.type, 'object');
    assert.equal(definition.parameters.additionalProperties, false);
  }
  assert.equal(definitions[0].parameters.required?.includes('name') ?? false, false);
  assert.deepEqual(definitions[3].parameters.required, ['type']);
  assert.deepEqual(enabled, [custom]);
  assert.match(requests[1].input.find(item => item.type === 'function_call_output').output, /lookup_note/);

  const withTool = { ...options, customTools: enabled, prompt: 'Look up note eight' };
  await send(withTool, buildTools(withTool, {}, signal));
  assert.match(requests[3].input.find(item => item.type === 'function_call_output').output, /Note 8/);
});
