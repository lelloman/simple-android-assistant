import { decodeStream } from './stream.js';
/**
 * Anthropic Claude API adapter
 *
 * Uses the Messages API with streaming and native tool support.
 * https://docs.anthropic.com/claude/reference/messages
 */

const API_URL = 'https://api.anthropic.com/v1/messages';
const API_VERSION = '2023-06-01';

// Default models
export const MODELS = [
  { id: 'claude-sonnet-4-20250514', name: 'Claude Sonnet 4' },
  { id: 'claude-3-5-sonnet-20241022', name: 'Claude 3.5 Sonnet' },
  { id: 'claude-3-5-haiku-20241022', name: 'Claude 3.5 Haiku' },
  { id: 'claude-3-opus-20240229', name: 'Claude 3 Opus' },
];

/**
 * Convert our unified message format to Anthropic format
 */
function toAnthropicMessages(messages) {
  return messages.map(msg => {
    if (msg.role === 'user') {
      return { role: 'user', content: msg.content };
    }

    if (msg.role === 'assistant') {
      // Handle tool calls in assistant messages
      if (msg.toolCalls && msg.toolCalls.length > 0) {
        const content = [];
        if (msg.content) {
          content.push({ type: 'text', text: msg.content });
        }
        for (const tc of msg.toolCalls) {
          content.push({
            type: 'tool_use',
            id: tc.id,
            name: tc.name,
            input: tc.input,
          });
        }
        return { role: 'assistant', content };
      }
      return { role: 'assistant', content: msg.content };
    }

    if (msg.role === 'tool') {
      // Tool results are sent as user messages with tool_result content
      return {
        role: 'user',
        content: [{
          type: 'tool_result',
          tool_use_id: msg.toolCallId,
          content: typeof msg.content === 'string' ? msg.content : JSON.stringify(msg.content),
        }],
      };
    }

    return msg;
  });
}

/**
 * Convert our unified tool format to Anthropic format
 */
function toAnthropicTools(tools) {
  return tools.map(tool => ({
    name: tool.name,
    description: tool.description,
    input_schema: tool.inputSchema,
  }));
}

/**
 * Stream chat completion from Anthropic API
 *
 * @param {Object} config - Provider config { apiKey, model }
 * @param {Array} messages - Message history
 * @param {Array} tools - Available tools
 * @yields {{ type: 'text', content: string } | { type: 'tool_use', id: string, name: string, input: object }}
 */
export async function* streamChat(config, messages, tools = []) {
  const { apiKey, model = 'claude-sonnet-4-20250514' } = config;

  if (!apiKey) {
    throw new Error('Anthropic API key is required');
  }

  const body = {
    model,
    system: config.systemPrompt || undefined,
    max_tokens: 4096,
    messages: toAnthropicMessages(messages),
    stream: true,
  };

  if (tools.length > 0) {
    body.tools = toAnthropicTools(tools);
  }

  const response = await fetch(API_URL, {
    signal: config.signal,
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'x-api-key': apiKey,
      'anthropic-version': API_VERSION,
      'anthropic-dangerous-direct-browser-access': 'true',
    },
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.error?.message || `Anthropic API error: ${response.status}`);
  }

  yield* decodeStream(response, 'anthropic', config.signal);
}

/**
 * Test connection to Anthropic API
 */
export async function testConnection(config) {
  const { apiKey, model = 'claude-sonnet-4-20250514' } = config;

  const response = await fetch(API_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'x-api-key': apiKey,
      'anthropic-version': API_VERSION,
      'anthropic-dangerous-direct-browser-access': 'true',
    },
    body: JSON.stringify({
      model,
      max_tokens: 10,
      messages: [{ role: 'user', content: 'Hi' }],
    }),
  });

  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.error?.message || `Connection failed: ${response.status}`);
  }

  return true;
}
