import { runAgent } from './runtime.mjs';
import { createNativeTransport } from './native-fetch.mjs';
import { createPhoneBridge } from './phone.mjs';

const bridge = globalThis.AndroidAgent;
const runs = new Map();
const emit = (id, type, value) => bridge.post(JSON.stringify({ id, type, value }));

async function start(options) {
  if (runs.has(options.id)) return;
  const run = { controller: new AbortController(), decisions: new Map(), appRequests: new Map() };
  runs.set(options.id, run);
  let nextDecision = 0;
  let updates = [];
  let flushTimer;
  let lastFlush = 0;
  const sanitize = value => {
    let text = String(value ?? '');
    if (options.apiKey) text = text.replaceAll(options.apiKey, '[API key]');
    if (options.mcpToken) text = text.replaceAll(options.mcpToken, '[MCP token]');
    return text;
  };
  const flush = () => {
    if (flushTimer !== undefined) clearTimeout(flushTimer);
    flushTimer = undefined;
    if (!updates.length) return;
    if (!run.controller.signal.aborted) emit(options.id, 'updates', updates);
    updates = [];
    lastFlush = Date.now();
  };
  const update = event => {
    if (run.controller.signal.aborted) return;
    const previous = updates.at(-1);
    if (previous?.kind === event.kind && ['text', 'reasoning'].includes(event.kind)) previous.text += event.text;
    else updates.push(event);
    const elapsed = Date.now() - lastFlush;
    if (elapsed >= 45 || updates.length >= 40) flush();
    else if (flushTimer === undefined) flushTimer = setTimeout(flush, 45 - elapsed);
  };
  const askUser = (name, args, url, asksUser, savesSettings = false) => new Promise(resolve => {
    if (run.controller.signal.aborted) return resolve(asksUser ? null : false);
    const decision = String(++nextDecision);
    run.decisions.set(decision, (accepted, answer) => resolve(asksUser ? (accepted ? answer : null) : accepted));
    flush();
    emit(options.id, 'approval', { decision, name, args, url, asksUser, savesSettings });
  });
  const appAction = (name, args) => new Promise((resolve, reject) => {
    const { signal } = run.controller;
    if (signal.aborted) return reject(signal.reason);
    const request = String(++nextDecision);
    run.appRequests.set(request, { resolve, reject });
    try {
      emit(options.id, 'app_request', { request, name, args });
    } catch (error) {
      run.appRequests.delete(request);
      reject(error);
    }
  });
  run.transport = createNativeTransport(options.id, bridge,
    (phase, detail) => update({ kind: 'phase', phase, detail }));
  run.phone = options.phoneControl ? createPhoneBridge(options.id, bridge, run.controller.signal) : undefined;
  try {
    const value = await runAgent(options, {
      fetcher: run.transport.fetcher,
      text: text => update({ kind: 'text', text }),
      update: event => {
        if (event.kind === 'reasoning' && options.sdk?.showReasoning === false) return;
        update(event.detail ? { ...event, detail: sanitize(event.detail) } : event);
      },
      register: result => { run.result = result; },
      approval: (name, args, url, savesSettings) => askUser(name, args, url, false, savesSettings),
      ask: (name, args) => askUser(name, args, '', true),
      appAction,
      phone: run.phone ? request => { flush(); return run.phone.call(request); } : undefined,
    }, run.controller.signal);
    flush();
    if (!run.controller.signal.aborted) {
      const { response, history, state } = value;
      emit(options.id, 'done', state.length <= 6_000_000 ? { response, state } : { response, history });
    }
  } catch (error) {
    flush();
    if (!run.controller.signal.aborted) {
      const message = error?.body?.error?.message ?? error?.message ?? error;
      emit(options.id, 'error', sanitize(message).slice(0, 800));
    }
  } finally {
    run.transport.abort();
    for (const { reject } of run.appRequests.values()) reject(new Error('The agent run ended.'));
    run.appRequests.clear();
    run.phone?.dispose();
    runs.delete(options.id);
  }
}

globalThis.AgentRuntime = Object.freeze({
  start,
  http(id, requestId, kind, value) {
    runs.get(id)?.transport.receive(requestId, kind, value);
  },
  phone(id, requestId, json) {
    runs.get(id)?.phone?.receive(requestId, json);
  },
  stop(id) {
    const run = runs.get(id);
    if (!run) return;
    run.controller.abort();
    run.transport.abort();
    for (const resolve of run.decisions.values()) resolve(false);
    run.decisions.clear();
    for (const { reject } of run.appRequests.values()) reject(run.controller.signal.reason);
    run.appRequests.clear();
    run.result?.cancel().catch(() => {});
  },
  decide(id, decision, accepted, answer) {
    const run = runs.get(id);
    const resolve = run?.decisions.get(decision);
    if (resolve) {
      run.decisions.delete(decision);
      resolve(accepted, answer);
    }
  },
  appResult(id, request, ok, value) {
    const run = runs.get(id);
    const pending = run?.appRequests.get(request);
    if (!pending) return;
    run.appRequests.delete(request);
    if (ok) pending.resolve(value);
    else pending.reject(new Error(value));
  },
});
bridge.post('{"type":"ready"}');
