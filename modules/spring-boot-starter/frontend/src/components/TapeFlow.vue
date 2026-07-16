<script setup lang="ts">
import { computed } from 'vue'
import { VueFlow, Handle, Position } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import { buildTapeGraph, shortId, firstUser, type ReplayTree } from '../lib/tapeGraph'

/**
 * Node-graph view of a replayed tape coordination tree: the coordinator run plus
 * its fan-out agent runs (linked by parentRunId), reconstructed from the tape
 * with no model in the loop. Complements the flat table with the actual shape of
 * the coordination — who dispatched whom. Read-only; clicking a node opens that
 * run's step stream in the parent.
 *
 * The graph derivation (node placement, edges) lives in the pure, unit-tested
 * {@link buildTapeGraph}; positions are applied via Vue's `:style` (CSSOM), which
 * the console's strict nonce CSP permits.
 */
const props = defineProps<{ tree: ReplayTree }>()
const emit = defineEmits<{ (e: 'select', runId: string): void }>()

const graph = computed(() => buildTapeGraph(props.tree))
</script>

<template>
  <div class="tape-flow" data-testid="tape-flow">
    <VueFlow
      :nodes="graph.nodes"
      :edges="graph.edges"
      :fit-view-on-init="true"
      :min-zoom="0.3"
      :max-zoom="1.5"
      :nodes-connectable="false"
      :edges-updatable="false"
      @node-click="({ node }) => emit('select', node.id)"
    >
      <template #node-run="{ data }">
        <div
          class="flow-node"
          :class="'flow-' + data.role"
          data-testid="tape-flow-node"
          :title="data.run.runId"
        >
          <Handle type="target" :position="Position.Top" :connectable="false" />
          <div class="flow-node-head">
            <span class="badge" :class="data.role === 'coordinator' ? 'st-coordinator' : 'st-agent'">
              {{ data.role }}
            </span>
            <span class="mono flow-id">{{ shortId(data.run.runId) }}</span>
          </div>
          <div class="small flow-meta">{{ data.run.runtime ?? '—' }} · {{ data.run.model ?? '—' }}</div>
          <div class="mono flow-prompt">▸ {{ firstUser(data.run) }}</div>
          <div class="mono flow-output">{{ data.run.output || '(no output)' }}</div>
          <div v-if="data.run.tools.length" class="flow-tools">
            <span v-for="(t, i) in data.run.tools" :key="i" class="tool-chip mono">🔧 {{ t.name }}</span>
          </div>
          <Handle type="source" :position="Position.Bottom" :connectable="false" />
        </div>
      </template>
    </VueFlow>
  </div>
</template>

<style scoped>
.tape-flow {
  height: 24rem;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--bg-surface);
  overflow: hidden;
}
.flow-node {
  width: 15rem;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 0.5rem 0.625rem;
  background: var(--bg-base, var(--bg-surface));
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  cursor: pointer;
  font-size: 0.75rem;
}
.flow-node:hover { border-color: var(--accent-color); }
.flow-coordinator { border-left: 3px solid #5e35b1; }
.flow-agent { border-left: 3px solid #0277bd; }
.flow-node-head { display: flex; align-items: center; gap: 0.5rem; margin-bottom: 0.25rem; }
.badge {
  font-size: 0.625rem; font-weight: 700; text-transform: uppercase;
  padding: 0.0625rem 0.375rem; border-radius: 4px; letter-spacing: 0.05em;
}
.st-coordinator { background: #ede7f6; color: #5e35b1; }
.st-agent { background: #e1f5fe; color: #0277bd; }
@media (prefers-color-scheme: dark) {
  .st-coordinator { background: rgba(94, 53, 177, 0.24); }
  .st-agent { background: rgba(2, 119, 189, 0.24); }
}
.flow-id { color: var(--text-primary); font-weight: 600; }
.flow-meta { color: var(--text-tertiary); margin-bottom: 0.25rem; }
.flow-prompt {
  color: var(--text-tertiary); white-space: nowrap; overflow: hidden;
  text-overflow: ellipsis; margin-bottom: 0.125rem;
}
.flow-output {
  color: var(--text-secondary); display: -webkit-box; -webkit-line-clamp: 2;
  line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.flow-tools { display: flex; gap: 0.25rem; flex-wrap: wrap; margin-top: 0.375rem; }
.tool-chip {
  font-size: 0.625rem; background: var(--bg-hover); border: 1px solid var(--border-color);
  border-radius: 9999px; padding: 0.0625rem 0.375rem; color: var(--text-secondary);
}
.mono { font-family: ui-monospace, Menlo, monospace; }
.small { font-size: 0.6875rem; }
</style>
