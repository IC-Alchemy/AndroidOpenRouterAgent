import test from 'node:test';
import assert from 'node:assert/strict';
import { streamObserver } from '../src/stream-events.mjs';

function collect() {
  const updates = [];
  const observer = streamObserver({
    text: text => updates.push({ kind: 'text', text }),
    update: event => updates.push(event),
  });
  return { updates, observe: observer.observe };
}

const joined = (updates, kind) => updates.filter(update => update.kind === kind).map(update => update.text).join('');

test('raw reasoning text streams live and its done snapshot is not repeated', () => {
  const { updates, observe } = collect();
  observe({ type: 'response.output_item.added', item: { type: 'reasoning', id: 'rs_1', summary: [] } });
  observe({ type: 'response.reasoning_text.delta', itemId: 'rs_1', contentIndex: 0, delta: 'Let me ' });
  observe({ type: 'response.reasoning_text.delta', itemId: 'rs_1', contentIndex: 0, delta: 'think.' });
  observe({ type: 'response.reasoning_text.done', itemId: 'rs_1', contentIndex: 0, text: 'Let me think.' });
  observe({ type: 'response.output_item.done', item: {
    type: 'reasoning', id: 'rs_1', summary: [{ type: 'summary_text', text: 'Thinking' }],
    content: [{ type: 'reasoning_text', text: 'Let me think.' }],
  } });
  assert.equal(joined(updates, 'reasoning'), 'Let me think.');
  assert.ok(updates.some(update => update.kind === 'phase' && update.phase === 'reasoning'));
});

test('a summary-only reasoning item streams its summary', () => {
  const { updates, observe } = collect();
  observe({ type: 'response.reasoning_summary_text.delta', itemId: 'rs_1', summaryIndex: 0, delta: 'Plan' });
  observe({ type: 'response.output_item.done', item: { type: 'reasoning', id: 'rs_1',
    summary: [{ type: 'summary_text', text: 'Plan it.' }] } });
  assert.equal(joined(updates, 'reasoning'), 'Plan it.');
});

test('separate reasoning and text parts are kept apart', () => {
  const { updates, observe } = collect();
  observe({ type: 'response.reasoning_text.delta', itemId: 'rs_1', contentIndex: 0, delta: 'First.' });
  observe({ type: 'response.reasoning_text.delta', itemId: 'rs_2', contentIndex: 0, delta: 'Second.' });
  observe({ type: 'response.output_text.delta', itemId: 'msg_1', contentIndex: 0, delta: 'Checking.' });
  observe({ type: 'response.output_text.delta', itemId: 'msg_2', contentIndex: 0, delta: 'Done.' });
  assert.equal(joined(updates, 'reasoning'), 'First.\n\nSecond.');
  assert.equal(joined(updates, 'text'), 'Checking.\n\nDone.');
});

test('function call arguments stream into the tool row before the call runs', () => {
  const { updates, observe } = collect();
  observe({ type: 'response.output_item.added', item: {
    type: 'function_call', id: 'fc_1', callId: 'call_1', name: 'write_project_file', arguments: '',
  } });
  observe({ type: 'response.function_call_arguments.delta', itemId: 'fc_1', delta: '{"path":' });
  observe({ type: 'response.function_call_arguments.delta', itemId: 'fc_1', delta: '"a.txt"}' });
  observe({ type: 'response.function_call_arguments.done', itemId: 'fc_1', arguments: '{"path":"a.txt"}' });
  const rows = updates.filter(update => update.kind === 'tool');
  assert.ok(rows.every(row => row.id === 'call_1'));
  assert.equal(rows[0].detail, undefined);
  assert.equal(rows[1].detail, '{"path":');
  assert.equal(rows.at(-1).detail, '{"path":"a.txt"}');
  assert.equal(rows.at(-1).name, 'write project file');
});

test('code interpreter code streams into its row', () => {
  const { updates, observe } = collect();
  observe({ type: 'response.output_item.added', item: { type: 'code_interpreter_call', id: 'ci_1', status: 'in_progress' } });
  observe({ type: 'response.code_interpreter_call_code.delta', itemId: 'ci_1', delta: 'print(1)' });
  const row = updates.filter(update => update.kind === 'tool').at(-1);
  assert.equal(row.id, 'ci_1');
  assert.equal(row.detail, 'print(1)');
});
