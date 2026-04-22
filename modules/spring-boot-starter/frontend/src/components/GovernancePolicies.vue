<script setup lang="ts">
import { computed, ref } from 'vue'
import { usePollingResource, type Policy } from '../composables/useGovernance'

const props = defineProps<{ active: boolean }>()
const active = computed(() => props.active)

const { data, error, loading, refresh } = usePollingResource<Policy[]>(
  '/api/admin/governance/policies',
  [],
  5000,
  active
)

const selected = ref<Policy | null>(null)
function shortClass(fqcn: string) {
  const idx = fqcn.lastIndexOf('.')
  return idx >= 0 ? fqcn.substring(idx + 1) : fqcn
}
</script>

<template>
  <div class="gov-view" data-testid="governance-policies">
    <div class="gov-toolbar">
      <h2 class="gov-title">Installed policies</h2>
      <button class="refresh-btn" @click="refresh" :disabled="loading">
        {{ loading ? 'Refreshing…' : 'Refresh' }}
      </button>
    </div>
    <p v-if="error" class="error">{{ error }}</p>
    <p v-else-if="data.length === 0" class="empty">
      No governance policies installed. Add <code>@AgentScope</code>, a
      YAML file, or programmatically publish via
      <code>GovernancePolicy.POLICIES_PROPERTY</code>.
    </p>
    <div v-else class="table-wrap">
      <table class="gov-table">
        <thead>
          <tr>
            <th>Name</th>
            <th>Source</th>
            <th>Version</th>
            <th>Class</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="policy in data"
            :key="policy.name + ':' + policy.source"
            @click="selected = policy"
            :class="{ selected: selected?.name === policy.name }"
          >
            <td class="mono">{{ policy.name }}</td>
            <td class="mono small">{{ policy.source }}</td>
            <td class="small">{{ policy.version }}</td>
            <td class="mono small" :title="policy.className">
              {{ shortClass(policy.className) }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="selected" class="detail">
      <h3>{{ selected.name }}</h3>
      <dl>
        <dt>Source</dt><dd class="mono small">{{ selected.source }}</dd>
        <dt>Version</dt><dd class="small">{{ selected.version }}</dd>
        <dt>Class</dt><dd class="mono small">{{ selected.className }}</dd>
      </dl>
    </div>
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
}
.gov-title {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
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
.table-wrap {
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  overflow: hidden;
}
.gov-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.875rem;
}
.gov-table th {
  text-align: left;
  background: var(--bg-tertiary);
  padding: 0.5rem 0.75rem;
  font-weight: 600;
  font-size: 0.75rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--text-tertiary);
  border-bottom: 1px solid var(--border-color);
}
.gov-table td {
  padding: 0.5rem 0.75rem;
  border-bottom: 1px solid var(--border-color);
  vertical-align: top;
}
.gov-table tbody tr {
  cursor: pointer;
  transition: background 0.12s;
}
.gov-table tbody tr:hover { background: var(--bg-hover); }
.gov-table tbody tr.selected { background: var(--accent-bg); }
.mono { font-family: ui-monospace, Menlo, monospace; }
.small { font-size: 0.8125rem; color: var(--text-secondary); }
.detail {
  margin-top: 1.5rem;
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 1rem 1.25rem;
}
.detail h3 {
  font-size: 0.9375rem;
  font-weight: 600;
  margin: 0 0 0.5rem 0;
}
.detail dl {
  display: grid;
  grid-template-columns: 6rem 1fr;
  gap: 0.25rem 0.75rem;
  font-size: 0.8125rem;
}
.detail dt { color: var(--text-tertiary); }
.detail dd { margin: 0; color: var(--text-secondary); word-break: break-all; }
code {
  background: var(--code-bg);
  padding: 0.125rem 0.375rem;
  border-radius: 3px;
  font-family: ui-monospace, Menlo, monospace;
  font-size: 0.85em;
}
</style>
