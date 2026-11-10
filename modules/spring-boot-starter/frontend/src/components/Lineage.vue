<script setup lang="ts">
/**
 * Lineage tab: one row per AI turn the server recorded, read from
 * `GET /api/admin/ai/lineage` (most recent first). Each row names the agent
 * configuration that ran the turn (the `ai.agent.*` fingerprint), the tools
 * it called, its tokens, cost and how it ended.
 *
 * The route is a recorded-content read, denied to anonymous callers by
 * default, so the poll sends the console's auth header (usePollingResource).
 * The prompt and tool-result text are present only when the server records
 * content (`atmosphere.ai.lineage.capture-content=true`); otherwise the
 * entries carry them as empty strings and the tab says so.
 *
 * The tab only appears when `/api/admin/ai/lineage/status` reports an
 * in-memory recorder, the one recorder this route reads back.
 */
import { computed } from 'vue'
import { usePollingResource } from '../composables/useGovernance'
import type { LineageEntry, LineageStatus } from '../lib/lineage'
import { fingerprintOf, shortHash } from '../lib/lineage'

const props = defineProps<{ active: boolean; status: LineageStatus | null }>()
const active = computed(() => props.active)

const { data, error, loading, refresh } = usePollingResource<LineageEntry[]>(
  '/api/admin/ai/lineage?limit=200',
  [],
  3000,
  active
)

const captureContent = computed(() => props.status?.captureContent === true)

function formatTime(iso: string) {
  try {
    return new Date(iso).toLocaleTimeString(undefined, {
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    })
  } catch {
    return iso
  }
}

function reasonClass(reason: string) {
  return `badge badge-${reason.toLowerCase()}`
}
</script>

<template>
  <div class="lineage-view" data-testid="lineage">
    <div class="toolbar">
      <h2 class="title">Recent turns</h2>
      <div class="toolbar-actions">
        <span v-if="status && status.recorder === 'in-memory'" class="chip" data-testid="lineage-status">
          last {{ status.capacity ?? '?' }} turns kept
          · {{ captureContent ? 'with prompt and tool text' : 'no prompt or tool text' }}
        </span>
        <button class="refresh-btn" @click="refresh" :disabled="loading">
          {{ loading ? 'Refreshing…' : 'Refresh' }}
        </button>
      </div>
    </div>
    <p v-if="error" class="error">{{ error }}</p>
    <p v-else-if="data.length === 0" class="empty">
      No turns recorded yet. Each AI request appears here when it ends.
    </p>
    <ul v-else class="entry-list">
      <li v-for="entry in data" :key="entry.requestId" class="entry" data-testid="lineage-entry">
        <div class="entry-head">
          <span :class="reasonClass(entry.terminalReason)">{{ entry.terminalReason }}</span>
          <span class="timestamp mono">{{ formatTime(entry.timestamp) }}</span>
          <span class="agent mono small">{{ entry.agentId || '—' }}</span>
          <span v-if="entry.userId" class="small muted">user {{ entry.userId }}</span>
          <span v-if="entry.tokens" class="small muted">{{ entry.tokens.total }} tokens</span>
          <span v-if="entry.costUsd" class="small muted">${{ entry.costUsd }}</span>
        </div>
        <div v-if="fingerprintOf(entry)" class="fingerprint small" data-testid="lineage-fingerprint">
          <span v-if="fingerprintOf(entry)!.runtime">runtime <b class="mono">{{ fingerprintOf(entry)!.runtime }}</b></span>
          <span v-if="fingerprintOf(entry)!.model">model <b class="mono">{{ fingerprintOf(entry)!.model }}</b></span>
          <span v-if="fingerprintOf(entry)!.promptSha" :title="fingerprintOf(entry)!.promptSha">
            prompt <b class="mono">{{ shortHash(fingerprintOf(entry)!.promptSha) }}</b></span>
          <span v-if="fingerprintOf(entry)!.toolsSha" :title="fingerprintOf(entry)!.toolsSha">
            tools <b class="mono">{{ shortHash(fingerprintOf(entry)!.toolsSha) }}</b></span>
        </div>
        <p v-if="captureContent && entry.userPrompt" class="prompt">{{ entry.userPrompt }}</p>
        <div v-if="entry.toolCalls.length > 0" class="tools small">
          <span v-for="(call, i) in entry.toolCalls" :key="i" class="tool mono" :title="call.resultSummary || undefined">
            {{ call.name }}
          </span>
        </div>
        <details v-if="Object.keys(entry.attributes).length > 0">
          <summary>Attributes</summary>
          <pre class="ctx">{{ JSON.stringify(entry.attributes, null, 2) }}</pre>
        </details>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.lineage-view {
  padding: 1.5rem;
  height: 100%;
  overflow-y: auto;
  max-width: 64rem;
  margin: 0 auto;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
  flex-wrap: wrap;
  gap: 0.5rem;
}
.title {
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
.chip {
  font-size: 0.75rem;
  padding: 0.125rem 0.5rem;
  border-radius: 9999px;
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  color: var(--text-secondary);
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
.entry-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.entry {
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 0.625rem 0.875rem;
}
.entry-head {
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
  background: #eceff1;
  color: #455a64;
}
.badge-ok { background: #e8f5e9; color: #2e7d32; }
.badge-denied { background: #ffebee; color: #c62828; }
.badge-error { background: #fff3e0; color: #ef6c00; }
.badge-cancelled { background: #eceff1; color: #546e7a; }
@media (prefers-color-scheme: dark) {
  .badge { background: rgba(84, 110, 122, 0.2); color: #b0bec5; }
  .badge-ok { background: rgba(46, 125, 50, 0.18); color: #81c784; }
  .badge-denied { background: rgba(198, 40, 40, 0.18); color: #ef9a9a; }
  .badge-error { background: rgba(239, 108, 0, 0.2); color: #ffb74d; }
}
.timestamp { color: var(--text-tertiary); font-size: 0.75rem; }
.agent { color: var(--text-primary); flex: 1 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; }
.muted { color: var(--text-tertiary); }
.mono { font-family: ui-monospace, Menlo, monospace; }
.small { font-size: 0.8125rem; }
.fingerprint {
  display: flex;
  gap: 0.875rem;
  flex-wrap: wrap;
  margin-top: 0.25rem;
  color: var(--text-secondary);
}
.fingerprint b { font-weight: 500; color: var(--text-primary); }
.prompt {
  margin: 0.375rem 0 0 0;
  font-size: 0.8125rem;
  color: var(--text-secondary);
  line-height: 1.4;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.tools {
  display: flex;
  gap: 0.375rem;
  flex-wrap: wrap;
  margin-top: 0.375rem;
}
.tool {
  font-size: 0.75rem;
  padding: 0.0625rem 0.375rem;
  border-radius: 4px;
  border: 1px solid var(--border-color);
  color: var(--text-secondary);
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
