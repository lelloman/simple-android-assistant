import { decodeStream } from './stream.js';
/**
 * OpenRouter API adapter
 *
 * OpenRouter provides access to many models with an OpenAI-compatible API.
 * https://openrouter.ai/docs
 */

const API_URL = 'https://openrouter.ai/api/v1/chat/completions';

export const MODELS = [
  { id: 'anthropic/claude-sonnet-4', name: 'Claude Sonnet 4' },
  { id: 'anthropic/claude-3.5-sonnet', name: 'Claude 3.5 Sonnet' },
  { id: 'openai/gpt-4o', name: 'GPT-4o' },
  { id: 'google/gemini-2.0-flash-001', name: 'Gemini 2.0 Flash' },
  { id: 'meta-llama/llama-3.3-70b-instruct', name: 'Llama 3.3 70B' },
  { id: 'mistralai/mistral-large-2411', name: 'Mistral Large' },
  { id: 'qwen/qwen-2.5-72b-instruct', name: 'Qwen 2.5 72B' },
  { id: 'deepseek/deepseek-chat', name: 'DeepSeek Chat' },
];

/**
 * Convert our unified message format to OpenAI format
 */
function toOpenRouterMessages(messages) {
  return messages.map(msg => {
    if (msg.role === 'user') {
      return { role: 'user', content: msg.content };
    }

    if (msg.role === 'assistant') {
      const result = { role: 'assistant', content: msg.content || null };
      if (msg.toolCalls && msg.toolCalls.length > 0) {
        result.tool_calls = msg.toolCalls.map(tc => ({
          id: tc.id,
          type: 'function',
          function: {
            name: tc.name,
            arguments: JSON.stringify(tc.input),
          },
        }));
      }
      return result;
    }

    if (msg.role === 'tool') {
      return {
        role: 'tool',
        tool_call_id: msg.toolCallId,
        content: typeof msg.content === 'string' ? msg.content : JSON.stringify(msg.content),
      };
    }

    return msg;
  });
}

/**
 * Convert our unified tool format to OpenAI format
 */
function toOpenRouterTools(tools) {
  return tools.map(tool => ({
    type: 'function',
    function: {
      name: tool.name,
      description: tool.description,
      parameters: tool.inputSchema,
    },
  }));
}

/**
 * Stream chat completion from OpenRouter API
 */
export async function* streamChat(config, messages, tools = []) {
  const { apiKey, model = 'anthropic/claude-sonnet-4' } = config;

  if (!apiKey) {
    throw new Error('OpenRouter API key is required');
  }

  const body = {
    model,
    messages: [...(config.systemPrompt ? [{ role: 'system', content: config.systemPrompt }] : []), ...toOpenRouterMessages(messages)],
    stream: true,
  };

  if (tools.length > 0) {
    body.tools = toOpenRouterTools(tools);
  }

  const response = await fetch(API_URL, {
    signal: config.signal,
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${apiKey}`,
      'HTTP-Referer': config.siteUrl || globalThis.location?.origin || '',
      'X-Title': config.appName || 'Simple Assistant',
    },
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.error?.message || `OpenRouter API error: ${response.status}`);
  }

  yield* decodeStream(response, 'openai', config.signal);
}

/**
 * Fetch available models from OpenRouter
 */
export async function fetchModels(config) {
  const { apiKey } = config;

  if (!apiKey) {
    return MODELS;
  }

  try {
    const response = await fetch('https://openrouter.ai/api/v1/models', {
      headers: {
        'Authorization': `Bearer ${apiKey}`,
      },
    });

    if (!response.ok) {
      throw new Error(`Failed to fetch models: ${response.status}`);
    }

    const data = await response.json();
    return data.data
      ?.filter(m => m.context_length > 0)
      ?.slice(0, 50) // Limit to first 50 models
      ?.map(m => ({
        id: m.id,
        name: m.name || m.id,
      })) || MODELS;
  } catch (e) {
    console.warn('Failed to fetch OpenRouter models:', e);
    return MODELS;
  }
}

/**
 * Test connection to OpenRouter API
 */
export async function testConnection(config) {
  const { apiKey, model = 'anthropic/claude-sonnet-4' } = config;

  const response = await fetch(API_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${apiKey}`,
      'HTTP-Referer': config.siteUrl || globalThis.location?.origin || '',
      'X-Title': config.appName || 'Simple Assistant',
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
