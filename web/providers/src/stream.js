/** SSE decoder shared by the extracted browser adapters. Never log model payloads. */
async function* events(response, signal) {
  if (!response.body) throw new Error('Provider returned no response body');
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let data = [];
  const abort = () => { void reader.cancel().catch(() => {}); };
  signal?.addEventListener('abort', abort, { once: true });
  try {
    while (true) {
      signal?.throwIfAborted();
      const { done, value } = await reader.read();
      buffer += done ? decoder.decode() : decoder.decode(value, { stream: true });
      if (buffer.length > 4 * 1024 * 1024) throw new Error('Provider event exceeds buffer limit');
      let index;
      while ((index = buffer.indexOf('\n')) !== -1) {
        const line = buffer.slice(0, index).replace(/\r$/, ''); buffer = buffer.slice(index + 1);
        if (line === '') { if (data.length) { yield data.join('\n'); data = []; } }
        else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
      }
      if (done) {
        if (buffer.startsWith('data:')) data.push(buffer.slice(5).replace(/^ /, ''));
        if (data.length) yield data.join('\n');
        signal?.throwIfAborted(); return;
      }
    }
  } finally { signal?.removeEventListener('abort', abort); try { await reader.cancel(); } catch {} reader.releaseLock(); }
}
function callEvent(call) {
  const input = typeof call.arguments === 'string' ? JSON.parse(call.arguments || '{}') : call.input || {};
  if (!call.id || !call.name || !input || typeof input !== 'object' || Array.isArray(input)) throw new Error('Malformed provider tool call');
  return { type: 'tool_use', id: call.id, name: call.name, input };
}
export async function* decodeStream(response, kind, signal) {
  const calls = new Map();
  let successfulFinish = false;
  let terminal = false;
  for await (const data of events(response, signal)) {
    if (data === '[DONE]') { terminal = true; break; }
    const event = JSON.parse(data);
    if (event.error || event.type === 'error') throw new Error(event.error?.message || 'Provider stream failed');
    if (kind === 'openai') {
      const choice = event.choices?.[0];
      if (!choice) continue;
      const delta = choice.delta || {};
      if (delta.content) yield { type: 'text', content: delta.content };
      for (const fragment of delta.tool_calls || []) {
        if (!Number.isInteger(fragment.index)) throw new Error('Tool fragment has no index');
        const call = calls.get(fragment.index) || { id: '', name: '', arguments: '' };
        if (fragment.id) call.id = fragment.id;
        if (fragment.function?.name) call.name += fragment.function.name;
        if (fragment.function?.arguments) call.arguments += fragment.function.arguments;
        if (call.arguments.length > 4 * 1024 * 1024) throw new Error('Tool arguments exceed buffer limit');
        calls.set(fragment.index, call);
      }
      if (choice.finish_reason) {
        if (!['stop', 'tool_calls'].includes(choice.finish_reason)) throw new Error(`Provider stopped: ${choice.finish_reason}`);
        successfulFinish = true;
      }
    } else if (kind === 'anthropic') {
      if (event.type === 'content_block_start' && event.content_block?.type === 'tool_use') {
        calls.set(event.index, { id: event.content_block.id, name: event.content_block.name, arguments: '', input: event.content_block.input });
      }
      if (event.type === 'content_block_delta') {
        if (event.delta?.type === 'text_delta') yield { type: 'text', content: event.delta.text };
        if (event.delta?.type === 'input_json_delta') {
          const call = calls.get(event.index); if (!call) throw new Error('Unknown tool fragment');
          call.arguments += event.delta.partial_json;
          if (call.arguments.length > 4 * 1024 * 1024) throw new Error('Tool arguments exceed buffer limit');
        }
      }
      if (event.type === 'message_delta' && event.delta?.stop_reason) {
        if (!['end_turn', 'tool_use', 'stop_sequence'].includes(event.delta.stop_reason)) throw new Error(`Provider stopped: ${event.delta.stop_reason}`);
        successfulFinish = true;
      }
      if (event.type === 'message_stop') { terminal = true; break; }
    } else {
      if (event.promptFeedback?.blockReason) throw new Error(`Provider blocked response: ${event.promptFeedback.blockReason}`);
      const candidate = event.candidates?.[0];
      for (const part of candidate?.content?.parts || []) {
        if (part.text) yield { type: 'text', content: part.text };
        if (part.functionCall) calls.set(calls.size, { id: part.functionCall.id || crypto.randomUUID(), name: part.functionCall.name, input: part.functionCall.args || {} });
      }
      if (candidate?.finishReason) {
        if (candidate.finishReason !== 'STOP') throw new Error(`Provider stopped: ${candidate.finishReason}`);
        successfulFinish = true;
      }
    }
  }
  signal?.throwIfAborted();
  if (!successfulFinish || (kind !== 'google' && !terminal)) throw new Error('Provider stream ended before successful completion');
  // Validate the whole batch before yielding any tool call.
  const complete = [...calls.values()].map(call => callEvent(call.arguments === '' ? { ...call, arguments: undefined } : call));
  if (new Set(complete.map(c => c.id)).size !== complete.length) throw new Error('Duplicate tool call IDs');
  yield* complete;
  yield { type: 'done' };
}
