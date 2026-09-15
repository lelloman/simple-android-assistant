<script setup>
import { ref, watch, nextTick, onMounted, onUnmounted } from 'vue';
import { inject, provide } from 'vue';
import ChatMessage from './ChatMessage.vue';
import ChatSettings from './ChatSettings.vue';
import LanguagePicker from './LanguagePicker.vue';

const props = defineProps({ controller: { type: Object, required: true }, storageKey: { type: String, default: 'simple_assistant_panel_geometry' } });
const chatStore = props.controller;
provide('simple-assistant-ui', chatStore);

const inputText = ref('');
const messagesContainer = ref(null);
const panelRef = ref(null);
const showSettings = ref(false);

// Panel position and size
const STORAGE_KEY = props.storageKey;
const MIN_WIDTH = 320;
const MIN_HEIGHT = 300;
const DEFAULT_WIDTH = 420;
const DEFAULT_HEIGHT = 550;

const panelStyle = ref({
  width: DEFAULT_WIDTH,
  height: DEFAULT_HEIGHT,
  x: null, // null means use default CSS position
  y: null,
});

// Load saved geometry
onMounted(() => {
  let saved;
  try { saved = localStorage.getItem(STORAGE_KEY); } catch { return; }
  if (saved) {
    try {
      const parsed = JSON.parse(saved);
      panelStyle.value = { ...panelStyle.value, ...parsed };
    } catch {
      // ignore
    }
  }
});

// Save geometry on change
function saveGeometry() {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(panelStyle.value)); } catch { /* optional geometry */ }
}

// Dragging state
const isDragging = ref(false);
const dragOffset = ref({ x: 0, y: 0 });

function startDrag(e) {
  if (e.target.closest('button') || e.target.closest('input') || e.target.closest('textarea')) {
    return;
  }

  isDragging.value = true;
  const rect = panelRef.value.getBoundingClientRect();

  // Initialize position if not set
  if (panelStyle.value.x === null) {
    panelStyle.value.x = rect.left;
    panelStyle.value.y = rect.top;
  }

  dragOffset.value = {
    x: e.clientX - panelStyle.value.x,
    y: e.clientY - panelStyle.value.y,
  };

  document.addEventListener('mousemove', onDrag);
  document.addEventListener('mouseup', stopDrag);
  e.preventDefault();
}

function onDrag(e) {
  if (!isDragging.value) return;

  const newX = e.clientX - dragOffset.value.x;
  const newY = e.clientY - dragOffset.value.y;

  // Constrain to viewport
  const maxX = window.innerWidth - panelStyle.value.width;
  const maxY = window.innerHeight - panelStyle.value.height;

  panelStyle.value.x = Math.max(0, Math.min(newX, maxX));
  panelStyle.value.y = Math.max(0, Math.min(newY, maxY));
}

function stopDrag() {
  isDragging.value = false;
  document.removeEventListener('mousemove', onDrag);
  document.removeEventListener('mouseup', stopDrag);
  saveGeometry();
}

// Resizing state
const isResizing = ref(false);
const resizeDirection = ref('');

function startResize(e, direction) {
  isResizing.value = true;
  resizeDirection.value = direction;

  const rect = panelRef.value.getBoundingClientRect();

  // Initialize position if not set
  if (panelStyle.value.x === null) {
    panelStyle.value.x = rect.left;
    panelStyle.value.y = rect.top;
  }

  document.addEventListener('mousemove', onResize);
  document.addEventListener('mouseup', stopResize);
  e.preventDefault();
  e.stopPropagation();
}

function onResize(e) {
  if (!isResizing.value) return;

  const dir = resizeDirection.value;
  let { x, y, width, height } = panelStyle.value;

  if (dir.includes('e')) {
    width = Math.max(MIN_WIDTH, e.clientX - x);
  }
  if (dir.includes('w')) {
    const newWidth = Math.max(MIN_WIDTH, (x + width) - e.clientX);
    const newX = x + width - newWidth;
    if (newX >= 0) {
      width = newWidth;
      x = newX;
    }
  }
  if (dir.includes('s')) {
    height = Math.max(MIN_HEIGHT, e.clientY - y);
  }
  if (dir.includes('n')) {
    const newHeight = Math.max(MIN_HEIGHT, (y + height) - e.clientY);
    const newY = y + height - newHeight;
    if (newY >= 0) {
      height = newHeight;
      y = newY;
    }
  }

  // Constrain to viewport
  width = Math.min(width, window.innerWidth - x);
  height = Math.min(height, window.innerHeight - y);

  panelStyle.value = { x, y, width, height };
}

function stopResize() {
  isResizing.value = false;
  resizeDirection.value = '';
  document.removeEventListener('mousemove', onResize);
  document.removeEventListener('mouseup', stopResize);
  saveGeometry();
}

// Reset to default position
function resetPosition() {
  panelStyle.value = {
    width: DEFAULT_WIDTH,
    height: DEFAULT_HEIGHT,
    x: null,
    y: null,
  };
  localStorage.removeItem(STORAGE_KEY);
}

// Cleanup
onUnmounted(() => {
  document.removeEventListener('mousemove', onDrag);
  document.removeEventListener('mouseup', stopDrag);
  document.removeEventListener('mousemove', onResize);
  document.removeEventListener('mouseup', stopResize);
});

// Auto-scroll to bottom when new messages arrive
watch(
  () => [chatStore.messages.length, chatStore.streamingText],
  async () => {
    await nextTick();
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight;
    }
  }
);

async function handleSubmit() {
  const text = inputText.value.trim();
  if (!text || chatStore.isLoading) return;

  inputText.value = '';
  await chatStore.sendMessage(text);
}

function handleKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    handleSubmit();
  }
}

// Computed style for the panel
function getPanelStyle() {
  const style = {
    width: `${panelStyle.value.width}px`,
    height: `${panelStyle.value.height}px`,
  };

  if (panelStyle.value.x !== null) {
    style.left = `${panelStyle.value.x}px`;
    style.top = `${panelStyle.value.y}px`;
    style.right = 'auto';
    style.bottom = 'auto';
  }

  return style;
}
</script>

<template>
  <Transition name="slide">
    <div
      v-if="chatStore.isOpen"
      ref="panelRef"
      class="chat-panel"
      :class="{ 'chat-panel--dragging': isDragging, 'chat-panel--resizing': isResizing }"
      :style="getPanelStyle()"
    >
      <!-- Resize handles -->
      <div class="resize-handle resize-handle--n" @mousedown="startResize($event, 'n')"></div>
      <div class="resize-handle resize-handle--s" @mousedown="startResize($event, 's')"></div>
      <div class="resize-handle resize-handle--e" @mousedown="startResize($event, 'e')"></div>
      <div class="resize-handle resize-handle--w" @mousedown="startResize($event, 'w')"></div>
      <div class="resize-handle resize-handle--ne" @mousedown="startResize($event, 'ne')"></div>
      <div class="resize-handle resize-handle--nw" @mousedown="startResize($event, 'nw')"></div>
      <div class="resize-handle resize-handle--se" @mousedown="startResize($event, 'se')"></div>
      <div class="resize-handle resize-handle--sw" @mousedown="startResize($event, 'sw')"></div>

      <!-- Settings View -->
      <ChatSettings
        v-if="showSettings"
        @close="showSettings = false"
      />

      <!-- Chat View -->
      <template v-else>
        <!-- Header (draggable) -->
        <div class="chat-panel__header" @mousedown="startDrag">
          <h3>AI Assistant</h3>
          <div class="chat-panel__actions">
            <LanguagePicker />
            <button
              class="chat-panel__action"
              :class="{ 'chat-panel__action--active': chatStore.debugMode }"
              :title="chatStore.debugMode ? 'Debug mode ON (click to switch to friendly view)' : 'Debug mode OFF (click to show technical details)'"
              @click="chatStore.toggleDebugMode()"
            >
              <!-- Bug icon for debug mode toggle -->
              <svg
                xmlns="http://www.w3.org/2000/svg"
                width="18"
                height="18"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
              >
                <path d="M8 2l1.88 1.88" />
                <path d="M14.12 3.88 16 2" />
                <path d="M9 7.13v-1a3.003 3.003 0 1 1 6 0v1" />
                <path d="M12 20c-3.3 0-6-2.7-6-6v-3a4 4 0 0 1 4-4h4a4 4 0 0 1 4 4v3c0 3.3-2.7 6-6 6" />
                <path d="M12 20v-9" />
                <path d="M6.53 9C4.6 8.8 3 7.1 3 5" />
                <path d="M6 13H2" />
                <path d="M3 21c0-2.1 1.7-3.9 3.8-4" />
                <path d="M20.97 5c0 2.1-1.6 3.8-3.5 4" />
                <path d="M22 13h-4" />
                <path d="M17.2 17c2.1.1 3.8 1.9 3.8 4" />
              </svg>
            </button>
            <button
              v-if="panelStyle.x !== null"
              class="chat-panel__action"
              title="Reset position"
              @click="resetPosition"
            >
              <svg
                xmlns="http://www.w3.org/2000/svg"
                width="18"
                height="18"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
              >
                <path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8" />
                <path d="M3 3v5h5" />
              </svg>
            </button>
            <button
              class="chat-panel__action"
              title="Settings"
              @click="showSettings = true"
            >
              <svg
                xmlns="http://www.w3.org/2000/svg"
                width="18"
                height="18"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
              >
                <circle cx="12" cy="12" r="3" />
                <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z" />
              </svg>
            </button>
            <button
              v-if="chatStore.messages.length > 0"
              class="chat-panel__action"
              title="Clear chat"
              @click="chatStore.clearHistory()"
            >
              <svg
                xmlns="http://www.w3.org/2000/svg"
                width="18"
                height="18"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
              >
                <polyline points="3 6 5 6 21 6" />
                <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
              </svg>
            </button>
          </div>
        </div>

        <div class="assistant-modes">
          <label>Mode
            <select aria-label="Mode" :value="chatStore.modeId" @change="chatStore.switchMode($event.target.value)">
              <option v-for="mode in (chatStore.modes || [])" :key="mode.id" :value="mode.id">{{ mode.label || mode.name }}</option>
            </select>
          </label>
          <button v-if="chatStore.isLoading" @click="chatStore.cancel()">Stop</button>
        </div>
        <div v-if="chatStore.restartChoice" role="dialog" aria-label="Choose a restart mode" class="assistant-restart">
          <p>The original mode changed or is unavailable. Choose a current mode to restart.</p>
          <button v-for="mode in chatStore.modes" :key="mode.id" @click="chatStore.confirmRestart(mode.id, chatStore.restartChoice.revision)">{{ mode.label || mode.name }}</button>
          <button @click="chatStore.dismissRestart()">Cancel</button>
        </div>
        <!-- Messages -->
        <div ref="messagesContainer" class="chat-panel__messages">
          <!-- Not configured message -->
          <div v-if="!chatStore.isConfigured" class="chat-panel__setup">
            <div class="chat-panel__setup-icon">
              <svg
                xmlns="http://www.w3.org/2000/svg"
                width="48"
                height="48"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.5"
              >
                <circle cx="12" cy="12" r="3" />
                <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z" />
              </svg>
            </div>
            <h4>Configure AI Provider</h4>
            <p>Set up your AI provider to start chatting.</p>
            <button class="chat-panel__setup-btn" @click="showSettings = true">
              Open Settings
            </button>
          </div>

          <!-- Empty state -->
          <div
            v-else-if="chatStore.messages.length === 0"
            class="chat-panel__empty"
          >
            <p>{{ chatStore.welcome || 'How can I help?' }}</p>
            <div class="chat-panel__suggestions">
              <button v-for="suggestion in (chatStore.suggestions || [])" :key="suggestion" @click="inputText = suggestion">{{ suggestion }}</button>
            </div>
          </div>

          <!-- Message list -->
          <template v-else>
            <ChatMessage
              v-for="msg in chatStore.messages"
              :key="msg.id"
              :message="msg"
            />

            <!-- Streaming text -->
            <div v-if="chatStore.streamingText" class="chat-panel__streaming">
              <div class="message__content message__content--assistant">
                {{ chatStore.streamingText }}
                <span class="chat-panel__cursor"></span>
              </div>
            </div>

            <!-- Loading indicator -->
            <div v-else-if="chatStore.isLoading" class="chat-panel__loading">
              <div class="chat-panel__loading-dots">
                <span></span>
                <span></span>
                <span></span>
              </div>
            </div>
          </template>

          <!-- Error message -->
          <div v-if="chatStore.error" class="chat-panel__error">
            {{ chatStore.error }}
          </div>
        </div>

        <!-- Input -->
        <div class="chat-panel__input-container">
          <textarea
            v-model="inputText"
            class="chat-panel__input"
            aria-label="Message"
            placeholder="Ask something..."
            :disabled="!chatStore.isConfigured || chatStore.isLoading"
            @keydown="handleKeydown"
            rows="1"
          ></textarea>
          <button
            class="chat-panel__send"
            aria-label="Send message"
            :disabled="!inputText.trim() || chatStore.isLoading || !chatStore.isConfigured"
            @click="handleSubmit"
          >
            <svg
              xmlns="http://www.w3.org/2000/svg"
              width="20"
              height="20"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
            >
              <line x1="22" y1="2" x2="11" y2="13" />
              <polygon points="22 2 15 22 11 13 2 9 22 2" />
            </svg>
          </button>
        </div>
      </template>
    </div>
  </Transition>
</template>

<style scoped>
.assistant-modes, .assistant-restart { padding: 10px; color: var(--assistant-text-base, #f4f4f5); display: flex; gap: 8px; flex-wrap: wrap; }
.assistant-modes select, .assistant-restart button { padding: 6px; }

.chat-panel {
  color: var(--assistant-text-base, #f4f4f5);
  position: fixed;
  bottom: calc(var(--assistant-bottom-offset, 0px) + 90px);
  right: 20px;
  width: 420px;
  height: 550px;
  background: var(--assistant-bg-elevated, #202124);
  border-radius: var(--assistant-radius-xl, 16px);
  box-shadow: var(--assistant-shadow-xl, 0 16px 48px #0006);
  display: flex;
  flex-direction: column;
  z-index: var(--assistant-z-modal, 101);
  overflow: hidden;
}

.chat-panel--dragging,
.chat-panel--resizing {
  user-select: none;
}

.chat-panel--dragging {
  cursor: grabbing;
}

/* Resize handles */
.resize-handle {
  position: absolute;
  z-index: 10;
}

.resize-handle--n,
.resize-handle--s {
  left: 10px;
  right: 10px;
  height: 6px;
  cursor: ns-resize;
}

.resize-handle--n { top: 0; }
.resize-handle--s { bottom: 0; }

.resize-handle--e,
.resize-handle--w {
  top: 10px;
  bottom: 10px;
  width: 6px;
  cursor: ew-resize;
}

.resize-handle--e { right: 0; }
.resize-handle--w { left: 0; }

.resize-handle--ne,
.resize-handle--nw,
.resize-handle--se,
.resize-handle--sw {
  width: 12px;
  height: 12px;
}

.resize-handle--ne { top: 0; right: 0; cursor: nesw-resize; }
.resize-handle--nw { top: 0; left: 0; cursor: nwse-resize; }
.resize-handle--se { bottom: 0; right: 0; cursor: nwse-resize; }
.resize-handle--sw { bottom: 0; left: 0; cursor: nesw-resize; }

.chat-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--assistant-spacing-3, 12px) var(--assistant-spacing-4, 16px);
  border-bottom: 1px solid var(--assistant-border-default, #45474c);
  flex-shrink: 0;
  cursor: grab;
}

.chat-panel--dragging .chat-panel__header {
  cursor: grabbing;
}

.chat-panel__header h3 {
  font-size: var(--assistant-text-md, 16px);
  font-weight: var(--assistant-font-semibold, 600);
  margin: 0;
}

.chat-panel__actions {
  display: flex;
  gap: var(--assistant-spacing-1, 4px);
}

.chat-panel__action {
  background: none;
  border: none;
  color: var(--assistant-text-subdued, #babdc4);
  cursor: pointer;
  padding: var(--assistant-spacing-1, 4px);
  border-radius: var(--assistant-radius-sm, 4px);
  display: flex;
  align-items: center;
  justify-content: center;
}

.chat-panel__action:hover {
  color: var(--assistant-text-base, #f4f4f5);
  background: var(--assistant-bg-highlight, #34363a);
}

.chat-panel__action--active {
  color: var(--assistant-accent, #3b82f6);
}

.chat-panel__action--active:hover {
  color: var(--assistant-accent-hover, #2563eb);
}

.chat-panel__messages {
  flex: 1;
  overflow-y: auto;
  padding: var(--assistant-spacing-3, 12px);
}

.chat-panel__setup,
.chat-panel__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  text-align: center;
  padding: var(--assistant-spacing-4, 16px);
}

.chat-panel__setup-icon {
  color: var(--assistant-text-subdued, #babdc4);
  margin-bottom: var(--assistant-spacing-3, 12px);
}

.chat-panel__setup h4,
.chat-panel__empty p {
  color: var(--assistant-text-subdued, #babdc4);
  margin-bottom: var(--assistant-spacing-2, 8px);
}

.chat-panel__setup p {
  font-size: var(--assistant-text-sm, 14px);
  color: var(--assistant-text-subtle, #969ba5);
  margin-bottom: var(--assistant-spacing-4, 16px);
}

.chat-panel__setup-btn {
  padding: var(--assistant-spacing-2, 8px) var(--assistant-spacing-4, 16px);
  background: var(--assistant-accent, #3b82f6);
  border: none;
  border-radius: var(--assistant-radius-md, 8px);
  color: var(--assistant-text-negative, #fff);
  font-size: var(--assistant-text-sm, 14px);
  font-weight: var(--assistant-font-medium, inherit);
  cursor: pointer;
  transition: background var(--assistant-transition-fast, 150ms ease);
}

.chat-panel__setup-btn:hover {
  background: var(--assistant-accent-hover, #2563eb);
}

.chat-panel__suggestions {
  display: flex;
  flex-direction: column;
  gap: var(--assistant-spacing-2, 8px);
  width: 100%;
  max-width: 250px;
}

.chat-panel__suggestions button {
  padding: var(--assistant-spacing-2, 8px) var(--assistant-spacing-3, 12px);
  background: var(--assistant-bg-highlight, #34363a);
  border: 1px solid var(--assistant-border-default, #45474c);
  border-radius: var(--assistant-radius-md, 8px);
  color: var(--assistant-text-base, #f4f4f5);
  font-size: var(--assistant-text-sm, 14px);
  cursor: pointer;
  transition: all var(--assistant-transition-fast, 150ms ease);
  text-align: left;
}

.chat-panel__suggestions button:hover {
  background: var(--assistant-bg-press, #42454b);
  border-color: var(--assistant-accent, #3b82f6);
}

.chat-panel__streaming .message__content--assistant {
  background: var(--assistant-bg-highlight, #34363a);
  color: var(--assistant-text-base, #f4f4f5);
  padding: var(--assistant-spacing-2, 8px) var(--assistant-spacing-3, 12px);
  border-radius: var(--assistant-radius-lg, 12px);
  border-bottom-left-radius: var(--assistant-radius-sm, 4px);
  font-size: var(--assistant-text-sm, 14px);
  white-space: pre-wrap;
}

.chat-panel__cursor {
  display: inline-block;
  width: 2px;
  height: 1em;
  background: var(--assistant-text-base, #f4f4f5);
  margin-left: 2px;
  animation: blink 1s infinite;
}

@keyframes blink {
  0%, 50% { opacity: 1; }
  51%, 100% { opacity: 0; }
}

.chat-panel__loading {
  display: flex;
  justify-content: flex-start;
  padding: var(--assistant-spacing-2, 8px);
}

.chat-panel__loading-dots {
  display: flex;
  gap: 4px;
  padding: var(--assistant-spacing-2, 8px) var(--assistant-spacing-3, 12px);
  background: var(--assistant-bg-highlight, #34363a);
  border-radius: var(--assistant-radius-lg, 12px);
}

.chat-panel__loading-dots span {
  width: 8px;
  height: 8px;
  background: var(--assistant-text-subdued, #babdc4);
  border-radius: 50%;
  animation: bounce 1.4s infinite ease-in-out both;
}

.chat-panel__loading-dots span:nth-child(1) { animation-delay: -0.32s; }
.chat-panel__loading-dots span:nth-child(2) { animation-delay: -0.16s; }

@keyframes bounce {
  0%, 80%, 100% { transform: scale(0); }
  40% { transform: scale(1); }
}

.chat-panel__error {
  margin-top: var(--assistant-spacing-2, 8px);
  padding: var(--assistant-spacing-2, 8px) var(--assistant-spacing-3, 12px);
  background: rgba(226, 33, 52, 0.1);
  border-radius: var(--assistant-radius-md, 8px);
  color: var(--assistant-error, #ef7777);
  font-size: var(--assistant-text-sm, 14px);
}

.chat-panel__input-container {
  display: flex;
  gap: var(--assistant-spacing-2, 8px);
  padding: var(--assistant-spacing-3, 12px);
  border-top: 1px solid var(--assistant-border-default, #45474c);
  flex-shrink: 0;
}

.chat-panel__input {
  flex: 1;
  padding: var(--assistant-spacing-2, 8px) var(--assistant-spacing-3, 12px);
  background: var(--assistant-bg-base, #17181a);
  border: 1px solid var(--assistant-border-default, #45474c);
  border-radius: var(--assistant-radius-lg, 12px);
  color: var(--assistant-text-base, #f4f4f5);
  font-size: var(--assistant-text-sm, 14px);
  resize: none;
  min-height: 40px;
  max-height: 100px;
  font-family: inherit;
}

.chat-panel__input:focus {
  outline: none;
  border-color: var(--assistant-accent, #3b82f6);
}

.chat-panel__input::placeholder {
  color: var(--assistant-text-subtle, #969ba5);
}

.chat-panel__input:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.chat-panel__send {
  width: 40px;
  height: 40px;
  background: var(--assistant-accent, #3b82f6);
  border: none;
  border-radius: var(--assistant-radius-full, 999px);
  color: var(--assistant-text-negative, #fff);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: all var(--assistant-transition-fast, 150ms ease);
}

.chat-panel__send:hover:not(:disabled) {
  background: var(--assistant-accent-hover, #2563eb);
}

.chat-panel__send:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* Slide animation */
.slide-enter-active,
.slide-leave-active {
  transition: all var(--assistant-transition-base, 200ms ease);
}

.slide-enter-from,
.slide-leave-to {
  opacity: 0;
  transform: translateY(20px) scale(0.95);
}

@media (max-width: 768px) {
  .chat-panel {
    /* On mobile, use fixed positioning and ignore saved geometry */
    bottom: calc(var(--assistant-mobile-bottom-offset, 0px) + var(--assistant-mobile-nav-height, 0px) + 80px) !important;
    right: 16px !important;
    left: 16px !important;
    top: auto !important;
    width: auto !important;
    height: 60vh !important;
    max-height: calc(100vh - var(--assistant-mobile-bottom-offset, 0px) - var(--assistant-mobile-nav-height, 0px) - 100px);
  }

  .chat-panel__header {
    cursor: default;
  }

  .resize-handle {
    display: none;
  }
}
</style>
