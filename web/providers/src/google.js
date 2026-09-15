import { decodeStream } from './stream.js';
/**
 * Google Gemini API adapter
 *
 * Uses the generateContent API with streaming and function calling.
 * https://ai.google.dev/gemini-api/docs/function-calling
 */

const API_BASE = 'https://generativelanguage.googleapis.com/v1beta/models';

export const MODELS = [
  { id: 'gemini-2.0-flash', name: 'Gemini 2.0 Flash' },
  { id: 'gemini-1.5-pro', name: 'Gemini 1.5 Pro' },
  { id: 'gemini-1.5-flash', name: 'Gemini 1.5 Flash' },
];

/**
 * Convert our unified message format to Gemini format
 */
function toGeminiContents(messages) {
  const contents = [];

  for (const msg of messages) {
    if (msg.role === 'user') {
      contents.push({
        role: 'user',
        parts: [{ text: msg.content }],
      });
    } else if (msg.role === 'assistant') {
      const parts = [];
      if (msg.content) {
        parts.push({ text: msg.content });
      }
      if (msg.toolCalls && msg.toolCalls.length > 0) {
        for (const tc of msg.toolCalls) {
          parts.push({
            functionCall: {
              name: tc.name,
              args: tc.input,
            },
          });
        }
      }
      contents.push({ role: 'model', parts });
    } else if (msg.role === 'tool') {
      contents.push({
        role: 'user',
        parts: [{
          functionResponse: {
            name: msg.toolName,
            response: typeof msg.content === 'string' ? { result: msg.content } : msg.content,
          },
        }],
      });
    }
  }

  return contents;
}

/**
 * Convert our unified tool format to Gemini format
 */
function toGeminiTools(tools) {
  if (tools.length === 0) return undefined;

  return [{
    functionDeclarations: tools.map(tool => ({
      name: tool.name,
      description: tool.description,
      parameters: tool.inputSchema,
    })),
  }];
}

/**
 * Stream chat completion from Gemini API
 */
export async function* streamChat(config, messages, tools = []) {
  const { apiKey, model = 'gemini-2.0-flash' } = config;

  if (!apiKey) {
    throw new Error('Google API key is required');
  }

  const apiUrl = `${API_BASE}/${model}:streamGenerateContent?key=${encodeURIComponent(apiKey)}&alt=sse`;

  const body = {
    contents: toGeminiContents(messages),
    systemInstruction: config.systemPrompt ? { parts: [{ text: config.systemPrompt }] } : undefined,
  };

  const geminiTools = toGeminiTools(tools);
  if (geminiTools) {
    body.tools = geminiTools;
  }

  const response = await fetch(apiUrl, {
    signal: config.signal,
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.error?.message || `Gemini API error: ${response.status}`);
  }

  yield* decodeStream(response, 'google', config.signal);
}

/**
 * Test connection to Gemini API
 */
export async function testConnection(config) {
  const { apiKey, model = 'gemini-2.0-flash' } = config;

  const apiUrl = `${API_BASE}/${model}:generateContent?key=${encodeURIComponent(apiKey)}`;

  const response = await fetch(apiUrl, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      contents: [{ role: 'user', parts: [{ text: 'Hi' }] }],
    }),
  });

  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.error?.message || `Connection failed: ${response.status}`);
  }

  return true;
}
