<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import ChatContainer from './components/ChatContainer.vue'
import GovernancePolicies from './components/GovernancePolicies.vue'
import GovernanceDecisions from './components/GovernanceDecisions.vue'
import GovernanceOwasp from './components/GovernanceOwasp.vue'
import logoUrl from './assets/logo.svg'

type Tab = 'chat' | 'policies' | 'decisions' | 'owasp'

const subtitle = ref('')
const endpoint = ref('/atmosphere/ai-chat')
const ready = ref(false)
const activeTab = ref<Tab>('chat')
const governanceAvailable = ref(false)
const governancePolicyCount = ref<number | null>(null)

async function probeGovernance() {
  try {
    const res = await fetch('/api/admin/governance/summary', {
      headers: { Accept: 'application/json' },
    })
    if (!res.ok) return
    const data = await res.json()
    governanceAvailable.value = true
    governancePolicyCount.value = typeof data.policyCount === 'number'
      ? data.policyCount : null
  } catch {
    // /api/admin/governance/summary not wired — hide the governance tabs.
  }
}

const tabs = computed(() => {
  const list: Array<{ id: Tab; label: string; badge?: string }> = [
    { id: 'chat', label: 'Chat' },
  ]
  if (governanceAvailable.value) {
    list.push({
      id: 'policies',
      label: 'Policies',
      badge: governancePolicyCount.value !== null
        ? String(governancePolicyCount.value) : undefined,
    })
    list.push({ id: 'decisions', label: 'Decisions' })
    list.push({ id: 'owasp', label: 'OWASP' })
  }
  return list
})

onMounted(async () => {
  try {
    const res = await fetch('/api/console/info')
    if (res.ok) {
      const data = await res.json()
      if (data.subtitle) subtitle.value = data.subtitle
      if (data.endpoint) endpoint.value = data.endpoint
    }
  } catch {
    // Console info not available — use defaults
  }
  await probeGovernance()
  ready.value = true
})
</script>

<template>
  <div class="app">
    <header class="app-header">
      <div class="header-content">
        <img :src="logoUrl" alt="Atmosphere" class="header-logo" />
        <div class="header-titles">
          <h1 class="header-title">Atmosphere AI Console</h1>
          <span v-if="subtitle" class="header-subtitle">{{ subtitle }}</span>
        </div>
        <span class="header-badge">v4</span>
      </div>
      <nav v-if="ready && tabs.length > 1" class="tab-nav" data-testid="console-tabs">
        <button
          v-for="tab in tabs"
          :key="tab.id"
          :class="['tab-btn', { active: activeTab === tab.id }]"
          @click="activeTab = tab.id"
          :data-testid="`tab-${tab.id}`"
        >
          {{ tab.label }}
          <span v-if="tab.badge !== undefined" class="tab-badge">{{ tab.badge }}</span>
        </button>
      </nav>
    </header>
    <main class="app-main">
      <ChatContainer v-if="ready && activeTab === 'chat'" :endpoint="endpoint" />
      <GovernancePolicies v-if="ready" v-show="activeTab === 'policies'"
                          :active="activeTab === 'policies'" />
      <GovernanceDecisions v-if="ready" v-show="activeTab === 'decisions'"
                           :active="activeTab === 'decisions'" />
      <GovernanceOwasp v-if="ready" v-show="activeTab === 'owasp'"
                       :active="activeTab === 'owasp'" />
    </main>
  </div>
</template>

<style scoped>
.app {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--bg-primary);
}

.app-header {
  border-bottom: 1px solid var(--border-color);
  background: var(--bg-surface);
  padding: 0 1.5rem;
  min-height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;
  gap: 1rem;
  flex-wrap: wrap;
}

.header-content {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.header-logo {
  width: 28px;
  height: 28px;
}

.header-titles {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.header-title {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
  line-height: 1.2;
}

.header-subtitle {
  font-size: 0.75rem;
  color: var(--text-secondary);
  font-weight: 400;
}

.header-badge {
  font-size: 0.6875rem;
  font-weight: 600;
  color: var(--accent-color);
  background: var(--accent-bg);
  padding: 0.125rem 0.5rem;
  border-radius: 9999px;
  letter-spacing: 0.025em;
}

.tab-nav {
  display: flex;
  align-items: center;
  gap: 0.125rem;
}

.tab-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.375rem 0.875rem;
  font-size: 0.8125rem;
  font-weight: 500;
  color: var(--text-secondary);
  background: none;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.12s;
}

.tab-btn:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.tab-btn.active {
  background: var(--accent-bg);
  color: var(--accent-color);
}

.tab-badge {
  font-size: 0.6875rem;
  font-weight: 700;
  padding: 0.0625rem 0.375rem;
  border-radius: 9999px;
  background: var(--border-color);
  color: var(--text-secondary);
}

.tab-btn.active .tab-badge {
  background: var(--accent-color);
  color: var(--bg-surface);
}

.app-main {
  flex: 1;
  overflow: hidden;
  position: relative;
}

/*
 * All governance views use height: 100% + overflow: auto internally, so
 * wrapping them in the flex main without forcing a child layout mode keeps
 * the existing chat layout unchanged.
 */
</style>
