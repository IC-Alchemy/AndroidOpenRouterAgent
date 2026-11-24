import assert from 'node:assert/strict';
import test from 'node:test';
import { MISSING_TEXT, attachmentParts, dehydrate, dehydrateState, hydrate, hydrateState, promptContent }
  from '../src/media.mjs';

const photo = 'data:image/jpeg;base64,' + 'A'.repeat(800);
const pngBase64 = 'iVBORw0KGgo' + 'B'.repeat(800);
const file = name => name.repeat(64).slice(0, 64);

function fakeStore() {
  const files = new Map();
  return {
    files,
    store(kind, value) {
      const name = file(String(files.size + 1)) + (kind === 'url' ? '.jpg' : '.png');
      files.set(name, value);
      return name;
    },
    read(kind, name) { return files.get(name) ?? ''; },
  };
}

test('media in a conversation is stored once and swapped back on the next run', () => {
  const media = fakeStore();
  const conversation = { messages: [
    { role: 'user', content: [{ type: 'input_text', text: 'Look' }, { type: 'input_image', imageUrl: photo }] },
    { type: 'openrouter:image_generation', imageB64: pngBase64, prompt: 'a cat' },
    { type: 'message', content: [{ type: 'output_text', text: 'short text stays' }] },
  ] };
  const saved = dehydrate(conversation, media.store);
  assert.equal(media.files.size, 2);
  assert.match(saved.messages[0].content[1].imageUrl, /^oratt:url:1{64}\.jpg$/);
  assert.match(saved.messages[1].imageB64, /^oratt:b64:2{64}\.png$/);
  assert.equal(saved.messages[2].content[0].text, 'short text stays');
  assert.deepEqual(hydrate(saved, media.read), conversation);

  // The serialized state takes the same path, and a value seen on this run is not stored again.
  const known = new Map();
  const state = hydrateState(JSON.stringify(saved), media.read, known);
  assert.deepEqual(JSON.parse(state), conversation);
  assert.equal(dehydrateState(state, () => assert.fail('stored twice'), known), JSON.stringify(saved));
});

test('a missing attachment becomes a note, and other items that needed it are dropped', () => {
  const marker = 'oratt:url:' + file('9') + '.jpg';
  const restored = hydrate({ messages: [
    { role: 'user', content: [{ type: 'input_text', text: 'Look' }, { type: 'input_image', imageUrl: marker }] },
    { type: 'image_generation_call', result: 'oratt:b64:' + file('8') + '.png' },
    { type: 'message', content: [] },
  ] }, () => '');
  assert.deepEqual(restored.messages[0].content[1], { type: 'input_text', text: MISSING_TEXT });
  assert.equal(restored.messages.length, 2);
  assert.equal(restored.messages[1].type, 'message');
});

test('values the device would not keep stay inline', () => {
  const text = 'data:text/plain;base64,' + 'Q'.repeat(700);
  assert.equal(dehydrate({ value: text }, () => '').value, text);
  // Long plain strings are left alone; only base64 fields and data URLs are candidates.
  const prose = 'word '.repeat(300);
  assert.equal(dehydrate({ result: prose }, () => assert.fail('not media')).result, prose);
});

test('attachments become input parts after the prompt text', () => {
  const media = fakeStore();
  const image = media.store('url', photo);
  const pdf = media.store('url', 'data:application/pdf;base64,' + 'J'.repeat(600));
  const audio = media.store('b64', 'S'.repeat(600));
  const parts = attachmentParts([
    { kind: 'image', file: image, name: 'cat.jpg', mimeType: 'image/jpeg' },
    { kind: 'file', file: pdf, name: 'notes.pdf', mimeType: 'application/pdf' },
    { kind: 'audio', file: audio, name: 'memo.wav', mimeType: 'audio/wav' },
    { kind: 'text', file: 'x', name: 'todo.txt', text: 'buy milk' },
  ], media.read);
  assert.deepEqual(parts.map(part => part.type), ['input_image', 'input_file', 'input_audio', 'input_text']);
  assert.equal(parts[0].detail, 'auto');
  assert.equal(parts[0].imageUrl, photo);
  assert.equal(parts[1].filename, 'notes.pdf');
  assert.deepEqual(parts[2].inputAudio, { data: 'S'.repeat(600), format: 'wav' });
  assert.equal(parts[3].text, 'Attached file todo.txt:\n\nbuy milk');
  assert.deepEqual(promptContent('What is this?', parts.slice(0, 1)),
    [{ type: 'input_text', text: 'What is this?' }, parts[0]]);
  assert.deepEqual(promptContent('', parts.slice(0, 1)), [parts[0]]);
  assert.equal(promptContent('Plain', []), 'Plain');
  assert.throws(() => attachmentParts([{ kind: 'image', file: 'gone', name: 'gone.jpg' }], media.read),
    /gone\.jpg is no longer on this device/);
});
