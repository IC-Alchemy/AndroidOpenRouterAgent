// Photos, documents and generated images travel as base64, which would make saved chats huge.
// Before a run's state is saved, each one is stored on the device and replaced by a short marker.
// Before the next run, the markers are swapped back for the data.

const MARKER = /^oratt:(url|b64):([0-9a-f]{64}\.[a-z0-9]{2,4})$/;
const BASE64_KEYS = new Set(['imageB64', 'image_b64', 'b64Json', 'b64_json', 'result', 'partialImageB64',
  'partial_image_b64']);
const MIN_INLINE = 512;
const MISSING = Symbol('missing');
export const MISSING_TEXT = '[An attachment from earlier in this chat is no longer on this device.]';

const isBase64 = text => /^[A-Za-z0-9+/=\r\n]+$/.test(text);

/** Swaps stored media for markers. `store(kind, value)` returns a file name, or '' when it can't keep the value. */
export function dehydrate(value, store, known = new Map()) {
  const walk = (item, key) => {
    if (typeof item === 'string') {
      if (item.length < MIN_INLINE) return item;
      const cached = known.get(item);
      if (cached) return cached;
      let kind = null;
      if (item.startsWith('data:') && item.slice(0, 120).includes(';base64,')) kind = 'url';
      else if (BASE64_KEYS.has(key) && isBase64(item)) kind = 'b64';
      if (!kind) return item;
      const file = store(kind, item);
      if (!file) return item;
      const marker = `oratt:${kind}:${file}`;
      known.set(item, marker);
      return marker;
    }
    if (Array.isArray(item)) return item.map(entry => walk(entry));
    if (item && typeof item === 'object') {
      return Object.fromEntries(Object.entries(item).map(([name, entry]) => [name, walk(entry, name)]));
    }
    return item;
  };
  return walk(value);
}

/**
 * Swaps markers back for media. `read(kind, file)` returns the data, or '' when the file is gone.
 * A missing input part becomes a short note; any other item that needed the file is dropped.
 */
export function hydrate(value, read, known = new Map()) {
  const walk = item => {
    if (typeof item === 'string') {
      const match = MARKER.exec(item);
      if (!match) return item;
      const data = read(match[1], match[2]);
      if (!data) return MISSING;
      known.set(data, item);
      return data;
    }
    if (Array.isArray(item)) {
      const out = [];
      for (const entry of item) {
        const next = walk(entry);
        if (next !== MISSING) out.push(next);
      }
      return out;
    }
    if (item && typeof item === 'object') {
      let missing = false;
      const out = {};
      for (const [name, entry] of Object.entries(item)) {
        const next = walk(entry);
        if (next === MISSING) missing = true;
        else out[name] = next;
      }
      if (!missing) return out;
      return typeof item.type === 'string' && item.type.startsWith('input_')
        ? { type: 'input_text', text: MISSING_TEXT }
        : MISSING;
    }
    return item;
  };
  const result = walk(value);
  return result === MISSING ? null : result;
}

/** The same, for the SDK's serialized conversation state. */
export function hydrateState(state, read, known) {
  if (typeof state !== 'string' || !state.includes('oratt:')) return state;
  return JSON.stringify(hydrate(JSON.parse(state), read, known));
}

export function dehydrateState(state, store, known) {
  if (typeof state !== 'string' || state.length < MIN_INLINE) return state;
  return JSON.stringify(dehydrate(JSON.parse(state), store, known));
}

/**
 * Builds the input parts for attachments on this message. Each attachment is
 * `{ kind, file, name, mimeType, text }`; `read(kind, file)` returns a data URL ('url') or base64 ('b64').
 */
export function attachmentParts(attachments, read, known = new Map()) {
  const parts = [];
  for (const item of attachments ?? []) {
    if (item.kind === 'text') {
      parts.push({ type: 'input_text', text: `Attached file ${item.name}:\n\n${item.text ?? ''}` });
      continue;
    }
    const kind = item.kind === 'audio' ? 'b64' : 'url';
    const data = read(kind, item.file);
    if (!data) throw new Error(`The attachment ${item.name} is no longer on this device. Remove it and attach it again.`);
    known.set(data, `oratt:${kind}:${item.file}`);
    if (item.kind === 'image') parts.push({ type: 'input_image', detail: 'auto', imageUrl: data });
    else if (item.kind === 'file') parts.push({ type: 'input_file', filename: item.name || 'document.pdf', fileData: data });
    else if (item.kind === 'audio') {
      parts.push({ type: 'input_audio', inputAudio: { data, format: item.mimeType === 'audio/wav' ? 'wav' : 'mp3' } });
    }
  }
  return parts;
}

/** The user message content: plain text, or text followed by attachment parts. */
export function promptContent(prompt, parts) {
  if (!parts?.length) return prompt;
  return [...(prompt ? [{ type: 'input_text', text: prompt }] : []), ...parts];
}
