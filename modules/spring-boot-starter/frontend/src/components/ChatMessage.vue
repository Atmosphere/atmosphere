<script setup lang="ts">
import { computed } from 'vue'
import type { ChatMessage } from '../composables/useAtmosphereChat'

const props = defineProps<{
  message: ChatMessage
}>()

const isUser = computed(() => props.message.role === 'user')

const formattedContent = computed(() => renderMarkdown(props.message.content))

const timeString = computed(() => {
  const d = new Date(props.message.timestamp)
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
})

function renderMarkdown(text: string): string {
  // Basic markdown rendering without external libraries
  let html = escapeHtml(text)

  // Code blocks (```)
  html = html.replace(/```(\w*)\n([\s\S]*?)```/g, (_match, lang, code) => {
    const langLabel = lang ? `<span class="code-lang">${lang}</span>` : ''
    return `<div class="code-block">${langLabel}<pre><code>${code.trim()}</code></pre></div>`
  })

  // Inline code (`)
  html = html.replace(/`([^`]+)`/g, '<code class="inline-code">$1</code>')

  // Bold (**)
  html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')

  // Italic (*)
  html = html.replace(/(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)/g, '<em>$1</em>')

  // Unordered lists
  html = html.replace(/^[-*] (.+)$/gm, '<li>$1</li>')
  html = html.replace(/(<li>.*<\/li>\n?)+/g, '<ul>$&</ul>')

  // Ordered lists
  html = html.replace(/^\d+\. (.+)$/gm, '<li>$1</li>')

  // Line breaks
  html = html.replace(/\n/g, '<br>')

  return html
}

function escapeHtml(text: string): string {
  const div = document.createElement('div')
  div.textContent = text
  return div.innerHTML
}
</script>

<template>
  <div class="message" :class="{ 'message--user': isUser, 'message--assistant': !isUser }">
    <div class="message-avatar">
      <span v-if="isUser" class="avatar avatar--user">U</span>
      <span v-else class="avatar avatar--assistant">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5"/>
        </svg>
      </span>
    </div>
    <div class="message-body">
      <div class="message-content" v-html="formattedContent"></div>
      <div class="message-meta">
        <span class="message-time">{{ timeString }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.message {
  display: flex;
  gap: 0.75rem;
  margin-bottom: 1.5rem;
  max-width: 48rem;
}

.message--user {
  margin-left: auto;
  flex-direction: row-reverse;
}

.message-avatar {
  flex-shrink: 0;
  margin-top: 2px;
}

.avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  font-size: 0.8125rem;
  font-weight: 600;
}

.avatar--user {
  background: var(--accent-color);
  color: white;
}

.avatar--assistant {
  background: var(--bg-tertiary);
  color: var(--text-secondary);
}

.message-body {
  min-width: 0;
}

.message-content {
  padding: 0.75rem 1rem;
  border-radius: 12px;
  font-size: 0.9375rem;
  line-height: 1.6;
  word-break: break-word;
}

.message--user .message-content {
  background: var(--accent-color);
  color: white;
  border-bottom-right-radius: 4px;
}

.message--assistant .message-content {
  background: var(--bg-surface);
  color: var(--text-primary);
  border: 1px solid var(--border-color);
  border-bottom-left-radius: 4px;
}

.message-meta {
  margin-top: 0.25rem;
  padding: 0 0.25rem;
}

.message-time {
  font-size: 0.6875rem;
  color: var(--text-tertiary);
}

/* Markdown styles */
.message-content :deep(strong) {
  font-weight: 600;
}

.message-content :deep(em) {
  font-style: italic;
}

.message-content :deep(.inline-code) {
  font-family: 'SF Mono', 'Fira Code', 'Fira Mono', 'Roboto Mono', monospace;
  font-size: 0.85em;
  padding: 0.125em 0.375em;
  border-radius: 4px;
  background: var(--code-bg);
}

.message--user .message-content :deep(.inline-code) {
  background: rgba(255, 255, 255, 0.2);
}

.message-content :deep(.code-block) {
  position: relative;
  margin: 0.75rem 0;
  border-radius: 8px;
  overflow: hidden;
  background: var(--code-block-bg);
  border: 1px solid var(--border-color);
}

.message-content :deep(.code-lang) {
  display: block;
  padding: 0.375rem 0.75rem;
  font-size: 0.6875rem;
  font-weight: 600;
  color: var(--text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  border-bottom: 1px solid var(--border-color);
  background: var(--bg-tertiary);
}

.message-content :deep(pre) {
  margin: 0;
  padding: 0.75rem;
  overflow-x: auto;
}

.message-content :deep(code) {
  font-family: 'SF Mono', 'Fira Code', 'Fira Mono', 'Roboto Mono', monospace;
  font-size: 0.8125rem;
  line-height: 1.5;
}

.message-content :deep(ul) {
  margin: 0.5rem 0;
  padding-left: 1.25rem;
}

.message-content :deep(li) {
  margin-bottom: 0.25rem;
}
</style>
