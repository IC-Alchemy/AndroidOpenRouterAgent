import { tool } from '@openrouter/agent';
import { z } from 'zod';

// Tools that run in Kotlin on the phone. Each call crosses the Android bridge and resolves with Android's JSON reply.

export function miniAppGuidance(options) {
  if (!options.miniApps || options.sdk?.toolChoice === 'none' ||
    options.modelDetails?.[options.model]?.tools === false) return '';
  return 'Mini apps: when the user asks for a small app, tool, tracker, game or calculator for their phone, build ' +
    'it as one complete HTML file and save it with mini_app_save, including a vibrant hex themeColor and a minimal ' +
    '24x24 iconSvg using #FFFFFF strokes or fills for the launcher icon. Ask up to three short questions first only ' +
    'if the request is unclear. Requirements: a <title>, a viewport meta tag, your own CSS and JavaScript inline, no ' +
    'iframes or form actions, and custom HTML modals instead of alert(), confirm() or prompt(). To use a library, ' +
    'add an empty <script src> or stylesheet <link> with a pinned version on cdn.jsdelivr.net, cdnjs.cloudflare.com ' +
    'or unpkg.com, for example <script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js"></script>. ' +
    'The phone downloads it once and writes it into the file, so the app works offline. Pick a single-file build ' +
    '(UMD, IIFE or a bundled .min.js); ES modules that import other files, and fonts or images a stylesheet links, do ' +
    'not load. No other external scripts, stylesheets or fonts. Design for a phone screen in light and dark mode with ' +
    'touch targets of at least 44px. Store data with localStorage. fetch may call public HTTPS APIs that allow CORS ' +
    'and need no key. To change an existing app, read it with mini_app_read and save it with its id so the ' +
    'user\'s data is kept. If saving reports problems, fix them and save again.';
}

export function miniAppTools(call) {
  const send = request => call({ tool: 'mini_app', ...request });
  return [
    tool({
      name: 'mini_app_list',
      description: 'List the mini apps saved on the phone with their ids, names and versions.',
      inputSchema: z.object({}),
      execute: async () => send({ type: 'list' }),
    }),
    tool({
      name: 'mini_app_read',
      description: 'Read the HTML of a saved mini app by id or name, to change it.',
      inputSchema: z.object({ app: z.string().min(1).max(120).describe('Mini app id or exact name.') }),
      execute: async ({ app }) => send({ type: 'read', app }),
    }),
    tool({
      name: 'mini_app_save',
      description: 'Save a mini app: one complete HTML file the user can open, pin to the home screen and share. ' +
        'Pass id to save a new version of an existing app and keep its data. Returns problems to fix if the file ' +
        'breaks the mini app rules. Libraries linked from cdn.jsdelivr.net, cdnjs.cloudflare.com or unpkg.com are ' +
        'downloaded and inlined.',
      inputSchema: z.object({
        name: z.string().min(1).max(40).describe('Short app name for the home screen.'),
        description: z.string().max(200).optional().describe('One sentence on what the app does.'),
        themeColor: z.string().max(16).optional().describe('Hex color code for the app icon and header (e.g. #2563EB).'),
        iconSvg: z.string().max(16000).optional().describe('Minimal 24x24 <svg> markup using #FFFFFF strokes/fills.'),
        html: z.string().min(1).max(400000).describe('The complete HTML document.'),
        id: z.string().max(64).optional().describe('UUID of the existing app to update.'),
      }),
      execute: async args => send({ type: 'save', ...args }),
    }),
  ];
}

export function createLocalBridge(id, bridge, signal) {
  let sequence = 0;
  const pending = new Map();
  const abort = () => {
    for (const { reject } of pending.values()) reject(signal.reason ?? new Error('The run was cancelled.'));
    pending.clear();
  };
  signal.addEventListener('abort', abort, { once: true });
  return {
    call(request) {
      if (signal.aborted) return Promise.reject(signal.reason ?? new Error('The run was cancelled.'));
      const requestId = String(++sequence);
      return new Promise((resolve, reject) => {
        pending.set(requestId, { resolve, reject });
        try {
          bridge.local(id, requestId, JSON.stringify(request));
        } catch (error) {
          pending.delete(requestId);
          reject(error);
        }
      });
    },
    receive(requestId, json) {
      const entry = pending.get(requestId);
      if (!entry) return;
      pending.delete(requestId);
      try {
        entry.resolve(JSON.parse(json));
      } catch {
        entry.reject(new Error('Android returned an invalid tool result.'));
      }
    },
    dispose() {
      signal.removeEventListener('abort', abort);
      abort();
    },
  };
}
