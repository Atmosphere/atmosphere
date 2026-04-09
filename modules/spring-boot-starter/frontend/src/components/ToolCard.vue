<script setup lang="ts">
import { computed } from 'vue'
import { marked } from 'marked'
import type { ToolCall } from '../composables/useAtmosphereChat'

marked.setOptions({ breaks: true, gfm: true })

const props = defineProps<{
  tool: ToolCall
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
      <span class="tool-status">{{ tool.done ? 'Done' : 'Working...' }}</span>
    </div>
    <div v-if="argEntries.length" class="tool-args">
      <div v-for="[key, val] in argEntries" :key="key" class="tool-arg">
        <span class="arg-key">{{ key }}:</span>
        <span class="arg-val">{{ String(val).slice(0, 120) }}</span>
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
