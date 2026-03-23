<script setup lang="ts">
import { ref, nextTick } from 'vue'

const props = defineProps<{
  disabled: boolean
  isStreaming: boolean
}>()

const emit = defineEmits<{
  send: [text: string]
}>()

const input = ref('')
const textarea = ref<HTMLTextAreaElement | null>(null)

function handleSend() {
  if (!input.value.trim() || props.disabled || props.isStreaming) return
  emit('send', input.value)
  input.value = ''
  nextTick(() => autoResize())
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

function autoResize() {
  const el = textarea.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}
</script>

<template>
  <div class="input-area">
    <div class="input-wrapper">
      <textarea
        ref="textarea"
        v-model="input"
        :placeholder="disabled ? 'Connecting...' : 'Type a message... (Shift+Enter for newline)'"
        rows="1"
        class="input-field"
        data-testid="chat-input"
        @keydown="handleKeydown"
        @input="autoResize"
      ></textarea>
      <button
        class="send-btn"
        :disabled="!input.trim() || disabled || isStreaming"
        @click="handleSend"
        title="Send message"
        data-testid="chat-send"
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="22" y1="2" x2="11" y2="13"/>
          <polygon points="22 2 15 22 11 13 2 9 22 2"/>
        </svg>
      </button>
    </div>
    <p class="input-hint">
      Press <kbd>Enter</kbd> to send, <kbd>Shift+Enter</kbd> for a new line
    </p>
  </div>
</template>

<style scoped>
.input-area {
  padding: 1rem 1.5rem;
  border-top: 1px solid var(--border-color);
  background: var(--bg-surface);
}

.input-wrapper {
  display: flex;
  align-items: flex-end;
  gap: 0.5rem;
  max-width: 48rem;
  margin: 0 auto;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  padding: 0.5rem 0.5rem 0.5rem 1rem;
  transition: border-color 0.15s;
}

.input-wrapper:focus-within {
  border-color: var(--accent-color);
  box-shadow: 0 0 0 3px var(--accent-bg);
}

.input-field {
  flex: 1;
  border: none;
  outline: none;
  resize: none;
  background: transparent;
  font-family: inherit;
  font-size: 0.9375rem;
  line-height: 1.5;
  color: var(--text-primary);
  padding: 0.25rem 0;
  max-height: 200px;
}

.input-field::placeholder {
  color: var(--text-tertiary);
}

.input-field:disabled {
  opacity: 0.5;
}

.send-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 8px;
  border: none;
  background: var(--accent-color);
  color: white;
  cursor: pointer;
  flex-shrink: 0;
  transition: opacity 0.15s, transform 0.1s;
}

.send-btn:hover:not(:disabled) {
  opacity: 0.9;
  transform: scale(1.02);
}

.send-btn:active:not(:disabled) {
  transform: scale(0.98);
}

.send-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.input-hint {
  text-align: center;
  font-size: 0.6875rem;
  color: var(--text-tertiary);
  margin: 0.5rem 0 0 0;
}

.input-hint kbd {
  font-family: inherit;
  font-size: 0.625rem;
  padding: 0.0625rem 0.375rem;
  border: 1px solid var(--border-color);
  border-radius: 4px;
  background: var(--bg-tertiary);
}
</style>
