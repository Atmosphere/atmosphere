<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'

/**
 * Observability tab — actuator health plus the atmosphere.* Micrometer
 * meters, polled while the tab is active. Gated by the hasActuator
 * runtime-truth flag in /api/console/info (Boot 4 health plane).
 */

interface Meter { name: string; value: number | string }

const props = defineProps<{ active: boolean }>()

const health = ref<string>('unknown')
const meters = ref<Meter[]>([])
const error = ref<string | null>(null)
let timer: ReturnType<typeof setInterval> | null = null

async function load() {
  try {
    const healthRes = await fetch('/actuator/health', { headers: { Accept: 'application/json' } })
    if (healthRes.ok) {
      const data = await healthRes.json()
      health.value = typeof data.status === 'string' ? data.status : 'unknown'
    }
    const listRes = await fetch('/actuator/metrics', { headers: { Accept: 'application/json' } })
    if (!listRes.ok) {
      error.value = `HTTP ${listRes.status}`
      return
    }
    const names = ((await listRes.json()).names as string[] | undefined ?? [])
      .filter(n => n.startsWith('atmosphere.'))
      .slice(0, 12)
    const loaded: Meter[] = []
    for (const name of names) {
      const res = await fetch(`/actuator/metrics/${encodeURIComponent(name)}`,
        { headers: { Accept: 'application/json' } })
      if (!res.ok) continue
      const data = await res.json()
      const measurement = Array.isArray(data.measurements) ? data.measurements[0] : undefined
      loaded.push({ name, value: typeof measurement?.value === 'number' ? measurement.value : '—' })
    }
    meters.value = loaded
    error.value = null
  } catch (e) {
    error.value = String(e)
  }
}

watch(() => props.active, (active) => {
  if (active) {
    load()
    timer = setInterval(load, 5000)
  } else if (timer) {
    clearInterval(timer)
    timer = null
  }
}, { immediate: true })

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="obs-view" data-testid="observability-view">
    <div class="obs-health" data-testid="health-status">
      <span class="obs-label">Health</span>
      <span :class="['obs-status', health === 'UP' ? 'up' : 'down']">{{ health }}</span>
    </div>
    <p v-if="error" class="obs-error">Metrics unavailable: {{ error }}</p>
    <table v-else-if="meters.length > 0" class="obs-table">
      <thead><tr><th>Atmosphere meter</th><th>Value</th></tr></thead>
      <tbody>
        <tr v-for="m in meters" :key="m.name">
          <td class="obs-meter">{{ m.name }}</td>
          <td class="obs-value">{{ m.value }}</td>
        </tr>
      </tbody>
    </table>
    <p v-else class="obs-empty">No atmosphere.* meters published yet.</p>
  </div>
</template>

<style scoped>
.obs-view {
  height: 100%;
  overflow: auto;
  padding: 1.5rem;
}

.obs-health {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  margin-bottom: 1rem;
}

.obs-label {
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--text-secondary);
}

.obs-status {
  font-size: 0.75rem;
  font-weight: 700;
  padding: 0.125rem 0.625rem;
  border-radius: 9999px;
}

.obs-status.up { color: #10b981; background: rgba(16, 185, 129, 0.12); }
.obs-status.down { color: #ef4444; background: rgba(239, 68, 68, 0.12); }

.obs-error, .obs-empty {
  color: var(--text-tertiary);
  font-size: 0.875rem;
}

.obs-table {
  border-collapse: collapse;
  width: 100%;
  max-width: 560px;
  font-size: 0.8125rem;
}

.obs-table th {
  text-align: left;
  color: var(--text-tertiary);
  font-size: 0.6875rem;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  padding: 0.375rem 0.75rem;
  border-bottom: 1px solid var(--border-color);
}

.obs-table td {
  padding: 0.375rem 0.75rem;
  border-bottom: 1px solid var(--border-color);
}

.obs-meter { color: var(--text-secondary); font-family: 'SF Mono', 'Fira Code', monospace; }
.obs-value { color: var(--text-primary); font-weight: 600; }
</style>
