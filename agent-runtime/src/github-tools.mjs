import { tool } from '@openrouter/agent';
import { z } from 'zod';

const path = z.string().min(1).max(255);
const text = z.string().max(65_536);
const actions = [
  ['github_list_files', 'List or search file paths in this project’s pinned GitHub repository. Results report their search scope.',
    z.strictObject({ directory: z.string().max(255).optional(), query: z.string().max(255).optional(),
      offset: z.int().min(0).optional() })],
  ['github_read_files', 'Read up to eight small UTF-8 files together. Reads include this project’s drafts.',
    z.strictObject({ paths: z.array(path).min(1).max(8) })],
  ['github_create_file', 'Create a new UTF-8 draft file on this phone.',
    z.strictObject({ path, text })],
  ['github_edit_file', 'Edit a UTF-8 draft on this phone by replacing exactly one occurrence of the original text.',
    z.strictObject({ path, oldText: text, newText: text })],
  ['github_publish', 'Propose one GitHub commit containing all project drafts. Android shows the full diff for approval.',
    z.strictObject({ message: z.string().trim().min(1).max(500) })],
  ['github_open_pull_request', 'Propose a pull request for this project’s published work branch. Android asks for approval.',
    z.strictObject({ title: z.string().trim().min(1).max(200), body: z.string().max(8000).optional() })],
];

export const githubToolNames = actions.map(([name]) => name);

export function githubTools(appAction) {
  return actions.map(([name, description, schema]) => tool({
    name, description, inputSchema: schema, strict: false,
    execute: async input => appAction(name, schema.parse(input)),
  }));
}
