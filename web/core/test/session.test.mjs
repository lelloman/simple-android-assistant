import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { setTimeout as delay } from 'node:timers/promises';
import { AssistantSession } from '../dist/index.js';
import { initSync, AssistantEngine } from '../wasm/assistant_wasm.js';
initSync({ module: await readFile(new URL('../wasm/assistant_wasm_bg.wasm', import.meta.url)) });
const config = { rootMode: { id: 'main', name: 'Main', toolIds: ['read'], children: [{ id: 'other', name: 'Other', prompt: 'other', toolIds: [] }] }, tools: [{ name: 'read', inputSchema: { type: 'object' } }] };
const bridge = async (config, id, archive) => new AssistantEngine(JSON.stringify(config), id, archive ? JSON.stringify(archive) : undefined);
async function create(options = {}) {
 const s = await AssistantSession.create({ config, createEngine: bridge, provider: { async *stream() { yield { type: 'text', content: 'hello' }; yield { type: 'done' }; } }, executeTool: async () => 'ok', ...options }); s.setLanguage('en'); return s;
}
async function until(predicate) { for(let i=0;i<300;i++) { if(predicate()) return; await delay(5); } throw new Error('Timed out'); }
test('actual WASM streams a tool turn, persists and restores the original mode', async () => {
 let round=0, stored=null, executed=0;
 const s=await create({ history: { load: async()=>stored, save: async value=>{stored=value;} }, provider: { async *stream() { if(round++===0) yield {type:'tool_use',id:'a',name:'read',input:{}}; else yield {type:'text',content:'done'}; yield {type:'done'}; } }, executeTool: async()=>{executed++;return 'ok';} });
 s.send('hi'); await until(()=>s.state.activity==='idle'); await s.flush(); assert.equal(executed,1); assert.equal(stored.messages.length,4);
 const id=s.state.messages[0].id; s.switchMode('other'); s.restart(id); await until(()=>s.state.activity==='idle'); assert.equal(s.state.modeId,'main'); assert.equal(s.state.messages.length,2); await s.dispose();
 const restored=await create({history:{load:async()=>stored,save:async value=>{stored=value;}}}); assert.equal(restored.state.messages[0].id,id); await restored.dispose();
});
test('clear aborts pending stream; old callbacks cannot execute tools or save messages', async () => {
 let finish, signal, executed=0;
 const s=await create({ provider:{async *stream(_request,s){signal=s;await new Promise(resolve=>{finish=resolve;});yield {type:'tool_use',id:'a',name:'read',input:{}};yield {type:'done'};}},executeTool:async()=>{executed++;return 'ok';} });
 s.send('old'); await until(()=>finish); await s.clear(); assert.equal(signal.aborted,true); finish(); await delay(10); assert.equal(executed,0);assert.deepEqual(s.state.messages,[]); await s.dispose();
});
test('clear is durably ordered after an outstanding write', async () => {
 let release, stored, block=false;
 const s=await create({history:{load:async()=>null,save:async archive=>{if(block){block=false;await new Promise(r=>{release=r;});}stored=archive;}}});await s.flush();block=true;s.send('old');await until(()=>release);const cleared=s.clear();release();await cleared;assert.deepEqual(stored.messages,[]);await s.dispose();
});
test('missing terminal event fails without executing pending tools', async () => {
 let calls=0;const s=await create({provider:{async *stream(){yield {type:'tool_use',id:'a',name:'read',input:{}};}},executeTool:async()=>{calls++;return 'ok';}});s.send('hi');await until(()=>s.state.activity==='idle');assert.match(s.state.error,/without completion/);assert.equal(calls,0);await s.dispose();
});
test('changed historical mode waits for explicit selection', async () => {
 let stored;const s=await create({history:{load:async()=>null,save:async a=>{stored=a;}}});s.send('old');await until(()=>s.state.activity==='idle');await s.dispose();
 const next=await create({config:{...config,rootMode:{...config.rootMode,prompt:'changed'}},history:{load:async()=>stored,save:async a=>{stored=a;}}});next.restart(next.state.messages[0].id);assert.equal(next.state.messages.length,2);assert.ok(next.state.restartChoice);next.confirmRestart('other');await until(()=>next.state.activity==='idle');assert.equal(next.state.modeId,'other');await next.dispose();
});
test('storage failure prevents provider dispatch', async () => {
 let calls=0, fail=false;const s=await create({provider:{async *stream(){calls++;yield {type:'done'};}},history:{load:async()=>null,save:async()=>{if(fail)throw new Error('disk full');}}});await s.flush();fail=true;s.send('hi');await assert.rejects(s.flush(),/disk full/);assert.equal(calls,0);assert.match(s.state.error,/disk full/);await assert.rejects(s.dispose(),/disk full/);
});
test('shared protocol fixture conforms through WASM', async () => {
 const fixture=JSON.parse(await readFile(new URL('../../../fixtures/conformance.json',import.meta.url),'utf8'));
 const e=new AssistantEngine(JSON.stringify(fixture.config),fixture.sessionId);
 function subset(actual,expected){if(Array.isArray(expected)){assert.equal(actual.length,expected.length);expected.forEach((v,i)=>subset(actual[i],v));}else if(expected&&typeof expected==='object'){for(const[k,v]of Object.entries(expected))subset(actual[k],v);}else assert.deepEqual(actual,expected);}
 try{for(const step of fixture.steps)subset(JSON.parse(e.dispatch(JSON.stringify(step.command))),step.expected);}finally{e.free();}
});
