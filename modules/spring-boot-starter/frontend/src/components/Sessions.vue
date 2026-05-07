<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'

interface AgentInfo {
  name: string
  type?: string
  status?: string
  totalRequests?: number
}

interface SessionInfo {
  sessionId: string
  agentName: string
  transport?: string
  startTime?: string
  lastActivity?: string
  messageCount?: number
}

const props = defineProps<{ active: boolean }>()
const active = computed(() => props.active)

const agents = ref<AgentInfo[]>([])
const sessions = ref<SessionInfo[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const lastAction = ref<{ uuid: string; status: string } | null>(null)

async function loadAgents() {
  loading.value = true
  error.value = null
  try {
    const res = await fetch('/api/admin/agents', {
      headers: { Accept: 'application/json' },
    })
    if (!res.ok) {
      error.value = `Agents endpoint returned ${res.status}`
      return
    }
    agents.value = await res.json()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function loadSessions() {
  if (agents.value.length === 0) {
    sessions.value = []
    return
  }
  const all: SessionInfo[] = []
  for (const agent of agents.value) {
    try {
      const res = await fetch(`/api/admin/agents/${encodeURIComponent(agent.name)}/sessions`)
      if (res.ok) {
        const list: SessionInfo[] = await res.json()
        all.push(...list.map((s) => ({ ...s, agentName: s.agentName ?? agent.name })))
      }
    } catch {
      // tolerate per-agent failure; surface aggregate error only when nothing loads
    }
  }
  sessions.value = all
}

async function refresh() {
  await loadAgents()
  await loadSessions()
}

async function cancelInflight(uuid: string) {
  // POST /api/admin/sessions/{uuid}/cancel-inflight aborts any in-flight LLM
  // call on the AI session for this resource WITHOUT closing the transport,
  // surfaced from the server-side AiStreamingSession.cancelInflight primitive.
  try {
    const res = await fetch(
      `/api/admin/sessions/${encodeURIComponent(uuid)}/cancel-inflight`,
      { method: 'POST' },
    )
    if (res.ok) {
      lastAction.value = { uuid, status: 'cancelled' }
    } else if (res.status === 404) {
      lastAction.value = { uuid, status: 'no active AI session' }
    } else {
      lastAction.value = { uuid, status: `error ${res.status}` }
    }
  } catch (e) {
    lastAction.value = { uuid, status: `network error: ${e instanceof Error ? e.message : String(e)}` }
  }
  await loadSessions()
}

function formatTime(iso?: string): string {
  if (!iso) return '—'
  try {
    return new Date(iso).toLocaleTimeString(undefined, {
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    })
  } catch {
    return iso
  }
}

function shortUuid(uuid: string): string {
  return uuid.length > 12 ? uuid.slice(0, 8) + '…' + uuid.slice(-4) : uuid
}

watch(active, (a) => { if (a) refresh() })
onMounted(() => { if (active.value) refresh() })
</script>

<template>
  <div class="sessions-view" data-testid="sessions-view">
    <div class="sessions-toolbar">
      <div>
        <h2 class="sessions-title">Active sessions</h2>
        <p class="subtitle">
          AI streaming sessions. Cancel-inflight aborts the upstream LLM call
          and releases parked tool approvals without closing the transport.
        </p>
      </div>
      <div class="toolbar-actions">
        <span class="count-chip">{{ sessions.length }} active</span>
        <button class="refresh-btn" @click="refresh" :disabled="loading">
          {{ loading ? 'Refreshing…' : 'Refresh' }}
        </button>
      </div>
    </div>

    <p v-if="error" class="error">{{ error }}</p>
    <p v-else-if="sessions.length === 0" class="empty">
      No active streaming sessions on this server.
    </p>

    <table v-else class="session-table">
      <thead>
        <tr>
          <th>UUID</th>
          <th>Agent</th>
          <th>Transport</th>
          <th>Started</th>
          <th>Last activity</th>
          <th class="num">Msgs</th>
          <th class="actions">Actions</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="s in sessions" :key="s.sessionId">
          <td class="mono" :title="s.sessionId">{{ shortUuid(s.sessionId) }}</td>
          <td>{{ s.agentName ?? '—' }}</td>
          <td>{{ s.transport ?? '—' }}</td>
          <td class="small">{{ formatTime(s.startTime) }}</td>
          <td class="small">{{ formatTime(s.lastActivity) }}</td>
          <td class="num">{{ s.messageCount ?? 0 }}</td>
          <td class="actions">
            <button
              class="cancel-btn"
              data-testid="cancel-inflight"
              @click="cancelInflight(s.sessionId)"
              title="Abort in-flight LLM call (refunds tokens, releases tool approvals). Transport stays open."
            >
              Cancel in-flight
            </button>
          </td>
        </tr>
      </tbody>
    </table>

    <p v-if="lastAction" class="action-result">
      Last action: <span class="mono">{{ shortUuid(lastAction.uuid) }}</span>
      → <strong>{{ lastAction.status }}</strong>
    </p>
  </div>
</template>

<style scoped>
.sessions-view {
  height: 100%;
  overflow: auto;
  padding: 1.25rem 1.5rem;
}

.sessions-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1rem;
  margin-bottom: 1rem;
}

.sessions-title {
  font-size: 1.125rem;
  font-weight: 600;
  margin: 0;
  color: var(--text-primary);
}

.subtitle {
  font-size: 0.8125rem;
  color: var(--text-secondary);
  margin: 0.125rem 0 0;
  max-width: 60ch;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.count-chip {
  font-size: 0.75rem;
  padding: 0.125rem 0.625rem;
  background: var(--accent-bg);
  color: var(--accent-color);
  border-radius: 9999px;
  font-weight: 600;
}

.refresh-btn {
  font-size: 0.8125rem;
  padding: 0.375rem 0.75rem;
  border: 1px solid var(--border-color);
  background: var(--bg-surface);
  color: var(--text-primary);
  border-radius: 6px;
  cursor: pointer;
}
.refresh-btn:disabled { opacity: 0.6; cursor: progress; }

.session-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.8125rem;
}
.session-table th, .session-table td {
  padding: 0.5rem 0.625rem;
  border-bottom: 1px solid var(--border-color);
  text-align: left;
}
.session-table th {
  font-weight: 600;
  color: var(--text-secondary);
  background: var(--bg-surface);
  position: sticky;
  top: 0;
}
.session-table td.num, .session-table th.num { text-align: right; }
.session-table td.actions, .session-table th.actions { text-align: right; }

.mono { font-family: ui-monospace, "SF Mono", Consolas, monospace; }
.small { font-size: 0.75rem; color: var(--text-secondary); }

.cancel-btn {
  font-size: 0.75rem;
  padding: 0.25rem 0.625rem;
  background: #b91c1c;
  color: #fff;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-weight: 500;
}
.cancel-btn:hover { background: #991b1b; }

.empty, .error {
  font-size: 0.875rem;
  color: var(--text-secondary);
  padding: 1rem 0;
}
.error { color: #b91c1c; }

.action-result {
  font-size: 0.75rem;
  margin-top: 0.75rem;
  color: var(--text-secondary);
}
</style>
