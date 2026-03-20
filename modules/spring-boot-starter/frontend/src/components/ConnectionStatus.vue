<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  state: string
}>()

const statusLabel = computed(() => {
  switch (props.state) {
    case 'connected': return 'Connected'
    case 'connecting': return 'Connecting'
    case 'reconnecting': return 'Reconnecting'
    case 'error': return 'Error'
    default: return 'Disconnected'
  }
})

const statusClass = computed(() => `status--${props.state}`)
</script>

<template>
  <div class="connection-status" :class="statusClass">
    <span class="status-dot"></span>
    <span class="status-label">{{ statusLabel }}</span>
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
