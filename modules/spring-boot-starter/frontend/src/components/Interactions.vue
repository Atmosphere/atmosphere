<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  type Interaction,
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

let listTimer: ReturnType<typeof setInterval> | null = null
let detailTimer: ReturnType<typeof setInterval> | null = null

const terminal = (s?: string) => s === 'COMPLETED' || s === 'FAILED' || s === 'CANCELLED'

async function refreshList() {
  try {
    list.value = await listInteractions()
    error.value = null
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

async function pollSelected() {
  const id = selected.value?.id
  if (!id) return
  try {
    selected.value = await getInteraction(id)
    if (terminal(selected.value?.status)) stopDetailPoll()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
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

function statusClass(s: string) {
  return `badge badge-${s.toLowerCase()}`
}

function start() {
  if (listTimer) return
  refreshList()
  listTimer = setInterval(refreshList, 2500)
}
function stop() {
  if (listTimer) { clearInterval(listTimer); listTimer = null }
  stopDetailPoll()
}

onMounted(() => { if (active.value) start() })
onUnmounted(stop)
watch(active, (on) => { if (on) start(); else stop() })
</script>

<template>
  <div class="ix-view" data-testid="interactions-view">
    <div class="ix-launch">
      <textarea
        v-model="message"
        class="ix-input"
        rows="2"
        placeholder="Ask the agent something…"
        data-testid="interaction-message"
      ></textarea>
      <div class="ix-launch-actions">
        <label class="ix-check">
          <input type="checkbox" v-model="background" data-testid="interaction-background" />
          Background
        </label>
        <button class="ix-run" :disabled="busy || !message.trim()"
                @click="run" data-testid="interaction-run">
          {{ busy ? 'Launching…' : 'Run interaction' }}
        </button>
      </div>
    </div>

    <p v-if="error" class="ix-error" data-testid="interaction-error">{{ error }}</p>

    <div class="ix-body">
      <ul class="ix-list" data-testid="interaction-list">
        <li v-if="list.length === 0" class="ix-empty">No interactions yet.</li>
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
            <span v-if="it.background" class="chip">bg</span>
            <span v-if="it.parentId" class="chip" title="chained turn">chain</span>
          </div>
          <div class="ix-item-id mono">{{ it.id }}</div>
          <div v-if="it.finalText" class="ix-item-preview">{{ it.finalText }}</div>
        </li>
      </ul>

      <div v-if="selected" class="ix-detail" data-testid="interaction-detail">
        <div class="ix-detail-head">
          <span :class="statusClass(selected.status)" data-testid="interaction-status">
            {{ selected.status }}
          </span>
          <span class="mono small">{{ selected.id }}</span>
          <button
            v-if="selected.background && !terminal(selected.status)"
            class="ix-cancel" @click="doCancel" data-testid="interaction-cancel"
          >Cancel</button>
        </div>

        <ol class="ix-steps" data-testid="interaction-steps">
          <li v-if="selected.steps.length === 0" class="ix-empty">
            Waiting for steps…
          </li>
          <li v-for="step in selected.steps" :key="step.seq"
              class="ix-step" data-testid="interaction-step">
            <span class="step-type">{{ step.type }}</span>
            <span v-if="step.toolName" class="step-tool mono">{{ step.toolName }}</span>
            <span v-if="step.text" class="step-text">{{ step.text }}</span>
            <span v-if="step.usage" class="step-usage mono small">
              {{ step.usage.total }} tok
            </span>
          </li>
        </ol>

        <div v-if="terminal(selected.status)" class="ix-continue">
          <input
            v-model="continueText"
            class="ix-input"
            placeholder="Continue this conversation…"
            data-testid="interaction-continue"
            @keyup.enter="doContinue"
          />
          <button class="ix-run" :disabled="busy || !continueText.trim()"
                  @click="doContinue" data-testid="interaction-continue-btn">
            Continue
          </button>
        </div>
      </div>
      <div v-else class="ix-detail ix-detail-empty">
        Select an interaction to see its durable step timeline.
      </div>
    </div>
  </div>
</template>

<style scoped>
.ix-view {
  padding: 1.5rem;
  height: 100%;
  overflow-y: auto;
  max-width: 64rem;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.ix-launch {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.ix-input {
  width: 100%;
  padding: 0.5rem 0.75rem;
  font-size: 0.875rem;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  background: var(--bg-surface);
  color: var(--text-primary);
  font-family: inherit;
  resize: vertical;
  box-sizing: border-box;
}
.ix-launch-actions {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}
.ix-check {
  font-size: 0.8125rem;
  color: var(--text-secondary);
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
}
.ix-run {
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--bg-surface);
  background: var(--accent-color);
  border: none;
  padding: 0.375rem 0.875rem;
  border-radius: 6px;
  cursor: pointer;
}
.ix-run:disabled { opacity: 0.5; cursor: not-allowed; }
.ix-cancel {
  font-size: 0.75rem;
  color: #c62828;
  background: var(--bg-surface);
  border: 1px solid #c6282833;
  padding: 0.125rem 0.5rem;
  border-radius: 6px;
  cursor: pointer;
  margin-left: auto;
}
.ix-error { color: #c62828; font-size: 0.8125rem; margin: 0; }
.ix-body {
  display: flex;
  gap: 1rem;
  align-items: flex-start;
}
.ix-list {
  list-style: none;
  margin: 0;
  padding: 0;
  flex: 0 0 16rem;
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}
.ix-item {
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 0.5rem 0.625rem;
  cursor: pointer;
}
.ix-item.selected { border-color: var(--accent-color); }
.ix-item-head { display: flex; align-items: center; gap: 0.375rem; }
.ix-item-id { font-size: 0.6875rem; color: var(--text-tertiary); margin-top: 0.25rem; }
.ix-item-preview {
  font-size: 0.75rem;
  color: var(--text-secondary);
  margin-top: 0.25rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ix-detail {
  flex: 1 1 auto;
  min-width: 0;
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 0.875rem;
}
.ix-detail-empty, .ix-empty {
  color: var(--text-tertiary);
  font-size: 0.8125rem;
}
.ix-detail-head {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.625rem;
}
.ix-steps {
  list-style: none;
  margin: 0 0 0.75rem 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}
.ix-step {
  display: flex;
  align-items: baseline;
  gap: 0.5rem;
  padding: 0.375rem 0.5rem;
  border-left: 2px solid var(--accent-color);
  background: var(--bg-hover);
  border-radius: 0 4px 4px 0;
}
.step-type {
  font-size: 0.6875rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--accent-color);
  flex: 0 0 auto;
}
.step-tool { font-size: 0.75rem; color: var(--text-primary); }
.step-text {
  font-size: 0.8125rem;
  color: var(--text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
}
.step-usage { color: var(--text-tertiary); margin-left: auto; }
.ix-continue { display: flex; gap: 0.5rem; }
.badge {
  font-size: 0.6875rem;
  font-weight: 700;
  text-transform: uppercase;
  padding: 0.125rem 0.5rem;
  border-radius: 4px;
  letter-spacing: 0.05em;
}
.badge-running { background: #fff3e0; color: #ef6c00; }
.badge-completed { background: #e8f5e9; color: #2e7d32; }
.badge-failed { background: #ffebee; color: #c62828; }
.badge-cancelled { background: #eceff1; color: #546e7a; }
.badge-created { background: #e3f2fd; color: #1565c0; }
.chip {
  font-size: 0.625rem;
  font-weight: 600;
  text-transform: uppercase;
  padding: 0.0625rem 0.375rem;
  border-radius: 9999px;
  background: var(--border-color);
  color: var(--text-secondary);
}
.mono { font-family: ui-monospace, Menlo, monospace; }
.small { font-size: 0.75rem; }
@media (prefers-color-scheme: dark) {
  .badge-running { background: rgba(239, 108, 0, 0.2); }
  .badge-completed { background: rgba(46, 125, 50, 0.18); }
  .badge-failed { background: rgba(198, 40, 40, 0.18); }
  .badge-cancelled { background: rgba(84, 110, 122, 0.2); }
  .badge-created { background: rgba(21, 101, 192, 0.2); }
}
</style>
