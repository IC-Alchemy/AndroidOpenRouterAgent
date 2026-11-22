import assert from 'node:assert/strict';
import test from 'node:test';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { readFile } from 'node:fs/promises';
import { runInNewContext } from 'node:vm';
import { OpenRouter } from '@openrouter/agent';
import { buildCall, buildTools, runAgent } from '../src/runtime.mjs';

const base = {
  apiKey: 'mock-key', history: [], model: 'mock/tool-capable', prompt: 'Tell me the device time zone',
  sessionId: 'mock-session', maxSteps: 4, maxCost: 0.25,
};

function itemEvents(item, index) {
  const events = [
    { type: 'response.output_item.added', output_index: index,
      item: item.subagent_items ? { ...item, subagent_items: undefined, arguments: '' } : item },
  ];
  if (item.type === 'message') {
    events.push({ type: 'response.output_text.delta', output_index: index, item_id: item.id, content_index: 0,
      delta: item.content[0].text, logprobs: [] });
  }
  if (item.type !== 'openrouter:subagent' || item.status !== 'in_progress') {
    events.push({ type: 'response.output_item.done', item, output_index: index });
  }
  return events;
}

function stream(record, item) {
  const output = Array.isArray(item) ? item : [item];
  const reply = {
    id: 'gen-' + record, object: 'response', model: base.model, created_at: 1700000000, completed_at: 1700000001,
    error: null, frequency_penalty: null, incomplete_details: null, instructions: null, metadata: null,
    output, parallel_tool_calls: false, presence_penalty: null, status: 'completed',
    temperature: null, tool_choice: 'auto', tools: [], top_p: null,
    usage: {
      input_tokens: 8, input_tokens_details: { cached_tokens: 0 },
      output_tokens: 3, output_tokens_details: { reasoning_tokens: 0 }, total_tokens: 11, cost: 0.01,
    },
  };
  return [...output.flatMap(itemEvents), { type: 'response.completed', response: reply }].map((event, sequence) =>
    'event: ' + event.type + '\ndata: ' + JSON.stringify({ ...event, sequence_number: sequence }) + '\n\n'
  ).join('') + 'data: [DONE]\n\n';
}

function answer(id, text) {
  return { type: 'message', id, role: 'assistant', status: 'completed',
    content: [{ type: 'output_text', text, annotations: [] }] };
}

async function bundledRuntime(AndroidAgent) {
  const browser = {
    Promise, fetch: () => { throw new Error('The model request must use the Android HTTP bridge.'); },
    URL, URLSearchParams, Headers, Request, Response, TextDecoder, TextEncoder, ReadableStream, TransformStream,
    AbortController, AbortSignal, DOMException, atob, Uint8Array, setTimeout, clearTimeout, setInterval,
    clearInterval, queueMicrotask, performance, console, Intl, crypto: globalThis.crypto, AndroidAgent,
  };
  const bundle = await readFile(new URL('../../app/src/main/assets/agent/agent.js', import.meta.url), 'utf8');
  runInNewContext(bundle, browser);
  return browser.AgentRuntime;
}

test('the bundled SDK handles project files, subagent approval and mini apps together',
  { timeout: 5000 }, async () => {
    const events = [];
    const requests = [];
    const localCalls = [];
    const responses = new Map();
    const fileArgs = { path: 'notes/plan.md', text: 'Initial plan.' };
    const subagentArgs = { name: 'Editor', model: 'mock/worker', advancedJson: '{"maxToolCalls":3}' };
    const call = (name, args, index) => ({ type: 'function_call', id: 'item_' + index, call_id: 'call_' + index,
      name, arguments: JSON.stringify(args), status: 'completed' });
    const outputs = [call('write_project_file', fileArgs, 1), call('create_subagent', subagentArgs, 2),
      call('mini_app_list', {}, 3),
      answer('msg_created', 'The file and subagent are saved.')];
    const runtime = await bundledRuntime({
      post(message) {
        const event = JSON.parse(message);
        events.push(event);
        if (event.type === 'approval') queueMicrotask(() => {
          runtime.decide(event.id, event.value.decision, true, '');
        });
        if (event.type === 'app_request') queueMicrotask(() => {
          const { request, name, args } = event.value;
          const result = name === 'write_project_file' ? { path: args.path, created: true } :
            { type: 'subagent', name: args.name };
          runtime.appResult(event.id, request, true, result);
        });
      },
      requestHttp(id, requestId, payload) {
        requests.push(JSON.parse(JSON.parse(payload).body));
        const round = requests.length;
        responses.set(requestId, Buffer.from(stream(round, outputs[round - 1])).toString('base64'));
        queueMicrotask(() => runtime.http(id, requestId, 'headers', { status: 200, contentType: 'text/event-stream' }));
      },
      readHttp(id, requestId) {
        const response = responses.get(requestId);
        responses.delete(requestId);
        queueMicrotask(() => runtime.http(id, requestId, response ? 'chunk' : 'end', response ?? null));
      },
      local(id, requestId, json) {
        localCalls.push(JSON.parse(json));
        queueMicrotask(() => runtime.local(id, requestId, JSON.stringify({ ok: true, apps: [], result: 'Done' })));
      },
      cancelHttp() {},
    });
    await runtime.start({ ...base, id: 'app-actions', appActions: true, projectFolder: 'projects/test',
      miniApps: true, shell: true });
    assert.equal(requests.length, 4);
    const actions = events.filter(event => event.type === 'app_request').map(event =>
      [event.value.name, event.value.args]);
    assert.deepEqual(actions, [['write_project_file', fileArgs], ['create_subagent', subagentArgs]]);
    const approvals = events.filter(event => event.type === 'approval');
    assert.equal(approvals.length, 1);
    assert.equal(approvals[0].value.savesSettings, true);
    assert.deepEqual(approvals[0].value.args, subagentArgs);
    assert.match(requests[1].input.find(item => item.type === 'function_call_output').output, /notes\/plan.md/);
    assert.match(requests[2].input.find(item => item.call_id === 'call_2' && item.type === 'function_call_output').output,
      /\"name\":\"Editor\"/);
    assert.deepEqual(localCalls, [{ tool: 'mini_app', type: 'list' }]);
    assert.match(requests[0].instructions, /one complete HTML file/);
    assert.match(requests[0].instructions, /projects\/test/);
    assert.match(requests[3].input.find(item => item.call_id === 'call_3' && item.type === 'function_call_output').output,
      /Done/);
    assert.ok(events.some(event => event.type === 'done'));
  });

test('a project run uses project file functions and attaches workspace files to its own sandbox', async () => {
  const calls = [];
  const requests = [];
  const fetcher = async request => {
    requests.push(await request.json());
    const round = requests.length;
    const output = round === 1
      ? { type: 'function_call', id: 'fc_file', call_id: 'read_file', status: 'completed',
        name: 'read_project_file', arguments: '{"path":"notes/plan.md"}' }
      : answer('msg_file', 'The plan is ready.');
    return new Response(stream(round, output), { headers: { 'Content-Type': 'text/event-stream' } });
  };
  const opts = { ...base, appActions: true, projectFolder: 'projects/project-1', workspaceFiles: true,
    shell: true, containerId: 'studio_project_mock', domains: ['example.com'],
    fileIds: ['or_file_a12345678', 'or_file_b98765432'] };
  const events = { text() {}, fetcher, appAction: async (name, args) => {
    calls.push([name, args]);
    return { path: args.path, text: 'Plan contents' };
  }};
  await runAgent(opts, events, new AbortController().signal);
  assert.deepEqual(calls, [['read_project_file', { path: 'notes/plan.md' }]]);
  assert.equal(requests.length, 2);
  for (const request of requests) {
    assert.ok(!request.tools.some(tool => tool.type === 'openrouter:files'));
    assert.match(request.instructions, /projects\/project-1\/outputs/);
    assert.deepEqual(request.tools.find(tool => tool.type === 'openrouter:shell').parameters.environment, {
      type: 'container_reference', container_id: 'studio_project_mock',
      file_ids: ['or_file_a12345678', 'or_file_b98765432'],
      network_policy: { type: 'allowlist', allowed_domains: ['example.com'] },
    });
  }
  const output = requests[1].input.find(item => item.type === 'function_call_output');
  assert.match(output.output, /Plan contents/);
});

test('the workspace Files tool goes on the wire for unfiled chats and is disabled with tools off', async () => {
  const requests = [];
  const opts = { ...base, workspaceFiles: true, fileIds: ['or_file_a12345678'], shell: true };
  const events = { text() {}, fetcher: async request => {
    requests.push(await request.json());
    return new Response(stream(1, answer('msg_files', 'Done.')),
      { headers: { 'Content-Type': 'text/event-stream' } });
  }};
  const signal = new AbortController().signal;
  await runAgent(opts, events, signal);
  await runAgent({ ...opts, sdk: { toolChoice: 'none' } }, events, signal);
  assert.deepEqual(requests[0].tools.find(tool => tool.type === 'openrouter:files'), { type: 'openrouter:files' });
  assert.deepEqual(requests[0].tools.find(tool => tool.type === 'openrouter:shell').parameters.environment, {
    type: 'container_auto', file_ids: ['or_file_a12345678'],
  });
  assert.equal(requests[1].tool_choice, 'none');
  assert.ok(!requests[1].tools?.length);
});

test('named subagents stay in order and the SDK resumes a worker across inherited function calls',
  { timeout: 5000 }, async () => {
    const type = 'openrouter:subagent';
    const suspended = { type, id: 'st_1', call_id: 'worker_call', status: 'in_progress',
      name: 'Time Worker', instance_name: 'Time Worker', task_name: 'local-time',
      task_description: 'Find the local time zone using the device tool.' };
    const projected = round => ({
      type: 'function_call', id: 'fc_' + round, call_id: 'child_' + round, status: 'completed',
      name: round === 1 ? 'device_time_zone' : 'lookup_time',
      arguments: round === 1 ? '{}' : '{"city":"Denver"}', subagent_id: suspended.call_id,
      subagent_items: [{ type: 'message', role: 'assistant',
        content: [{ type: 'output_text', text: 'Worker-only text', provider_extension: { camelKey: round } }] }],
    });
    const complete = { ...suspended, id: 'st_2', status: 'completed', model: 'mock/worker',
      outcome: 'The worker found the device time zone.' };
    const requests = [];
    const deltas = [];
    const fetcher = async request => {
      requests.push(await request.json());
      const round = requests.length;
      const output = round === 1 ? [suspended, projected(1)] : round === 2 ? [projected(2)] :
        round === 3 ? [complete, answer('msg_final', 'The time zone was found.')] :
          [answer('msg_reopened', 'The earlier task succeeded.')];
      return new Response(stream(round, output), { headers: { 'Content-Type': 'text/event-stream' } });
    };
    const signal = new AbortController().signal;
    const options = { ...base, sdk: { detectRepetition: false }, deviceTools: true, extraTools: [
      { type, parameters: { name: ' Time Worker ', model: 'mock/worker', instructions: 'Find the time zone.',
        inheritedFunctionNames: [' device_time_zone ', 'lookup_time'], maxToolCalls: 2, maxCompletionTokens: 256,
        reasoning: { effort: 'low', maxTokens: 32 }, temperature: 0.25,
        tools: [{ type: 'openrouter:web_search', parameters: { max_results: 2 } }] } },
      { type, parameters: { name: 'Summarizer' } },
      { type },
    ], customTools: [{
      name: 'lookup_time', description: 'Look up the time in a city', handler: 'webhook',
      schema: '{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}',
      value: 'https://example.com/time',
    }] };
    const approvals = [];
    let webhookRequests = 0;
    const events = {
      fetcher, text: text => deltas.push(text),
      approval: async (...args) => { approvals.push(args); return false; },
      webhookFetch: async () => { webhookRequests++; return new Response('Unexpected webhook result'); },
    };
    const result = await runAgent(options, events, signal);
    assert.deepEqual(approvals, [['lookup_time', { city: 'Denver' }, 'https://example.com/time']]);
    assert.equal(webhookRequests, 0);
    assert.equal(requests.length, 3);
    const workers = requests[0].tools.filter(tool => tool.type === type);
    assert.deepEqual(workers, [
      { type, parameters: { name: 'Time Worker', model: 'mock/worker', instructions: 'Find the time zone.',
        inherited_function_names: ['device_time_zone', 'lookup_time'], max_tool_calls: 2, max_completion_tokens: 256,
        reasoning: { effort: 'low', max_tokens: 32 }, temperature: 0.25,
        tools: [{ type: 'openrouter:web_search', parameters: { max_results: 2 } }] } },
      { type, parameters: { name: 'Summarizer' } },
      { type },
    ]);
    for (const round of [1, 2]) {
      const request = requests[round];
      assert.deepEqual(request.tools, requests[0].tools);
      assert.deepEqual(request.input.find(item => item.type === type), suspended);
      assert.deepEqual(request.input.filter(item => item.subagent_id), [projected(1), projected(2)].slice(0, round));
      for (let child = 1; child <= round; child++) {
        const result = request.input.find(item => item.type === 'function_call_output' && item.call_id === 'child_' + child);
        assert.match(result.output, child === 1 ? /timeZone/ : /declined/);
      }
    }
    assert.equal(deltas.join(''), 'The time zone was found.');
    assert.equal(result.response.output.find(item => item.id === complete.id).outcome, complete.outcome);
    await runAgent({ ...options, state: result.state, prompt: 'What happened earlier?' }, events, signal);
    assert.deepEqual(requests[3].input.filter(item => item.type === type), [suspended, complete]);
    assert.deepEqual(requests[3].input.filter(item => item.subagent_id), [projected(1), projected(2)]);
  });

test('invalid subagent configurations are rejected before transport, and tool choice none disables them', () => {
  const type = 'openrouter:subagent';
  const worker = parameters => ({ type, parameters });
  const options = extraTools => ({ ...base, extraTools });
  assert.throws(() => buildTools(options([{ type }, worker({})])), /one default/i);
  assert.throws(() => buildTools(options([worker({ name: ' Worker ' }), worker({ name: 'Worker' })])), /unique/i);
  assert.throws(() => buildTools(options([worker({ name: ' ' })])), /name/i);
  for (const invalid of ['function', type]) {
    assert.throws(() => buildTools(options([worker({ tools: [{ type: invalid }] })])), /worker tools/i);
  }
  assert.throws(() => buildTools(options([worker({ reasoning: { maxTokens: -1 } })])), /maxTokens/i);
  assert.throws(() => buildTools(options([worker({ maxToolCalls: 26 })])), /maxToolCalls/i);
  assert.throws(() => buildTools(options([worker({ inheritedFunctionNames: [' '] })])), /inheritedFunctionNames/i);
  assert.deepEqual(buildTools({ ...options([{ type }]), sdk: { toolChoice: 'none' } }), []);
});

test('a worker left suspended at the step limit does not leave dangling calls in a saved chat', async () => {
  const suspended = { type: 'openrouter:subagent', id: 'st_pending', call_id: 'worker_pending',
    status: 'in_progress', task_name: 'local-time', task_description: 'Look up the local time zone.' };
  const projected = { type: 'function_call', id: 'fc_pending', call_id: 'child_pending',
    status: 'completed', name: 'device_time_zone', arguments: '{}', subagent_id: suspended.call_id };
  const requests = [];
  const fetcher = async request => {
    requests.push(await request.json());
    const round = requests.length;
    const output = round === 1 ? [suspended, projected] :
      round === 2 ? [{ ...projected, id: 'fc_second', call_id: 'child_pending_second' }] :
        [answer('msg_next', 'This is a new turn.')];
    return new Response(stream(round, output), { headers: { 'Content-Type': 'text/event-stream' } });
  };
  const options = { ...base, maxSteps: 1, sdk: { finalResponse: 'off' }, deviceTools: true,
    extraTools: [{ type: suspended.type, parameters: { inheritFunctions: true } }] };
  const signal = new AbortController().signal;
  const events = { fetcher, text() {} };
  const stopped = await runAgent(options, events, signal);
  assert.equal(requests.length, 2);
  assert.ok(stopped.response.output.some(item => item.subagent_id === suspended.call_id));
  await runAgent({ ...options, state: stopped.state, prompt: 'Start the next turn.' }, events, signal);
  assert.equal(requests.length, 3);
  assert.ok(requests[2].input.some(item => item.content === 'Start the next turn.'));
  assert.doesNotMatch(JSON.stringify(requests[2].input), /worker_pending|child_pending/);

  const legacy = JSON.parse(stopped.state);
  legacy.messages.push(
    { type: suspended.type, id: suspended.id, callId: suspended.call_id, status: 'in_progress' },
    { type: projected.type, id: projected.id, callId: projected.call_id, status: 'completed',
      name: projected.name, arguments: '{}', subagentId: suspended.call_id },
  );
  for (const saved of [{ state: JSON.stringify(legacy) }, { history: legacy.messages }]) {
    await runAgent({ ...options, ...saved, prompt: 'Open an older chat.' }, events, signal);
    assert.ok(requests.at(-1).input.some(item => item.content === 'Open an older chat.'));
    assert.doesNotMatch(JSON.stringify(requests.at(-1).input), /worker_pending|child_pending/);
  }
});

test('the official agent runs a local tool, streams a final answer and replays its results', async () => {
  const requests = [];
  const server = createServer(async (req, res) => {
    const chunks = [];
    for await (const bytes of req) chunks.push(bytes);
    const body = JSON.parse(Buffer.concat(chunks));
    requests.push({ body, url: req.url, authorization: req.headers.authorization });
    const number = requests.length;
    const item = number === 1 ? {
      type: 'function_call', name: 'device_time_zone', call_id: 'call_1', id: 'fc_1',
      arguments: '{}', status: 'completed',
    } : answer('msg_' + number, number === 2 ? 'Here is your time zone.' : 'It was reported in the earlier turn.');
    res.setHeader('Content-Type', 'text/event-stream');
    res.end(stream(number, item));
  }).listen(0, '127.0.0.1');
  await once(server, 'listening');
  try {
    const createClient = options => new OpenRouter({ ...options, serverURL: 'http://127.0.0.1:' + server.address().port + '/api/v1' });
    const deltas = [];
    const activities = [];
    const events = { text: text => deltas.push(text), activity: name => activities.push(name), approval: async () => false };
    const signal = new AbortController().signal;
    const result = await runAgent({ ...base, deviceTools: true }, events, signal, createClient);
    assert.equal(requests[0].url, '/api/v1/responses');
    assert.equal(requests[0].authorization, 'Bearer mock-key');
    assert.equal(requests[0].body.stream, true);
    assert.equal(requests[0].body.stop_server_tools_when[0].step_count, 4);
    assert.equal(requests[0].body.stop_server_tools_when[1].max_cost_in_dollars, 0.25);
    assert.equal(requests[1].body.input.find(item => item.type === 'function_call_output').call_id, 'call_1');
    assert.match(requests[1].body.input.find(item => item.type === 'function_call_output').output, /timeZone/);
    assert.equal(deltas.join(''), 'Here is your time zone.');
    assert.ok(activities.includes('device_time_zone'));
    assert.equal(result.response.usage.total_tokens, 22);
    assert.equal(result.response.output.find(item => item.type === 'message').content[0].text, 'Here is your time zone.');
    assert.ok(result.history.some(item => item.type === 'function_call_output'));
    await runAgent({ ...base, history: result.history, prompt: 'What did I ask?' }, events, signal, createClient);
    assert.ok(requests[2].body.input.some(item => item.type === 'function_call_output'));
    const persisted = JSON.parse(result.state);
    assert.equal(persisted.version, 1);
    await runAgent({ ...base, state: result.state, prompt: 'Resume the saved chat.' }, events, signal, createClient);
    const reopened = requests[3].body.input;
    assert.equal(reopened.filter(item => item.type === 'function_call_output' && item.call_id === 'call_1').length, 1);
    assert.equal(reopened.filter(item => item.role === 'user').length, 2);
    assert.match(JSON.stringify(reopened.at(-1)), /Resume the saved chat/);
    await runAgent({ ...base, prompt: 'Start a different chat.' }, events, signal, createClient);
    assert.equal(requests[4].body.input.length, 1);
  } finally {
    server.closeAllConnections();
    server.close();
  }
});

test('shell settings use explicit hosted execution, preserved container identity, and opt-in egress', () => {
  const shell = opts => buildTools({ shell: true, ...opts })[0].config.parameters;
  assert.equal(shell().engine, 'openrouter');
  assert.deepEqual(shell().environment, { type: 'container_auto' });
  assert.deepEqual(shell({ containerId: 'sess_abc' }).environment,
    { type: 'container_reference', containerId: 'sess_abc' });
  assert.deepEqual(shell({ domains: ['pypi.org'] }).environment.networkPolicy,
    { type: 'allowlist', allowedDomains: ['pypi.org'] });
  assert.throws(() => buildTools({ extraTools: [{ type: 'openrouter:shell' }] }), /Unsupported/);
});

test('remote calls are gated with arguments and endpoint and honour cancellation', async () => {
  const controller = new AbortController();
  const approvals = [];
  const call = buildCall({ ...base, mcpUrl: 'https://example.com/mcp' }, [],
    async (...args) => { approvals.push(args); return false; }, controller.signal);
  assert.deepEqual(await call.hooks.PreToolUse[0].handler({ toolName: 'device_time_zone', toolInput: {} }), {});
  assert.match((await call.hooks.PreToolUse[0].handler({ toolName: 'remote_query', toolInput: { query: 'test' } })).block, /declined/);
  assert.deepEqual(approvals, [['query', { query: 'test' }, 'https://example.com/mcp']]);
  controller.abort();
  assert.match((await call.hooks.PreToolUse[0].handler({ toolName: 'remote_query', toolInput: {} })).block, /cancelled/);
});

test('the official SDK gates MCP execution and feeds approved tool results back', async () => {
  let remoteInvocations = 0;
  let modelInvocations = 0;
  const server = createServer(async (req, res) => {
    if (req.method !== 'POST') {
      res.writeHead(405);
      res.end();
      return;
    }
    const chunks = [];
    for await (const bytes of req) chunks.push(bytes);
    const body = JSON.parse(Buffer.concat(chunks));
    if (req.url === '/mcp') {
      if (body.id == null) {
        res.writeHead(202);
        res.end();
        return;
      }
      let result;
      if (body.method === 'initialize') {
        result = { protocolVersion: body.params.protocolVersion, capabilities: { tools: {} },
          serverInfo: { name: 'Test MCP', version: '1' } };
      } else if (body.method === 'tools/list') {
        result = { tools: [{ name: 'echo', description: 'Return a test string',
          inputSchema: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'] } }] };
      } else if (body.method === 'tools/call') {
        remoteInvocations++;
        assert.equal(req.headers.authorization, 'Bearer mock-mcp');
        assert.equal(body.params.name, 'echo');
        result = { content: [{ type: 'text', text: 'MCP returned ' + body.params.arguments.text }] };
      }
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({ jsonrpc: '2.0', id: body.id, result }));
      return;
    }
    assert.equal(req.headers.authorization, 'Bearer mock-key');
    const number = ++modelInvocations;
    let item;
    if (number % 2 === 1) {
      assert.ok(body.tools.some(tool => tool.name === 'remote_echo'));
      item = { type: 'function_call', name: 'remote_echo', call_id: 'remote_call', id: 'fc_remote',
        arguments: '{"text":"hello"}', status: 'completed' };
    } else {
      const output = body.input.find(item => item.type === 'function_call_output').output;
      assert.match(output, number === 2 ? /declined/ : /MCP returned hello/);
      item = answer('msg_' + number, 'Finished.');
    }
    res.setHeader('Content-Type', 'text/event-stream');
    res.end(stream(number, item));
  }).listen(0, '127.0.0.1');
  await once(server, 'listening');
  try {
    const origin = 'http://127.0.0.1:' + server.address().port;
    const createClient = options => new OpenRouter({ ...options, serverURL: origin + '/api/v1' });
    const opts = { ...base, mcpUrl: origin + '/mcp', mcpToken: 'mock-mcp' };
    const events = { text() {}, activity() {}, approval: async (name, args) => {
      assert.equal(name, 'echo');
      assert.equal(args.text, 'hello');
      return false;
    }};
    const signal = new AbortController().signal;
    await runAgent(opts, events, signal, createClient);
    assert.equal(remoteInvocations, 0);
    await runAgent(opts, { ...events, approval: async () => true }, signal, createClient);
    assert.equal(remoteInvocations, 1);
  } finally {
    server.closeAllConnections();
    server.close();
  }
});

test('the bundled SDK sends text, summaries and files across the native bridge before finalization', { timeout: 5000 }, async () => {
  const events = [];
  const requests = [];
  let read = 0;
  let liveObserved;
  const observed = new Promise(resolve => { liveObserved = resolve; });
  let release;
  const finalAllowed = new Promise(resolve => { release = resolve; });
  const shell = {
    type: 'openrouter:shell', id: 'st_fixture', status: 'completed', call_id: 'shell_fixture',
    container_id: 'sess_fixture', action: { commands: ['printf test'] },
    output: [{ stdout: 'test', stderr: '', outcome: { type: 'exit', exit_code: 0 } }],
    files: [{ type: 'container_file_citation', container_id: 'sess_fixture', file_id: 'cfile_fixture',
      filename: 'outputs/result.txt', start_index: 0, end_index: 0 }],
  };
  const worker = {
    type: 'openrouter:subagent', id: 'st_worker', call_id: 'call_worker', status: 'completed',
    name: 'Summarizer', task_name: 'summarize', model: 'mock/worker', outcome: 'A short summary.',
  };
  const wire = (type, sequence, fields) => 'event: ' + type + '\ndata: ' +
    JSON.stringify({ ...fields, type, sequence_number: sequence }) + '\n\n';
  const start = wire('response.reasoning_summary_text.delta', 0, {
    item_id: 'rs_fixture', output_index: 0, summary_index: 0, delta: 'Comparing options. ',
  }) + wire('response.output_text.delta', 1, {
    item_id: 'msg_fixture', output_index: 1, content_index: 0, delta: 'Streaming early.', logprobs: [],
  }) + wire('response.output_item.done', 2, { item: shell, output_index: 2 }) +
    wire('response.output_item.added', 3, { item: { ...worker, status: 'in_progress', outcome: undefined },
      output_index: 3 });

  const runtime = await bundledRuntime({
      post(text) {
        const event = JSON.parse(text);
        events.push(event);
        const updates = events.filter(event => event.type === 'updates').flatMap(event => event.value);
        if (updates.some(update => update.kind === 'text') && updates.some(update => update.kind === 'reasoning') &&
            updates.some(update => update.kind === 'files') &&
            updates.some(update => update.id === 'call_worker' && update.status === 'running')) liveObserved();
      },
      requestHttp(id, requestId, json) {
        const payload = JSON.parse(json);
        assert.equal(payload.authorization, undefined);
        requests.push(JSON.parse(payload.body));
        queueMicrotask(() => runtime.http(id, requestId, 'headers',
          { status: 200, contentType: 'text/event-stream' }));
      },
      readHttp(id, requestId) {
        const turn = ++read;
        if (turn === 1) queueMicrotask(() => runtime.http(id, requestId, 'chunk',
          Buffer.from(start).toString('base64')));
        if (turn === 2) finalAllowed.then(() => runtime.http(id, requestId, 'chunk',
          Buffer.from(stream(1, [shell, worker])).toString('base64')));
        if (turn > 2) queueMicrotask(() => runtime.http(id, requestId, 'end', null));
      },
      cancelHttp() {},
  });
  assert.equal(events[0]?.type, 'ready');
  const run = runtime.start({ ...base, id: 'fixture', shell: true, webSearch: true,
    dateTime: true, domains: ['pypi.org'], extraTools: [{ type: worker.type,
      parameters: { name: worker.name, model: worker.model } }] });
  await Promise.race([observed, run.then(() => { throw new Error(JSON.stringify(events)); })]);
  assert.ok(!events.some(event => ['done', 'error'].includes(event.type)));
  assert.equal(requests.length, 1);
  assert.equal(requests[0].stream, true);
  assert.equal(requests[0].tools[0].parameters.engine, 'openrouter');
  assert.deepEqual(requests[0].tools[0].parameters.environment.network_policy.allowed_domains, ['pypi.org']);
  assert.ok(requests[0].tools.some(tool => tool.type === 'openrouter:web_search'));
  assert.ok(requests[0].tools.some(tool => tool.type === 'openrouter:datetime'));
  assert.deepEqual(requests[0].tools.find(tool => tool.type === worker.type),
    { type: worker.type, parameters: { name: worker.name, model: worker.model } });
  release();
  await run;
  const done = events.find(event => event.type === 'done');
  assert.ok(done, JSON.stringify(events));
  const finalShell = done.value.response.output.find(item => item.type === 'openrouter:shell');
  assert.equal(finalShell.container_id, 'sess_fixture');
  assert.equal(finalShell.files[0].file_id, 'cfile_fixture');
  const updates = events.filter(event => event.type === 'updates').flatMap(event => event.value);
  assert.ok(updates.some(update => update.kind === 'phase' && update.phase === 'connected'));
  assert.ok(updates.some(update => update.kind === 'usage' && update.inputTokens === 8));
  assert.ok(updates.some(update => update.id === 'call_worker' && update.status === 'completed' &&
    update.detail.includes(worker.outcome)));
});

test('the native transport aborts a pending read and frees its HTTP exchange', async () => {
  const { createNativeTransport } = await import('../src/native-fetch.mjs');
  const controller = new AbortController();
  let started;
  const seen = new Promise(resolve => { started = resolve; });
  let cancelled = 0;
  const adapter = createNativeTransport('run', {
    requestHttp: (_, id) => queueMicrotask(() => adapter.receive(id, 'headers',
      { status: 200, contentType: 'text/event-stream' })),
    readHttp: () => started(),
    cancelHttp: () => { cancelled++; },
  }, () => {});
  const response = await adapter.fetcher('https://openrouter.ai/api/v1/responses', {
    method: 'POST', body: '{}', signal: controller.signal,
  });
  const reader = response.body.getReader();
  const read = reader.read();
  await seen;
  controller.abort();
  await assert.rejects(read, { name: 'AbortError' });
  assert.equal(cancelled, 1);
});

test('native retry notices update progress and do not end the request', async () => {
  const { createNativeTransport } = await import('../src/native-fetch.mjs');
  const phases = [];
  let adapter;
  adapter = createNativeTransport('run', {
    requestHttp: (_, id) => queueMicrotask(() => {
      adapter.receive(id, 'retry', 'HTTP 429 · retry 1 of 3 in 1 s');
      adapter.receive(id, 'headers', { status: 200, contentType: 'text/event-stream' });
    }),
    readHttp: (_, id) => queueMicrotask(() => adapter.receive(id, 'end', null)),
    cancelHttp: () => {},
  }, (phase, detail) => phases.push([phase, detail]));
  const response = await adapter.fetcher('https://openrouter.ai/api/v1/responses', { method: 'POST', body: '{}' });
  assert.equal(response.status, 200);
  assert.equal(await response.text(), '');
  assert.deepEqual(phases, [['connecting', undefined], ['retrying', 'HTTP 429 · retry 1 of 3 in 1 s'],
    ['connected', undefined]]);
});

test('hosted shell runs get non-interactive guidance that reflects sandbox network access', () => {
  const instructions = opts => buildCall({ ...base, shell: true, instructions: 'Base.', ...opts }, [], async () => false,
    new AbortController().signal).instructions({ numberOfTurns: 0 });
  const offline = instructions({});
  assert.match(offline, /^Base\./);
  assert.match(offline, /no stdin/);
  assert.match(offline, /test -f/);
  assert.match(offline, /timeout 120/);
  assert.match(offline, /network access is off/);
  assert.match(instructions({ domains: ['pypi.org', 'github.com'] }), /limited to pypi\.org, github\.com/);
  const noShell = buildCall({ ...base, instructions: 'Base.' }, [], async () => false, new AbortController().signal);
  assert.equal(noShell.instructions({ numberOfTurns: 0 }), 'Base.');
});

test('custom tools, progressive skill loading, and dynamic model settings work on real SDK follow-ups',
  { timeout: 7000 }, async () => {
  const requests = [];
  const server = createServer(async (req, res) => {
    const chunks = [];
    for await (const bytes of req) chunks.push(bytes);
    const body = JSON.parse(Buffer.concat(chunks));
    requests.push(body);
    const n = requests.length;
    const item = n === 1
      ? { type: 'function_call', name: 'format_city', call_id: 'format_1', id: 'fc_format',
        arguments: '{"city":"Aurora"}', status: 'completed' }
      : n === 2 ? { type: 'function_call', name: 'load_skill', call_id: 'skill_1', id: 'fc_skill',
        arguments: '{"name":"how-to-write"}', status: 'completed' }
      : answer('msg_custom', 'Both tools worked.');
    res.setHeader('Content-Type', 'text/event-stream');
    res.end(stream(n, item));
  }).listen(0, '127.0.0.1');
  await once(server, 'listening');
  try {
    const output = await runAgent({
      ...base,
      sdk: { followUpModel: 'mock/follow-up', followUpTurn: 2, followUpTemperature: 0.25,
        followUpOutputTokens: 400, followUpInstructions: 'Use short paragraphs.', maxRunTokens: 9000,
        topP: 0.91, providerOnly: 'test-one, test-two', requireParameters: true },
      modelDetails: {
        [base.model]: { tools: true, parameters: ['temperature', 'top_p'] },
        'mock/follow-up': { tools: true, parameters: ['temperature', 'top_p', 'max_tokens'] },
      },
      customTools: [{ name: 'format_city', description: 'Format a city', handler: 'template',
        schema: '{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}',
        value: 'Weather in {{city}}' }],
      skills: [{ name: 'how-to-write', description: 'Use for a writing task.', body: 'Full skill instructions.' },
        { name: 'house-style', description: 'Use for all writing.', body: 'Pinned instructions.', pinned: true }],
    }, { text() {}, activity() {}, approval: async () => false }, new AbortController().signal,
    opts => new OpenRouter({ ...opts, serverURL: 'http://127.0.0.1:' + server.address().port + '/api/v1' }));
    assert.equal(requests.length, 3);
    assert.ok(requests.every(req => req.stream));
    assert.equal(requests[0].model, base.model);
    assert.equal(requests[1].model, 'mock/follow-up');
    assert.equal(requests[1].temperature, 0.25);
    assert.equal(requests[1].max_output_tokens, 400);
    assert.equal(requests[0].top_p, 0.91);
    assert.deepEqual(requests[0].provider.only, ['test-one', 'test-two']);
    assert.ok(requests[0].provider.require_parameters);
    assert.ok(requests[0].stop_server_tools_when.some(condition => condition.max_tokens === 9000));
    assert.match(requests[1].instructions, /Use short paragraphs/);
    assert.match(requests[0].instructions, /Pinned instructions/);
    assert.doesNotMatch(JSON.stringify(requests[0]), /Full skill instructions/);
    assert.match(JSON.stringify(requests[1].input), /Weather in Aurora/);
    assert.match(JSON.stringify(requests[2].input), /Full skill instructions/);
    assert.match(JSON.stringify(output.history), /Full skill instructions/);
  } finally {
    server.closeAllConnections();
    server.close();
  }
});

test('custom webhook calls require approval and can stream preliminary progress', { timeout: 7000 }, async () => {
  let requests = 0;
  const server = createServer(async (req, res) => {
    for await (const unused of req) { void unused; }
    const number = ++requests;
    const item = number % 2 === 1
      ? { type: 'function_call', name: 'send_city', call_id: 'hook_1', id: 'fc_hook',
        arguments: '{"city":"Aurora"}', status: 'completed' }
      : answer('msg_hook_' + number, 'Done.');
    res.setHeader('Content-Type', 'text/event-stream');
    res.end(stream(number, item));
  }).listen(0, '127.0.0.1');
  await once(server, 'listening');
  try {
    let sends = 0;
    const approvals = [];
    const events = [];
    const options = { ...base, customTools: [{
      name: 'send_city', description: 'Sends the city to a fixed endpoint',
      handler: 'webhook', value: 'https://example.com/weather',
      schema: '{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}',
    }] };
    const client = opts => new OpenRouter({ ...opts, serverURL: 'http://127.0.0.1:' + server.address().port + '/api/v1' });
    const callbacks = {
      text() {},
      update: event => events.push(event),
      webhookFetch: async (endpoint, request) => {
        sends++;
        assert.equal(endpoint.href, 'https://example.com/weather');
        assert.equal(request.method, 'POST');
        assert.equal(request.redirect, 'error');
        assert.deepEqual(JSON.parse(request.body), { city: 'Aurora' });
        return new Response('Lookup complete.', { status: 200 });
      },
    };
    await runAgent(options, { ...callbacks, approval: async (...args) => {
      approvals.push(args);
      return false;
    } }, new AbortController().signal, client);
    assert.equal(sends, 0);
    assert.deepEqual(approvals[0], ['send_city', { city: 'Aurora' }, 'https://example.com/weather']);
    const output = await runAgent(options, { ...callbacks, approval: async () => true },
      new AbortController().signal, client);
    assert.equal(sends, 1);
    assert.match(JSON.stringify(output.history), /Lookup complete/);
    assert.ok(events.some(event => event.kind === 'tool' && event.detail?.includes('Waiting for the webhook')));
  } finally {
    server.closeAllConnections();
    server.close();
  }
});

test('the ask-me tool pauses the SDK until a user answer arrives', { timeout: 7000 }, async () => {
  const wire = [];
  const server = createServer(async (req, res) => {
    const chunks = [];
    for await (const bytes of req) chunks.push(bytes);
    wire.push(JSON.parse(Buffer.concat(chunks)));
    const n = wire.length;
    const item = n === 1 ? {
      type: 'function_call', name: 'ask_preference', call_id: 'ask_1', id: 'fc_ask',
      arguments: '{"question":"Tea or coffee?"}', status: 'completed',
    } : answer('msg_ask', 'Thanks.');
    res.setHeader('Content-Type', 'text/event-stream');
    res.end(stream(n, item));
  }).listen(0, '127.0.0.1');
  await once(server, 'listening');
  try {
    let answerUser;
    let shown;
    const promptShown = new Promise(resolve => { shown = resolve; });
    const run = runAgent({ ...base, customTools: [{
      name: 'ask_preference', description: 'Ask for a preference', handler: 'ask',
      schema: '{"type":"object","properties":{"question":{"type":"string"}},"required":["question"]}',
      value: '',
    }] }, {
      text() {}, approval: async () => false,
      ask: (name, args) => new Promise(resolve => {
        shown({ name, args });
        answerUser = resolve;
      }),
    }, new AbortController().signal,
    opts => new OpenRouter({ ...opts, serverURL: 'http://127.0.0.1:' + server.address().port + '/api/v1' }));
    const visible = await Promise.race([promptShown, run.then(() => { throw new Error('Tool never waited.'); })]);
    assert.equal(wire.length, 1);
    assert.deepEqual(visible, { name: 'ask_preference', args: { question: 'Tea or coffee?' } });
    answerUser('Tea');
    await run;
    assert.equal(wire.length, 2);
    assert.match(JSON.stringify(wire[1].input), /Tea/);
  } finally {
    server.closeAllConnections();
    server.close();
  }
});

test('mini app tools reach Android through the local bridge and carry build guidance', { timeout: 7000 }, async () => {
  const requests = [];
  const server = createServer(async (req, res) => {
    const chunks = [];
    for await (const bytes of req) chunks.push(bytes);
    requests.push(JSON.parse(Buffer.concat(chunks)));
    const item = requests.length === 1 ? {
      type: 'function_call', name: 'mini_app_save', call_id: 'call_m', id: 'fc_m', status: 'completed',
      arguments: JSON.stringify({ name: 'Tips', html: '<html><head><title>Tips</title></head></html>' }),
    } : answer('msg_' + requests.length, 'Saved your app.');
    res.setHeader('Content-Type', 'text/event-stream');
    res.end(stream(requests.length, item));
  }).listen(0, '127.0.0.1');
  await once(server, 'listening');
  try {
    const createClient = options => new OpenRouter({ ...options, serverURL: 'http://127.0.0.1:' + server.address().port + '/api/v1' });
    const calls = [];
    const events = {
      text: () => {}, approval: async () => false,
      local: async request => { calls.push(request); return { ok: true, id: 'abc12345', version: 1 }; },
    };
    await runAgent({ ...base, miniApps: true, prompt: 'Make me a tip calculator' }, events,
      new AbortController().signal, createClient);
    const names = requests[0].tools.map(tool => tool.name ?? tool.type);
    assert.deepEqual(names.filter(name => name.startsWith('mini_app_')), ['mini_app_list', 'mini_app_read', 'mini_app_save']);
    assert.match(requests[0].instructions, /one complete HTML file/);
    assert.equal(calls[0].tool, 'mini_app');
    assert.equal(calls[0].type, 'save');
    assert.equal(calls[0].name, 'Tips');
    const output = requests[1].input.find(item => item.type === 'function_call_output');
    assert.match(output.output, /abc12345/);
  } finally {
    server.closeAllConnections();
    server.close();
  }
});

test('mini app tools need the setting and a bridge, and the local bridge rejects on cancel', async () => {
  assert.equal(buildTools({ miniApps: true }, {}).length, 0);
  assert.equal(buildTools({ miniApps: false }, { local: async () => ({}) }).length, 0);
  assert.equal(buildTools({ miniApps: true }, { local: async () => ({}) }).length, 3);
  const textOnly = { miniApps: true, model: 'm/plain', modelDetails: { 'm/plain': { tools: false, parameters: [] } } };
  assert.equal(buildTools(textOnly, { local: async () => ({}) }).length, 0);
  const { createLocalBridge } = await import('../src/local-tools.mjs');
  const sent = [];
  const controller = new AbortController();
  const local = createLocalBridge('run', { local: (...args) => sent.push(args) }, controller.signal);
  const first = local.call({ tool: 'mini_app', type: 'list' });
  assert.deepEqual(sent[0].slice(0, 2), ['run', '1']);
  local.receive('1', '{"ok":true,"apps":[]}');
  assert.deepEqual(await first, { ok: true, apps: [] });
  const second = local.call({ tool: 'mini_app', type: 'list' });
  controller.abort(new Error('stopped'));
  await assert.rejects(second, /stopped/);
});
