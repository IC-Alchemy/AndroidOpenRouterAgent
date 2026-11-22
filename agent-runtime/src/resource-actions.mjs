import { tool } from '@openrouter/agent';
import { z } from 'zod';
import { customInputSchema, webhookEndpoint } from './customization.mjs';
import { hostedToolTypes, subagentConfig, subagentType } from './subagent.mjs';

const customName = /^[a-z][a-z0-9_]{0,62}$/;
const skillName = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const requiredText = max => z.string().trim().min(1).max(max);
const isObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const isText = value => typeof value === 'string';
const isCount = value => Number.isSafeInteger(value) && value >= 0;
const numericLimits = {
  minimum: Number.isFinite, maximum: Number.isFinite,
  exclusiveMinimum: Number.isFinite, exclusiveMaximum: Number.isFinite,
  multipleOf: value => Number.isFinite(value) && value > 0,
};
const fieldKeywords = {
  string: { minLength: isCount, maxLength: isCount, pattern: isText },
  integer: numericLimits,
  number: numericLimits,
  boolean: {},
  array: { items: isObject, minItems: isCount, maxItems: isCount },
  object: { properties: isObject, required: Array.isArray, additionalProperties: value =>
    typeof value === 'boolean' || isObject(value) },
};
const commonKeywords = { type: isText, description: isText, title: isText, $schema: isText };

const actionDefinitions = {
  create_subagent: {
    label: 'Create subagent',
    description: 'Save an OpenRouter subagent for future chat messages. Set its model and instructions when useful.',
    schema: z.strictObject({
      name: requiredText(64).optional().describe('Unique subagent name. Omit to create the single unnamed default.'),
      model: requiredText(300).optional(),
      instructions: z.string().max(36_000).optional(),
      advancedJson: z.string().max(16_000).optional().describe('Optional JSON object string using SDK field names: ' +
        'maxToolCalls (1–25), maxCompletionTokens, temperature, reasoning (effort, maxTokens), inheritFunctions, ' +
        'inheritedFunctionNames, and tools. The tools array accepts objects with type and optional parameters. ' +
        'Supported types: ' + hostedToolTypes.join(', ') + '.'),
    }),
    validate({ name, model, instructions, advancedJson }) {
      let advanced = {};
      if (advancedJson !== undefined) {
        try {
          advanced = JSON.parse(advancedJson);
        } catch {
          throw new Error('Advanced subagent options must be a JSON object string.');
        }
        if (!isObject(advanced)) throw new Error('Advanced subagent options must be a JSON object string.');
        if (['name', 'model', 'instructions'].some(key => Object.hasOwn(advanced, key))) {
          throw new Error('Set subagent name, model, and instructions in their dedicated fields.');
        }
      }
      const parameters = { ...advanced, name, model, instructions };
      subagentConfig({ type: subagentType, parameters }, new Set(), { strict: true });
    },
  },
  create_custom_tool: {
    label: 'Create custom tool',
    description: 'Save a custom tool for future chat messages. Use a response template, ask the user for text, ' +
      'or POST arguments to a fixed HTTPS webhook. Each webhook call requires approval.',
    schema: z.strictObject({
      name: requiredText(63).regex(customName),
      description: requiredText(1024),
      schema: z.string().max(8192).describe('JSON object schema string with properties and optionally required. ' +
        'Use object, array, string, number, integer, or boolean properties. Descriptions, lengths, numeric bounds, and string patterns are supported.'),
      handler: z.enum(['template', 'ask', 'webhook']),
      value: z.string().max(12_000).optional().describe('Template text with {{property}} placeholders, or a fixed HTTPS ' +
        'webhook without credentials, query parameters, or a fragment. Omit for ask.'),
    }),
    validate(args) {
      const { name, schema, handler, value } = args;
      if (name.startsWith('remote_') || name.startsWith('mini_app_') || reservedCustomNames.has(name)) {
        throw new Error('Choose a different custom tool name.');
      }
      if (handler === 'template' && !value?.trim()) throw new Error('Enter a response template.');
      if (handler === 'ask' && value?.trim()) throw new Error('The ask handler takes no value.');
      if (handler === 'webhook') webhookEndpoint(value);
      try {
        const input = JSON.parse(schema);
        if (input?.type !== 'object' || !validField(input, 0)) throw new Error();
        z.toJSONSchema(customInputSchema(args));
      } catch {
        throw new Error('Enter a JSON object schema with supported properties.');
      }
    },
  },
  create_skill: {
    label: 'Create skill',
    description: 'Save Markdown instructions as an enabled skill for future chat messages. Pin the skill only when ' +
      'its instructions should be included automatically.',
    schema: z.strictObject({
      name: requiredText(64).regex(skillName),
      description: requiredText(1024),
      body: requiredText(36_000),
      pinned: z.boolean().optional(),
    }),
    validate({ name }) {
      if (name === 'openrouter-app-guide') throw new Error('Choose a different skill name.');
    },
  },
  enable_server_tool: {
    label: 'Enable server tool',
    description: 'Enable a supported hosted OpenRouter tool for future chat messages using its default configuration.',
    schema: z.strictObject({ type: z.enum(hostedToolTypes) }),
  },
};

export const resourceActionNames = Object.keys(actionDefinitions);
const reservedCustomNames = new Set([
  'device_time_zone', 'load_skill', 'shared', 'task',
  'list_project_files', 'read_project_file', 'write_project_file', 'edit_project_file',
  ...resourceActionNames,
]);

function validField(field, depth) {
  if (!isObject(field) || depth > 5) return false;
  if (!Object.hasOwn(fieldKeywords, field.type)) return false;
  const keywords = { ...commonKeywords, ...fieldKeywords[field.type] };
  if (!Object.entries(field).every(([key, value]) => Object.hasOwn(keywords, key) && keywords[key](value))) return false;
  switch (field.type) {
    case 'string':
    case 'integer':
    case 'number':
    case 'boolean':
      return true;
    case 'array':
      return validField(field.items, depth + 1);
    case 'object': {
      if (!isObject(field.properties)) return false;
      const properties = Object.entries(field.properties);
      if (properties.length > 24 || !properties.every(([name, child]) =>
        /^[a-zA-Z][a-zA-Z0-9_]{0,63}$/.test(name) && validField(child, depth + 1))) return false;
      if (isObject(field.additionalProperties) && !validField(field.additionalProperties, depth + 1)) return false;
      const required = field.required;
      return required === undefined || (Array.isArray(required) && new Set(required).size === required.length &&
        required.every(key => typeof key === 'string' && Object.hasOwn(field.properties, key)));
    }
    default:
      return false;
  }
}

export function resourceActionTools(events, signal) {
  if (typeof events.approval !== 'function' || typeof events.appAction !== 'function') return [];
  return Object.entries(actionDefinitions).map(([name, { label, description, schema, validate }]) => tool({
    name,
    description,
    inputSchema: schema,
    // Models may omit optional arguments. The local schema still rejects any unknown input.
    strict: false,
    execute: async input => {
      if (signal.aborted) throw new Error('The run was cancelled.');
      const args = schema.parse(input);
      validate?.(args);
      const accepted = await events.approval(label, args, '');
      if (signal.aborted) throw new Error('The run was cancelled.');
      if (!accepted) throw new Error('The user declined this change.');
      return await events.appAction(name, args);
    },
  }));
}
