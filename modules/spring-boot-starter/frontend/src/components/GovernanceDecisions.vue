<script setup lang="ts">
import { computed, ref } from 'vue'
import { usePollingResource, type Decision } from '../composables/useGovernance'

const props = defineProps<{ active: boolean }>()
const active = computed(() => props.active)

const filterDecision = ref<string>('all')
const { data, error, loading, refresh } = usePollingResource<Decision[]>(
  '/api/admin/governance/decisions?limit=200',
  [],
  2000,
  active
)

const filtered = computed(() => {
  if (filterDecision.value === 'all') return data.value
  return data.value.filter((d) => d.decision === filterDecision.value)
})

const counts = computed(() => {
  const acc: Record<string, number> = { admit: 0, deny: 0, transform: 0, error: 0 }
  for (const entry of data.value) {
    acc[entry.decision] = (acc[entry.decision] ?? 0) + 1
  }
  return acc
})

function formatTime(iso: string) {
  try {
    return new Date(iso).toLocaleTimeString(undefined, {
      hour: '2-digit', minute: '2-digit', second: '2-digit', fractionalSecondDigits: 3,
    } as Intl.DateTimeFormatOptions)
  } catch {
    return iso
  }
}

function decisionClass(d: string) {
  return `badge badge-${d}`
}
</script>

<template>
  <div class="gov-view" data-testid="governance-decisions">
    <div class="gov-toolbar">
      <h2 class="gov-title">Recent decisions</h2>
      <div class="toolbar-actions">
        <span class="count-chip admit">admit {{ counts.admit ?? 0 }}</span>
        <span class="count-chip deny">deny {{ counts.deny ?? 0 }}</span>
        <span class="count-chip transform">xform {{ counts.transform ?? 0 }}</span>
        <span class="count-chip error">error {{ counts.error ?? 0 }}</span>
        <select v-model="filterDecision" class="filter-select">
          <option value="all">All</option>
          <option value="admit">Admit</option>
          <option value="deny">Deny</option>
          <option value="transform">Transform</option>
          <option value="error">Error</option>
        </select>
        <button class="refresh-btn" @click="refresh" :disabled="loading">
          {{ loading ? 'Refreshing…' : 'Refresh' }}
        </button>
      </div>
    </div>
    <p v-if="error" class="error">{{ error }}</p>
    <p v-else-if="filtered.length === 0" class="empty">
      No decisions recorded yet. Governance decisions appear as prompts flow
      through installed policies.
    </p>
    <ul v-else class="decision-list">
      <li
        v-for="(entry, idx) in filtered"
        :key="entry.timestamp + ':' + idx"
        class="decision"
      >
        <div class="decision-head">
          <span :class="decisionClass(entry.decision)">{{ entry.decision }}</span>
          <span class="timestamp mono">{{ formatTime(entry.timestamp) }}</span>
          <span class="policy mono small">{{ entry.policy_name }}</span>
          <span class="evalms small">{{ entry.evaluation_ms.toFixed(2) }} ms</span>
        </div>
        <p v-if="entry.reason" class="reason">{{ entry.reason }}</p>
        <details v-if="entry.context_snapshot && Object.keys(entry.context_snapshot).length > 0">
          <summary>Context</summary>
          <pre class="ctx">{{ JSON.stringify(entry.context_snapshot, null, 2) }}</pre>
        </details>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.gov-view {
  padding: 1.5rem;
  height: 100%;
  overflow-y: auto;
  max-width: 64rem;
  margin: 0 auto;
}
.gov-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
  flex-wrap: wrap;
  gap: 0.5rem;
}
.gov-title {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}
.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  flex-wrap: wrap;
}
.count-chip {
  font-size: 0.75rem;
  padding: 0.125rem 0.5rem;
  border-radius: 9999px;
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  font-weight: 600;
}
.count-chip.admit { color: #2e7d32; border-color: #2e7d3233; }
.count-chip.deny { color: #c62828; border-color: #c6282833; }
.count-chip.transform { color: #6a1b9a; border-color: #6a1b9a33; }
.count-chip.error { color: #ef6c00; border-color: #ef6c0033; }
.filter-select {
  padding: 0.25rem 0.5rem;
  font-size: 0.8125rem;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  background: var(--bg-surface);
  color: var(--text-primary);
}
.refresh-btn {
  font-size: 0.8125rem;
  color: var(--text-secondary);
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  padding: 0.25rem 0.75rem;
  border-radius: 6px;
  cursor: pointer;
}
.refresh-btn:hover:not(:disabled) { background: var(--bg-hover); }
.empty, .error {
  padding: 2rem;
  text-align: center;
  color: var(--text-tertiary);
  font-size: 0.875rem;
  line-height: 1.5;
}
.error { color: #c62828; }
.decision-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.decision {
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 0.625rem 0.875rem;
}
.decision-head {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  flex-wrap: wrap;
}
.badge {
  font-size: 0.6875rem;
  font-weight: 700;
  text-transform: uppercase;
  padding: 0.125rem 0.5rem;
  border-radius: 4px;
  letter-spacing: 0.05em;
}
.badge-admit { background: #e8f5e9; color: #2e7d32; }
.badge-deny { background: #ffebee; color: #c62828; }
.badge-transform { background: #f3e5f5; color: #6a1b9a; }
.badge-error { background: #fff3e0; color: #ef6c00; }
@media (prefers-color-scheme: dark) {
  .badge-admit { background: rgba(46, 125, 50, 0.18); }
  .badge-deny { background: rgba(198, 40, 40, 0.18); }
  .badge-transform { background: rgba(106, 27, 154, 0.2); }
  .badge-error { background: rgba(239, 108, 0, 0.2); }
}
.timestamp { color: var(--text-tertiary); font-size: 0.75rem; }
.policy { color: var(--text-primary); flex: 1 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; }
.evalms { color: var(--text-tertiary); }
.mono { font-family: ui-monospace, Menlo, monospace; }
.small { font-size: 0.8125rem; }
.reason {
  margin: 0.25rem 0 0 0;
  font-size: 0.8125rem;
  color: var(--text-secondary);
  line-height: 1.4;
}
details {
  margin-top: 0.25rem;
  font-size: 0.75rem;
}
details summary {
  color: var(--text-tertiary);
  cursor: pointer;
  user-select: none;
}
.ctx {
  margin: 0.375rem 0 0 0;
  padding: 0.5rem;
  background: var(--code-block-bg);
  border-radius: 4px;
  font-family: ui-monospace, Menlo, monospace;
  font-size: 0.75rem;
  overflow-x: auto;
  color: var(--text-secondary);
  white-space: pre-wrap;
}
</style>
