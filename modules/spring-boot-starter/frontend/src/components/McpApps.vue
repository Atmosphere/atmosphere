<script setup lang="ts">
import { ref, watch, onMounted, onBeforeUnmount } from 'vue'

// MCP Apps host (SEP-1865). The console acts as a stateless 2026-07-28 MCP
// client: it lists tools, finds those that declare a ui:// UI resource via
// _meta.ui.resourceUri, reads that resource's HTML, and renders it in a
// SANDBOXED iframe (allow-scripts, NO allow-same-origin → opaque origin, so the
// app can never touch the console's DOM, cookies, or storage).
//
// It also implements the App Bridge: JSON-RPC 2.0 over postMessage between the
// app (iframe) and this host. tools/call and tools/list flow BIDIRECTIONALLY:
//   • App→Host→Server — the app calls MCP methods (tools/call, tools/list); the
//     host forwards non-ui/ requests to the MCP server, which still enforces its
//     policy gateway on tools/call, so an app can't bypass governance.
//   • Host→App — when the app declares appCapabilities.tools in ui/initialize,
//     the host lists the app-registered tools (tools/list sent INTO the iframe)
//     and can invoke them (tools/call), letting the host drive the app's UI.
// Isolation: when a distinct sandbox origin is available, the app HTML is
// rendered through a separate-origin SANDBOX PROXY (SEP-1865 "Sandbox proxy",
// sandbox.html): the host loads the proxy in an iframe at a different origin
// (allow-scripts allow-same-origin) and hands it the untrusted HTML via
// ui/notifications/sandbox-resource-ready; the proxy renders the app in a nested
// opaque-origin iframe with a CSP and transparently relays every non-sandbox-*
// message. The sandbox origin is the deployer-configured mcpSandboxOrigin, or in
// dev the localhost↔127.0.0.1 sibling origin. When no distinct origin exists
// (a single real domain with no config), the host falls back to rendering the
// HTML directly in an opaque-origin sandboxed iframe (allow-scripts, no
// allow-same-origin) — still isolated from the host, just without the proxy hop.

const props = defineProps<{ endpoint: string; active: boolean; sandboxOrigin?: string }>()

const frameEl = ref<HTMLIFrameElement | null>(null)
const bridgeReady = ref(false)

// Whether the current render goes through the separate-origin sandbox proxy.
const useProxy = ref(false)
const proxySrc = ref('')

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

// Host→App: tools the running app registers (filled from a tools/list sent INTO
// the iframe once the app declares appCapabilities.tools).
interface RegisteredTool {
  name: string
  title: string
  description: string
}
const appTools = ref<RegisteredTool[]>([])
const appToolResult = ref('')
let appDeclaresTools = false

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

// Resolve the origin that should host the sandbox proxy. It MUST differ from
// the console's own origin to isolate the proxy from the host.
function resolveSandboxOrigin(): string | null {
  const configured = props.sandboxOrigin?.trim().replace(/\/$/, '')
  if (configured) {
    return configured !== window.location.origin ? configured : null
  }
  // Dev convenience: localhost and 127.0.0.1 are distinct origins served by the
  // same process, so we can exercise the real separate-origin path locally.
  const { protocol, hostname, port } = window.location
  const portPart = port ? `:${port}` : ''
  if (hostname === 'localhost') return `${protocol}//127.0.0.1${portPart}`
  if (hostname === '127.0.0.1') return `${protocol}//localhost${portPart}`
  return null
}

async function open(app: AppTool) {
  selected.value = app
  html.value = ''
  error.value = ''
  bridgeReady.value = false
  appTools.value = []
  appToolResult.value = ''
  appDeclaresTools = false
  useProxy.value = false
  proxySrc.value = ''
  rejectAllAppRequests('app reloaded')
  try {
    const result = await rpc('resources/read', { uri: app.resourceUri })
    const content = (result?.contents ?? [])[0]
    if (!content || typeof content.text !== 'string') {
      throw new Error('UI resource returned no HTML')
    }
    html.value = content.text
    const origin = resolveSandboxOrigin()
    if (origin) {
      // sandbox.html lives next to the console document, served from the
      // distinct origin. The HTML is delivered later via postMessage, never
      // injected into the host DOM.
      const base = window.location.pathname.replace(/[^/]*$/, '')
      proxySrc.value = `${origin}${base}sandbox.html`
      useProxy.value = true
    }
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

// Host→App requests: the host is the JSON-RPC client and the app the server.
// Each outbound request gets a unique id; the matching response arrives back
// through onBridgeMessage and resolves the pending entry. A timeout bounds the
// wait so a silent app can never leak a pending promise (terminal-path safety).
let appReqId = 0
const appPending = new Map<
  number,
  { resolve: (v: unknown) => void; reject: (e: Error) => void; timer: ReturnType<typeof setTimeout> }
>()

function rejectAllAppRequests(reason: string) {
  for (const [, p] of appPending) {
    clearTimeout(p.timer)
    p.reject(new Error(reason))
  }
  appPending.clear()
}

function sendToApp(method: string, params: Record<string, unknown>): Promise<any> {
  const win = frameEl.value?.contentWindow
  if (!win) return Promise.reject(new Error('app not loaded'))
  const id = ++appReqId
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      appPending.delete(id)
      reject(new Error(`${method} → app did not respond`))
    }, 5000)
    appPending.set(id, { resolve, reject, timer })
    win.postMessage({ jsonrpc: '2.0', id, method, params }, '*')
  })
}

async function refreshAppTools() {
  try {
    const r = await sendToApp('tools/list', {})
    const tools: any[] = (r as any)?.tools ?? []
    appTools.value = tools.map((t) => ({
      name: t.name,
      title: t.title || t.name,
      description: t.description || '',
    }))
  } catch {
    // App declared the tools capability but did not answer tools/list; show none.
    appTools.value = []
  }
}

async function callAppTool(tool: RegisteredTool) {
  appToolResult.value = `Calling ${tool.name}…`
  try {
    const r = await sendToApp('tools/call', { name: tool.name, arguments: {} })
    const content = (r as any)?.content
    appToolResult.value =
      content?.[0]?.text ?? JSON.stringify((r as any)?.structuredContent ?? r)
  } catch (e) {
    appToolResult.value = e instanceof Error ? e.message : String(e)
  }
}

async function onBridgeMessage(event: MessageEvent) {
  // Only accept messages from the app iframe we rendered — never a foreign
  // window. The sandboxed iframe has an opaque ("null") origin, so the source
  // check (not the origin) is the trustworthy guard.
  if (!frameEl.value || event.source !== frameEl.value.contentWindow) return
  const msg = event.data
  if (!msg || msg.jsonrpc !== '2.0') return

  // Response to a Host→App request (has id, no method): resolve the pending call.
  if (typeof msg.method !== 'string') {
    if (msg.id !== undefined && msg.id !== null) {
      const pending = appPending.get(msg.id)
      if (pending) {
        appPending.delete(msg.id)
        clearTimeout(pending.timer)
        if (msg.error) pending.reject(new Error(msg.error.message ?? 'app error'))
        else pending.resolve(msg.result)
      }
    }
    return
  }

  // Notification (no id).
  if (msg.id === undefined || msg.id === null) {
    if (msg.method === 'ui/notifications/sandbox-proxy-ready') {
      // The separate-origin proxy is live: hand it the untrusted HTML to render
      // in its nested opaque-origin iframe. The HTML never enters the host DOM.
      frameEl.value?.contentWindow?.postMessage(
        { jsonrpc: '2.0', method: 'ui/notifications/sandbox-resource-ready', params: { html: html.value } },
        '*',
      )
      return
    }
    if (msg.method === 'ui/notifications/initialized') {
      bridgeReady.value = true
      // App is live; if it registered tools, enumerate them (Host→App tools/list).
      if (appDeclaresTools) refreshAppTools()
    }
    return
  }
  // Request.
  if (msg.method === 'ui/initialize') {
    appDeclaresTools = !!msg.params?.appCapabilities?.tools
    // serverTools/serverResources: this host proxies both to the MCP server.
    reply(msg.id, { hostCapabilities: { serverTools: {}, serverResources: {} }, hostContext: {} })
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
onBeforeUnmount(() => {
  window.removeEventListener('message', onBridgeMessage)
  rejectAllAppRequests('host unmounted')
})

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
      <!-- Separate-origin sandbox proxy: the proxy needs allow-same-origin to
           script in its OWN distinct origin; isolation comes from that origin
           differing from the host's, and from the nested opaque-origin View. -->
      <iframe
        v-if="html && useProxy"
        ref="frameEl"
        :src="proxySrc"
        sandbox="allow-scripts allow-same-origin"
        class="app-frame"
        data-testid="mcp-app-frame"
        data-sandbox-mode="proxy"
        title="MCP App"
      ></iframe>
      <iframe
        v-else-if="html"
        ref="frameEl"
        :srcdoc="html"
        sandbox="allow-scripts"
        class="app-frame"
        data-testid="mcp-app-frame"
        data-sandbox-mode="direct"
        title="MCP App"
      ></iframe>
      <p v-else-if="!loading && !error" class="muted stage-empty">
        Select an app to render it in a sandboxed iframe.
      </p>
      <div v-if="html && appTools.length > 0" class="app-tools" data-testid="mcp-app-tools">
        <div class="app-tools-title">App-registered tools (Host → App)</div>
        <div class="app-tools-row">
          <button
            v-for="t in appTools"
            :key="t.name"
            class="app-tool-btn"
            :data-testid="`mcp-app-tool-${t.name}`"
            :title="t.description"
            @click="callAppTool(t)"
          >
            {{ t.title }}
          </button>
        </div>
        <p v-if="appToolResult" class="app-tool-result" data-testid="mcp-app-tool-result">
          {{ appToolResult }}
        </p>
      </div>
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
  flex-direction: column;
  align-items: stretch;
  justify-content: stretch;
  gap: 0.5rem;
  padding: 0.75rem;
  overflow: hidden;
}

.app-frame {
  width: 100%;
  flex: 1;
  min-height: 0;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--bg-surface);
}

.app-tools {
  flex-shrink: 0;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 0.5rem 0.625rem;
  background: var(--bg-surface);
}

.app-tools-title {
  font-size: 0.6875rem;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--text-secondary);
  margin-bottom: 0.375rem;
}

.app-tools-row {
  display: flex;
  flex-wrap: wrap;
  gap: 0.375rem;
}

.app-tool-btn {
  padding: 0.375rem 0.625rem;
  font-size: 0.8125rem;
  color: var(--accent-color);
  background: var(--accent-bg);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  cursor: pointer;
}

.app-tool-btn:hover {
  background: var(--bg-hover);
}

.app-tool-result {
  margin: 0.5rem 0 0;
  font-size: 0.8125rem;
  color: var(--text-primary);
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
