import { computed, onScopeDispose, shallowRef, watch, type Ref } from 'vue';
import type { AssistantSession, AssistantState } from '@lelloman/simple-assistant';
/** Presentation binding only. The host owns session creation and disposal. */
export function useAssistant(session: Ref<AssistantSession | null>) {
  const state = shallowRef<AssistantState | null>(null);
  let unsubscribe: (() => void) | undefined;
  watch(session, value => {
    unsubscribe?.(); state.value = value?.state ?? null;
    unsubscribe = value?.subscribe(next => { state.value = next; });
  }, { immediate: true });
  onScopeDispose(() => unsubscribe?.());
  return {
    state,
    messages: computed(() => state.value?.messages ?? []),
    streamingText: computed(() => state.value?.streamingText ?? ''),
    isLoading: computed(() => !!state.value && state.value.activity !== 'idle'),
    isDetectingLanguage: computed(() => state.value?.activity === 'detect'),
    isCompacting: computed(() => state.value?.activity === 'summary'),
    error: computed(() => state.value?.error ?? null),
    restartChoice: computed(() => state.value?.restartChoice ?? null),
    modeId: computed(() => state.value?.modeId),
    language: computed(() => state.value?.language ?? null),
    sendMessage: (text: string) => session.value?.send(text),
    clearHistory: () => session.value?.clear(),
    cancel: () => session.value?.cancel(),
    switchMode: (id: string) => session.value?.switchMode(id),
    restartFromMessage: (id: string) => session.value?.restart(id),
    confirmRestart: (id: string, revision: number) => session.value?.confirmRestart(id, revision),
    dismissRestart: () => session.value?.dismissRestart(),
    setLanguage: (language: string | null) => session.value?.setLanguage(language),
    resetLanguage: () => session.value?.setLanguage(null),
  };
}
/** Panels accept reactive state/actions plus host-specific settings and presentation. */
export interface AssistantController {
  messages: AssistantState['messages']; streamingText: string; isLoading: boolean;
  isOpen: boolean; isConfigured: boolean; debugMode: boolean;
  modeId?: string; modes: { id: string; name: string; label?: string }[];
  restartChoice: AssistantState['restartChoice']; error: string | null;
  config: Record<string, unknown>;
  sendMessage(text: string): unknown; clearHistory(): unknown; cancel(): unknown;
  switchMode(id: string): unknown; restartFromMessage(id: string): unknown;
  confirmRestart(id: string, revision: number): unknown; dismissRestart(): unknown;
  toggle(): void; toggleDebugMode(): void; setConfig(config: Record<string, unknown>): unknown;
  setLanguage(code: string | null): unknown; resetLanguage(): unknown;
  availableLanguages: { code: string; name: string; nativeName: string; flag: string }[];
  currentLanguage: { code: string; name: string; flag: string } | null;
  isDetectingLanguage: boolean;
  availableProviders: { id: string; name: string }[];
  welcome?: string; suggestions?: string[];
  describeTool?: (name: string, input: unknown) => string;
  describeResult?: (name: string, result: unknown) => string;
}
