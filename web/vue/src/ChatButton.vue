<script setup>
import { inject, provide } from 'vue';

const props = defineProps({ controller: { type: Object, required: true }, storageKey: { type: String, default: 'simple_assistant_panel_geometry' } });
const chatStore = props.controller;
provide('simple-assistant-ui', chatStore);
</script>

<template>
  <button
    class="chat-button"
    :class="{ 'is-open': chatStore.isOpen }"
    @click="chatStore.toggle()"
    title="AI Assistant"
  >
    <svg
      v-if="!chatStore.isOpen"
      xmlns="http://www.w3.org/2000/svg"
      width="24"
      height="24"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
    >
      <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
      <circle cx="9" cy="10" r="1" fill="currentColor" />
      <circle cx="12" cy="10" r="1" fill="currentColor" />
      <circle cx="15" cy="10" r="1" fill="currentColor" />
    </svg>
    <svg
      v-else
      xmlns="http://www.w3.org/2000/svg"
      width="24"
      height="24"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
    >
      <line x1="18" y1="6" x2="6" y2="18" />
      <line x1="6" y1="6" x2="18" y2="18" />
    </svg>
  </button>
</template>

<style scoped>
.chat-button {
  position: fixed;
  bottom: calc(var(--assistant-bottom-offset, 0px) + 20px);
  right: 20px;
  width: 56px;
  height: 56px;
  border-radius: var(--assistant-radius-full, 999px);
  background: var(--assistant-accent, #3b82f6);
  color: var(--assistant-text-negative, #fff);
  border: none;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: var(--assistant-shadow-lg, 0 8px 24px #0004);
  transition: all var(--assistant-transition-fast, 150ms ease);
  z-index: var(--assistant-z-fixed, 100);
}

.chat-button:hover {
  background: var(--assistant-accent-hover, #2563eb);
  transform: scale(1.05);
}

.chat-button:active {
  background: var(--assistant-accent-active, #1d4ed8);
  transform: scale(0.95);
}

.chat-button.is-open {
  background: var(--assistant-bg-highlight, #34363a);
}

.chat-button.is-open:hover {
  background: var(--assistant-bg-press, #42454b);
}

@media (max-width: 768px) {
  .chat-button {
    bottom: calc(var(--assistant-mobile-bottom-offset, 0px) + var(--assistant-mobile-nav-height, 0px) + 16px);
    right: 16px;
    width: 48px;
    height: 48px;
  }
}
</style>
