import { createApp, ref, shallowRef, reactive, computed } from 'vue';
import { AssistantSession } from '@lelloman/simple-assistant';
import { AssistantPanel, AssistantButton, useAssistant } from '@lelloman/simple-assistant-vue';
import '@lelloman/simple-assistant-vue/style.css';
const root = { id: 'general', name: 'General', prompt: 'General prompt', toolIds: ['echo'], children: [{ id: 'focused', name: 'Focused', prompt: 'Focused prompt', inheritPrompts: ['general'], toolIds: ['echo'] }] };
const config = { basePrompt: 'Example assistant', rootMode: root, tools: [{ name: 'echo', description: 'Echo text', inputSchema: { type: 'object' } }] };
createApp({
  components: { AssistantPanel, AssistantButton },
  setup() {
    const session = shallowRef(null);
    const bindings = useAssistant(session);
    const open = ref(true);
    const error = ref(null);
    const settings = ref({ provider: 'ollama', model: 'demo', baseUrl: '', language: 'en', debugMode: false });
    const controller = reactive({
      ...bindings, isOpen: open, config: settings, isConfigured: computed(() => !!session.value),
      error: computed(() => error.value || bindings.error.value), debugMode: computed(() => settings.value.debugMode),
      modes: [{ id: 'general', name: 'General' }, { id: 'focused', name: 'Focused', label: 'General / Focused' }],
      currentLanguage: { code: 'en', name: 'English', flag: '🇬🇧' }, availableLanguages: [{ code: 'en', name: 'English', nativeName: 'English', flag: '🇬🇧' }],
      availableProviders: [], toggle: () => { open.value = !open.value; }, toggleDebugMode: () => { settings.value.debugMode = !settings.value.debugMode; },
      setConfig: () => {}, welcome: 'A local example with a simulated provider.', suggestions: ['Hello', 'Use a tool'],
    });
    const provider = { async *stream(request, signal) {
      if (request.purpose === 'detect') yield { type: 'text', content: 'en' };
      else if (request.messages.at(-1)?.role === 'user' && request.messages.at(-1).content === 'Use a tool') yield { type: 'tool_use', id: crypto.randomUUID(), name: 'echo', input: { text: 'example' } };
      else { yield { type: 'text', content: 'Hello ' }; await new Promise(resolve => setTimeout(resolve, 50)); signal.throwIfAborted(); yield { type: 'text', content: 'from the shared Rust engine.' }; }
      yield { type: 'done' };
    } };
    let archive = null;
    const history = { load: async () => archive, save: async value => { archive = value; } };
    const create = async config => {
      const s = await AssistantSession.create({ config, provider, history, executeTool: async call => ({ echoed: call.input }) });
      session.value = s; window.assistantExample = s;
    };
    window.updateExampleMode = async () => {
      await session.value.dispose();
      await create({ ...config, rootMode: { ...root, prompt: 'Updated mode prompt' } });
    };
    create(config).catch(e => { error.value = e.message; });
    return { controller };
  },
  template: '<main style="font-family:system-ui;padding:2rem"><h1>Simple Assistant</h1><p>Vue and WASM example. No model credentials required.</p><AssistantButton :controller="controller"/><AssistantPanel :controller="controller"/></main>',
}).mount('#app');
