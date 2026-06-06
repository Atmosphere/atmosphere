<script setup lang="ts">
import { ref, watch, onMounted, onBeforeUnmount } from 'vue'

// MCP Apps host (SEP-1865). The console acts as a stateless 2026-07-28 MCP
// client: it lists tools, finds those that declare a ui:// UI resource via
// _meta.ui.resourceUri, reads that resource's HTML, and renders it in a
// SANDBOXED iframe (allow-scripts, NO allow-same-origin → opaque origin, so the
// app can never touch the console's DOM, cookies, or storage).
//
// It also implements the App Bridge: JSON-RPC 2.0 over postMessage between the
// app (iframe) and this host. The app handshakes via ui/initialize and may then
// call MCP methods (tools/call, tools/list); the host forwards non-ui/ requests
// to the MCP server — which still enforces its policy gateway on tools/call, so
// an app can't bypass governance. NOTE: this implements the App→Host→Server
// direction and the ui/initialize handshake; the separate-origin sandbox-proxy
// hardening and Host→App-registered-tools are not implemented here.

const props = defineProps<{ endpoint: string; active: boolean }>()

const frameEl = ref<HTMLIFrameElement | null>(null)
const bridgeReady = ref(false)

interface AppTool {
  name: string
  title: string
  resourceUri: string
}

const apps = ref<AppTool[]>([])
const selected = ref<AppTool | null>(null)
const html = ref('')
const loading = ref(false)
const error = ref('')
let loaded = false

// Per-request _meta for the stateless protocol, declaring the apps extension.
function meta() {
  return {
    'io.modelcontextprotocol/protocolVersion': '2026-07-28',
    'io.modelcontextprotocol/clientInfo': { name: 'atmosphere-console', version: '1.0' },
    'io.modelcontextprotocol/clientCapabilities': {
      extensions: { 'io.modelcontextprotocol/apps': {} },
    },
  }
}

let rpcId = 0
async function rpc(method: string, params: Record<string, unknown>): Promise<any> {
  const res = await fetch(props.endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: ++rpcId,
      method,
      params: { ...params, _meta: meta() },
    }),
  })
  if (!res.ok) {
    throw new Error(`${method} → HTTP ${res.status}`)
  }
  const env = await res.json()
  if (env.error) {
    throw new Error(`${method} → ${env.error.message ?? 'error'}`)
  }
  return env.result
}

async function loadApps() {
  if (loaded || !props.endpoint) return
  loaded = true
  loading.value = true
  error.value = ''
  try {
    const result = await rpc('tools/list', {})
    const tools: any[] = result?.tools ?? []
    apps.value = tools
      .map((t) => {
        const uri = t?._meta?.ui?.resourceUri
        return uri ? { name: t.name, title: t.title || t.name, resourceUri: uri } : null
      })
      .filter((t): t is AppTool => t !== null)
    if (apps.value.length > 0) {
      await open(apps.value[0])
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function open(app: AppTool) {
  selected.value = app
  html.value = ''
  error.value = ''
  bridgeReady.value = false
  try {
    const result = await rpc('resources/read', { uri: app.resourceUri })
    const content = (result?.contents ?? [])[0]
    if (!content || typeof content.text !== 'string') {
      throw new Error('UI resource returned no HTML')
    }
    html.value = content.text
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

// ── App Bridge (SEP-1865): JSON-RPC over postMessage ─────────────────────

function reply(id: unknown, result: unknown) {
  frameEl.value?.contentWindow?.postMessage({ jsonrpc: '2.0', id, result }, '*')
}

function replyError(id: unknown, code: number, message: string) {
  frameEl.value?.contentWindow?.postMessage({ jsonrpc: '2.0', id, error: { code, message } }, '*')
}

async function onBridgeMessage(event: MessageEvent) {
  // Only accept messages from the app iframe we rendered — never a foreign
  // window. The sandboxed iframe has an opaque ("null") origin, so the source
  // check (not the origin) is the trustworthy guard.
  if (!frameEl.value || event.source !== frameEl.value.contentWindow) return
  const msg = event.data
  if (!msg || msg.jsonrpc !== '2.0' || typeof msg.method !== 'string') return

  // Notification (no id).
  if (msg.id === undefined || msg.id === null) {
    if (msg.method === 'ui/notifications/initialized') bridgeReady.value = true
    return
  }
  // Request.
  if (msg.method === 'ui/initialize') {
    reply(msg.id, { hostCapabilities: { serverTools: {} }, hostContext: {} })
    return
  }
  if (msg.method.startsWith('ui/')) {
    replyError(msg.id, -32601, `Unsupported ui/ method: ${msg.method}`)
    return
  }
  // Forward MCP methods (tools/call, tools/list, …) to the server. The server's
  // policy gateway still gates tools/call, so the app inherits governance.
  try {
    reply(msg.id, await rpc(msg.method, (msg.params as Record<string, unknown>) ?? {}))
  } catch (e) {
    replyError(msg.id, -32000, e instanceof Error ? e.message : String(e))
  }
}

onMounted(() => window.addEventListener('message', onBridgeMessage))
onBeforeUnmount(() => window.removeEventListener('message', onBridgeMessage))

watch(() => props.active, (a) => { if (a) loadApps() }, { immediate: true })
</script>

<template>
  <div class="mcp-apps" data-testid="mcp-apps">
    <aside class="app-list">
      <div class="app-list-title">MCP Apps</div>
      <p v-if="loading" class="muted">Loading…</p>
      <p v-else-if="error" class="error" data-testid="mcp-apps-error">{{ error }}</p>
      <p v-else-if="apps.length === 0" class="muted" data-testid="mcp-apps-empty">
        No MCP Apps advertised by this server.
      </p>
      <button
        v-for="app in apps"
        :key="app.name"
        :class="['app-item', { active: selected?.name === app.name }]"
        :data-testid="`mcp-app-${app.name}`"
        @click="open(app)"
      >
        {{ app.title }}
      </button>
    </aside>
    <section class="app-stage">
      <iframe
        v-if="html"
        ref="frameEl"
        :srcdoc="html"
        sandbox="allow-scripts"
        class="app-frame"
        data-testid="mcp-app-frame"
        title="MCP App"
      ></iframe>
      <p v-else-if="!loading && !error" class="muted stage-empty">
        Select an app to render it in a sandboxed iframe.
      </p>
    </section>
  </div>
</template>

<style scoped>
.mcp-apps {
  display: flex;
  height: 100%;
  overflow: hidden;
}

.app-list {
  width: 220px;
  flex-shrink: 0;
  border-right: 1px solid var(--border-color);
  padding: 0.75rem;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.app-list-title {
  font-size: 0.6875rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--text-secondary);
  padding: 0.25rem 0.5rem 0.5rem;
}

.app-item {
  text-align: left;
  padding: 0.5rem 0.625rem;
  font-size: 0.8125rem;
  color: var(--text-primary);
  background: none;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}

.app-item:hover {
  background: var(--bg-hover);
}

.app-item.active {
  background: var(--accent-bg);
  color: var(--accent-color);
}

.app-stage {
  flex: 1;
  display: flex;
  align-items: stretch;
  justify-content: stretch;
  padding: 0.75rem;
}

.app-frame {
  width: 100%;
  height: 100%;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--bg-surface);
}

.muted {
  color: var(--text-secondary);
  font-size: 0.8125rem;
}

.stage-empty {
  margin: auto;
}

.error {
  color: #ef4444;
  font-size: 0.8125rem;
}
</style>
