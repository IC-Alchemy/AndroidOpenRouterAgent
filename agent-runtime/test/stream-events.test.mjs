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

const rows = updates => updates.filter(update => update.kind === 'tool');

test('the apply-patch diff on the done event becomes the row detail', () => {
  const { updates, observe } = collect();
  observe({ type: 'response.output_item.added', item: { type: 'apply_patch_call', id: 'ap_1', status: 'in_progress' } });
  observe({ type: 'response.apply_patch_call_operation_diff.delta', itemId: 'ap_1', delta: '@@ a' });
  observe({ type: 'response.apply_patch_call_operation_diff.delta', itemId: 'ap_1', delta: ' b c' });
  observe({ type: 'response.apply_patch_call_operation_diff.done', itemId: 'ap_1', diff: '@@ a b c' });
  assert.equal(rows(updates).at(-1).detail, '@@ a b c');
});

test('streamed input does not move a running row back to preparing', () => {
  const { updates, observe } = collect();
  observe({ type: 'response.output_item.added', item: { type: 'mcp_call', id: 'mcp_1', status: 'in_progress' } });
  observe({ type: 'response.mcp_call.in_progress', itemId: 'mcp_1' });
  observe({ type: 'response.mcp_call_arguments.delta', itemId: 'mcp_1', delta: '{"q":1}' });
  observe({ type: 'response.mcp_call_arguments.done', itemId: 'mcp_1', arguments: '{"q":1}' });
  assert.ok(rows(updates).slice(1).every(row => row.status !== 'preparing'));
});

test('long arguments keep showing their end when the call is done', () => {
  const { updates, observe } = collect();
  const value = 'x'.repeat(1500) + 'END';
  observe({ type: 'response.output_item.added', item: { type: 'function_call', id: 'fc_1', callId: 'c1', name: 'run' } });
  observe({ type: 'response.function_call_arguments.done', itemId: 'fc_1', arguments: value });
  assert.ok(rows(updates).at(-1).detail.endsWith('END'));
});

test('input held back by the throttle is shown when the stream ends without a done event', () => {
  const { updates, observe } = collect();
  observe({ type: 'response.output_item.added', item: { type: 'custom_tool_call', id: 'ct_1', callId: 'c1', name: 'x' } });
  observe({ type: 'response.custom_tool_call_input.delta', itemId: 'ct_1', delta: '{"a":' });
  observe({ type: 'response.custom_tool_call_input.delta', itemId: 'ct_1', delta: '1}' });
  observe({ type: 'response.failed', response: { id: 'r1', output: [] } });
  assert.equal(rows(updates).at(-1).detail, '{"a":1}');
});

test('each fusion panel gets its own row with streamed text', () => {
  const { updates, observe } = collect();
  observe({ type: 'response.fusion_call.panel.added', itemId: 'fu_1', model: 'a/one' });
  observe({ type: 'response.fusion_call.panel.added', itemId: 'fu_1', model: 'b/two' });
  observe({ type: 'response.fusion_call.panel.delta', itemId: 'fu_1', model: 'a/one', delta: 'Hello' });
  observe({ type: 'response.fusion_call.panel.completed', itemId: 'fu_1', model: 'a/one', content: 'Hello world' });
  const one = rows(updates).filter(row => row.id === 'fu_1:a/one');
  const two = rows(updates).filter(row => row.id === 'fu_1:b/two');
  assert.equal(one.at(-1).status, 'completed');
  assert.equal(one.at(-1).detail, 'Hello world');
  assert.equal(one.at(-1).name, 'fusion · a/one');
  assert.equal(two.at(-1).status, 'running');
});

test('reasoning tokens are added up from each response', () => {
  const { updates, observe } = collect();
  observe({ type: 'response.completed', response: { id: 'r1', output: [],
    usage: { inputTokens: 5, outputTokens: 9, outputTokensDetails: { reasoningTokens: 4 } } } });
  assert.equal(updates.find(update => update.kind === 'usage').reasoningTokens, 4);
});

test('private reasoning is reported instead of showing nothing', () => {
  const { updates, observe } = collect();
  observe({ type: 'response.output_item.done', item: { type: 'reasoning', id: 'rs_1', summary: [], encryptedContent: 'x' } });
  assert.ok(updates.some(update => update.kind === 'phase' && update.detail === 'hidden'));
});

test('reasoning summaries are requested while reasoning is shown, unless the user chose otherwise', async () => {
  const { sdkParameters } = await import('../src/customization.mjs');
  const details = { m: { tools: true, parameters: ['reasoning'] } };
  const call = sdk => sdkParameters({ model: 'm', modelDetails: details, sdk }, false).reasoning?.({});
  assert.deepEqual(call({}), { summary: 'auto' });
  assert.equal(call({ showReasoning: false }), undefined);
  assert.deepEqual(call({ reasoning: 'off' }), { enabled: false });
  assert.deepEqual(call({ reasoningSummary: 'detailed' }), { summary: 'detailed' });
  const plain = { m: { tools: true, parameters: [] } };
  assert.equal(sdkParameters({ model: 'm', modelDetails: plain, sdk: {} }, false).reasoning?.({}), undefined);
});
