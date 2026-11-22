import test from 'node:test';
import assert from 'node:assert/strict';
import { sdkParameters, withVariant } from '../src/customization.mjs';

const details = { 'a/m': { tools: true, parameters: [] }, 'b/f': { tools: true, parameters: [] } };
const call = sdk => sdkParameters({ model: 'a/m', modelDetails: details, sdk }, true);

test('routing variants are added to every model ID that is sent', () => {
  assert.equal(call({ routingVariant: 'floor' }).model, 'a/m:floor');
  assert.equal(call({}).model, 'a/m');
  assert.deepEqual(call({ routingVariant: 'nitro', fallbackModels: 'b/f' }).models({}), ['a/m:nitro', 'b/f:nitro']);
  assert.equal(call({ routingVariant: 'exacto', followUpModel: 'b/f' }).model({ numberOfTurns: 1 }), 'b/f:exacto');
});

test('a variant replaces another sorting variant and keeps catalog suffixes', () => {
  assert.equal(withVariant('a/m:free', 'nitro'), 'a/m:free:nitro');
  assert.equal(withVariant('a/m:floor', 'exacto'), 'a/m:exacto');
  assert.equal(withVariant('a/m', 'unknown'), 'a/m');
});

test('provider filters and thresholds become provider preferences', () => {
  const { provider } = call({ providerSort: 'throughput', sortPartition: 'none', zdr: true, distillableText: true,
    quantizations: 'fp8, bf16', minThroughput: 50, maxLatency: 2.5, maxPromptPrice: 1, maxCompletionPrice: 4 });
  assert.deepEqual(provider, {
    sort: { by: 'throughput', partition: 'none' }, zdr: true, enforceDistillableText: true,
    quantizations: ['fp8', 'bf16'], preferredMinThroughput: 50, preferredMaxLatency: 2.5,
    maxPrice: { prompt: '1', completion: '4' },
  });
  assert.equal(call({ providerSort: 'price' }).provider.sort, 'price');
  assert.equal(call({}).provider, undefined);
});
