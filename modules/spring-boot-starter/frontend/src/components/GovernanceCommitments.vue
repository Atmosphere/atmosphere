<script setup lang="ts">
import { computed } from 'vue'
import { usePollingResource, type CommitmentRecord } from '../composables/useGovernance'

const props = defineProps<{ active: boolean }>()
const active = computed(() => props.active)

const { data, error, loading, refresh } = usePollingResource<CommitmentRecord[]>(
  '/api/admin/governance/commitments?limit=100',
  [],
  3000,
  active
)

const signedCount = computed(() => data.value.filter((r) => r.signed).length)

function formatTime(iso: string) {
  try {
    return new Date(iso).toLocaleTimeString(undefined, {
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    })
  } catch {
    return iso
  }
}

function outcomeClass(outcome: string) {
  const map: Record<string, string> = {
    started: 'badge-neutral',
    completed: 'badge-ok',
    failed: 'badge-err',
    deny: 'badge-err',
    admit: 'badge-ok',
    transform: 'badge-warn',
    error: 'badge-err',
  }
  return 'badge ' + (map[outcome] ?? 'badge-neutral')
}

function truncSig(sig: string) {
  if (!sig) return ''
  return sig.length > 20 ? sig.slice(0, 10) + '…' + sig.slice(-6) : sig
}
</script>

<template>
  <div class="gov-view" data-testid="governance-commitments">
    <div class="gov-toolbar">
      <div>
        <h2 class="gov-title">Commitment records</h2>
        <p class="subtitle">
          Ed25519-signed trace-of-dispatch from the coordination journal
          (Phase B1, experimental)
        </p>
      </div>
      <div class="toolbar-actions">
        <span class="count-chip signed">{{ signedCount }} signed</span>
        <span class="count-chip total">{{ data.length }} total</span>
        <button class="refresh-btn" @click="refresh" :disabled="loading">
          {{ loading ? 'Refreshing…' : 'Refresh' }}
        </button>
      </div>
    </div>
    <p v-if="error" class="error">{{ error }}</p>
    <p v-else-if="data.length === 0" class="empty">
      No commitment records emitted yet. Install a
      <code>CommitmentSigner</code> on your <code>JournalingAgentFleet</code>
      to enable signed dispatch records (<code>Ed25519CommitmentSigner.generate()</code>
      is a good starting point — JDK 21 built-in EdDSA, zero external crypto dep).
    </p>
    <ul v-else class="commit-list">
      <li v-for="rec in data" :key="rec.id" class="commit">
        <div class="commit-head">
          <span :class="outcomeClass(rec.outcome)">{{ rec.outcome }}</span>
          <span class="issuer mono">{{ rec.issuer }}</span>
          <span class="arrow">→</span>
          <span class="subject mono">{{ rec.subject }}</span>
          <span class="timestamp small">{{ formatTime(rec.eventTimestamp) }}</span>
        </div>
        <div class="commit-meta">
          <span v-if="rec.principal" class="meta-item">principal <code>{{ rec.principal }}</code></span>
          <span v-if="rec.scope" class="meta-item">scope <code>{{ rec.scope }}</code></span>
          <span class="meta-item">
            <span :class="rec.signed ? 'sig-ok' : 'sig-none'">
              {{ rec.signed ? '✓ signed' : 'unsigned' }}
            </span>
            <template v-if="rec.signed">
              {{ rec.proof.scheme }} <code>{{ rec.proof.keyId }}</code>
            </template>
          </span>
        </div>
        <details v-if="rec.signed">
          <summary>Signature</summary>
          <pre class="sig">{{ truncSig(rec.proof.signature) }}</pre>
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
  max-width: 72rem;
  margin: 0 auto;
}
.gov-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 1rem;
  gap: 1rem;
  flex-wrap: wrap;
}
.gov-title {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}
.subtitle {
  font-size: 0.75rem;
  color: var(--text-tertiary);
  margin: 0.125rem 0 0 0;
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
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.count-chip.signed { color: #2e7d32; border-color: #2e7d3233; }
.count-chip.total { color: var(--text-tertiary); }
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
.empty code {
  background: var(--code-bg);
  padding: 0.125rem 0.375rem;
  border-radius: 3px;
  font-family: ui-monospace, Menlo, monospace;
  font-size: 0.85em;
}
.commit-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.commit {
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 0.625rem 0.875rem;
}
.commit-head {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
  font-size: 0.875rem;
}
.badge {
  font-size: 0.6875rem;
  font-weight: 700;
  text-transform: uppercase;
  padding: 0.125rem 0.5rem;
  border-radius: 4px;
  letter-spacing: 0.05em;
}
.badge-ok { background: #e8f5e9; color: #2e7d32; }
.badge-err { background: #ffebee; color: #c62828; }
.badge-warn { background: #fff3e0; color: #ef6c00; }
.badge-neutral { background: var(--bg-tertiary); color: var(--text-secondary); }
@media (prefers-color-scheme: dark) {
  .badge-ok { background: rgba(46, 125, 50, 0.18); }
  .badge-err { background: rgba(198, 40, 40, 0.18); }
  .badge-warn { background: rgba(239, 108, 0, 0.2); }
}
.mono { font-family: ui-monospace, Menlo, monospace; font-size: 0.8125rem; }
.issuer { color: var(--text-primary); }
.subject { color: var(--accent-color); }
.arrow { color: var(--text-tertiary); }
.timestamp { color: var(--text-tertiary); margin-left: auto; }
.small { font-size: 0.75rem; }
.commit-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem 1rem;
  font-size: 0.75rem;
  color: var(--text-secondary);
  margin-top: 0.25rem;
}
.commit-meta code {
  background: var(--code-bg);
  padding: 0.0625rem 0.3rem;
  border-radius: 3px;
  font-family: ui-monospace, Menlo, monospace;
  font-size: 0.9em;
}
.sig-ok { color: #2e7d32; font-weight: 600; }
.sig-none { color: var(--text-tertiary); }
details {
  margin-top: 0.25rem;
  font-size: 0.75rem;
}
details summary {
  cursor: pointer;
  color: var(--text-tertiary);
  user-select: none;
}
.sig {
  margin: 0.375rem 0 0 0;
  padding: 0.5rem;
  background: var(--code-block-bg);
  border-radius: 4px;
  font-family: ui-monospace, Menlo, monospace;
  font-size: 0.75rem;
  color: var(--text-secondary);
  overflow-x: auto;
  word-break: break-all;
}
</style>
