<script setup lang="ts">
import { computed } from 'vue'
import { usePollingResource, type OwaspMatrix } from '../composables/useGovernance'

const props = defineProps<{ active: boolean }>()
const active = computed(() => props.active)

const initialMatrix: OwaspMatrix = {
  framework: '',
  rows: [],
  coverage_counts: {},
  total_rows: 0,
}

// Matrix is static per deployment — no need for aggressive polling.
const { data, error, loading, refresh } = usePollingResource<OwaspMatrix>(
  '/api/admin/governance/owasp',
  initialMatrix,
  30000,
  active
)

function coverageLabel(coverage: string) {
  switch (coverage) {
    case 'COVERED': return 'Covered'
    case 'PARTIAL': return 'Partial'
    case 'DESIGN': return 'Design only'
    case 'NOT_ADDRESSED': return 'Not addressed'
    default: return coverage
  }
}
</script>

<template>
  <div class="gov-view" data-testid="governance-owasp">
    <div class="gov-toolbar">
      <div>
        <h2 class="gov-title">OWASP Agentic AI Top 10</h2>
        <p class="framework" v-if="data.framework">{{ data.framework }}</p>
      </div>
      <button class="refresh-btn" @click="refresh" :disabled="loading">
        {{ loading ? 'Refreshing…' : 'Refresh' }}
      </button>
    </div>
    <p v-if="error" class="error">{{ error }}</p>
    <template v-else>
      <div class="coverage-bar" v-if="data.rows.length > 0">
        <span class="chip covered">Covered {{ data.coverage_counts.COVERED ?? 0 }}</span>
        <span class="chip partial">Partial {{ data.coverage_counts.PARTIAL ?? 0 }}</span>
        <span class="chip design">Design {{ data.coverage_counts.DESIGN ?? 0 }}</span>
        <span class="chip not-addressed">Not addressed {{ data.coverage_counts.NOT_ADDRESSED ?? 0 }}</span>
        <span class="chip total">Total {{ data.total_rows }}</span>
      </div>
      <p v-if="data.rows.length === 0" class="empty">
        OWASP matrix not available yet.
      </p>
      <div v-else class="row-list">
        <article v-for="row in data.rows" :key="row.id" class="owasp-row">
          <header class="row-head">
            <span class="row-id">{{ row.id }}</span>
            <span class="row-title">{{ row.title }}</span>
            <span :class="['badge', `badge-${row.coverage.toLowerCase()}`]">
              {{ coverageLabel(row.coverage) }}
            </span>
          </header>
          <p class="row-desc">{{ row.description }}</p>
          <p v-if="row.notes" class="row-notes">{{ row.notes }}</p>
          <details v-if="row.evidence.length > 0">
            <summary>{{ row.evidence.length }} evidence pointer{{ row.evidence.length === 1 ? '' : 's' }}</summary>
            <ul class="evidence-list">
              <li v-for="(ev, idx) in row.evidence" :key="idx">
                <code>{{ ev.class }}</code>
                <span v-if="ev.test" class="small"> • test: <code>{{ ev.test }}</code></span>
                <span v-if="ev.consumer_grep" class="small"> • consumer grep: <code>{{ ev.consumer_grep }}</code></span>
                <p v-if="ev.description" class="small">{{ ev.description }}</p>
              </li>
            </ul>
          </details>
        </article>
      </div>
    </template>
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
  align-items: flex-start;
  margin-bottom: 1rem;
  gap: 1rem;
}
.gov-title {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}
.framework {
  font-size: 0.75rem;
  color: var(--text-tertiary);
  margin: 0.125rem 0 0 0;
}
.refresh-btn {
  font-size: 0.8125rem;
  color: var(--text-secondary);
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  padding: 0.25rem 0.75rem;
  border-radius: 6px;
  cursor: pointer;
  flex-shrink: 0;
}
.refresh-btn:hover:not(:disabled) { background: var(--bg-hover); }
.coverage-bar {
  display: flex;
  gap: 0.375rem;
  flex-wrap: wrap;
  margin-bottom: 1rem;
}
.chip {
  font-size: 0.75rem;
  padding: 0.125rem 0.5rem;
  border-radius: 9999px;
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  color: var(--text-secondary);
  font-weight: 600;
}
.chip.covered { color: #2e7d32; border-color: #2e7d3233; }
.chip.partial { color: #f9a825; border-color: #f9a82533; }
.chip.design { color: #1565c0; border-color: #1565c033; }
.chip.not-addressed { color: #c62828; border-color: #c6282833; }
.chip.total { color: var(--text-tertiary); }
.empty, .error {
  padding: 2rem;
  text-align: center;
  color: var(--text-tertiary);
  font-size: 0.875rem;
  line-height: 1.5;
}
.error { color: #c62828; }
.row-list {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.owasp-row {
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 0.75rem 1rem;
}
.row-head {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 0.375rem;
}
.row-id {
  font-family: ui-monospace, Menlo, monospace;
  font-size: 0.75rem;
  color: var(--text-tertiary);
  background: var(--bg-tertiary);
  padding: 0.125rem 0.375rem;
  border-radius: 4px;
}
.row-title {
  font-size: 0.9375rem;
  font-weight: 600;
  color: var(--text-primary);
  flex: 1 1 auto;
}
.badge {
  font-size: 0.6875rem;
  font-weight: 700;
  text-transform: uppercase;
  padding: 0.125rem 0.5rem;
  border-radius: 4px;
  letter-spacing: 0.05em;
}
.badge-covered { background: #e8f5e9; color: #2e7d32; }
.badge-partial { background: #fff8e1; color: #f9a825; }
.badge-design { background: #e3f2fd; color: #1565c0; }
.badge-not_addressed { background: #ffebee; color: #c62828; }
@media (prefers-color-scheme: dark) {
  .badge-covered { background: rgba(46, 125, 50, 0.2); }
  .badge-partial { background: rgba(249, 168, 37, 0.2); }
  .badge-design { background: rgba(21, 101, 192, 0.2); }
  .badge-not_addressed { background: rgba(198, 40, 40, 0.2); }
}
.row-desc {
  font-size: 0.8125rem;
  color: var(--text-secondary);
  line-height: 1.45;
  margin: 0;
}
.row-notes {
  margin: 0.5rem 0 0 0;
  font-size: 0.8125rem;
  color: var(--text-secondary);
  line-height: 1.45;
  padding-left: 0.75rem;
  border-left: 2px solid var(--border-color);
}
details {
  margin-top: 0.5rem;
  font-size: 0.75rem;
}
details summary {
  cursor: pointer;
  color: var(--text-tertiary);
  user-select: none;
}
.evidence-list {
  margin: 0.375rem 0 0 1rem;
  padding: 0;
  list-style: disc;
  font-size: 0.75rem;
  line-height: 1.5;
  color: var(--text-secondary);
}
.evidence-list li { margin: 0.25rem 0; }
.evidence-list code {
  background: var(--code-bg);
  padding: 0.0625rem 0.3rem;
  border-radius: 3px;
  font-family: ui-monospace, Menlo, monospace;
  font-size: 0.9em;
  word-break: break-all;
}
.small { font-size: 0.75rem; color: var(--text-tertiary); }
</style>
