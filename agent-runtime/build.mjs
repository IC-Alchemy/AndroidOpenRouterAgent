import { readFile, readdir, writeFile } from 'node:fs/promises';
import { build } from 'esbuild';

const destination = '../app/src/main/assets/agent';
const { metafile } = await build({
  entryPoints: ['src/webview.mjs'],
  bundle: true,
  platform: 'browser',
  target: 'chrome100',
  format: 'iife',
  minify: true,
  legalComments: 'external',
  metafile: true,
  outfile: destination + '/agent.js',
});

const packages = new Set(Object.keys(metafile.inputs).flatMap(path => {
  const match = path.match(/^node_modules\/((?:@[^/]+\/)?[^/]+)\//);
  return match ? [match[1]] : [];
}));
const apache = await readFile('node_modules/@openrouter/sdk/LICENSE.md', 'utf8');
const notices = await Promise.all([...packages].sort().map(async name => {
  const root = 'node_modules/' + name;
  const metadata = JSON.parse(await readFile(root + '/package.json', 'utf8'));
  const files = (await readdir(root)).filter(file => /^(license|notice)(\..+)?$/i.test(file));
  const contents = await Promise.all(files.map(file => readFile(root + '/' + file, 'utf8')));
  if (!contents.length && metadata.license === 'Apache-2.0') contents.push(apache);
  return [name + '@' + metadata.version + ' (' + metadata.license + ')', ...contents].join('\n\n');
}));
await writeFile(destination + '/THIRD_PARTY_NOTICES.txt', notices.join('\n\n' + '='.repeat(72) + '\n\n'));
