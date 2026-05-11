<script setup lang="ts">
import { computed } from 'vue'
import type { ConnectionStatusSnapshot } from 'atmosphere.js'

/**
 * Admin-console connection-state pill. Themed to match the console
 * design tokens. When the full ConnectionStatusSnapshot is passed via
 * `status`, the pill also surfaces the active transport and a
 * "(fallback)" marker when the primary transport failed.
 */
const props = defineProps<{
  /** Legacy string state (kept for callers that haven't migrated to snapshot). */
  state: string
  /** Full resilience snapshot — when provided, drives the richer display. */
  status?: ConnectionStatusSnapshot
}>()

// Map snapshot.phase onto the legacy state vocabulary so .status--<state>
// styles still kick in without duplicating CSS rules.
const effectiveState = computed(() => {
  if (!props.status) return props.state
  switch (props.status.phase) {
    case 'open': return 'connected'
    case 'connecting': return 'connecting'
    case 'reconnecting': return 'reconnecting'
    case 'lost': return 'error'
    case 'closed': return 'disconnected'
    case 'idle': return 'disconnected'
    default: return props.state
  }
})

const statusLabel = computed(() => {
  const base = (() => {
    switch (effectiveState.value) {
      case 'connected': return 'Connected'
      case 'connecting': return 'Connecting'
      case 'reconnecting': return 'Reconnecting'
      case 'error':
        return props.status?.phase === 'lost' ? 'Connection lost' : 'Error'
      default: return 'Disconnected'
    }
  })()
  if (!props.status) return base
  const transport = props.status.transport
  const fallbackTag = props.status.viaFallback && props.status.phase === 'open' ? ' · fallback' : ''
  // Only annotate transport for non-idle phases so the pill stays compact at rest.
  const transportTag = props.status.phase === 'idle' ? '' : ` · ${transport}`
  return `${base}${transportTag}${fallbackTag}`
})

const statusClass = computed(() => `status--${effectiveState.value}`)
const tooltip = computed(() => {
  if (!props.status?.lastError) return undefined
  return `Last error: ${props.status.lastError.message}`
})
</script>

<template>
  <div class="connection-status" :class="statusClass"
       :title="tooltip"
       :data-phase="status?.phase ?? state"
       :data-transport="status?.transport"
       :data-via-fallback="status?.viaFallback ?? false"
       data-testid="atmosphere-connection-status">
    <span class="status-dot"></span>
    <span class="status-label" data-testid="status-label">{{ statusLabel }}</span>
  </div>
</template>

<style scoped>
.connection-status {
  display: flex;
  align-items: center;
  gap: 0.375rem;
}

.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--text-tertiary);
  transition: background 0.3s;
}

.status-label {
  font-size: 0.75rem;
  color: var(--text-secondary);
  font-weight: 500;
}

.status--connected .status-dot { background: #22c55e; }
.status--connecting .status-dot { background: #f59e0b; animation: pulse 1.5s infinite; }
.status--reconnecting .status-dot { background: #f59e0b; animation: pulse 1.5s infinite; }
.status--error .status-dot { background: #ef4444; }
.status--disconnected .status-dot { background: #94a3b8; }

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}
</style>
