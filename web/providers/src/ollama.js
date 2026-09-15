import { decodeStream } from './stream.js';
/**
 * Ollama API adapter
 *
 * Ollama provides an OpenAI-compatible API at /v1/chat/completions
 * https://ollama.com/blog/openai-compatibility
 */

export const MODELS = [
  { id: 'llama3.3', name: 'Llama 3.3' },
  { id: 'llama3.2', name: 'Llama 3.2' },
  { id: 'qwen2.5', name: 'Qwen 2.5' },
  { id: 'mistral', name: 'Mistral' },
  { id: 'mixtral', name: 'Mixtral' },
  { id: 'codellama', name: 'Code Llama' },
];

/**
 * Convert our unified message format to OpenAI format (Ollama uses OpenAI format)
 */
function toOllamaMessages(messages) {
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
function toOllamaTools(tools) {
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
 * Stream chat completion from Ollama API
 */
export async function* streamChat(config, messages, tools = []) {
  const { baseUrl = 'http://localhost:11434', model = 'llama3.3' } = config;

  const apiUrl = `${baseUrl.replace(/\/$/, '')}/v1/chat/completions`;

  const body = {
    model,
    messages: [...(config.systemPrompt ? [{ role: 'system', content: config.systemPrompt }] : []), ...toOllamaMessages(messages)],
    stream: true,
  };

  if (tools.length > 0) {
    body.tools = toOllamaTools(tools);
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
    throw new Error(error.error?.message || `Ollama API error: ${response.status}`);
  }

  yield* decodeStream(response, 'openai', config.signal);
}

/**
 * Fetch available models from Ollama
 */
export async function fetchModels(config) {
  const { baseUrl = 'http://localhost:11434' } = config;

  try {
    const response = await fetch(`${baseUrl.replace(/\/$/, '')}/api/tags`);
    if (!response.ok) {
      throw new Error(`Failed to fetch models: ${response.status}`);
    }

    const data = await response.json();
    return data.models?.map(m => ({
      id: m.name,
      name: m.name,
    })) || MODELS;
  } catch (e) {
    console.warn('Failed to fetch Ollama models:', e);
    return MODELS;
  }
}

/**
 * Test connection to Ollama API
 */
export async function testConnection(config) {
  const { baseUrl = 'http://localhost:11434', model = 'llama3.3' } = config;

  // First check if Ollama is running
  const healthResponse = await fetch(`${baseUrl.replace(/\/$/, '')}/api/tags`);
  if (!healthResponse.ok) {
    throw new Error(`Cannot connect to Ollama at ${baseUrl}`);
  }

  // Then try a simple chat completion
  const apiUrl = `${baseUrl.replace(/\/$/, '')}/v1/chat/completions`;
  const response = await fetch(apiUrl, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
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
