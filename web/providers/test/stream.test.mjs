import test from 'node:test';
import assert from 'node:assert/strict';
import { decodeStream } from '../dist/stream.js';
function response(events, chunk=3){const bytes=new TextEncoder().encode(events.map(e=>`data: ${typeof e==='string'?e:JSON.stringify(e)}\r\n\r\n`).join(''));return new Response(new ReadableStream({start(c){for(let i=0;i<bytes.length;i+=chunk)c.enqueue(bytes.slice(i,i+chunk));c.close();}}));}
const collect=async iterable=>{const all=[];for await(const value of iterable)all.push(value);return all;};
test('OpenAI split UTF-8 and tool fragments complete only at terminal event',async()=>{
 const r=response([{choices:[{delta:{content:'café'}}]},{choices:[{delta:{tool_calls:[{index:0,id:'a',function:{name:'read',arguments:'{"q":'}}]}}]},{choices:[{delta:{tool_calls:[{index:0,function:{arguments:'"x"}'}}]},finish_reason:'tool_calls'}]},'[DONE]']);
 const events=await collect(decodeStream(r,'openai'));assert.deepEqual(events,[{type:'text',content:'café'},{type:'tool_use',id:'a',name:'read',input:{q:'x'}},{type:'done'}]);
});
test('truncated and malformed tool responses fail closed',async()=>{
 for(const events of [[{choices:[{delta:{},finish_reason:'stop'}]}],[{choices:[{delta:{tool_calls:[{index:0,id:'a',function:{name:'read',arguments:'invalid'}}]},finish_reason:'tool_calls'}]},'[DONE]']]) await assert.rejects(collect(decodeStream(response(events),'openai')));
});
test('Anthropic interleaved blocks produce complete calls',async()=>{
 const events=await collect(decodeStream(response([{type:'content_block_start',index:1,content_block:{type:'tool_use',id:'a',name:'read',input:{}}},{type:'content_block_delta',index:1,delta:{type:'input_json_delta',partial_json:'{"q":"x"}'}},{type:'message_delta',delta:{stop_reason:'tool_use'}},{type:'message_stop'}]),'anthropic'));assert.equal(events[0].input.q,'x');assert.equal(events.at(-1).type,'done');
});
test('Gemini requires successful finish and normalizes calls',async()=>{
 const events=await collect(decodeStream(response([{candidates:[{content:{parts:[{functionCall:{name:'read',args:{q:'x'}}}]},finishReason:'STOP'}]}]),'google'));assert.equal(events[0].name,'read');assert.equal(events.at(-1).type,'done');
});
test('provider errors and output limits do not expose executable calls',async()=>{
 await assert.rejects(collect(decodeStream(response([{error:{message:'bad key'}}]),'openai')),/bad key/);
 await assert.rejects(collect(decodeStream(response([{choices:[{delta:{},finish_reason:'length'}]},'[DONE]']),'openai')),/length/);
});
