<script setup lang="ts">
import { computed, ref } from 'vue'
import { usePollingResource } from '../composables/useGovernance'

/**
 * Session tape view. Reads the read-only admin surface `/api/admin/tape/runs`
 * (recorded AI runs, newest-first) and, on selecting a run,
 * `/api/admin/tape/runs/{runId}/steps` (the ordered typed step stream). Both
 * sit behind the content-read-auth gate — the tape holds pre-redaction content.
 * The tab only appears when the tape is actually installed (`hasTape`).
 */
interface TapeRun {
  runId: string
  tapeId: string
  status: string
  model: string | null
  runtime: string | null
  endpoint: string | null
  startedAt: number
  endedAt: number | null
  stepCount: number
  droppedSteps: number
}
interface TapeStep {
  seq: number
  kind: string
  payload: string
  ts: number
}

const props = defineProps<{ active: boolean }>()
const active = computed(() => props.active)

const { data: runs, error, loading, refresh } = usePollingResource<TapeRun[]>(
  '/api/admin/tape/runs?limit=200',
  [],
  4000,
  active
)

const selected = ref<string | null>(null)
const steps = ref<TapeStep[]>([])
const stepsError = ref<string | null>(null)
const stepsLoading = ref(false)

async function openRun(runId: string) {
  selected.value = runId
  stepsLoading.value = true
  stepsError.value = null
  steps.value = []
  try {
    const res = await fetch(`/api/admin/tape/runs/${encodeURIComponent(runId)}/steps?max=1000`)
    if (!res.ok) {
      stepsError.value = res.status === 401 || res.status === 403
        ? 'Not authorized to read tape content.'
        : `Failed to load steps (HTTP ${res.status}).`
      return
    }
    const body = await res.json()
    steps.value = Array.isArray(body.steps) ? body.steps : []
  } catch (e) {
    stepsError.value = 'Failed to load steps.'
  } finally {
    stepsLoading.value = false
  }
}

function closeRun() {
  selected.value = null
  steps.value = []
}

function stepText(payload: string): string {
  try {
    const o = JSON.parse(payload)
    if (typeof o.text === 'string') return o.text
    if (typeof o.message === 'string') return o.message
    if (Array.isArray(o.messages)) {
      return o.messages.map((m: { role: string; content: string }) =>
        `${m.role}: ${m.content}`).join('\n')
    }
    if (o.key !== undefined) return `${o.key} = ${JSON.stringify(o.value)}`
    return payload
  } catch {
    return payload
  }
}

function formatTime(ms: number | null) {
  if (!ms) return '—'
  try {
    return new Date(ms).toLocaleString(undefined, {
      month: 'short', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    })
  } catch {
    return String(ms)
  }
}

function shortId(id: string | null) {
  if (!id) return '—'
  return id.length > 8 ? id.slice(0, 8) : id
}

const completed = computed(() => runs.value.filter((r) => r.status === 'COMPLETED').length)
</script>

<template>
  <div class="tape-view" data-testid="tape">
    <div class="tape-toolbar">
      <h2 class="tape-title">Session tape</h2>
      <div class="toolbar-actions">
        <span class="count-chip">{{ runs.length }} runs</span>
        <span class="count-chip">{{ completed }} completed</span>
        <button class="refresh-btn" @click="refresh" :disabled="loading">
          {{ loading ? 'Refreshing…' : 'Refresh' }}
        </button>
      </div>
    </div>
    <p v-if="error" class="error">{{ error }}</p>
    <p v-else-if="runs.length === 0" class="empty">
      No tape runs recorded yet. Each AI turn is recorded as an ordered typed
      step stream (input prompt, streamed text, tool calls, terminal); runs
      appear here as conversations happen.
    </p>
    <table v-else class="tape-table" data-testid="tape-table">
      <thead>
        <tr>
          <th>Run</th>
          <th>Status</th>
          <th>Model</th>
          <th>Runtime</th>
          <th>Steps</th>
          <th>Started</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="run in runs"
          :key="run.runId"
          class="tape-row"
          data-testid="tape-row"
          @click="openRun(run.runId)"
        >
          <td class="mono"><span class="tape-id" :title="run.runId">{{ shortId(run.runId) }}</span></td>
          <td><span class="badge" :class="'st-' + run.status.toLowerCase()">{{ run.status }}</span></td>
          <td class="small mono">{{ run.model ?? '—' }}</td>
          <td class="small">{{ run.runtime ?? '—' }}</td>
          <td class="small mono">{{ run.stepCount }}<span v-if="run.droppedSteps > 0" class="dropped"> (+{{ run.droppedSteps }} dropped)</span></td>
          <td class="small timestamp mono">{{ formatTime(run.startedAt) }}</td>
        </tr>
      </tbody>
    </table>

    <div v-if="selected" class="steps-drawer" data-testid="tape-steps">
      <div class="steps-header">
        <span class="mono steps-run" :title="selected">Run {{ shortId(selected) }} — steps</span>
        <button class="refresh-btn" @click="closeRun">Close</button>
      </div>
      <p v-if="stepsLoading" class="empty">Loading steps…</p>
      <p v-else-if="stepsError" class="error">{{ stepsError }}</p>
      <ol v-else class="steps-list">
        <li v-for="s in steps" :key="s.seq" class="step-item" data-testid="tape-step">
          <span class="step-seq mono">{{ s.seq }}</span>
          <span class="step-kind" :class="'kind-' + s.kind">{{ s.kind }}</span>
          <span class="step-text mono">{{ stepText(s.payload) }}</span>
        </li>
      </ol>
    </div>
  </div>
</template>

<style scoped>
.tape-view {
  padding: 1.5rem;
  height: 100%;
  overflow-y: auto;
  max-width: 64rem;
  margin: 0 auto;
}
.tape-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
  flex-wrap: wrap;
  gap: 0.5rem;
}
.tape-title { font-size: 1.125rem; font-weight: 600; color: var(--text-primary); margin: 0; }
.toolbar-actions { display: flex; align-items: center; gap: 0.375rem; flex-wrap: wrap; }
.count-chip {
  font-size: 0.75rem; padding: 0.125rem 0.5rem; border-radius: 9999px;
  background: var(--bg-surface); border: 1px solid var(--border-color);
  color: var(--text-secondary); font-weight: 600;
}
.refresh-btn {
  font-size: 0.8125rem; color: var(--text-secondary); background: var(--bg-surface);
  border: 1px solid var(--border-color); padding: 0.25rem 0.75rem; border-radius: 6px; cursor: pointer;
}
.refresh-btn:hover:not(:disabled) { background: var(--bg-hover); }
.empty, .error { padding: 2rem; text-align: center; color: var(--text-tertiary); font-size: 0.875rem; line-height: 1.5; }
.error { color: #c62828; }
.tape-table { width: 100%; border-collapse: collapse; font-size: 0.8125rem; }
.tape-table th {
  text-align: left; padding: 0.5rem 0.625rem; color: var(--text-tertiary); font-weight: 600;
  text-transform: uppercase; letter-spacing: 0.04em; font-size: 0.6875rem; border-bottom: 1px solid var(--border-color);
}
.tape-row { cursor: pointer; }
.tape-row td { padding: 0.5rem 0.625rem; border-bottom: 1px solid var(--border-color); color: var(--text-secondary); vertical-align: middle; }
.tape-row:hover { background: var(--bg-hover); }
.badge { font-size: 0.625rem; font-weight: 700; text-transform: uppercase; padding: 0.0625rem 0.375rem; border-radius: 4px; letter-spacing: 0.05em; }
.st-completed { background: #e8f5e9; color: #2e7d32; }
.st-open { background: #e3f2fd; color: #1565c0; }
.st-error { background: #ffebee; color: #c62828; }
.st-cancelled, .st-abandoned { background: var(--bg-surface); color: var(--text-tertiary); }
@media (prefers-color-scheme: dark) {
  .st-completed { background: rgba(46, 125, 50, 0.18); }
  .st-open { background: rgba(21, 101, 192, 0.2); }
  .st-error { background: rgba(198, 40, 40, 0.2); }
}
.tape-id { color: var(--text-primary); }
.dropped { color: #c62828; }
.timestamp { color: var(--text-tertiary); }
.mono { font-family: ui-monospace, Menlo, monospace; }
.small { font-size: 0.75rem; }
.steps-drawer { margin-top: 1.5rem; border-top: 2px solid var(--border-color); padding-top: 1rem; }
.steps-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.75rem; }
.steps-run { font-weight: 600; color: var(--text-primary); }
.steps-list { list-style: none; margin: 0; padding: 0; }
.step-item { display: flex; gap: 0.625rem; padding: 0.375rem 0.5rem; border-bottom: 1px solid var(--border-color); align-items: baseline; }
.step-seq { color: var(--text-tertiary); min-width: 2rem; text-align: right; font-size: 0.75rem; }
.step-kind { font-size: 0.625rem; font-weight: 700; text-transform: uppercase; padding: 0.0625rem 0.375rem; border-radius: 4px; min-width: 5rem; text-align: center; background: var(--bg-surface); color: var(--text-secondary); }
.kind-input { background: #ede7f6; color: #5e35b1; }
.kind-text { background: #e8f5e9; color: #2e7d32; }
.kind-complete { background: #e3f2fd; color: #1565c0; }
.kind-error { background: #ffebee; color: #c62828; }
.step-text { white-space: pre-wrap; word-break: break-word; font-size: 0.75rem; color: var(--text-secondary); flex: 1; }
</style>
