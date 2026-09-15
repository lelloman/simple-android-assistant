import type { Archive, AssistantConfig, AssistantState, Command, Effect, EngineBridge, EngineOutput, HistoryStore, Provider, ToolExecutor } from './types.js';
export * from './types.js';
export interface SessionOptions {
  config: AssistantConfig;
  provider: Provider;
  executeTool: ToolExecutor;
  history?: HistoryStore;
  sessionId?: string;
  /** Optional bridge injection for embedding or conformance tests. */
  createEngine?: (config: AssistantConfig, sessionId: string, archive: Archive | null) => Promise<EngineBridge>;
}
let wasmReady: Promise<typeof import('../wasm/assistant_wasm.js')> | undefined;
async function defaultEngine(config: AssistantConfig, id: string, archive: Archive | null): Promise<EngineBridge> {
  wasmReady ??= import('../wasm/assistant_wasm.js').then(async module => { await module.default(); return module; }).catch(error => { wasmReady = undefined; throw error; });
  const module = await wasmReady;
  return new module.AssistantEngine(JSON.stringify(config), id, archive ? JSON.stringify(archive) : undefined);
}
function archiveOf(state: AssistantState): Archive {
  const { version, messages, modeId, language, modeSnapshots, modeChanges, summary, nextId } = state;
  return structuredClone({ version, messages, modeId, language, modeSnapshots, modeChanges, summary, nextId });
}
export class AssistantSession {
  private listeners = new Set<(state: AssistantState) => void>();
  private requests = new Map<string, AbortController>();
  private writes: Promise<void> = Promise.resolve();
  private closed = false;
  private storageError: Error | undefined;
  private current!: AssistantState;
  private constructor(private bridge: EngineBridge, private options: SessionOptions) {}
  static async create(options: SessionOptions): Promise<AssistantSession> {
    const archive = await options.history?.load() ?? null;
    const bridge = await (options.createEngine ?? defaultEngine)(options.config, options.sessionId ?? crypto.randomUUID(), archive);
    const session = new AssistantSession(bridge, options);
    session.apply({ type: 'snapshot' });
    // Persist repaired interrupted tool calls before any new work begins.
    if (options.history) session.enqueueSave(archiveOf(session.current));
    await session.flush();
    return session;
  }
  get state(): AssistantState { return structuredClone(this.current); }
  subscribe(listener: (state: AssistantState) => void): () => void {
    this.listeners.add(listener); listener(this.state); return () => this.listeners.delete(listener);
  }
  send(text: string): void { this.apply({ type: 'send', text, timestamp: Date.now() }); }
  cancel(): void { this.apply({ type: 'cancel' }); }
  clear(): Promise<void> { this.apply({ type: 'clear' }); return this.flush(); }
  switchMode(modeId: string): void { this.apply({ type: 'switch_mode', modeId }); }
  setLanguage(language: string | null): void { this.apply({ type: 'set_language', language }); }
  restart(messageId: string): void { this.apply({ type: 'restart', messageId }); }
  confirmRestart(modeId: string, revision = this.current.restartChoice?.revision): void {
    if (revision === undefined) throw new Error('No pending restart');
    this.apply({ type: 'confirm_restart', modeId, revision });
  }
  dismissRestart(): void { this.apply({ type: 'dismiss_restart' }); }
  async flush(): Promise<void> { await this.writes; if (this.storageError) throw this.storageError; }
  async dispose(): Promise<void> {
    if (this.closed) return;
    this.apply({ type: 'cancel' }); this.closed = true;
    for (const request of this.requests.values()) request.abort();
    this.requests.clear(); this.listeners.clear(); this.bridge.free(); await this.flush();
  }
  private notify(): void {
    for (const listener of this.listeners) { try { listener(this.state); } catch { /* A view must not interrupt engine effects. */ } }
  }
  private enqueueSave(archive: Archive): void {
    this.writes = this.writes.then(async () => {
      if (this.storageError) return;
      try { await this.options.history?.save(archive); }
      catch (error) {
        this.storageError = error instanceof Error ? error : new Error(String(error));
        for (const controller of this.requests.values()) controller.abort();
        this.requests.clear();
        if (!this.closed) {
          const output: EngineOutput = JSON.parse(this.bridge.dispatch(JSON.stringify({ type: 'cancel' })));
          this.current = { ...output.state, error: `History could not be saved: ${this.storageError.message}` };
          this.notify();
        }
      }
    });
  }
  private apply(command: Command): void {
    if (this.closed) throw new Error('Assistant session is closed');
    if (this.storageError && command.type !== 'cancel') throw this.storageError;
    const output: EngineOutput = JSON.parse(this.bridge.dispatch(JSON.stringify(command)));
    if (output.version !== 1) throw new Error('Unsupported engine protocol version');
    this.current = output.state;
    // Abort first, before delivering state to potentially reentrant consumers.
    for (const effect of output.effects) if (effect.type === 'cancel') { this.requests.get(effect.requestId)?.abort(); this.requests.delete(effect.requestId); }
    if (output.persist) this.enqueueSave(archiveOf(this.current));
    for (const effect of output.effects) {
      if (effect.type === 'cancel') continue;
      const controller = new AbortController(); this.requests.set(effect.requestId, controller);
      void this.run(effect, controller);
    }
    this.notify();
  }
  private async run(effect: Exclude<Effect, { type: 'cancel' }>, controller: AbortController): Promise<void> {
    const live = () => !this.closed && !this.storageError && !controller.signal.aborted && this.requests.get(effect.requestId) === controller;
    try {
      await this.writes;
      if (!live()) return;
      if (effect.type === 'provider') {
        let terminal = false;
        for await (const event of this.options.provider.stream(effect, controller.signal)) {
          if (!live()) return;
          this.apply({ type: 'provider_event', requestId: effect.requestId, event });
          if (event.type === 'done' || event.type === 'error') { terminal = true; break; }
        }
        if (live() && !terminal) this.apply({ type: 'provider_event', requestId: effect.requestId, event: { type: 'error', message: 'Provider stream ended without completion' } });
      } else {
        const result = await this.options.executeTool(effect.call, controller.signal);
        if (live()) this.apply({ type: 'tool_result', requestId: effect.requestId, result });
      }
    } catch (error) {
      if (live()) {
        const message = error instanceof Error ? error.message : String(error);
        if (effect.type === 'provider') this.apply({ type: 'provider_event', requestId: effect.requestId, event: { type: 'error', message } });
        else this.apply({ type: 'tool_result', requestId: effect.requestId, result: { error: message } });
      }
    } finally { if (this.requests.get(effect.requestId) === controller) this.requests.delete(effect.requestId); }
  }
}
