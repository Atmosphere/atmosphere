<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { livePlan, type PlanStep } from '../lib/workspaceStore'

interface OwnerInfo {
  owner: string
  plan: boolean
  filesystem: boolean
}

interface FileEntry {
  path: string
  size: number
  directory: boolean
}

interface PlanSnapshot {
  goal?: string
  steps: PlanStep[]
}

const props = defineProps<{ active: boolean }>()
const active = computed(() => props.active)

const owners = ref<OwnerInfo[]>([])
const selectedOwner = ref('')
const knownSessions = ref<string[]>([])
const sessionId = ref('')
const error = ref<string | null>(null)
const loading = ref(false)

// True once the operator manually picked/typed a session — from then on the
// live plan's conversation id no longer overwrites their choice (manual
// entry stays the fallback for token-gated or cross-conversation browsing).
const manualSession = ref(false)

// Plan snapshot (persisted state from the admin endpoint, per owner × session)
const snapshot = ref<PlanSnapshot | null>(null)
const snapshotStatus = ref<string | null>(null)

// File browser: directory listing + selected file detail
const dirPath = ref('')
const entries = ref<FileEntry[]>([])
const filesStatus = ref<string | null>(null)
const selectedFile = ref<string | null>(null)
const fileContent = ref<string | null>(null)
const fileStatus = ref<string | null>(null)

const selectedOwnerInfo = computed(() =>
  owners.value.find((o) => o.owner === selectedOwner.value) ?? null)

const breadcrumbs = computed(() => {
  if (!dirPath.value) return []
  const parts = dirPath.value.split('/')
  return parts.map((name, i) => ({ name, path: parts.slice(0, i + 1).join('/') }))
})

function statusMarker(step: PlanStep): string {
  switch (step.status) {
    case 'completed': return '✓'
    case 'in_progress': return '◐'
    case 'abandoned': return '✕'
    default: return '○'
  }
}

function stepLabel(step: PlanStep): string {
  return step.status === 'in_progress' && step.activeForm ? step.activeForm : step.content
}

function formatSize(size: number): string {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / (1024 * 1024)).toFixed(1)} MB`
}

function baseName(path: string): string {
  const idx = path.lastIndexOf('/')
  return idx >= 0 ? path.slice(idx + 1) : path
}

async function loadOwners() {
  try {
    const res = await fetch('/api/admin/workspace/owners', {
      headers: { Accept: 'application/json' },
    })
    if (!res.ok) {
      error.value = `Workspace endpoint returned ${res.status}`
      return
    }
    owners.value = await res.json()
    if (!selectedOwner.value && owners.value.length > 0) {
      selectedOwner.value = owners.value[0].owner
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

async function loadSessions() {
  // Best-effort: active streaming sessions for the selected owner give the
  // operator session ids to pick from. Owners without an agent controller
  // entry (e.g. plain @AiEndpoint paths) simply yield none — the manual
  // session-id input still works.
  knownSessions.value = []
  if (!selectedOwner.value) return
  try {
    const res = await fetch(
      `/api/admin/agents/${encodeURIComponent(selectedOwner.value)}/sessions`,
      { headers: { Accept: 'application/json' } },
    )
    if (res.ok) {
      const list: Array<{ sessionId?: string }> = await res.json()
      knownSessions.value = list
        .map((s) => s.sessionId)
        .filter((s): s is string => typeof s === 'string' && s.length > 0)
      if (!sessionId.value && knownSessions.value.length > 0) {
        sessionId.value = knownSessions.value[0]
      }
    }
  } catch {
    // No sessions listing for this owner — manual entry only.
  }
}

async function loadSnapshot() {
  snapshot.value = null
  snapshotStatus.value = null
  if (!selectedOwner.value || !sessionId.value) return
  try {
    const res = await fetch(
      `/api/admin/agents/${encodeURIComponent(selectedOwner.value)}/plan`
        + `?sessionId=${encodeURIComponent(sessionId.value)}`,
      { headers: { Accept: 'application/json' } },
    )
    if (res.status === 404) {
      snapshotStatus.value = 'No plan recorded for this session yet.'
      return
    }
    if (!res.ok) {
      snapshotStatus.value = `Plan endpoint returned ${res.status}`
      return
    }
    const data = await res.json()
    snapshot.value = {
      goal: typeof data.goal === 'string' ? data.goal : undefined,
      steps: Array.isArray(data.steps) ? data.steps : [],
    }
  } catch (e) {
    snapshotStatus.value = e instanceof Error ? e.message : String(e)
  }
}

async function loadFiles(path = '') {
  entries.value = []
  filesStatus.value = null
  selectedFile.value = null
  fileContent.value = null
  fileStatus.value = null
  dirPath.value = path
  if (!selectedOwner.value || !sessionId.value) return
  try {
    let url = `/api/admin/agents/${encodeURIComponent(selectedOwner.value)}/files`
      + `?sessionId=${encodeURIComponent(sessionId.value)}`
    if (path) url += `&path=${encodeURIComponent(path)}`
    const res = await fetch(url, { headers: { Accept: 'application/json' } })
    if (res.status === 404) {
      filesStatus.value = 'No files in this session’s workspace yet.'
      return
    }
    if (!res.ok) {
      filesStatus.value = `Files endpoint returned ${res.status}`
      return
    }
    const data = await res.json()
    entries.value = Array.isArray(data.entries) ? data.entries : []
    if (entries.value.length === 0) {
      filesStatus.value = 'Empty directory.'
    }
  } catch (e) {
    filesStatus.value = e instanceof Error ? e.message : String(e)
  }
}

async function openEntry(entry: FileEntry) {
  if (entry.directory) {
    await loadFiles(entry.path)
    return
  }
  selectedFile.value = entry.path
  fileContent.value = null
  fileStatus.value = null
  try {
    const res = await fetch(
      `/api/admin/agents/${encodeURIComponent(selectedOwner.value)}/files/content`
        + `?sessionId=${encodeURIComponent(sessionId.value)}`
        + `&path=${encodeURIComponent(entry.path)}`,
      { headers: { Accept: 'application/json' } },
    )
    if (!res.ok) {
      fileStatus.value = `Content endpoint returned ${res.status}`
      return
    }
    const data = await res.json()
    fileContent.value = typeof data.content === 'string' ? data.content : ''
  } catch (e) {
    fileStatus.value = e instanceof Error ? e.message : String(e)
  }
}

async function loadWorkspace() {
  await Promise.all([loadSnapshot(), loadFiles('')])
}

/**
 * One-click correlation: live `plan-update` events carry the exact
 * conversation id (and owner) the plan/file stores key on, so the stored
 * view can be pre-filled and loaded without manual session entry. Adopts
 * only while the operator has not manually picked a session. Returns true
 * when the selection changed and the stored view needs a reload.
 */
function adoptLiveConversation(): boolean {
  const lp = livePlan.value
  if (!lp?.conversationId || manualSession.value) return false
  let changed = false
  if (lp.agentId && lp.agentId !== selectedOwner.value
      && owners.value.some((o) => o.owner === lp.agentId)) {
    selectedOwner.value = lp.agentId
    changed = true
  }
  if (sessionId.value !== lp.conversationId) {
    sessionId.value = lp.conversationId
    changed = true
  }
  return changed
}

async function refresh() {
  loading.value = true
  error.value = null
  try {
    await loadOwners()
    // Adopt the live conversation before the sessions fallback so the chat's
    // own conversation wins over "first active session" auto-selection.
    adoptLiveConversation()
    await loadSessions()
    await loadWorkspace()
  } finally {
    loading.value = false
  }
}

// Explicit @change handler (not a watcher) so the programmatic auto-select in
// loadOwners cannot race refresh()'s own loadSessions/loadWorkspace sequence.
async function onOwnerChange() {
  sessionId.value = ''
  manualSession.value = false
  await loadSessions()
  await loadWorkspace()
}

// The operator explicitly chose a session — stop auto-adopting the live one.
async function onManualSessionPick() {
  manualSession.value = true
  await loadWorkspace()
}

// Follow the live plan's conversation while the tab is open: a new
// conversation id on the stream re-targets the stored view automatically
// (unless the operator manually picked a session).
watch(() => livePlan.value?.conversationId, async () => {
  if (active.value && adoptLiveConversation()) {
    await loadWorkspace()
  }
})

watch(active, (a) => { if (a) refresh() })
onMounted(() => { if (active.value) refresh() })
</script>

<template>
  <div class="workspace-view" data-testid="workspace-view">
    <div class="workspace-toolbar">
      <div>
        <h2 class="workspace-title">Agent workspace</h2>
        <p class="subtitle">
          The plan the agent maintains (write_todos / native plan bridges) and
          the bounded per-conversation file store behind its file tools.
        </p>
      </div>
      <div class="toolbar-actions">
        <select
          v-if="owners.length > 0"
          v-model="selectedOwner"
          class="owner-select"
          data-testid="workspace-owner"
          @change="onOwnerChange"
        >
          <option v-for="o in owners" :key="o.owner" :value="o.owner">{{ o.owner }}</option>
        </select>
        <button class="refresh-btn" @click="refresh" :disabled="loading">
          {{ loading ? 'Refreshing…' : 'Refresh' }}
        </button>
      </div>
    </div>

    <p v-if="error" class="error">{{ error }}</p>

    <!-- Live plan: streamed over the chat connection (plan-update events) -->
    <section class="panel" data-testid="workspace-plan">
      <div class="panel-header">
        <h3 class="panel-title">Live plan</h3>
        <span v-if="livePlan" class="count-chip">
          {{ livePlan.steps.filter(s => s.status === 'completed').length }}/{{ livePlan.steps.length }} done
        </span>
      </div>
      <p v-if="!livePlan" class="empty">
        No plan yet in this chat — it appears here the moment the agent calls
        its plan tool.
      </p>
      <template v-else>
        <p v-if="livePlan.goal" class="plan-goal">{{ livePlan.goal }}</p>
        <ul class="plan-list">
          <li
            v-for="(step, i) in livePlan.steps"
            :key="i"
            :class="['plan-step', step.status]"
            data-testid="plan-step"
          >
            <span class="step-marker">{{ statusMarker(step) }}</span>
            <span class="step-label">{{ stepLabel(step) }}</span>
          </li>
        </ul>
      </template>
    </section>

    <!-- Persisted workspace: admin snapshot per owner × session -->
    <section class="panel" data-testid="workspace-browser">
      <div class="panel-header">
        <h3 class="panel-title">Stored workspace</h3>
        <div class="session-picker">
          <select
            v-if="knownSessions.length > 0"
            v-model="sessionId"
            class="owner-select"
            data-testid="workspace-session-select"
            @change="onManualSessionPick"
          >
            <option v-for="s in knownSessions" :key="s" :value="s">{{ s }}</option>
          </select>
          <input
            v-model.trim="sessionId"
            class="session-input"
            placeholder="session id"
            data-testid="workspace-session"
            @input="manualSession = true"
            @keyup.enter="loadWorkspace"
          />
          <button class="refresh-btn" :disabled="!sessionId || !selectedOwner" @click="loadWorkspace">
            Load
          </button>
        </div>
      </div>

      <p v-if="!sessionId" class="empty">
        Pick or paste a session id to inspect its persisted plan and files.
      </p>

      <template v-else>
        <div class="stored-grid">
          <!-- Plan snapshot -->
          <div v-if="selectedOwnerInfo?.plan !== false" class="stored-plan">
            <h4 class="stored-heading">Plan</h4>
            <p v-if="snapshotStatus" class="empty">{{ snapshotStatus }}</p>
            <template v-else-if="snapshot">
              <p v-if="snapshot.goal" class="plan-goal">{{ snapshot.goal }}</p>
              <ul class="plan-list">
                <li
                  v-for="(step, i) in snapshot.steps"
                  :key="i"
                  :class="['plan-step', step.status]"
                >
                  <span class="step-marker">{{ statusMarker(step) }}</span>
                  <span class="step-label">{{ stepLabel(step) }}</span>
                </li>
              </ul>
            </template>
          </div>

          <!-- File browser: list → detail -->
          <div v-if="selectedOwnerInfo?.filesystem !== false" class="stored-files">
            <h4 class="stored-heading">Files</h4>
            <nav v-if="dirPath" class="breadcrumbs">
              <button class="crumb" @click="loadFiles('')">root</button>
              <template v-for="crumb in breadcrumbs" :key="crumb.path">
                <span class="crumb-sep">/</span>
                <button class="crumb" @click="loadFiles(crumb.path)">{{ crumb.name }}</button>
              </template>
            </nav>
            <p v-if="filesStatus" class="empty">{{ filesStatus }}</p>
            <table v-else-if="entries.length > 0" class="file-table">
              <tbody>
                <tr
                  v-for="entry in entries"
                  :key="entry.path"
                  :class="{ selected: entry.path === selectedFile }"
                  data-testid="workspace-file"
                  @click="openEntry(entry)"
                >
                  <td class="file-name mono">
                    {{ entry.directory ? '📁' : '📄' }} {{ baseName(entry.path) }}
                  </td>
                  <td class="file-size">{{ entry.directory ? '—' : formatSize(entry.size) }}</td>
                </tr>
              </tbody>
            </table>
            <div v-if="selectedFile" class="file-detail" data-testid="workspace-file-detail">
              <div class="file-detail-header mono">{{ selectedFile }}</div>
              <p v-if="fileStatus" class="empty">{{ fileStatus }}</p>
              <pre v-else-if="fileContent !== null" class="file-content">{{ fileContent }}</pre>
            </div>
          </div>
        </div>
      </template>
    </section>
  </div>
</template>

<style scoped>
.workspace-view {
  height: 100%;
  overflow: auto;
  padding: 1.25rem 1.5rem;
}

.workspace-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1rem;
  margin-bottom: 1rem;
}

.workspace-title {
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

.owner-select, .session-input {
  font-size: 0.8125rem;
  padding: 0.375rem 0.5rem;
  border: 1px solid var(--border-color);
  background: var(--bg-surface);
  color: var(--text-primary);
  border-radius: 6px;
}

.session-input {
  width: 20rem;
  max-width: 40vw;
  font-family: ui-monospace, "SF Mono", Consolas, monospace;
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
.refresh-btn:disabled { opacity: 0.6; }

.panel {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 0.875rem 1rem;
  margin-bottom: 1rem;
  background: var(--bg-surface);
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 0.5rem;
  flex-wrap: wrap;
}

.panel-title {
  font-size: 0.9375rem;
  font-weight: 600;
  margin: 0;
  color: var(--text-primary);
}

.session-picker {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.count-chip {
  font-size: 0.75rem;
  padding: 0.125rem 0.625rem;
  background: var(--accent-bg);
  color: var(--accent-color);
  border-radius: 9999px;
  font-weight: 600;
}

.plan-goal {
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0 0 0.375rem;
}

.plan-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.plan-step {
  display: flex;
  align-items: baseline;
  gap: 0.5rem;
  font-size: 0.8125rem;
  padding: 0.25rem 0;
  color: var(--text-primary);
}

.plan-step.completed .step-label {
  color: var(--text-secondary);
  text-decoration: line-through;
}
.plan-step.abandoned .step-label {
  color: var(--text-secondary);
  text-decoration: line-through;
  opacity: 0.7;
}
.plan-step.in_progress .step-label { font-weight: 600; }
.plan-step.in_progress .step-marker { color: var(--accent-color); }
.plan-step.completed .step-marker { color: var(--accent-color); }

.step-marker {
  width: 1rem;
  text-align: center;
  flex-shrink: 0;
}

.stored-grid {
  display: grid;
  grid-template-columns: minmax(16rem, 1fr) 2fr;
  gap: 1rem;
}
@media (max-width: 900px) {
  .stored-grid { grid-template-columns: 1fr; }
}

.stored-heading {
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--text-secondary);
  margin: 0 0 0.375rem;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.breadcrumbs {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  margin-bottom: 0.375rem;
  flex-wrap: wrap;
}

.crumb {
  font-size: 0.75rem;
  background: none;
  border: none;
  color: var(--accent-color);
  cursor: pointer;
  padding: 0.125rem 0.25rem;
  border-radius: 4px;
}
.crumb:hover { background: var(--bg-hover); }
.crumb-sep { color: var(--text-secondary); font-size: 0.75rem; }

.file-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.8125rem;
}
.file-table td {
  padding: 0.375rem 0.5rem;
  border-bottom: 1px solid var(--border-color);
}
.file-table tr { cursor: pointer; }
.file-table tr:hover { background: var(--bg-hover); }
.file-table tr.selected { background: var(--accent-bg); }
.file-name { color: var(--text-primary); }
.file-size {
  text-align: right;
  color: var(--text-secondary);
  font-size: 0.75rem;
  white-space: nowrap;
}

.file-detail {
  margin-top: 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  overflow: hidden;
}

.file-detail-header {
  font-size: 0.75rem;
  padding: 0.375rem 0.625rem;
  background: var(--bg-hover);
  color: var(--text-secondary);
  border-bottom: 1px solid var(--border-color);
}

.file-content {
  margin: 0;
  padding: 0.625rem;
  font-size: 0.75rem;
  font-family: ui-monospace, "SF Mono", Consolas, monospace;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 24rem;
  overflow: auto;
}

.mono { font-family: ui-monospace, "SF Mono", Consolas, monospace; }

.empty, .error {
  font-size: 0.8125rem;
  color: var(--text-secondary);
  padding: 0.25rem 0;
  margin: 0;
}
.error { color: #b91c1c; }
</style>
