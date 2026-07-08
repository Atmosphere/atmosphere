<script setup lang="ts">
import { computed } from 'vue'
import { usePollingResource } from '../composables/useGovernance'

/**
 * Durable-run checkpoints view. Reads the read-only admin surface
 * `/api/admin/checkpoints`, which lists the WorkflowSnapshot envelopes the
 * resolved CheckpointStore has persisted (in-memory by default, SQLite /
 * Postgres when configured). Newest-first, bounded server-side.
 */
interface Checkpoint {
  id: string
  parentId: string | null
  root: boolean
  coordinationId: string
  agentName: string | null
  createdAt: string
  metadata: Record<string, string>
}

const props = defineProps<{ active: boolean }>()
const active = computed(() => props.active)

const { data, error, loading, refresh } = usePollingResource<Checkpoint[]>(
  '/api/admin/checkpoints?limit=200',
  [],
  3000,
  active
)

const coordinationCount = computed(
  () => new Set(data.value.map((c) => c.coordinationId)).size
)

function formatTime(iso: string) {
  try {
    return new Date(iso).toLocaleString(undefined, {
      month: 'short', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    })
  } catch {
    return iso
  }
}

function shortId(id: string | null) {
  if (!id) return '—'
  return id.length > 8 ? id.slice(0, 8) : id
}
</script>

<template>
  <div class="ckpt-view" data-testid="checkpoints">
    <div class="ckpt-toolbar">
      <h2 class="ckpt-title">Durable-run checkpoints</h2>
      <div class="toolbar-actions">
        <span class="count-chip">{{ data.length }} snapshots</span>
        <span class="count-chip">{{ coordinationCount }} runs</span>
        <button class="refresh-btn" @click="refresh" :disabled="loading">
          {{ loading ? 'Refreshing…' : 'Refresh' }}
        </button>
      </div>
    </div>
    <p v-if="error" class="error">{{ error }}</p>
    <p v-else-if="data.length === 0" class="empty">
      No checkpoints persisted yet. Durable executions and agent passivation
      write WorkflowSnapshots to the configured CheckpointStore; they appear
      here as runs execute.
    </p>
    <table v-else class="ckpt-table" data-testid="checkpoints-table">
      <thead>
        <tr>
          <th>Checkpoint</th>
          <th>Parent</th>
          <th>Coordination</th>
          <th>Agent</th>
          <th>Created</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="ckpt in data"
          :key="ckpt.id"
          class="ckpt-row"
          data-testid="checkpoint-row"
        >
          <td class="mono">
            <span class="badge" :class="ckpt.root ? 'badge-root' : 'badge-child'">
              {{ ckpt.root ? 'root' : 'fork' }}
            </span>
            <span class="ckpt-id" :title="ckpt.id">{{ shortId(ckpt.id) }}</span>
          </td>
          <td class="mono small" :title="ckpt.parentId ?? ''">{{ shortId(ckpt.parentId) }}</td>
          <td class="mono small coord" :title="ckpt.coordinationId">{{ ckpt.coordinationId }}</td>
          <td class="small">{{ ckpt.agentName ?? '—' }}</td>
          <td class="small timestamp mono">{{ formatTime(ckpt.createdAt) }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.ckpt-view {
  padding: 1.5rem;
  height: 100%;
  overflow-y: auto;
  max-width: 64rem;
  margin: 0 auto;
}
.ckpt-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
  flex-wrap: wrap;
  gap: 0.5rem;
}
.ckpt-title {
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
  font-weight: 600;
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
.ckpt-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.8125rem;
}
.ckpt-table th {
  text-align: left;
  padding: 0.5rem 0.625rem;
  color: var(--text-tertiary);
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  font-size: 0.6875rem;
  border-bottom: 1px solid var(--border-color);
}
.ckpt-row td {
  padding: 0.5rem 0.625rem;
  border-bottom: 1px solid var(--border-color);
  color: var(--text-secondary);
  vertical-align: middle;
}
.ckpt-row:hover { background: var(--bg-hover); }
.badge {
  font-size: 0.625rem;
  font-weight: 700;
  text-transform: uppercase;
  padding: 0.0625rem 0.375rem;
  border-radius: 4px;
  letter-spacing: 0.05em;
  margin-right: 0.5rem;
}
.badge-root { background: #e8f5e9; color: #2e7d32; }
.badge-child { background: #e3f2fd; color: #1565c0; }
@media (prefers-color-scheme: dark) {
  .badge-root { background: rgba(46, 125, 50, 0.18); }
  .badge-child { background: rgba(21, 101, 192, 0.2); }
}
.ckpt-id { color: var(--text-primary); }
.coord { max-width: 16rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.timestamp { color: var(--text-tertiary); }
.mono { font-family: ui-monospace, Menlo, monospace; }
.small { font-size: 0.75rem; }
</style>
