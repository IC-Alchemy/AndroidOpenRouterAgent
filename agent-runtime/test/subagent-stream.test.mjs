import assert from 'node:assert/strict';
import test from 'node:test';
import {
  streamEventsResponseOutputItemAddedFromJSON,
  streamEventsResponseOutputItemDoneFromJSON,
} from '@openrouter/sdk/models';
import { streamObserver } from '../src/stream-events.mjs';

function capture() {
  const updates = [];
  const answer = [];
  const observer = streamObserver({ update: event => updates.push(event), text: text => answer.push(text) });
  function item(item, ready = false) {
    const decode = ready ? streamEventsResponseOutputItemDoneFromJSON : streamEventsResponseOutputItemAddedFromJSON;
    const parsed = decode(JSON.stringify({
      type: ready ? 'response.output_item.done' : 'response.output_item.added',
      item, output_index: 0, sequence_number: 0,
    }));
    assert.equal(parsed.ok, true, parsed.error?.message);
    observer.observe(parsed.value);
    return parsed.value.item;
  }
  return {
    ...observer, item, answer, updates,
    activity: id => updates.filter(event => event.kind === 'tool' && event.id === id),
    snapshot: (...output) => observer.observe({ type: 'response.completed', response: { output } }),
    turn: turnNumber => observer.observe({ type: 'turn.start', turnNumber }),
  };
}

test('a suspended worker retains one activity across inherited function rounds and a fresh terminal item', () => {
  const stream = capture();
  stream.turn(0);
  const worker = {
    type: 'openrouter:subagent', id: 'st_1', call_id: 'call_worker', status: 'in_progress',
    instance_name: 'Warehouse Desk', task_name: 'track-order', task_description: 'Find the latest shipping status.',
  };
  const suspended = stream.item(worker);
  assert.deepEqual(stream.activity('call_worker').map(event => event.status), ['running']);

  const call = {
    type: 'function_call', id: 'fc_1', call_id: 'call_lookup', name: 'lookup_order', arguments: '{}',
    status: 'completed', subagent_id: 'call_worker',
  };
  stream.item(call);
  const lookup = stream.item({
    ...call, subagent_items: [{ type: 'message', role: 'assistant', content: [
      { type: 'output_text', text: 'Opaque worker transcript' },
    ] }],
  }, true);
  const sibling = stream.item({ ...call, id: 'fc_2', call_id: 'call_carrier', name: 'lookup_carrier' }, true);
  stream.snapshot(suspended, lookup, sibling);
  stream.snapshot(suspended, lookup, sibling);
  assert.deepEqual(stream.activity('call_worker').map(event => event.status), ['running', 'waiting for client tools']);
  assert.match(stream.activity('call_lookup')[0].name, /lookup order.*Warehouse Desk/);
  assert.match(stream.activity('call_carrier')[0].name, /lookup carrier.*Warehouse Desk/);
  stream.toolStarted('lookup_order');
  assert.equal(stream.activity('call_lookup').at(-1).status, 'running');
  stream.observe({ type: 'tool.result', toolCallId: 'call_lookup', toolName: 'lookup_order', result: { found: true } });
  assert.match(stream.activity('call_lookup').at(-1).name, /lookup order.*Warehouse Desk/);

  stream.turn(1);
  const nextCall = stream.item({ ...call, id: 'fc_3', call_id: 'call_status', name: 'get_tracking_status' }, true);
  stream.snapshot(nextCall);
  stream.turn(2);
  const done = {
    type: 'openrouter:subagent', id: 'st_2', call_id: 'call_worker', status: 'completed',
    model: 'mock/shipment-model', outcome: 'Order was shipped.',
  };
  stream.item({ ...done, outcome: undefined });
  const completed = stream.item(done, true);
  const answer = stream.item({ type: 'message', id: 'msg_1', role: 'assistant', status: 'completed',
    content: [{ type: 'output_text', text: 'Your order has shipped.', annotations: [] }],
  }, true);
  stream.snapshot(completed, answer);
  stream.snapshot(completed, answer);
  stream.snapshot(suspended);

  const activity = stream.activity('call_worker');
  assert.deepEqual(activity.map(event => event.status), [
    'running', 'waiting for client tools', 'running', 'waiting for client tools', 'running', 'completed', 'completed',
  ]);
  assert.equal(stream.activity('st_1').length + stream.activity('st_2').length, 0);
  assert.match(activity.at(-1).name, /Subagent.*Warehouse Desk/);
  assert.match(activity.at(-1).detail, /mock\/shipment-model/);
  assert.match(activity.at(-1).detail, /track-order/);
  assert.match(activity.at(-1).detail, /Find the latest shipping status\./);
  assert.match(activity.at(-1).detail, /Order was shipped\./);
  assert.deepEqual(stream.answer, ['Your order has shipped.']);
  assert.doesNotMatch(JSON.stringify(stream.updates), /Opaque worker transcript/);
});

test('an initially anonymous item and its enriched failure update one bounded activity', () => {
  const stream = capture();
  const placeholder = { type: 'openrouter:subagent', id: 'st_1', status: 'in_progress' };
  stream.item(placeholder);
  const failed = stream.item({
    ...placeholder, call_id: 'call_worker', name: 'Researcher', status: 'error',
    task_name: 'Research', task_description: 'Long task description '.repeat(100),
    error: 'Worker unavailable. '.repeat(100),
  }, true);
  stream.snapshot(failed);
  stream.snapshot({ ...failed, id: 'st_2' });
  const activity = stream.activity('st_1');
  assert.deepEqual(activity.map(event => event.status), ['running', 'failed']);
  assert.match(activity.at(-1).name, /Researcher/);
  assert.match(activity.at(-1).detail, /Worker unavailable/);
  assert.ok(activity.at(-1).detail.length <= 1201);
  assert.equal(stream.activity('call_worker').length + stream.activity('st_2').length, 0);
});

test('a run that stops marks still-active workers without replacing known terminal statuses', () => {
  const stream = capture();
  stream.item({ type: 'openrouter:subagent', call_id: 'call_active', status: 'in_progress' });
  stream.item({ type: 'openrouter:subagent', call_id: 'call_done', status: 'completed', outcome: 'Done.' }, true);
  stream.settleSubagents('incomplete');
  assert.equal(stream.activity('call_active').at(-1).status, 'incomplete');
  assert.equal(stream.activity('call_done').at(-1).status, 'completed');
});

test('an incomplete outer response can still resume a waiting worker', () => {
  const stream = capture();
  stream.turn(0);
  const worker = stream.item({
    type: 'openrouter:subagent', id: 'st_1', call_id: 'call_worker', status: 'in_progress',
  });
  const call = stream.item({
    type: 'function_call', id: 'fc_1', call_id: 'call_lookup', name: 'lookup_order', arguments: '{}',
    status: 'completed', subagent_id: 'call_worker',
  }, true);
  stream.observe({ type: 'response.incomplete', response: { output: [worker, call] } });
  stream.turn(1);
  stream.item({
    type: 'openrouter:subagent', id: 'st_2', call_id: 'call_worker', status: 'completed', outcome: 'Found it.',
  }, true);
  assert.deepEqual(stream.activity('call_worker').map(event => event.status), [
    'running', 'waiting for client tools', 'running', 'completed',
  ]);
});
