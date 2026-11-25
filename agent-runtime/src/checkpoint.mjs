import { discardUnfinishedSubagents } from './subagent-state.mjs';

/** The longest note the app writes about work that a stopped run did after its last saved step. */
export const MAX_PROGRESS_NOTE = 12_000;

const short = (value, limit) => {
  const text = String(value ?? '').replace(/\s+/g, ' ').trim();
  return text.length > limit ? text.slice(0, limit - 1) + '…' : text;
};

/**
 * Removes what a later request can't replay: unfinished subagent workers, function calls that never got an output
 * and outputs whose call is gone. OpenRouter rejects a history with a call that has no result.
 */
export function replayableHistory(messages) {
  const cleaned = discardUnfinishedSubagents({ messages: Array.isArray(messages) ? messages : [] }).messages;
  const calls = new Set(cleaned.filter(item => item?.type === 'function_call').map(item => item.callId));
  const outputs = new Set(cleaned.filter(item => item?.type === 'function_call_output').map(item => item.callId));
  return cleaned.filter(item => {
    if (item?.type === 'function_call') return outputs.has(item.callId);
    if (item?.type === 'function_call_output') return calls.has(item.callId);
    return item != null;
  });
}

function messageText(item) {
  if (typeof item.content === 'string') return item.content;
  return (Array.isArray(item.content) ? item.content : [])
    .map(part => part?.text ?? part?.refusal ?? '').filter(Boolean).join('\n');
}

/** One line for a hosted or local tool call that finished before the run stopped, or undefined. */
function toolLine(item) {
  const type = item.type ?? '';
  if (type === 'openrouter:shell' || type === 'shell_call') {
    const commands = item.action?.commands ?? [];
    const outputs = Array.isArray(item.output) ? item.output : [];
    const exits = outputs.map(output => output?.outcome?.exitCode ?? output?.outcome?.exit_code)
      .filter(code => code != null);
    const result = exits.length ? ' (exit ' + exits.join(', ') + ')' : item.status ? ' (' + item.status + ')' : '';
    const text = commands.map(command => short(command, 240)).join('; ');
    return text ? 'shell: ' + text + result : undefined;
  }
  if (type === 'openrouter:web_search' || type === 'web_search_call') {
    const query = item.action?.query ?? item.query;
    return query ? 'web search: ' + short(query, 200) : undefined;
  }
  if (type === 'openrouter:web_fetch') {
    const url = item.action?.url ?? item.url;
    return url ? 'web fetch: ' + short(url, 200) : undefined;
  }
  if (type === 'openrouter:files') {
    const what = [item.operation, item.filename].filter(Boolean).join(' ');
    return what ? 'files: ' + short(what, 200) : undefined;
  }
  if (type === 'openrouter:image_generation') return 'image generation' + (item.status ? ' (' + item.status + ')' : '');
  if (type === 'function_call' && item.name) return 'tool ' + item.name + ': ' + short(item.arguments, 200);
  if (type.startsWith('openrouter:')) return type.slice('openrouter:'.length) + (item.status ? ' (' + item.status + ')' : '');
  return undefined;
}

/**
 * A note in the assistant's voice that tells the next request what the stopped run did after its last saved step:
 * the tool calls it finished and the text it had written. Returns '' when there is nothing to tell.
 */
export function progressNote(items) {
  const lines = [];
  const text = [];
  for (const item of items) {
    if (!item) continue;
    // Text is worth keeping even from a message that was cut off mid-stream.
    if (item.type === 'message') {
      const said = item.role === 'user' ? '' : messageText(item).trim();
      if (said) text.push(said);
      continue;
    }
    if (item.status === 'in_progress' || item.status === 'incomplete') continue;
    const line = toolLine(item);
    if (line) lines.push('- ' + line);
  }
  if (!lines.length && !text.length) return '';
  const parts = ['[This reply stopped before it finished. Work done before the stop:]'];
  if (lines.length) parts.push(lines.join('\n'));
  if (text.length) parts.push('What I had written so far:\n' + text.join('\n\n'));
  const note = parts.join('\n\n');
  return note.length > MAX_PROGRESS_NOTE ? note.slice(0, MAX_PROGRESS_NOTE - 1) + '…' : note;
}

/**
 * The conversation so far, for continuing after a failed or stopped run.
 *
 * `start` is the history the run began with, ending with the user's message. `saved` is the SDK's last saved state
 * messages, written after each finished step. `live` holds every item streamed so far, including work from the
 * request that was cut off. The result replays cleanly as `history` in a new run: the saved steps, then a note
 * about anything that finished after them. `progressed` says whether the run got anywhere at all.
 */
export function runCheckpoint(start, saved, live) {
  const base = Array.isArray(saved) && saved.length > start.length ? saved : start;
  const history = replayableHistory(base);
  const known = new Set(base.map(item => item?.id).filter(Boolean));
  const note = progressNote(live.filter(item => !item?.id || !known.has(item.id)));
  if (note) history.push({ type: 'message', role: 'assistant', content: note });
  return { history, progressed: base.length > start.length || note.length > 0 };
}
