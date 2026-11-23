import assert from 'node:assert/strict';
import test from 'node:test';
import { buildTools } from '../src/runtime.mjs';

const signal = new AbortController().signal;
const options = { appActions: true, model: 'example/model' };
const functions = tools => Object.fromEntries(tools.filter(item => item.function).map(item => [item.function.name, item.function]));

test('GitHub tools require a project connection and forward proposals to native Android', async () => {
  const appAction = async (name, args) => ({ name, args });
  assert.equal(functions(buildTools(options, { appAction }, signal)).github_publish, undefined);
  const actions = functions(buildTools({ ...options, github: true }, { appAction }, signal));
  assert.equal(typeof actions.github_list_files?.execute, 'function');
  assert.deepEqual(await actions.github_read_files.execute({ paths: ['a', 'b'] }),
    { name: 'github_read_files', args: { paths: ['a', 'b'] } });
  await assert.rejects(() => actions.github_publish.execute({ message: 'Update docs', branch: 'main' }));
  assert.deepEqual(await actions.github_publish.execute({ message: 'Update docs' }),
    { name: 'github_publish', args: { message: 'Update docs' } });
  assert.equal(buildTools({ ...options, github: true, sdk: { toolChoice: 'none' } }, { appAction }, signal).length, 0);
});
