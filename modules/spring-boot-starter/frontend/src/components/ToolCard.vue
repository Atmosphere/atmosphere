<script setup lang="ts">
import { computed } from 'vue'
import { marked } from 'marked'
import type { ToolCall } from '../composables/useAtmosphereChat'

marked.setOptions({ breaks: true, gfm: true })

const props = defineProps<{
  tool: ToolCall
}>()

const emit = defineEmits<{
  (e: 'approve', approvalId: string): void
  (e: 'deny', approvalId: string): void
}>()

const displayName = computed(() => {
  return props.tool.name
    .replace(/_/g, ' ')
    .replace(/\b\w/g, c => c.toUpperCase())
})

const renderedResult = computed(() => {
  if (!props.tool.result) return ''
  const text = props.tool.result.length > 800
    ? props.tool.result.slice(0, 800) + '...'
    : props.tool.result
  // Render markdown (tables, bold, lists) if content has markdown syntax
  if (text.includes('|') || text.includes('**') || text.includes('- ')) {
    return marked.parse(text) as string
  }
  return ''
})

const truncatedResult = computed(() => {
  if (!props.tool.result) return ''
  return props.tool.result.length > 400
    ? props.tool.result.slice(0, 400) + '...'
    : props.tool.result
})

const argEntries = computed(() =>
  Object.entries(props.tool.args).filter(([, v]) => v !== undefined && v !== '')
)
</script>

<template>
  <div class="tool-card" :class="{ 'tool-card--done': tool.done }" data-testid="tool-card">
    <div class="tool-header">
      <div class="tool-label">
        <span class="tool-icon">{{ tool.done ? '\u2713' : '\u25CF' }}</span>
        <span class="tool-name">{{ displayName }}</span>
      </div>
      <span class="tool-status" :class="{ 'tool-status--approval': tool.approval }">
        {{ tool.approval ? 'Awaiting approval' : (tool.done ? 'Done' : 'Working...') }}
      </span>
    </div>
    <div v-if="argEntries.length" class="tool-args">
      <div v-for="[key, val] in argEntries" :key="key" class="tool-arg">
        <span class="arg-key">{{ key }}:</span>
        <span class="arg-val">{{ String(val).slice(0, 120) }}</span>
      </div>
    </div>
    <div v-if="tool.approval && !tool.done" class="approval-prompt" data-testid="approval-prompt">
      <p class="approval-message">{{ tool.approval.message }}</p>
      <div class="approval-actions">
        <button
          class="approval-btn approval-btn--approve"
          data-testid="approval-approve"
          @click="emit('approve', tool.approval!.approvalId)"
        >Approve</button>
        <button
          class="approval-btn approval-btn--deny"
          data-testid="approval-deny"
          @click="emit('deny', tool.approval!.approvalId)"
        >Deny</button>
      </div>
    </div>
    <div v-if="renderedResult" class="tool-result tool-result--md" v-html="renderedResult"></div>
    <div v-else-if="truncatedResult" class="tool-result">
      {{ truncatedResult }}
    </div>
  </div>
</template>

<style scoped>
.tool-card {
  border-left: 3px solid var(--accent-color);
  background: var(--bg-surface);
  border-radius: 4px 10px 10px 4px;
  padding: 0.75rem 1rem;
  margin-bottom: 0.5rem;
  animation: slideIn 0.3s ease;
  border: 1px solid var(--border-color);
  border-left: 3px solid var(--accent-color);
}

.tool-card--done {
  border-left-color: #10b981;
}

.tool-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 0.375rem;
}

.tool-label {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--text-primary);
}

.tool-icon {
  font-size: 0.625rem;
  color: var(--accent-color);
}

.tool-card--done .tool-icon {
  color: #10b981;
}

.tool-status {
  font-size: 0.6875rem;
  color: var(--text-tertiary);
  font-weight: 500;
}

.tool-card--done .tool-status {
  color: #10b981;
}

.tool-status--approval {
  color: #f59e0b;
}

.approval-prompt {
  margin: 0.5rem 0 0.25rem 0;
  padding: 0.625rem 0.75rem;
  background: var(--bg-tertiary);
  border: 1px solid var(--border-color);
  border-left: 3px solid #f59e0b;
  border-radius: 6px;
}

.approval-message {
  margin: 0 0 0.5rem 0;
  font-size: 0.8125rem;
  color: var(--text-primary);
  line-height: 1.4;
}

.approval-actions {
  display: flex;
  gap: 0.5rem;
}

.approval-btn {
  font-size: 0.75rem;
  font-weight: 600;
  padding: 0.375rem 0.875rem;
  border-radius: 6px;
  border: 1px solid transparent;
  cursor: pointer;
  transition: all 0.15s;
  font-family: inherit;
}

.approval-btn--approve {
  background: var(--accent-color);
  color: #fff;
  border-color: var(--accent-color);
}

.approval-btn--approve:hover {
  filter: brightness(1.08);
}

.approval-btn--deny {
  background: transparent;
  color: var(--text-secondary);
  border-color: var(--border-color);
}

.approval-btn--deny:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.tool-args {
  font-size: 0.75rem;
  font-family: 'SF Mono', 'Fira Code', monospace;
  color: var(--text-tertiary);
  margin-bottom: 0.375rem;
}

.tool-arg {
  line-height: 1.4;
}

.arg-key {
  color: var(--text-tertiary);
}

.arg-val {
  color: var(--text-secondary);
}

.tool-result {
  font-size: 0.75rem;
  color: var(--text-secondary);
  background: var(--bg-tertiary);
  border-radius: 6px;
  padding: 0.5rem 0.75rem;
  max-height: 200px;
  overflow-y: auto;
  white-space: pre-wrap;
  line-height: 1.5;
}

.tool-result--md {
  white-space: normal;
}

.tool-result--md :deep(table) {
  border-collapse: collapse;
  width: 100%;
  font-size: 0.7rem;
}

.tool-result--md :deep(th),
.tool-result--md :deep(td) {
  border: 1px solid var(--border-color);
  padding: 0.25rem 0.5rem;
  text-align: left;
}

.tool-result--md :deep(th) {
  background: var(--bg-surface);
  font-weight: 600;
}

@keyframes slideIn {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
