<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import ChatContainer from './components/ChatContainer.vue'
import GovernancePolicies from './components/GovernancePolicies.vue'
import GovernanceDecisions from './components/GovernanceDecisions.vue'
import GovernanceOwasp from './components/GovernanceOwasp.vue'
import GovernanceCommitments from './components/GovernanceCommitments.vue'
import Sessions from './components/Sessions.vue'
import Workspace from './components/Workspace.vue'
import Interactions from './components/Interactions.vue'
import Validation from './components/Validation.vue'
import Checkpoints from './components/Checkpoints.vue'
import Tape from './components/Tape.vue'
import McpApps from './components/McpApps.vue'
import { livePlan } from './lib/workspaceStore'
import type { ConsoleTransportName } from './transports'
import logoUrl from './assets/logo.svg'

type Tab = 'chat' | 'sessions' | 'workspace' | 'interactions' | 'validation' | 'checkpoints' | 'tape' | 'apps' | 'policies' | 'decisions' | 'owasp' | 'commitments'

const subtitle = ref('')
const endpoint = ref('/atmosphere/ai-chat')
// 'ai' is the safe default — the bundled console is AI-shaped. Server picks
// 'broadcast' for endpoints whose registered handler is not in the
// org.atmosphere.{ai,agent,coordinator} packages (e.g. @ManagedService chats
// in spring-boot-mcp-server / spring-boot-otel-chat).
const mode = ref<'ai' | 'broadcast'>('ai')
// Wire transport for the chat connection. 'atmosphere' (WS + long-polling)
// unless the sample opts into a foreign protocol via
// atmosphere.console-transport — value is server-validated
// (AtmosphereConsoleInfoEndpoint#detectTransport), so only known names arrive.
const transport = ref<ConsoleTransportName>('atmosphere')
const ready = ref(false)
const activeTab = ref<Tab>('chat')
// The live MCP endpoint, when one is registered — enables the MCP Apps host tab.
const mcpEndpoint = ref<string | null>(null)
const mcpSandboxOrigin = ref<string | null>(null)
const governanceAvailable = ref(false)
const governancePolicyCount = ref<number | null>(null)
const agentsAvailable = ref(false)
const workspaceAvailable = ref(false)
const interactionsAvailable = ref(false)
const validationAvailable = ref(false)
const checkpointsAvailable = ref(false)
const tapeAvailable = ref(false)

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

async function probeWorkspace() {
  // The Workspace tab shows only when at least one plan / filesystem surface
  // genuinely attached — the owners list is populated by the harness attach
  // engines at registration time, never from config intent (Runtime Truth).
  // Only called when the console-info harness block exists AND the agents
  // probe succeeded, which together guarantee the endpoint is mapped — so
  // this fetch can never 404-spam the browser console on slim samples.
  try {
    const res = await fetch('/api/admin/workspace/owners', {
      headers: { Accept: 'application/json' },
    })
    if (res.ok) {
      const list = await res.json()
      workspaceAvailable.value = Array.isArray(list) && list.length > 0
    }
  } catch {
    // /api/admin/workspace/owners not wired — hide the Workspace tab.
  }
}

// Interactions + Validation availability are read from /api/console/info's
// runtime-resolved capability flags (hasInteractions / hasVerifier) in
// onMounted — NOT probed. A speculative fetch to /api/interactions or
// /api/admin/verifier/summary on a sample without those modules returns 404,
// which the browser logs as a red console error that JS cannot suppress.
// Gating on the server-confirmed flag keeps the console quiet (Runtime Truth).

// The Workspace tab renders when a plan / filesystem surface is browsable via
// the admin API, or as soon as a live plan-update event arrived on the chat
// connection (covers deployments whose admin reads are token-gated).
const workspaceVisible = computed(() =>
  workspaceAvailable.value || livePlan.value !== null)

const tabs = computed(() => {
  const list: Array<{ id: Tab; label: string; badge?: string }> = [
    { id: 'chat', label: 'Chat' },
  ]
  if (agentsAvailable.value) {
    list.push({ id: 'sessions', label: 'Sessions' })
  }
  if (workspaceVisible.value) {
    list.push({ id: 'workspace', label: 'Workspace' })
  }
  if (interactionsAvailable.value) {
    list.push({ id: 'interactions', label: 'Interactions' })
  }
  if (validationAvailable.value) {
    list.push({ id: 'validation', label: 'Validation' })
  }
  if (checkpointsAvailable.value) {
    list.push({ id: 'checkpoints', label: 'Checkpoints' })
  }
  if (tapeAvailable.value) {
    list.push({ id: 'tape', label: 'Tape' })
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
  // Truthy only when the harness preset genuinely ran on this server — the
  // console-info harness block is runtime state, not config intent. Used to
  // gate the workspace probe below.
  let harnessPresent = false
  try {
    const res = await fetch('/api/console/info')
    if (res.ok) {
      const data = await res.json()
      if (data.subtitle) subtitle.value = data.subtitle
      if (data.endpoint) endpoint.value = data.endpoint
      if (data.mode === 'broadcast' || data.mode === 'ai') mode.value = data.mode
      // Narrow to the known adapter names — the server already validates,
      // but the frontend must not trust the wire (Boundary Safety).
      if (data.transport === 'grpc' || data.transport === 'a2a'
          || data.transport === 'ag-ui' || data.transport === 'atmosphere') {
        transport.value = data.transport
      }
      if (data.mcpEndpoint) mcpEndpoint.value = data.mcpEndpoint
      if (data.mcpSandboxOrigin) mcpSandboxOrigin.value = data.mcpSandboxOrigin
      // Runtime-resolved capability flags — gate the optional tabs without a
      // 404-producing probe. Absent/false means the module isn't wired here.
      interactionsAvailable.value = data.hasInteractions === true
      validationAvailable.value = data.hasVerifier === true
      checkpointsAvailable.value = data.hasCheckpoints === true
      tapeAvailable.value = data.hasTape === true
      harnessPresent = data.harness != null && typeof data.harness === 'object'
    }
  } catch {
    // Console info not available — use defaults
  }
  await Promise.all([probeGovernance(), probeAgents()])
  // harness block ⇒ atmosphere-ai (and the workspace endpoint class) is on
  // the classpath; agents probe OK ⇒ the AtmosphereAdmin bean is live. Both
  // together mean /api/admin/workspace/owners is mapped — no 404 probe spam.
  if (harnessPresent && agentsAvailable.value) {
    await probeWorkspace()
  }
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
      <ChatContainer v-if="ready && activeTab === 'chat'" :endpoint="endpoint" :mode="mode" :transport="transport" />
      <Sessions v-if="ready && agentsAvailable" v-show="activeTab === 'sessions'"
                :active="activeTab === 'sessions'" />
      <Workspace v-if="ready && workspaceVisible" v-show="activeTab === 'workspace'"
                 :active="activeTab === 'workspace'" />
      <Interactions v-if="ready && interactionsAvailable" v-show="activeTab === 'interactions'"
                    :active="activeTab === 'interactions'" />
      <Validation v-if="ready && validationAvailable" v-show="activeTab === 'validation'"
                  :active="activeTab === 'validation'" />
      <Checkpoints v-if="ready && checkpointsAvailable" v-show="activeTab === 'checkpoints'"
                   :active="activeTab === 'checkpoints'" />
      <Tape v-if="ready && tapeAvailable" v-show="activeTab === 'tape'"
            :active="activeTab === 'tape'" />
      <McpApps v-if="ready && mcpEndpoint" v-show="activeTab === 'apps'"
               :endpoint="mcpEndpoint" :active="activeTab === 'apps'"
               :sandbox-origin="mcpSandboxOrigin ?? undefined" />
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
