<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import ChatContainer from './components/ChatContainer.vue'
import GovernancePolicies from './components/GovernancePolicies.vue'
import GovernanceDecisions from './components/GovernanceDecisions.vue'
import GovernanceOwasp from './components/GovernanceOwasp.vue'
import GovernanceCommitments from './components/GovernanceCommitments.vue'
import Sessions from './components/Sessions.vue'
import Interactions from './components/Interactions.vue'
import Validation from './components/Validation.vue'
import McpApps from './components/McpApps.vue'
import logoUrl from './assets/logo.svg'

type Tab = 'chat' | 'sessions' | 'interactions' | 'validation' | 'apps' | 'policies' | 'decisions' | 'owasp' | 'commitments'

const subtitle = ref('')
const endpoint = ref('/atmosphere/ai-chat')
// 'ai' is the safe default — the bundled console is AI-shaped. Server picks
// 'broadcast' for endpoints whose registered handler is not in the
// org.atmosphere.{ai,agent,coordinator} packages (e.g. @ManagedService chats
// in spring-boot-mcp-server / spring-boot-otel-chat).
const mode = ref<'ai' | 'broadcast'>('ai')
const ready = ref(false)
const activeTab = ref<Tab>('chat')
// The live MCP endpoint, when one is registered — enables the MCP Apps host tab.
const mcpEndpoint = ref<string | null>(null)
const governanceAvailable = ref(false)
const governancePolicyCount = ref<number | null>(null)
const agentsAvailable = ref(false)
const interactionsAvailable = ref(false)
const validationAvailable = ref(false)

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

async function probeAgents() {
  try {
    const res = await fetch('/api/admin/agents', { headers: { Accept: 'application/json' } })
    if (res.ok) {
      agentsAvailable.value = true
    }
  } catch {
    // /api/admin/agents not wired — hide the Sessions tab.
  }
}

async function probeInteractions() {
  try {
    // GET /api/interactions is the ownership-scoped read surface — a 200 (even
    // an empty list) means the Interactions API is wired on this sample.
    const res = await fetch('/api/interactions', { headers: { Accept: 'application/json' } })
    if (res.ok) {
      interactionsAvailable.value = true
    }
  } catch {
    // atmosphere-interactions not on the classpath — hide the Interactions tab.
  }
}

async function probeValidation() {
  try {
    // 200 means atmosphere-verifier + a PlanAndVerify bean are wired — show
    // the Validation tab. A 404 (controller absent) hides it.
    const res = await fetch('/api/admin/verifier/summary', { headers: { Accept: 'application/json' } })
    if (res.ok) {
      validationAvailable.value = true
    }
  } catch {
    // atmosphere-verifier not on the classpath — hide the Validation tab.
  }
}

const tabs = computed(() => {
  const list: Array<{ id: Tab; label: string; badge?: string }> = [
    { id: 'chat', label: 'Chat' },
  ]
  if (agentsAvailable.value) {
    list.push({ id: 'sessions', label: 'Sessions' })
  }
  if (interactionsAvailable.value) {
    list.push({ id: 'interactions', label: 'Interactions' })
  }
  if (validationAvailable.value) {
    list.push({ id: 'validation', label: 'Validation' })
  }
  if (mcpEndpoint.value) {
    list.push({ id: 'apps', label: 'MCP Apps' })
  }
  if (governanceAvailable.value) {
    list.push({
      id: 'policies',
      label: 'Policies',
      badge: governancePolicyCount.value !== null
        ? String(governancePolicyCount.value) : undefined,
    })
    list.push({ id: 'decisions', label: 'Decisions' })
    list.push({ id: 'commitments', label: 'Commitments' })
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
      if (data.mode === 'broadcast' || data.mode === 'ai') mode.value = data.mode
      if (data.mcpEndpoint) mcpEndpoint.value = data.mcpEndpoint
    }
  } catch {
    // Console info not available — use defaults
  }
  await Promise.all([probeGovernance(), probeAgents(), probeInteractions(), probeValidation()])
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
      <ChatContainer v-if="ready && activeTab === 'chat'" :endpoint="endpoint" :mode="mode" />
      <Sessions v-if="ready && agentsAvailable" v-show="activeTab === 'sessions'"
                :active="activeTab === 'sessions'" />
      <Interactions v-if="ready && interactionsAvailable" v-show="activeTab === 'interactions'"
                    :active="activeTab === 'interactions'" />
      <Validation v-if="ready && validationAvailable" v-show="activeTab === 'validation'"
                  :active="activeTab === 'validation'" />
      <McpApps v-if="ready && mcpEndpoint" v-show="activeTab === 'apps'"
               :endpoint="mcpEndpoint" :active="activeTab === 'apps'" />
      <GovernancePolicies v-if="ready" v-show="activeTab === 'policies'"
                          :active="activeTab === 'policies'" />
      <GovernanceDecisions v-if="ready" v-show="activeTab === 'decisions'"
                           :active="activeTab === 'decisions'" />
      <GovernanceCommitments v-if="ready" v-show="activeTab === 'commitments'"
                             :active="activeTab === 'commitments'" />
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
