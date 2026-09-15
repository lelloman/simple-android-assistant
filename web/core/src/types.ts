export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
export interface ToolSpec { name: string; description?: string; inputSchema: Json }
export interface Mode { id: string; name: string; description?: string; prompt?: string; toolIds?: string[]; inheritPrompts?: 'all' | string[]; revision?: string; children?: Mode[] }
export interface AssistantConfig { basePrompt?: string; rootMode: Mode; tools?: ToolSpec[]; maxToolRounds?: number; keepRecentTokens?: number; summaryThresholdTokens?: number }
export interface ToolCall { id: string; name: string; input: { [key: string]: Json } }
export interface ModeSnapshot { modeId: string; name: string; description?: string; ownPrompt?: string; basePrompt?: string; ancestorPrompts?: Record<string, string>; inheritPrompts?: "all" | string[] | null; prompt: string; revision: string; tools: ToolSpec[]; ancestors: string[] }
export interface Message { id: string; role: 'user' | 'assistant' | 'tool'; content: string; timestamp: number; toolCalls?: ToolCall[]; toolCallId?: string; toolName?: string; modeSnapshotId?: string }
export interface Archive { version: number; messages: Message[]; modeId: string; language: string | null; modeSnapshots: Record<string, ModeSnapshot>; modeChanges: { position: number; snapshotId: string }[]; summary: { content: string; through: number } | null; nextId: number }
export interface RestartChoice { messageId: string; historicalMode: ModeSnapshot | null; revision: number }
export interface AssistantState extends Archive { sessionId: string; revision: number; activity: 'idle' | 'chat' | 'tool' | 'detect' | 'summary'; streamingText: string; error: string | null; restartChoice: RestartChoice | null; modePath: string[] }
export type ProviderEvent = { type: 'text'; content: string } | ({ type: 'tool_use' } & ToolCall) | { type: 'done' } | { type: 'error'; message: string };
export interface ProviderRequest { purpose: 'chat' | 'detect' | 'summary'; systemPrompt: string; messages: Message[]; tools: ToolSpec[] }
export interface Provider { stream(request: ProviderRequest, signal: AbortSignal): AsyncIterable<ProviderEvent> }
/** Host implementations must recheck signal/authorization after confirmation and before side effects. */
export type ToolExecutor = (call: ToolCall, signal: AbortSignal) => Promise<Json>;
/** One store per account/session owner. Writes are serialized by AssistantSession. */
export interface HistoryStore { load(): Promise<Archive | null>; save(archive: Archive): Promise<void> }
export interface EngineBridge { dispatch(command: string): string; free(): void }
export type Command =
 | { type: 'send'; text: string; timestamp: number }
 | { type: 'cancel' | 'clear' | 'snapshot' | 'dismiss_restart' }
 | { type: 'switch_mode'; modeId: string }
 | { type: 'set_language'; language: string | null }
 | { type: 'restart'; messageId: string }
 | { type: 'confirm_restart'; modeId: string; revision: number }
 | { type: 'provider_event'; requestId: string; event: ProviderEvent }
 | { type: 'tool_result'; requestId: string; result: Json };
export type Effect = ({ type: 'provider'; requestId: string } & ProviderRequest) | { type: 'tool'; requestId: string; call: ToolCall } | { type: 'cancel'; requestId: string };
export interface EngineOutput { version: number; state: AssistantState; effects: Effect[]; persist: boolean }
