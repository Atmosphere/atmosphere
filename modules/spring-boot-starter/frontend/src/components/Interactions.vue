<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  type Interaction,
  type InteractionStep,
  cancelInteraction,
  continueInteraction,
  createInteraction,
  getInteraction,
  listInteractions,
} from '../composables/useInteractions'

const props = defineProps<{ active: boolean }>()
const active = computed(() => props.active)

const message = ref('')
const background = ref(true)
const continueText = ref('')
const list = ref<Interaction[]>([])
const selected = ref<Interaction | null>(null)
const error = ref<string | null>(null)
const busy = ref(false)
const copiedId = ref<string | null>(null)

let listTimer: ReturnType<typeof setInterval> | null = null
let detailTimer: ReturnType<typeof setInterval> | null = null

const terminal = (s?: string) => s === 'COMPLETED' || s === 'FAILED' || s === 'CANCELLED'

async function refreshList() {
  try {
    list.value = await listInteractions()
    error.value = null
    // Drop a selection the server no longer knows about (e.g. the backend was
    // restarted and its in-memory store reset) so the detail pane never shows
    // an interaction that is absent from the list.
    if (selected.value && !list.value.some((i) => i.id === selected.value!.id)) {
      selected.value = null
      stopDetailPoll()
    }
  } catch (e) {
    // The list could not be loaded — surface the disconnected state and clear
    // stale rows/selection rather than leaving contradictory cached data.
    error.value = e instanceof Error ? e.message : String(e)
    list.value = []
    selected.value = null
    stopDetailPoll()
  }
}

async function pollSelected() {
  const id = selected.value?.id
  if (!id) return
  try {
    selected.value = await getInteraction(id)
    if (terminal(selected.value?.status)) stopDetailPoll()
  } catch (e) {
    // The selected interaction is gone (404) or unreachable — clear it so the
    // detail pane does not show a phantom record.
    error.value = e instanceof Error ? e.message : String(e)
    selected.value = null
    stopDetailPoll()
  }
}

function startDetailPoll() {
  stopDetailPoll()
  if (selected.value && !terminal(selected.value.status)) {
    detailTimer = setInterval(pollSelected, 700)
  }
}

function stopDetailPoll() {
  if (detailTimer) { clearInterval(detailTimer); detailTimer = null }
}

async function select(it: Interaction) {
  selected.value = it
  await pollSelected()
  startDetailPoll()
}

async function run() {
  if (!message.value.trim() || busy.value) return
  busy.value = true
  error.value = null
  try {
    const created = await createInteraction({
      message: message.value.trim(),
      background: background.value,
    })
    message.value = ''
    await refreshList()
    await select(created)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function doContinue() {
  const parent = selected.value
  if (!parent || !continueText.value.trim() || busy.value) return
  busy.value = true
  error.value = null
  try {
    const next = await continueInteraction(parent.id, {
      message: continueText.value.trim(),
      background: background.value,
    })
    continueText.value = ''
    await refreshList()
    await select(next)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function doCancel() {
  const id = selected.value?.id
  if (!id) return
  await cancelInteraction(id)
  await pollSelected()
}

async function selectParent() {
  const parentId = selected.value?.parentId
  if (!parentId) return
  const fromList = list.value.find((i) => i.id === parentId)
  if (fromList) {
    await select(fromList)
  } else {
    try {
      selected.value = await getInteraction(parentId)
      startDetailPoll()
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
    }
  }
}

async function copyId(id: string) {
  try {
    await navigator.clipboard.writeText(id)
    copiedId.value = id
    setTimeout(() => { if (copiedId.value === id) copiedId.value = null }, 1200)
  } catch {
    // Clipboard unavailable (insecure context) — non-fatal.
  }
}

function statusClass(s: string) {
  return `badge badge-${s.toLowerCase()}`
}

const STEP_LABELS: Record<string, string> = {
  text: 'Text',
  'tool-call': 'Tool call',
  'tool-result': 'Tool result',
  'tool-error': 'Tool error',
  'agent-step': 'Agent step',
  approval: 'Approval',
  usage: 'Usage',
  completion: 'Completion',
  error: 'Error',
}
function stepLabel(type: string) {
  return STEP_LABELS[type] ?? type
}

function shortId(id: string) {
  // int-<uuid> → int-<first 8 of uuid>
  const body = id.startsWith('int-') ? id.slice(4) : id
  return 'int-' + body.slice(0, 8)
}

function formatTime(iso: string | null | undefined) {
  if (!iso) return ''
  try {
    return new Date(iso).toLocaleTimeString(undefined, {
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    })
  } catch {
    return iso
  }
}

function relativeTime(iso: string | null | undefined) {
  if (!iso) return ''
  const then = new Date(iso).getTime()
  if (Number.isNaN(then)) return ''
  const secs = Math.max(0, Math.round((nowTick.value - then) / 1000))
  if (secs < 5) return 'just now'
  if (secs < 60) return `${secs}s ago`
  const mins = Math.round(secs / 60)
  if (mins < 60) return `${mins}m ago`
  const hrs = Math.round(mins / 60)
  if (hrs < 24) return `${hrs}h ago`
  return `${Math.round(hrs / 24)}d ago`
}

// A 1s ticking clock so relative timestamps stay fresh without re-fetching.
const nowTick = ref(Date.now())
let clockTimer: ReturnType<typeof setInterval> | null = null

function dataEntries(step: InteractionStep): Array<[string, string]> {
  const out: Array<[string, string]> = []
  for (const [k, v] of Object.entries(step.data ?? {})) {
    if (v === null || v === undefined || v === '') continue
    out.push([k, typeof v === 'string' ? v : JSON.stringify(v)])
  }
  return out
}

function start() {
  if (listTimer) return
  refreshList()
  listTimer = setInterval(refreshList, 2500)
  if (!clockTimer) clockTimer = setInterval(() => { nowTick.value = Date.now() }, 1000)
}
function stop() {
  if (listTimer) { clearInterval(listTimer); listTimer = null }
  if (clockTimer) { clearInterval(clockTimer); clockTimer = null }
  stopDetailPoll()
}

onMounted(() => { if (active.value) start() })
onUnmounted(stop)
watch(active, (on) => { if (on) start(); else stop() })
</script>

<template>
  <div class="ix-view" data-testid="interactions-view">
    <!-- Intro / explainer -->
    <header class="ix-intro">
      <h2 class="ix-intro-title">Interactions</h2>
      <p class="ix-intro-sub">
        Stateful agent turns over <code>/api/interactions</code>. Run a turn
        <strong>synchronously</strong> or in the <strong>background</strong> (returns
        immediately; poll its durable step timeline), then <strong>continue</strong> the
        conversation — the follow-up chains via <code>previous_interaction_id</code> and
        inherits the prior turn's history.
      </p>
    </header>

    <!-- Launch -->
    <section class="ix-launch" aria-label="New interaction">
      <label class="ix-field-label" for="ix-message">New interaction</label>
      <textarea
        id="ix-message"
        v-model="message"
        class="ix-input"
        rows="2"
        placeholder="Ask the agent something…"
        data-testid="interaction-message"
        @keydown.enter.exact.prevent="run"
      ></textarea>
      <div class="ix-launch-actions">
        <label class="ix-check" title="Detach the run and poll its durable timeline">
          <input type="checkbox" v-model="background" data-testid="interaction-background" />
          Background
          <span class="ix-hint">— return immediately, retrievable after disconnect</span>
        </label>
        <button class="ix-run" :disabled="busy || !message.trim()"
                @click="run" data-testid="interaction-run">
          {{ busy ? 'Launching…' : 'Run interaction' }}
        </button>
      </div>
    </section>

    <p v-if="error" class="ix-error" data-testid="interaction-error">
      <strong>Request failed:</strong> {{ error }}
    </p>

    <div class="ix-body">
      <!-- History list -->
      <aside class="ix-list-pane">
        <div class="ix-list-head">
          <span class="ix-list-title">History</span>
          <span class="ix-count">{{ list.length }}</span>
        </div>
        <ul class="ix-list" data-testid="interaction-list">
          <li v-if="list.length === 0 && error" class="ix-empty">
            Can't reach the server — is the app running?
          </li>
          <li v-else-if="list.length === 0" class="ix-empty">
            No interactions yet. Run one above to see its durable step timeline here.
          </li>
          <li
            v-for="it in list"
            :key="it.id"
            :class="['ix-item', { selected: selected && selected.id === it.id }]"
            @click="select(it)"
            data-testid="interaction-item"
          >
            <div class="ix-item-head">
              <span :class="statusClass(it.status)" data-testid="interaction-item-status">
                {{ it.status }}
              </span>
              <span v-if="it.background" class="chip" title="Launched in the background">BG</span>
              <span v-if="it.parentId" class="chip chain" title="Chained from a previous turn">CHAIN</span>
              <span class="ix-item-time">{{ relativeTime(it.createdAt) }}</span>
            </div>
            <div class="ix-item-id mono" :title="it.id">{{ shortId(it.id) }}</div>
            <div v-if="it.finalText" class="ix-item-preview">{{ it.finalText }}</div>
            <div v-else-if="it.status === 'RUNNING'" class="ix-item-preview running">running…</div>
          </li>
        </ul>
      </aside>

      <!-- Detail -->
      <section v-if="selected" class="ix-detail" data-testid="interaction-detail">
        <div class="ix-detail-head">
          <span :class="statusClass(selected.status)" data-testid="interaction-status">
            {{ selected.status }}
          </span>
          <span v-if="selected.background" class="chip">BG</span>
          <button class="ix-id-copy mono" :title="'Copy ' + selected.id"
                  @click="copyId(selected.id)">
            {{ selected.id }}
            <span class="ix-copy-flag">{{ copiedId === selected.id ? '✓ copied' : '⧉' }}</span>
          </button>
          <button
            v-if="selected.background && !terminal(selected.status)"
            class="ix-cancel" @click="doCancel" data-testid="interaction-cancel"
          >Cancel</button>
        </div>

        <!-- Metadata grid -->
        <dl class="ix-meta">
          <div class="ix-meta-row">
            <dt>Created</dt>
            <dd>{{ formatTime(selected.createdAt) }} · {{ relativeTime(selected.createdAt) }}</dd>
          </div>
          <div class="ix-meta-row" v-if="selected.updatedAt && selected.updatedAt !== selected.createdAt">
            <dt>Updated</dt>
            <dd>{{ formatTime(selected.updatedAt) }}</dd>
          </div>
          <div class="ix-meta-row" v-if="selected.model">
            <dt>Model</dt>
            <dd class="mono">{{ selected.model }}</dd>
          </div>
          <div class="ix-meta-row" v-if="selected.usage && selected.usage.total">
            <dt>Tokens</dt>
            <dd class="mono">
              {{ selected.usage.total }} total
              <span class="ix-muted">({{ selected.usage.input }} in / {{ selected.usage.output }} out)</span>
            </dd>
          </div>
          <div class="ix-meta-row">
            <dt>Conversation</dt>
            <dd class="mono ix-muted" :title="selected.conversationId">{{ selected.conversationId }}</dd>
          </div>
          <div class="ix-meta-row" v-if="selected.userId">
            <dt>Owner</dt>
            <dd class="mono">{{ selected.userId }}</dd>
          </div>
          <div class="ix-meta-row" v-if="selected.parentId">
            <dt>Chained from</dt>
            <dd>
              <button class="ix-link mono" @click="selectParent" :title="selected.parentId">
                {{ shortId(selected.parentId) }} ↑
              </button>
            </dd>
          </div>
        </dl>

        <p v-if="selected.status === 'FAILED' && selected.errorMessage" class="ix-fail">
          {{ selected.errorMessage }}
        </p>

        <!-- Step timeline -->
        <div class="ix-steps-head">
          <span>Step timeline</span>
          <span class="ix-count">{{ selected.steps.length }}</span>
          <span v-if="!terminal(selected.status)" class="ix-running-flag">
            running… {{ selected.steps.length }} step(s) so far
          </span>
        </div>
        <ol class="ix-steps" data-testid="interaction-steps">
          <li v-if="selected.steps.length === 0" class="ix-empty">
            Waiting for the first step…
          </li>
          <li v-for="step in selected.steps" :key="step.seq"
              :class="['ix-step', 'step-' + step.type]" data-testid="interaction-step">
            <div class="ix-step-head">
              <span class="step-type">{{ stepLabel(step.type) }}</span>
              <span v-if="step.toolName" class="step-tool mono">{{ step.toolName }}</span>
              <span v-if="step.usage" class="step-usage mono">{{ step.usage.total }} tok</span>
              <span class="step-time mono">{{ formatTime(step.createdAt) }}</span>
            </div>
            <div v-if="step.text" class="step-text">{{ step.text }}</div>
            <dl v-if="dataEntries(step).length" class="step-data">
              <div v-for="[k, v] in dataEntries(step)" :key="k" class="step-data-row">
                <dt class="mono">{{ k }}</dt>
                <dd class="mono">{{ v }}</dd>
              </div>
            </dl>
          </li>
        </ol>

        <!-- Continue -->
        <div v-if="terminal(selected.status)" class="ix-continue">
          <label class="ix-field-label" for="ix-continue">Continue this conversation</label>
          <div class="ix-continue-row">
            <input
              id="ix-continue"
              v-model="continueText"
              class="ix-input"
              placeholder="Follow-up message — chains via previous_interaction_id…"
              data-testid="interaction-continue"
              @keyup.enter="doContinue"
            />
            <button class="ix-run" :disabled="busy || !continueText.trim()"
                    @click="doContinue" data-testid="interaction-continue-btn">
              Continue
            </button>
          </div>
        </div>
      </section>
      <section v-else class="ix-detail ix-detail-empty">
        <p>Select an interaction to inspect its metadata and durable step timeline.</p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.ix-view {
  padding: 1.5rem;
  height: 100%;
  overflow-y: auto;
  max-width: 72rem;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.ix-intro { display: flex; flex-direction: column; gap: 0.25rem; }
.ix-intro-title {
  font-size: 1.125rem; font-weight: 600; color: var(--text-primary); margin: 0;
}
.ix-intro-sub {
  font-size: 0.8125rem; color: var(--text-secondary); margin: 0; line-height: 1.5;
  max-width: 64rem;
}
.ix-intro-sub code {
  font-family: ui-monospace, Menlo, monospace; font-size: 0.75rem;
  background: var(--code-block-bg); padding: 0.05rem 0.3rem; border-radius: 4px;
}
.ix-launch {
  display: flex; flex-direction: column; gap: 0.5rem;
  background: var(--bg-surface); border: 1px solid var(--border-color);
  border-radius: 10px; padding: 0.875rem;
}
.ix-field-label {
  font-size: 0.75rem; font-weight: 600; color: var(--text-secondary);
  text-transform: uppercase; letter-spacing: 0.04em;
}
.ix-input {
  width: 100%; padding: 0.5rem 0.75rem; font-size: 0.875rem;
  border: 1px solid var(--border-color); border-radius: 6px;
  background: var(--bg-primary); color: var(--text-primary);
  font-family: inherit; resize: vertical; box-sizing: border-box;
}
.ix-launch-actions { display: flex; align-items: center; gap: 0.75rem; flex-wrap: wrap; }
.ix-check {
  font-size: 0.8125rem; color: var(--text-secondary);
  display: inline-flex; align-items: center; gap: 0.375rem;
}
.ix-hint { color: var(--text-tertiary); font-size: 0.75rem; }
.ix-run {
  font-size: 0.8125rem; font-weight: 600; color: #fff;
  background: var(--accent-color); border: none; padding: 0.4rem 0.95rem;
  border-radius: 6px; cursor: pointer; margin-left: auto;
}
.ix-run:disabled { opacity: 0.5; cursor: not-allowed; }
.ix-cancel {
  font-size: 0.75rem; color: #c62828; background: var(--bg-surface);
  border: 1px solid #c6282833; padding: 0.2rem 0.6rem; border-radius: 6px;
  cursor: pointer; margin-left: auto;
}
.ix-error {
  color: #c62828; font-size: 0.8125rem; margin: 0;
  background: #ffebee33; border: 1px solid #c6282822;
  padding: 0.5rem 0.75rem; border-radius: 6px;
}
.ix-fail {
  color: #c62828; font-size: 0.8125rem; margin: 0 0 0.5rem 0;
  background: #ffebee22; border-left: 2px solid #c62828;
  padding: 0.4rem 0.6rem; border-radius: 0 4px 4px 0;
}
.ix-body { display: flex; gap: 1rem; align-items: flex-start; }
.ix-list-pane { flex: 0 0 18rem; display: flex; flex-direction: column; gap: 0.5rem; }
.ix-list-head, .ix-steps-head {
  display: flex; align-items: center; gap: 0.5rem;
  font-size: 0.75rem; font-weight: 600; color: var(--text-secondary);
  text-transform: uppercase; letter-spacing: 0.04em;
}
.ix-steps-head { margin: 0.25rem 0 0.5rem 0; }
.ix-count {
  font-size: 0.6875rem; font-weight: 700; padding: 0.05rem 0.4rem;
  border-radius: 9999px; background: var(--border-color); color: var(--text-secondary);
}
.ix-running-flag { color: #ef6c00; text-transform: none; font-weight: 500; margin-left: auto; }
.ix-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 0.375rem; }
.ix-item {
  background: var(--bg-surface); border: 1px solid var(--border-color);
  border-radius: 8px; padding: 0.5rem 0.625rem; cursor: pointer; transition: border-color 0.12s;
}
.ix-item:hover { border-color: var(--text-tertiary); }
.ix-item.selected { border-color: var(--accent-color); }
.ix-item-head { display: flex; align-items: center; gap: 0.375rem; }
.ix-item-time { margin-left: auto; font-size: 0.6875rem; color: var(--text-tertiary); }
.ix-item-id { font-size: 0.6875rem; color: var(--text-tertiary); margin-top: 0.25rem; }
.ix-item-preview {
  font-size: 0.75rem; color: var(--text-secondary); margin-top: 0.25rem;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.ix-item-preview.running { color: #ef6c00; font-style: italic; }
.ix-detail {
  flex: 1 1 auto; min-width: 0;
  background: var(--bg-surface); border: 1px solid var(--border-color);
  border-radius: 10px; padding: 1rem;
}
.ix-detail-empty p, .ix-empty { color: var(--text-tertiary); font-size: 0.8125rem; line-height: 1.5; }
.ix-detail-head { display: flex; align-items: center; gap: 0.5rem; margin-bottom: 0.75rem; flex-wrap: wrap; }
.ix-id-copy {
  font-size: 0.75rem; color: var(--text-secondary); background: var(--bg-primary);
  border: 1px solid var(--border-color); border-radius: 6px; padding: 0.15rem 0.5rem;
  cursor: pointer; display: inline-flex; align-items: center; gap: 0.4rem;
}
.ix-copy-flag { color: var(--text-tertiary); font-size: 0.6875rem; }
.ix-meta {
  margin: 0 0 0.75rem 0; padding: 0.625rem 0.75rem;
  background: var(--bg-primary); border: 1px solid var(--border-color); border-radius: 8px;
  display: flex; flex-direction: column; gap: 0.25rem;
}
.ix-meta-row { display: flex; gap: 0.75rem; font-size: 0.75rem; }
.ix-meta-row dt { flex: 0 0 7rem; color: var(--text-tertiary); margin: 0; }
.ix-meta-row dd { margin: 0; color: var(--text-secondary); min-width: 0; overflow: hidden; text-overflow: ellipsis; }
.ix-muted { color: var(--text-tertiary); }
.ix-link {
  background: none; border: none; color: var(--accent-color); cursor: pointer;
  padding: 0; font-size: 0.75rem;
}
.ix-link:hover { text-decoration: underline; }
.ix-steps { list-style: none; margin: 0 0 0.75rem 0; padding: 0; display: flex; flex-direction: column; gap: 0.375rem; }
.ix-step {
  padding: 0.5rem 0.625rem; border-left: 2px solid var(--border-color);
  background: var(--bg-primary); border-radius: 0 6px 6px 0;
}
.ix-step.step-completion { border-left-color: #2e7d32; }
.ix-step.step-error, .ix-step.step-tool-error { border-left-color: #c62828; }
.ix-step.step-tool-call, .ix-step.step-tool-result { border-left-color: #6a1b9a; }
.ix-step.step-text { border-left-color: var(--accent-color); }
.ix-step.step-usage { border-left-color: #ef6c00; }
.ix-step-head { display: flex; align-items: baseline; gap: 0.5rem; }
.step-type {
  font-size: 0.6875rem; font-weight: 700; text-transform: uppercase;
  letter-spacing: 0.04em; color: var(--text-secondary);
}
.step-tool { font-size: 0.75rem; color: var(--text-primary); }
.step-usage { font-size: 0.6875rem; color: #ef6c00; }
.step-time { margin-left: auto; font-size: 0.6875rem; color: var(--text-tertiary); }
.step-text {
  font-size: 0.8125rem; color: var(--text-secondary); white-space: pre-wrap;
  word-break: break-word; margin-top: 0.3rem; line-height: 1.45;
}
.step-data { margin: 0.3rem 0 0 0; display: flex; flex-direction: column; gap: 0.15rem; }
.step-data-row { display: flex; gap: 0.5rem; font-size: 0.7rem; }
.step-data-row dt { color: var(--text-tertiary); margin: 0; flex: 0 0 auto; }
.step-data-row dd {
  margin: 0; color: var(--text-secondary); min-width: 0; overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap;
}
.ix-continue { display: flex; flex-direction: column; gap: 0.4rem; margin-top: 0.5rem; }
.ix-continue-row { display: flex; gap: 0.5rem; }
.badge {
  font-size: 0.6875rem; font-weight: 700; text-transform: uppercase;
  padding: 0.125rem 0.5rem; border-radius: 4px; letter-spacing: 0.05em;
}
.badge-running { background: #fff3e0; color: #ef6c00; }
.badge-completed { background: #e8f5e9; color: #2e7d32; }
.badge-failed { background: #ffebee; color: #c62828; }
.badge-cancelled { background: #eceff1; color: #546e7a; }
.badge-created { background: #e3f2fd; color: #1565c0; }
.chip {
  font-size: 0.625rem; font-weight: 700; text-transform: uppercase;
  padding: 0.0625rem 0.375rem; border-radius: 9999px;
  background: var(--border-color); color: var(--text-secondary);
}
.chip.chain { background: rgba(106, 27, 154, 0.15); color: #6a1b9a; }
.mono { font-family: ui-monospace, Menlo, monospace; }
@media (prefers-color-scheme: dark) {
  .badge-running { background: rgba(239, 108, 0, 0.2); }
  .badge-completed { background: rgba(46, 125, 50, 0.18); }
  .badge-failed { background: rgba(198, 40, 40, 0.18); }
  .badge-cancelled { background: rgba(84, 110, 122, 0.2); }
  .badge-created { background: rgba(21, 101, 192, 0.2); }
  .chip.chain { background: rgba(106, 27, 154, 0.28); color: #ce93d8; }
}
</style>
