<script setup>
import { ref, computed } from 'vue';
import { inject, provide } from 'vue';


const props = defineProps({
  message: {
    type: Object,
    required: true,
  },
});

const chatStore = inject('simple-assistant-ui');
const showToolDetails = ref(false);

const isUser = computed(() => props.message.role === 'user');
const isAssistant = computed(() => props.message.role === 'assistant');
const isTool = computed(() => props.message.role === 'tool');

const hasToolCalls = computed(() =>
  props.message.toolCalls && props.message.toolCalls.length > 0
);

// Parse tool result content
const toolResultParsed = computed(() => {
  if (!isTool.value) return null;
  try {
    return JSON.parse(props.message.content);
  } catch {
    return props.message.content;
  }
});

// Get friendly description for a tool call
function getFriendlyToolDescription(tc) {
  return chatStore.describeTool?.(tc.name, tc.input) || tc.name;
}

// Get friendly result description
const friendlyToolResult = computed(() => {
  if (!isTool.value) return null;
  return chatStore.describeResult?.(props.message.toolName, props.message.content) || 'Completed';
});
</script>

<template>
  <div
    class="message"
    :class="{
      'message--user': isUser,
      'message--assistant': isAssistant,
      'message--tool': isTool,
    }"
  >
    <!-- User message -->
    <div v-if="isUser" class="message__content message__content--user">
      {{ message.content }}
      <button class="assistant-restart-button" :aria-label="'Restart from: ' + message.content" @click="chatStore.restartFromMessage(message.id)">Restart here</button>
    </div>

    <!-- Assistant message -->
    <div v-else-if="isAssistant" class="message__content message__content--assistant">
      <div v-if="message.content" class="message__text">
        {{ message.content }}
      </div>

      <!-- Tool calls indicator -->
      <div v-if="hasToolCalls" class="message__tools">
        <!-- Debug mode: expandable technical details -->
        <template v-if="chatStore.debugMode">
          <button
            class="message__tools-toggle"
            @click="showToolDetails = !showToolDetails"
          >
            <svg
              xmlns="http://www.w3.org/2000/svg"
              width="14"
              height="14"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
            >
              <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z" />
            </svg>
            {{ message.toolCalls.length }} tool{{ message.toolCalls.length > 1 ? 's' : '' }} used
            <svg
              class="message__tools-chevron"
              :class="{ 'is-open': showToolDetails }"
              xmlns="http://www.w3.org/2000/svg"
              width="14"
              height="14"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
            >
              <polyline points="6 9 12 15 18 9" />
            </svg>
          </button>

          <div v-if="showToolDetails" class="message__tools-list">
            <div
              v-for="tc in message.toolCalls"
              :key="tc.id"
              class="message__tool-call"
            >
              <div class="message__tool-name">{{ tc.name }}</div>
              <pre class="message__tool-input">{{ JSON.stringify(tc.input, null, 2) }}</pre>
            </div>
          </div>
        </template>

        <!-- Regular mode: friendly descriptions -->
        <template v-else>
          <div class="message__tools-friendly">
            <span
              v-for="tc in message.toolCalls"
              :key="tc.id"
              class="message__tool-friendly"
            >
              {{ getFriendlyToolDescription(tc) }}
            </span>
          </div>
        </template>
      </div>
    </div>

    <!-- Tool result -->
    <div v-else-if="isTool" class="message__content message__content--tool">
      <!-- Debug mode: technical details -->
      <template v-if="chatStore.debugMode">
        <div class="message__tool-result">
          <span class="message__tool-label">{{ message.toolName }}</span>
          <span v-if="toolResultParsed?.success" class="message__tool-success">success</span>
          <span v-else-if="toolResultParsed?.error" class="message__tool-error">error</span>
        </div>
      </template>

      <!-- Regular mode: friendly result -->
      <template v-else>
        <div class="message__tool-result message__tool-result--friendly">
          <span :class="toolResultParsed?.error ? 'message__tool-error' : 'message__tool-success'">
            {{ friendlyToolResult }}
          </span>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.assistant-restart-button { display: block; margin-top: 6px; cursor: pointer; font-size: 12px; }
.message {
  display: flex;
  margin-bottom: var(--assistant-spacing-2, 8px);
}

.message--user {
  justify-content: flex-end;
}

.message--assistant {
  justify-content: flex-start;
}

.message--tool {
  justify-content: flex-start;
}

.message__content {
  max-width: 85%;
  padding: var(--assistant-spacing-2, 8px) var(--assistant-spacing-3, 12px);
  border-radius: var(--assistant-radius-lg, 12px);
  font-size: var(--assistant-text-sm, 14px);
  line-height: var(--assistant-leading-normal, inherit);
}

.message__content--user {
  background: var(--assistant-accent, #3b82f6);
  color: var(--assistant-text-negative, #fff);
  border-bottom-right-radius: var(--assistant-radius-sm, 4px);
}

.message__content--assistant {
  background: var(--assistant-bg-highlight, #34363a);
  color: var(--assistant-text-base, #f4f4f5);
  border-bottom-left-radius: var(--assistant-radius-sm, 4px);
}

.message__content--tool {
  background: var(--assistant-bg-elevated, #202124);
  color: var(--assistant-text-subdued, #babdc4);
  font-size: var(--assistant-text-xs, 12px);
  padding: var(--assistant-spacing-1, 4px) var(--assistant-spacing-2, 8px);
}

.message__text {
  white-space: pre-wrap;
  word-break: break-word;
}

.message__tools {
  margin-top: var(--assistant-spacing-2, 8px);
  border-top: 1px solid var(--assistant-border-subtle, inherit);
  padding-top: var(--assistant-spacing-2, 8px);
}

.message__tools-toggle {
  display: flex;
  align-items: center;
  gap: var(--assistant-spacing-1, 4px);
  background: none;
  border: none;
  color: var(--assistant-text-subdued, #babdc4);
  font-size: var(--assistant-text-xs, 12px);
  cursor: pointer;
  padding: 0;
}

.message__tools-toggle:hover {
  color: var(--assistant-text-base, #f4f4f5);
}

.message__tools-chevron {
  transition: transform var(--assistant-transition-fast, 150ms ease);
}

.message__tools-chevron.is-open {
  transform: rotate(180deg);
}

.message__tools-list {
  margin-top: var(--assistant-spacing-2, 8px);
}

.message__tool-call {
  background: var(--assistant-bg-base, #17181a);
  border-radius: var(--assistant-radius-sm, 4px);
  padding: var(--assistant-spacing-2, 8px);
  margin-bottom: var(--assistant-spacing-1, 4px);
}

.message__tool-name {
  font-size: var(--assistant-text-xs, 12px);
  font-weight: var(--assistant-font-medium, inherit);
  color: var(--assistant-accent, #3b82f6);
  margin-bottom: var(--assistant-spacing-1, 4px);
}

.message__tool-input {
  font-size: var(--assistant-text-xs, 12px);
  color: var(--assistant-text-subdued, #babdc4);
  margin: 0;
  overflow-x: auto;
  font-family: monospace;
}

.message__tool-result {
  display: flex;
  align-items: center;
  gap: var(--assistant-spacing-2, 8px);
}

.message__tool-label {
  font-family: monospace;
}

.message__tool-success {
  color: var(--assistant-success, inherit);
}

.message__tool-error {
  color: var(--assistant-error, #ef7777);
}

/* Friendly mode styles */
.message__tools-friendly {
  display: flex;
  flex-wrap: wrap;
  gap: var(--assistant-spacing-1, 4px);
}

.message__tool-friendly {
  font-size: var(--assistant-text-xs, 12px);
  color: var(--assistant-text-subdued, #babdc4);
  font-style: italic;
}

.message__tool-result--friendly {
  font-style: italic;
}
</style>
