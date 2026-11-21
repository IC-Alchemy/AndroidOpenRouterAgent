const terminal = new Set(['completed', 'failed', 'cancelled', 'timed_out', 'expired', 'incomplete', 'declined']);

function label(name) {
  return String(name || 'Tool').replace(/^openrouter:|^remote_/g, '').replaceAll('_', ' ');
}

function preview(value, limit = 1200) {
  if (value == null) return undefined;
  const candidate = typeof value === 'object' && value !== null
    ? value.message ?? value.status ?? value.progressMessage ?? value
    : value;
  const text = typeof candidate === 'string' ? candidate : JSON.stringify(candidate);
  return text?.length > limit ? text.slice(0, limit) + '…' : text;
}

export function streamObserver(events) {
  const tools = new Map();
  const textParts = new Map();
  const summaryParts = new Map();
  const counts = { inputTokens: 0, outputTokens: 0, cachedTokens: 0, cost: 0 };
  const accounted = new Set();
  let hasCost = false;
  let step = 1;
  let lastPhase;
  let lastPulse = 0;

  function tool(id, name, status, detail) {
    if (!id) return;
    const previous = tools.get(id);
    const next = {
      id, name: name ? label(name) : previous?.name ?? 'Tool',
      status: terminal.has(previous?.status) && (!terminal.has(status) || (previous.status !== 'completed' && status === 'completed')) ? previous.status : status,
      detail: detail === undefined ? previous?.detail : preview(detail),
    };
    if (previous && previous.name === next.name && previous.status === next.status && previous.detail === next.detail) return;
    if (!previous && tools.size >= 80) tools.delete(tools.keys().next().value);
    tools.set(id, next);
    events.update?.({ kind: 'tool', ...next });
  }

  function text(map, id, value, type, snapshot = false) {
    if (!value || typeof value !== 'string') return;
    const previous = map.get(id) ?? '';
    const delta = snapshot ? (value.startsWith(previous) ? value.slice(previous.length) : '') : value;
    if (!delta) return;
    map.set(id, previous + delta);
    if (type === 'text') events.text(delta);
    else events.update?.({ kind: 'reasoning', text: delta });
  }

  function usage(response) {
    const metrics = response?.usage;
    if (!metrics || accounted.has(response.id)) return;
    if (response.id) accounted.add(response.id);
    counts.inputTokens += metrics.inputTokens ?? 0;
    counts.outputTokens += metrics.outputTokens ?? 0;
    counts.cachedTokens += metrics.inputTokensDetails?.cachedTokens ?? 0;
    if (typeof metrics.cost === 'number') {
      hasCost = true;
      counts.cost += metrics.cost;
    }
    events.update?.({ kind: 'usage', ...counts, cost: hasCost ? counts.cost : undefined });
  }

  function phase(name, detail) {
    const key = JSON.stringify([name, detail, step]);
    if (lastPhase === key) return;
    lastPhase = key;
    events.update?.({ kind: 'phase', phase: name, detail, step });
  }

  function readItem(item, ready) {
    if (!item) return;
    const id = item.callId || item.id;
    if (item.type === 'message') {
      if (ready) item.content?.forEach((part, index) => {
        if (part.type === 'output_text' || part.type === 'refusal') {
          text(textParts, item.id + ':' + index, part.text ?? part.refusal, 'text', true);
          part.annotations?.forEach(readSource);
        }
      });
      return;
    }
    if (item.type === 'reasoning') {
      phase('reasoning');
      if (ready) item.summary?.forEach((part, index) =>
        text(summaryParts, item.id + ':' + index, part.text, 'reasoning', true));
      return;
    }
    if (item.type === 'function_call_output') {
      tool(id, undefined, 'completed', item.output);
      return;
    }
    if (item.type === 'function_call') {
      phase('tools');
      tool(id, item.name, 'preparing', ready ? item.arguments : undefined);
      return;
    }
    if (item.type?.startsWith('openrouter:') || /_call$/.test(item.type ?? '')) {
      phase('tools');
      tool(id, item.type, item.status ?? (ready ? 'completed' : 'running'), item.error || item.action?.query);
      if (item.files?.length) {
        events.update?.({ kind: 'files', containerId: item.containerId,
          files: item.files.map(file => ({ id: file.fileId, name: file.filename })).filter(file => file.id && file.name) });
      }
    }
  }

  const sources = new Set();
  function readSource(annotation) {
    if (annotation?.type !== 'url_citation' || !annotation.url || sources.has(annotation.url)) return;
    if (sources.size >= 48) return;
    sources.add(annotation.url);
    events.update?.({ kind: 'source', url: annotation.url, title: annotation.title });
  }

  function observe(event) {
    const now = Date.now();
    if (now - lastPulse >= 1000) {
      lastPulse = now;
      events.update?.({ kind: 'pulse' });
    }
    const index = event.contentIndex ?? event.summaryIndex ?? 0;
    const itemId = event.itemId ?? event.item_id;
    const part = itemId + ':' + index;
    switch (event.type) {
      case 'turn.start':
        step = (event.turnNumber ?? 0) + 1;
        phase('requesting');
        break;
      case 'turn.end':
        break;
      case 'response.created':
        phase('waiting', event.response?.model);
        break;
      case 'response.in_progress':
        phase('waiting');
        break;
      case 'response.output_text.delta':
      case 'response.refusal.delta':
        phase('answering');
        text(textParts, part, event.delta, 'text');
        break;
      case 'response.output_text.done':
        text(textParts, part, event.text, 'text', true);
        break;
      case 'response.refusal.done':
        text(textParts, part, event.refusal, 'text', true);
        break;
      case 'response.reasoning_summary_text.delta':
        phase('reasoning');
        text(summaryParts, part, event.delta, 'reasoning');
        break;
      case 'response.reasoning_summary_text.done':
        text(summaryParts, part, event.text, 'reasoning', true);
        break;
      case 'response.reasoning.delta':
      case 'response.reasoning_text.delta':
        phase('reasoning');
        break;
      case 'response.output_item.added':
        readItem(event.item, false);
        break;
      case 'response.output_item.done':
        readItem(event.item, true);
        break;
      case 'response.function_call_arguments.delta':
      case 'response.custom_tool_call_input.delta':
        phase('tools');
        break;
      case 'response.function_call_arguments.done':
      case 'response.custom_tool_call_input.done':
        phase('tools');
        break;
      case 'response.output_text.annotation.added': {
        readSource(event.annotation);
        break;
      }
      case 'tool.preliminary_result':
        phase('tools');
        tool(event.toolCallId, event.toolName, 'running', event.result);
        break;
      case 'tool.result':
        tool(event.toolCallId, event.toolName, event.result?.isError ? 'failed' : 'completed', event.result);
        break;
      case 'tool.call_output':
        readItem(event.output, true);
        break;
      case 'tool.async_started':
        phase('tools');
        tool(event.toolCallId, event.toolName, 'running in background', event.ack);
        break;
      case 'tool.async_settled':
        tool(event.toolCallId, undefined, event.status, event.error ?? event.result);
        break;
      case 'response.completed':
      case 'response.incomplete':
      case 'response.failed':
        usage(event.response);
        event.response?.output?.forEach(item => readItem(item, true));
        break;
      default: {
        const match = /^response\.(web_search_call|file_search_call|mcp_call|mcp_list_tools|code_interpreter_call|image_generation_call|fusion_call(?:\.panel|\.analysis)?)\.([a-z_]+)$/.exec(event.type);
        if (!match) break;
        phase('tools');
        const status = match[2];
        // Media payloads and raw reasoning are not copied into the Android event queue.
        tool(itemId ?? event.callId, match[1].replace(/_call$/, ''),
          terminal.has(status) ? status : 'running', ['delta', 'partial_image', 'reasoning_delta'].includes(status) ? undefined : label(status));
      }
    }
  }

  function toolStarted(name, status = 'running', detail) {
    const row = Array.from(tools.values()).reverse().find(row => row.name === label(name) && !terminal.has(row.status));
    if (row) tool(row.id, name, status, detail);
    phase('tools');
  }

  return { observe, phase, toolStarted };
}
