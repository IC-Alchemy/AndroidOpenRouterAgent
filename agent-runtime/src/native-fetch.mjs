export function createNativeTransport(id, bridge, onPhase) {
  let sequence = 0;
  const pending = new Map();

  function dispose(requestId, notify = true) {
    const request = pending.get(requestId);
    if (!request) return;
    pending.delete(requestId);
    request.signal.removeEventListener('abort', request.abort);
    if (notify) bridge.cancelHttp(id, requestId);
    return request;
  }

  async function fetcher(input, init) {
    const request = new Request(input, init);
    if (request.url !== 'https://openrouter.ai/api/v1/responses' || request.method !== 'POST') {
      throw new TypeError('The agent transport only accepts OpenRouter Responses POST requests.');
    }
    const body = await request.text();
    if (request.signal.aborted) throw request.signal.reason;
    const requestId = String(++sequence);
    return new Promise((resolve, reject) => {
      const abort = () => {
        const entry = dispose(requestId);
        const reason = request.signal.reason ?? new DOMException('Request cancelled.', 'AbortError');
        entry?.resolveRead?.();
        if (entry?.stream) entry.stream.error(reason);
        else reject(reason);
      };
      pending.set(requestId, { signal: request.signal, abort, resolve, reject });
      request.signal.addEventListener('abort', abort, { once: true });
      if (request.signal.aborted) return abort();
      onPhase('connecting');
      try {
        bridge.requestHttp(id, requestId, JSON.stringify({
          body,
        }));
      } catch (error) {
        dispose(requestId, false);
        reject(error);
      }
    });
  }

  function receive(requestId, kind, value) {
    const entry = pending.get(requestId);
    if (!entry) return;
    if (kind === 'headers') {
      const bodyless = [204, 205, 304].includes(value.status);
      const stream = bodyless ? null : new ReadableStream({
        start(controller) { entry.stream = controller; },
        pull() {
          return new Promise(resolve => {
            entry.resolveRead = resolve;
            bridge.readHttp(id, requestId);
          });
        },
        cancel() { entry.resolveRead?.(); dispose(requestId); },
      }, { highWaterMark: 0 });
      onPhase('connected');
      entry.resolve(new Response(stream, { status: value.status,
        headers: { 'Content-Type': value.contentType, ...(value.retryAfter ? { 'Retry-After': value.retryAfter } : {}) } }));
      if (bodyless) dispose(requestId);
    } else if (kind === 'chunk') {
      const bytes = Uint8Array.from(atob(value), character => character.charCodeAt(0));
      entry.stream.enqueue(bytes);
      entry.resolveRead?.();
      entry.resolveRead = undefined;
    } else {
      dispose(requestId, false);
      entry.resolveRead?.();
      if (kind === 'end') entry.stream?.close();
      else if (entry.stream) entry.stream.error(new Error(value));
      else entry.reject(new Error(value));
    }
  }

  return { fetcher, receive, abort() { for (const request of pending.values()) request.abort(); } };
}
