<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { authHeaders } from '../lib/authToken'

/**
 * Rooms tab — live Room Protocol rooms from the framework read plane
 * (/api/admin/rooms), polled while the tab is active. Gated by the
 * hasRooms runtime-truth flag in /api/console/info.
 */

interface MemberDetail { id: string; metadata?: Record<string, unknown> }
interface RoomInfo { name: string; members: number; destroyed: boolean; memberDetails: MemberDetail[] }

const props = defineProps<{ active: boolean }>()

const rooms = ref<RoomInfo[]>([])
const error = ref<string | null>(null)
let timer: ReturnType<typeof setInterval> | null = null

async function load() {
  try {
    const res = await fetch('/api/admin/rooms', { headers: authHeaders({ Accept: 'application/json' }) })
    if (!res.ok) {
      error.value = `HTTP ${res.status}`
      return
    }
    rooms.value = await res.json()
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
  <div class="rooms-view" data-testid="rooms-view">
    <p v-if="error" class="rooms-error">Rooms unavailable: {{ error }}</p>
    <p v-else-if="rooms.length === 0" class="rooms-empty">No active rooms — rooms appear when the first member joins.</p>
    <div v-else class="room-cards">
      <div v-for="room in rooms" :key="room.name" class="room-card" data-testid="room-card">
        <div class="room-head">
          <span class="room-name">{{ room.name }}</span>
          <span class="room-count">{{ room.members }} member{{ room.members === 1 ? '' : 's' }}</span>
        </div>
        <ul class="room-members">
          <li v-for="m in room.memberDetails" :key="m.id" class="room-member">{{ m.id }}</li>
        </ul>
      </div>
    </div>
  </div>
</template>

<style scoped>
.rooms-view {
  height: 100%;
  overflow: auto;
  padding: 1.5rem;
}

.rooms-empty, .rooms-error {
  color: var(--text-tertiary);
  font-size: 0.875rem;
}

.room-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 0.75rem;
}

.room-card {
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  padding: 0.875rem 1rem;
}

.room-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 0.5rem;
}

.room-name {
  font-weight: 600;
  color: var(--text-primary);
}

.room-count {
  font-size: 0.75rem;
  color: #10b981;
  font-weight: 600;
}

.room-members {
  margin: 0;
  padding-left: 1.1rem;
  font-size: 0.8125rem;
  color: var(--text-secondary);
}
</style>
