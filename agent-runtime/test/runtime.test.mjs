import assert from 'node:assert/strict';
import test from 'node:test';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { OpenRouter } from '@openrouter/agent';
import { buildCall, buildTools, runAgent } from '../src/runtime.mjs';

const base = {
  apiKey: 'mock-key', history: [], model: 'mock/tool-capable', prompt: 'Tell me the device time zone',
  sessionId: 'mock-session', maxSteps: 4, maxCost: 0.25,
};

function itemEvents(item) {
  const events = [
    { type: 'response.output_item.added', item, output_index: 0 },
  ];
  if (item.type === 'message') {
    events.push({ type: 'response.output_text.delta', output_index: 0, item_id: item.id, content_index: 0,
      delta: item.content[0].text, logprobs: [] });
  }
  events.push({ type: 'response.output_item.done', item, output_index: 0 });
  return events;
}

function stream(record, item) {
  const reply = {
    id: 'gen-' + record, object: 'response', model: base.model, created_at: 1700000000, completed_at: 1700000001,
    error: null, frequency_penalty: null, incomplete_details: null, instructions: null, metadata: null,
    output: [item], parallel_tool_calls: false, presence_penalty: null, status: 'completed',
    temperature: null, tool_choice: 'auto', tools: [], top_p: null,
    usage: {
      input_tokens: 8, input_tokens_details: { cached_tokens: 0 },
      output_tokens: 3, output_tokens_details: { reasoning_tokens: 0 }, total_tokens: 11, cost: 0.01,
    },
  };
  return [...itemEvents(item), { type: 'response.completed', response: reply }].map((event, sequence) =>
    'event: ' + event.type + '\ndata: ' + JSON.stringify({ ...event, sequence_number: sequence }) + '\n\n'
  ).join('') + 'data: [DONE]\n\n';
}

function answer(id, text) {
  return { type: 'message', id, role: 'assistant', status: 'completed',
    content: [{ type: 'output_text', text, annotations: [] }] };
}

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
  const { readFile } = await import('node:fs/promises');
  const { runInNewContext } = await import('node:vm');
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
  const wire = (type, sequence, fields) => 'event: ' + type + '\ndata: ' +
    JSON.stringify({ ...fields, type, sequence_number: sequence }) + '\n\n';
  const start = wire('response.reasoning_summary_text.delta', 0, {
    item_id: 'rs_fixture', output_index: 0, summary_index: 0, delta: 'Comparing options. ',
  }) + wire('response.output_text.delta', 1, {
    item_id: 'msg_fixture', output_index: 1, content_index: 0, delta: 'Streaming early.', logprobs: [],
  }) + wire('response.output_item.done', 2, { item: shell, output_index: 2 });

  const browserGlobals = {
    Promise, fetch: () => { throw new Error('The model request must use the Android HTTP bridge.'); },
    URL, URLSearchParams, Headers, Request, Response, TextDecoder, TextEncoder, ReadableStream, TransformStream,
    AbortController, AbortSignal, DOMException, atob, Uint8Array, setTimeout, clearTimeout, setInterval,
    clearInterval, queueMicrotask, performance, console, Intl, crypto: globalThis.crypto,
    AndroidAgent: {
      post(text) {
        const event = JSON.parse(text);
        events.push(event);
        const updates = events.filter(event => event.type === 'updates').flatMap(event => event.value);
        if (updates.some(update => update.kind === 'text') && updates.some(update => update.kind === 'reasoning') &&
            updates.some(update => update.kind === 'files')) liveObserved();
      },
      requestHttp(id, requestId, json) {
        const payload = JSON.parse(json);
        assert.equal(payload.authorization, undefined);
        requests.push(JSON.parse(payload.body));
        queueMicrotask(() => browserGlobals.AgentRuntime.http(id, requestId, 'headers',
          { status: 200, contentType: 'text/event-stream' }));
      },
      readHttp(id, requestId) {
        const turn = ++read;
        if (turn === 1) queueMicrotask(() => browserGlobals.AgentRuntime.http(id, requestId, 'chunk',
          Buffer.from(start).toString('base64')));
        if (turn === 2) finalAllowed.then(() => browserGlobals.AgentRuntime.http(id, requestId, 'chunk',
          Buffer.from(stream(1, shell)).toString('base64')));
        if (turn > 2) queueMicrotask(() => browserGlobals.AgentRuntime.http(id, requestId, 'end', null));
      },
      cancelHttp() {},
    },
  };
  const bundle = await readFile(new URL('../../app/src/main/assets/agent/agent.js', import.meta.url), 'utf8');
  runInNewContext(bundle, browserGlobals);
  assert.equal(events[0]?.type, 'ready');
  const run = browserGlobals.AgentRuntime.start({ ...base, id: 'fixture', shell: true, webSearch: true,
    dateTime: true, domains: ['pypi.org'] });
  await Promise.race([observed, run.then(() => { throw new Error(JSON.stringify(events)); })]);
  assert.ok(!events.some(event => ['done', 'error'].includes(event.type)));
  assert.equal(requests.length, 1);
  assert.equal(requests[0].stream, true);
  assert.equal(requests[0].tools[0].parameters.engine, 'openrouter');
  assert.deepEqual(requests[0].tools[0].parameters.environment.network_policy.allowed_domains, ['pypi.org']);
  assert.ok(requests[0].tools.some(tool => tool.type === 'openrouter:web_search'));
  assert.ok(requests[0].tools.some(tool => tool.type === 'openrouter:datetime'));
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

test('phone control tools reach Android through the bridge, one call at a time, with untrusted-screen guidance',
  { timeout: 7000 }, async () => {
    const requests = [];
    const server = createServer(async (req, res) => {
      const chunks = [];
      for await (const bytes of req) chunks.push(bytes);
      requests.push(JSON.parse(Buffer.concat(chunks)));
      const item = requests.length === 1 ? {
        type: 'function_call', name: 'phone_action', call_id: 'call_p', id: 'fc_p',
        arguments: '{"action":"tap","element":3}', status: 'completed',
      } : answer('msg_' + requests.length, 'Tapped it.');
      res.setHeader('Content-Type', 'text/event-stream');
      res.end(stream(requests.length, item));
    }).listen(0, '127.0.0.1');
    await once(server, 'listening');
    try {
      const createClient = options => new OpenRouter({ ...options, serverURL: 'http://127.0.0.1:' + server.address().port + '/api/v1' });
      const calls = [];
      const events = {
        text: () => {}, approval: async () => false,
        phone: async request => {
          calls.push(request);
          return { ok: true, result: 'Done.', screen: '<untrusted_screen>\nApp: Notes\n</untrusted_screen>' };
        },
      };
      await runAgent({ ...base, phoneControl: true, prompt: 'Tap the third item' }, events,
        new AbortController().signal, createClient);
      const names = requests[0].tools.map(tool => tool.name ?? tool.type);
      assert.deepEqual(names.filter(name => name.startsWith('phone_')), ['phone_screen', 'phone_apps', 'phone_action']);
      assert.equal(requests[0].parallel_tool_calls, false);
      assert.match(requests[0].instructions, /untrusted_screen/);
      assert.deepEqual(calls, [{ type: 'action', action: 'tap', element: 3 }]);
      const output = requests[1].input.find(item => item.type === 'function_call_output');
      assert.equal(output.call_id, 'call_p');
      assert.match(output.output, /App: Notes/);
    } finally {
      server.closeAllConnections();
      server.close();
    }
  });

test('phone tools are absent without the setting or an Android bridge, and the bridge rejects on cancel', async () => {
  assert.equal(buildTools({ phoneControl: true }, {}).length, 0);
  assert.equal(buildTools({ phoneControl: true }, { phone: async () => ({}) }).length, 3);
  assert.equal(buildTools({ phoneControl: false }, { phone: async () => ({}) }).length, 0);
  const { createPhoneBridge } = await import('../src/phone.mjs');
  const sent = [];
  const controller = new AbortController();
  const phone = createPhoneBridge('run', { phone: (...args) => sent.push(args) }, controller.signal);
  const first = phone.call({ type: 'screen' });
  assert.deepEqual(sent[0].slice(0, 2), ['run', '1']);
  phone.receive('1', '{"ok":true,"screen":"x"}');
  assert.deepEqual(await first, { ok: true, screen: 'x' });
  const second = phone.call({ type: 'action', action: 'back' });
  controller.abort(new Error('stopped'));
  await assert.rejects(second, /stopped/);
});
