<script setup>
import { ref, computed, watch } from 'vue';
import { inject, provide } from 'vue';
import { testConnection, getModels, getProvider } from '@lelloman/simple-assistant-providers';

const emit = defineEmits(['close']);

const chatStore = inject('simple-assistant-ui');

// Local state for form
const localConfig = ref({ ...chatStore.config });
const testing = ref(false);
const testResult = ref(null);
const availableModels = ref([]);
const loadingModels = ref(false);

// Update local config when store config changes
watch(() => chatStore.config, (newConfig) => {
  localConfig.value = { ...newConfig };
}, { deep: true });

// Load models when provider changes
watch(() => localConfig.value.provider, async (newProvider) => {
  await refreshModels(newProvider);
}, { immediate: true });

// Refresh available models from provider
async function refreshModels(provider = localConfig.value.provider) {
  loadingModels.value = true;
  testResult.value = null;
  try {
    const models = await getModels(provider, localConfig.value);

    availableModels.value = models;
    // Set default model if not set and we have models
    if (!localConfig.value.model && availableModels.value.length > 0) {
      localConfig.value.model = availableModels.value[0].id;
    }
  } catch (e) {
    console.warn('Failed to load models:', e);
    availableModels.value = [];
  }
  loadingModels.value = false;
}

// Get provider info based on the LOCAL config (not saved config)
const currentProviderInfo = computed(() => getProvider(localConfig.value.provider));

const requiresApiKey = computed(() =>
  currentProviderInfo.value?.requiresApiKey ?? true
);

const requiresBaseUrl = computed(() =>
  currentProviderInfo.value?.requiresBaseUrl ?? false
);

async function handleTestConnection() {
  testing.value = true;
  testResult.value = null;

  try {
    await testConnection(localConfig.value.provider, localConfig.value);
    testResult.value = { success: true, message: 'Connection successful!' };
  } catch (e) {
    testResult.value = { success: false, message: e.message };
  }

  testing.value = false;
}

function handleSave() {
  chatStore.setConfig(localConfig.value);
  emit('close');
}

function handleCancel() {
  localConfig.value = { ...chatStore.config };
  emit('close');
}
</script>

<template>
  <div class="settings">
    <div class="settings__header">
      <h3>AI Settings</h3>
      <button class="settings__close" @click="handleCancel">
        <svg
          xmlns="http://www.w3.org/2000/svg"
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
        >
          <line x1="18" y1="6" x2="6" y2="18" />
          <line x1="6" y1="6" x2="18" y2="18" />
        </svg>
      </button>
    </div>

    <div class="settings__body">
      <!-- Provider Selection -->
      <div class="settings__field">
        <label class="settings__label">Provider</label>
        <select v-model="localConfig.provider" class="settings__select">
          <option
            v-for="p in chatStore.availableProviders"
            :key="p.id"
            :value="p.id"
          >
            {{ p.name }}
          </option>
        </select>
      </div>

      <!-- API Key (if required) -->
      <div v-if="requiresApiKey" class="settings__field">
        <label class="settings__label">API Key</label>
        <input
          v-model="localConfig.apiKey"
          type="password"
          class="settings__input"
          placeholder="Enter your API key"
        />
        <p class="settings__hint">
          Your application manages provider credentials.
        </p>
      </div>

      <!-- Base URL (for Ollama) -->
      <div v-if="requiresBaseUrl" class="settings__field">
        <label class="settings__label">Base URL</label>
        <input
          v-model="localConfig.baseUrl"
          type="text"
          class="settings__input"
          placeholder="http://localhost:11434"
        />
      </div>

      <!-- Model Selection -->
      <div class="settings__field">
        <label class="settings__label">Model</label>
        <div class="settings__model-row">
          <input
            v-model="localConfig.model"
            type="text"
            class="settings__input"
            :placeholder="loadingModels ? 'Loading...' : 'Enter or select model'"
            list="model-suggestions"
            :disabled="loadingModels"
          />
          <button
            type="button"
            class="settings__refresh-btn"
            :disabled="loadingModels"
            @click="refreshModels()"
            title="Refresh model list"
          >
            <svg
              xmlns="http://www.w3.org/2000/svg"
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              :class="{ 'is-loading': loadingModels }"
            >
              <path d="M21 2v6h-6" />
              <path d="M3 12a9 9 0 0 1 15-6.7L21 8" />
              <path d="M3 22v-6h6" />
              <path d="M21 12a9 9 0 0 1-15 6.7L3 16" />
            </svg>
          </button>
        </div>
        <datalist id="model-suggestions">
          <option
            v-for="m in availableModels"
            :key="m.id"
            :value="m.id"
          >
            {{ m.name }}
          </option>
        </datalist>
        <p class="settings__hint">
          Type a model name or select from suggestions.
          <span v-if="availableModels.length > 0" class="settings__model-count">
            ({{ availableModels.length }} models available)
          </span>
        </p>
      </div>

      <!-- Test Connection -->
      <div class="settings__field">
        <button
          class="settings__test-btn"
          :disabled="testing || (!localConfig.apiKey && requiresApiKey)"
          @click="handleTestConnection"
        >
          {{ testing ? 'Testing...' : 'Test Connection' }}
        </button>
        <div
          v-if="testResult"
          class="settings__test-result"
          :class="{ 'is-success': testResult.success, 'is-error': !testResult.success }"
        >
          {{ testResult.message }}
        </div>
      </div>

      <!-- Debug Mode -->
      <div class="settings__field settings__field--toggle">
        <label class="settings__toggle">
          <input
            v-model="localConfig.debugMode"
            type="checkbox"
            class="settings__toggle-input"
          />
          <span class="settings__toggle-switch"></span>
          <span class="settings__toggle-label">Debug mode</span>
        </label>
        <p class="settings__hint">
          Show tool invocations and technical details in messages.
        </p>
      </div>
    </div>

    <div class="settings__footer">
      <button class="settings__btn settings__btn--secondary" @click="handleCancel">
        Cancel
      </button>
      <button class="settings__btn settings__btn--primary" @click="handleSave">
        Save
      </button>
    </div>
  </div>
</template>

<style scoped>
.settings {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.settings__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--assistant-spacing-3, 12px) var(--assistant-spacing-4, 16px);
  border-bottom: 1px solid var(--assistant-border-default, #45474c);
}

.settings__header h3 {
  font-size: var(--assistant-text-lg, 18px);
  font-weight: var(--assistant-font-semibold, 600);
  margin: 0;
}

.settings__close {
  background: none;
  border: none;
  color: var(--assistant-text-subdued, #babdc4);
  cursor: pointer;
  padding: var(--assistant-spacing-1, 4px);
  border-radius: var(--assistant-radius-sm, 4px);
}

.settings__close:hover {
  color: var(--assistant-text-base, #f4f4f5);
  background: var(--assistant-bg-highlight, #34363a);
}

.settings__body {
  flex: 1;
  padding: var(--assistant-spacing-4, 16px);
  overflow-y: auto;
}

.settings__field {
  margin-bottom: var(--assistant-spacing-4, 16px);
}

.settings__label {
  display: block;
  font-size: var(--assistant-text-sm, 14px);
  font-weight: var(--assistant-font-medium, inherit);
  color: var(--assistant-text-base, #f4f4f5);
  margin-bottom: var(--assistant-spacing-1, 4px);
}

.settings__input,
.settings__select {
  width: 100%;
  padding: var(--assistant-spacing-2, 8px) var(--assistant-spacing-3, 12px);
  background: var(--assistant-bg-base, #17181a);
  border: 1px solid var(--assistant-border-default, #45474c);
  border-radius: var(--assistant-radius-md, 8px);
  color: var(--assistant-text-base, #f4f4f5);
  font-size: var(--assistant-text-sm, 14px);
}

.settings__input:focus,
.settings__select:focus {
  outline: none;
  border-color: var(--assistant-accent, #3b82f6);
}

.settings__input::placeholder {
  color: var(--assistant-text-subtle, #969ba5);
}

.settings__hint {
  font-size: var(--assistant-text-xs, 12px);
  color: var(--assistant-text-subdued, #babdc4);
  margin-top: var(--assistant-spacing-1, 4px);
}

.settings__model-count {
  color: var(--assistant-accent, #3b82f6);
}

.settings__model-row {
  display: flex;
  gap: var(--assistant-spacing-2, 8px);
}

.settings__model-row .settings__input {
  flex: 1;
}

.settings__refresh-btn {
  padding: var(--assistant-spacing-2, 8px);
  background: var(--assistant-bg-highlight, #34363a);
  border: 1px solid var(--assistant-border-default, #45474c);
  border-radius: var(--assistant-radius-md, 8px);
  color: var(--assistant-text-subdued, #babdc4);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all var(--assistant-transition-fast, 150ms ease);
}

.settings__refresh-btn:hover:not(:disabled) {
  background: var(--assistant-bg-press, #42454b);
  color: var(--assistant-text-base, #f4f4f5);
}

.settings__refresh-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.settings__refresh-btn svg.is-loading {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.settings__test-btn {
  padding: var(--assistant-spacing-2, 8px) var(--assistant-spacing-3, 12px);
  background: var(--assistant-bg-highlight, #34363a);
  border: 1px solid var(--assistant-border-default, #45474c);
  border-radius: var(--assistant-radius-md, 8px);
  color: var(--assistant-text-base, #f4f4f5);
  font-size: var(--assistant-text-sm, 14px);
  cursor: pointer;
  transition: all var(--assistant-transition-fast, 150ms ease);
}

.settings__test-btn:hover:not(:disabled) {
  background: var(--assistant-bg-press, #42454b);
}

.settings__test-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.settings__test-result {
  margin-top: var(--assistant-spacing-2, 8px);
  padding: var(--assistant-spacing-2, 8px);
  border-radius: var(--assistant-radius-sm, 4px);
  font-size: var(--assistant-text-sm, 14px);
}

.settings__test-result.is-success {
  background: rgba(29, 185, 84, 0.1);
  color: var(--assistant-success, inherit);
}

.settings__test-result.is-error {
  background: rgba(226, 33, 52, 0.1);
  color: var(--assistant-error, #ef7777);
}

.settings__footer {
  display: flex;
  gap: var(--assistant-spacing-2, 8px);
  padding: var(--assistant-spacing-3, 12px) var(--assistant-spacing-4, 16px);
  border-top: 1px solid var(--assistant-border-default, #45474c);
}

.settings__btn {
  flex: 1;
  padding: var(--assistant-spacing-2, 8px) var(--assistant-spacing-3, 12px);
  border-radius: var(--assistant-radius-md, 8px);
  font-size: var(--assistant-text-sm, 14px);
  font-weight: var(--assistant-font-medium, inherit);
  cursor: pointer;
  transition: all var(--assistant-transition-fast, 150ms ease);
}

.settings__btn--secondary {
  background: transparent;
  border: 1px solid var(--assistant-border-default, #45474c);
  color: var(--assistant-text-base, #f4f4f5);
}

.settings__btn--secondary:hover {
  background: var(--assistant-bg-highlight, #34363a);
}

.settings__btn--primary {
  background: var(--assistant-accent, #3b82f6);
  border: none;
  color: var(--assistant-text-negative, #fff);
}

.settings__btn--primary:hover {
  background: var(--assistant-accent-hover, #2563eb);
}

/* Toggle styles */
.settings__field--toggle {
  margin-top: var(--assistant-spacing-4, 16px);
  padding-top: var(--assistant-spacing-4, 16px);
  border-top: 1px solid var(--assistant-border-subtle, inherit);
}

.settings__toggle {
  display: flex;
  align-items: center;
  gap: var(--assistant-spacing-2, 8px);
  cursor: pointer;
}

.settings__toggle-input {
  position: absolute;
  opacity: 0;
  width: 0;
  height: 0;
}

.settings__toggle-switch {
  position: relative;
  width: 36px;
  height: 20px;
  background: var(--assistant-bg-press, #42454b);
  border-radius: 10px;
  transition: background var(--assistant-transition-fast, 150ms ease);
  flex-shrink: 0;
}

.settings__toggle-switch::after {
  content: '';
  position: absolute;
  top: 2px;
  left: 2px;
  width: 16px;
  height: 16px;
  background: var(--assistant-text-subdued, #babdc4);
  border-radius: 50%;
  transition: transform var(--assistant-transition-fast, 150ms ease), background var(--assistant-transition-fast, 150ms ease);
}

.settings__toggle-input:checked + .settings__toggle-switch {
  background: var(--assistant-accent, #3b82f6);
}

.settings__toggle-input:checked + .settings__toggle-switch::after {
  transform: translateX(16px);
  background: var(--assistant-text-negative, #fff);
}

.settings__toggle-label {
  font-size: var(--assistant-text-sm, 14px);
  color: var(--assistant-text-base, #f4f4f5);
}
</style>
