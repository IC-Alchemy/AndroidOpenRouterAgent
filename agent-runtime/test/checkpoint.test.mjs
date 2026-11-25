import assert from 'node:assert/strict';
import test from 'node:test';
import { MAX_PROGRESS_NOTE, progressNote, replayableHistory, runCheckpoint } from '../src/checkpoint.mjs';

const user = { type: 'message', role: 'user', content: 'Build an astronomy web app' };
const earlier = [
  { type: 'message', role: 'user', content: 'Hi' },
  { type: 'message', role: 'assistant', content: 'Hello' },
];

test('a run stopped mid-request keeps the hosted tool work it finished as a note for the next request', () => {
  const live = [
    { id: 'sh1', type: 'openrouter:shell', status: 'completed', action: { commands: ['npm view three version'] },
      output: [{ outcome: { type: 'exit', exitCode: 0 } }] },
    { id: 'ws1', type: 'openrouter:web_search', status: 'completed', action: { query: 'three.js UMD build' } },
    { id: 'sh2', type: 'openrouter:shell', status: 'in_progress', action: { commands: ['sleep 900'] } },
    { id: 'm1', type: 'message', role: 'assistant', status: 'in_progress',
      content: [{ type: 'output_text', text: 'Confirmed the builds I need.' }] },
  ];
  const { history, progressed } = runCheckpoint([...earlier, user], undefined, live);
  assert.equal(progressed, true);
  assert.deepEqual(history.slice(0, 3), [...earlier, user]);
  assert.equal(history.length, 4);
  const note = history[3];
  assert.equal(note.role, 'assistant');
  assert.match(note.content, /stopped before it finished/);
  assert.match(note.content, /shell: npm view three version \(exit 0\)/);
  assert.match(note.content, /web search: three\.js UMD build/);
  assert.match(note.content, /Confirmed the builds I need\./);
  assert.doesNotMatch(note.content, /sleep 900/, 'a command that never finished is not reported as done');
});

test('saved SDK steps are used, and live items already in them are not repeated', () => {
  const call = { id: 'fc1', type: 'function_call', callId: 'c1', name: 'list_project_files', arguments: '{}' };
  const output = { type: 'function_call_output', callId: 'c1', output: '[]' };
  const reply = { id: 'm1', type: 'message', role: 'assistant', content: [{ type: 'output_text', text: 'Listed.' }] };
  const saved = [user, call, output, reply];
  const { history, progressed } = runCheckpoint([user], saved, [call, reply]);
  assert.equal(progressed, true);
  assert.deepEqual(history, saved);
});

test('dangling function calls and unfinished subagents are dropped so the history replays', () => {
  const messages = [
    user,
    { type: 'function_call', callId: 'done', name: 'a', arguments: '{}' },
    { type: 'function_call_output', callId: 'done', output: 'ok' },
    { type: 'function_call', callId: 'open', name: 'b', arguments: '{}' },
    { type: 'function_call_output', callId: 'orphan', output: 'x' },
    { type: 'openrouter:subagent', callId: 'w1', status: 'in_progress' },
    { type: 'function_call', callId: 'child', subagentId: 'w1', name: 'c', arguments: '{}' },
  ];
  assert.deepEqual(replayableHistory(messages).map(item => item.callId ?? item.role),
    ['user', 'done', 'done']);
});

test('a run that did nothing reports no progress and adds no note', () => {
  const { history, progressed } = runCheckpoint([user], [user], []);
  assert.equal(progressed, false);
  assert.deepEqual(history, [user]);
  assert.equal(progressNote([{ type: 'message', role: 'user', content: 'x' }]), '');
});

test('the progress note stays bounded', () => {
  const live = Array.from({ length: 400 }, (_, index) => ({
    type: 'openrouter:shell', status: 'completed', action: { commands: ['echo ' + 'x'.repeat(300) + index] },
  }));
  assert.ok(progressNote(live).length <= MAX_PROGRESS_NOTE);
});
