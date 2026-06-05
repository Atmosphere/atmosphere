<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useVerifier } from '../composables/useVerifier'

defineProps<{ active: boolean }>()

const { summary, examples, error, running, result, probe, check } = useVerifier()
const goal = ref('')
const showInfo = ref(false)

onMounted(probe)

async function runExample(g: string) {
  goal.value = g
  await check(g)
}

async function runGoal() {
  if (goal.value.trim()) await check(goal.value.trim())
}

function argText(args?: Record<string, unknown>): string {
  if (!args) return ''
  return Object.entries(args)
    .map(([k, v]) => `${k}=${String(v)}`)
    .join(', ')
}
</script>

<template>
  <div class="val-view" data-testid="validation-view">
    <div class="val-toolbar">
      <div class="val-title-row">
        <h2 class="val-title">Plan-and-Verify validation</h2>
        <button
          class="info-btn"
          data-testid="validation-info-toggle"
          :aria-expanded="showInfo"
          aria-label="What is validation?"
          title="What is validation?"
          @click="showInfo = !showInfo"
        >i</button>
      </div>
      <span v-if="summary" class="solver-badge" data-testid="smt-solver">
        SMT&nbsp;solver: <strong>{{ summary.smtSolver }}</strong>
      </span>
    </div>

    <!-- New-user explainer: what "validation" means in Atmosphere. -->
    <div v-if="showInfo" class="info-card" data-testid="validation-info">
      <button class="info-close" aria-label="Close" @click="showInfo = false">×</button>
      <h3>What is validation?</h3>
      <p>
        An AI agent doesn't call your tools directly. Instead the model emits a
        <strong>plan</strong> — a small JSON workflow of tool calls — and
        Atmosphere checks that <em>entire plan</em> against a declarative
        <strong>policy</strong> <em>before any tool runs</em>. Only a plan that
        passes every check is dispatched; an unsafe plan is refused and
        <strong>nothing executes</strong>.
      </p>
      <p>
        It's the difference between parameterised SQL and string-concatenated
        SQL: instead of trusting the model to "be careful," the structure of the
        request is verified mechanically. Each plan flows through a chain of
        verifiers:
      </p>
      <ul>
        <li><strong>allowlist / well-formed</strong> — only declared tools, shaped correctly.</li>
        <li><strong>capability</strong> — the plan only uses capabilities it was granted.</li>
        <li><strong>taint</strong> — sensitive data (e.g. your inbox) can't flow into an external sink (e.g. an outbound email). Stops prompt-injection exfiltration.</li>
        <li><strong>automaton</strong> — the call sequence follows the allowed order.</li>
        <li><strong>smt</strong> — numeric rules (e.g. <code>send_bulk.count ≤ quota</code>) are <em>proven</em> by a solver for every possible value, not just the ones in one run.</li>
      </ul>
      <p>
        Click an example below — two plans pass and execute, two are refused.
        The result shows the plan, which verifier passed or failed, and why.
      </p>
    </div>

    <p class="val-intro">
      Every goal is planned into a JSON workflow, then run through the verifier
      chain over the plan AST. A plan dispatches only if <em>every</em> verifier
      passes — otherwise it is refused before any tool fires.
    </p>

    <!-- The verifier chain + policy this deployment actually runs -->
    <div v-if="summary" class="panels">
      <div class="panel">
        <h3>Verifier chain</h3>
        <div class="chain">
          <template v-for="(v, i) in summary.verifiers" :key="v.name">
            <span class="chain-stage" :data-testid="`chain-${v.name}`">{{ v.name }}</span>
            <span v-if="i < summary.verifiers.length - 1" class="chain-arrow">→</span>
          </template>
        </div>
      </div>
      <div class="panel">
        <h3>Policy: {{ summary.policy.name }}</h3>
        <dl class="policy-dl">
          <dt>Allowed tools</dt>
          <dd class="mono small">{{ summary.policy.allowedTools.join(', ') }}</dd>
          <dt>Taint rules</dt>
          <dd class="small">{{ summary.policy.taintRuleCount }}</dd>
          <dt v-if="summary.policy.numericInvariants.length">SMT invariants</dt>
          <dd v-if="summary.policy.numericInvariants.length" class="mono small">
            <div v-for="inv in summary.policy.numericInvariants" :key="inv">{{ inv }}</div>
          </dd>
        </dl>
      </div>
    </div>

    <p v-if="error" class="error">{{ error }}</p>

    <!-- Example goals + free-text -->
    <div class="run-bar">
      <button
        v-for="ex in examples"
        :key="ex.id"
        class="example-btn"
        :data-testid="`example-${ex.id}`"
        :title="ex.description"
        :disabled="running"
        @click="runExample(ex.goal)"
      >
        {{ ex.label }}
      </button>
    </div>
    <div class="goal-row">
      <input
        v-model="goal"
        class="goal-input"
        data-testid="goal-input"
        placeholder="Enter a goal…"
        :disabled="running"
        @keyup.enter="runGoal"
      />
      <button class="run-btn" data-testid="run-check" :disabled="running || !goal.trim()" @click="runGoal">
        {{ running ? 'Verifying…' : 'Verify' }}
      </button>
    </div>

    <!-- Result -->
    <div v-if="result" class="result" data-testid="check-result">
      <div class="result-head">
        <span
          class="status-badge"
          :class="result.status"
          data-testid="check-status"
        >{{ result.status }}</span>
        <span class="result-goal mono small">{{ result.goal }}</span>
      </div>

      <div v-if="result.plan" class="plan">
        <h4>Plan</h4>
        <ol class="plan-steps">
          <li v-for="(s, i) in result.plan.steps" :key="i" class="mono small">
            <span class="step-tool">{{ s.tool }}</span>(<span>{{ argText(s.arguments) }}</span>)
            <span v-if="s.binding" class="step-bind">→ @{{ s.binding }}</span>
          </li>
        </ol>
      </div>

      <div v-if="result.verifiers" class="verdicts">
        <h4>Verifier chain</h4>
        <div
          v-for="v in result.verifiers"
          :key="v.name"
          class="verdict"
          :class="{ ok: v.ok, bad: !v.ok }"
          :data-testid="`verdict-${v.name}`"
        >
          <span class="verdict-mark">{{ v.ok ? '✓' : '✗' }}</span>
          <span class="verdict-name">{{ v.name }}</span>
          <span v-if="!v.ok" class="verdict-msg small">
            {{ v.violations.map(x => x.message).join('; ') }}
          </span>
        </div>
      </div>

      <div v-if="result.violations && result.violations.length" class="violations">
        <h4>Why it was refused</h4>
        <div
          v-for="(viol, i) in result.violations"
          :key="i"
          class="violation"
          data-testid="violation"
        >
          <span class="viol-cat">{{ viol.category }}</span>
          <span v-if="viol.path" class="viol-path mono small">{{ viol.path }}</span>
          <div class="viol-msg small">{{ viol.message }}</div>
        </div>
      </div>

      <div v-if="result.env" class="env" data-testid="check-env">
        <h4>Executed — bound environment</h4>
        <dl class="policy-dl">
          <template v-for="(val, key) in result.env" :key="key">
            <dt class="mono small">{{ key }}</dt>
            <dd class="mono small">{{ val }}</dd>
          </template>
        </dl>
      </div>

      <p v-if="result.status === 'error'" class="error">{{ result.error }}</p>
    </div>
  </div>
</template>

<style scoped>
.val-view {
  padding: 1.5rem;
  height: 100%;
  overflow-y: auto;
  max-width: 64rem;
  margin: 0 auto;
}
.val-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.5rem;
  gap: 1rem;
  flex-wrap: wrap;
}
.val-title-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.val-title {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}
.info-btn {
  width: 1.25rem;
  height: 1.25rem;
  border-radius: 9999px;
  border: 1px solid var(--border-color);
  background: var(--bg-tertiary);
  color: var(--text-secondary);
  font-size: 0.75rem;
  font-style: italic;
  font-weight: 700;
  font-family: Georgia, serif;
  line-height: 1;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
}
.info-btn:hover { background: var(--accent-bg); color: var(--accent-color); }
.info-card {
  position: relative;
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  border-left: 3px solid var(--accent-color);
  border-radius: 8px;
  padding: 1rem 1.25rem;
  margin-bottom: 1rem;
}
.info-card h3 {
  font-size: 0.9375rem;
  font-weight: 600;
  margin: 0 0 0.5rem 0;
  color: var(--text-primary);
}
.info-card p {
  font-size: 0.8125rem;
  line-height: 1.55;
  color: var(--text-secondary);
  margin: 0 0 0.625rem 0;
}
.info-card ul {
  margin: 0 0 0.625rem 0;
  padding-left: 1.1rem;
  font-size: 0.8125rem;
  line-height: 1.55;
  color: var(--text-secondary);
}
.info-card li { margin-bottom: 0.25rem; }
.info-card code {
  background: var(--code-bg);
  padding: 0.0625rem 0.3rem;
  border-radius: 3px;
  font-family: ui-monospace, Menlo, monospace;
  font-size: 0.85em;
}
.info-close {
  position: absolute;
  top: 0.5rem;
  right: 0.625rem;
  background: none;
  border: none;
  color: var(--text-tertiary);
  font-size: 1.125rem;
  line-height: 1;
  cursor: pointer;
}
.info-close:hover { color: var(--text-primary); }
.solver-badge {
  font-size: 0.75rem;
  color: var(--text-secondary);
  background: var(--bg-tertiary);
  border: 1px solid var(--border-color);
  padding: 0.125rem 0.5rem;
  border-radius: 9999px;
}
.val-intro {
  font-size: 0.8125rem;
  color: var(--text-secondary);
  line-height: 1.5;
  margin: 0 0 1rem 0;
}
.panels {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1rem;
  margin-bottom: 1.25rem;
}
@media (max-width: 640px) { .panels { grid-template-columns: 1fr; } }
.panel {
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 0.875rem 1rem;
}
.panel h3 {
  font-size: 0.8125rem;
  font-weight: 600;
  margin: 0 0 0.625rem 0;
  color: var(--text-primary);
}
.chain { display: flex; flex-wrap: wrap; align-items: center; gap: 0.375rem; }
.chain-stage {
  font-family: ui-monospace, Menlo, monospace;
  font-size: 0.75rem;
  background: var(--accent-bg);
  color: var(--accent-color);
  padding: 0.125rem 0.5rem;
  border-radius: 6px;
}
.chain-arrow { color: var(--text-tertiary); font-size: 0.75rem; }
.policy-dl {
  display: grid;
  grid-template-columns: 7rem 1fr;
  gap: 0.25rem 0.75rem;
  font-size: 0.8125rem;
  margin: 0;
}
.policy-dl dt { color: var(--text-tertiary); }
.policy-dl dd { margin: 0; color: var(--text-secondary); word-break: break-word; }
.run-bar { display: flex; flex-wrap: wrap; gap: 0.5rem; margin-bottom: 0.75rem; }
.example-btn {
  font-size: 0.8125rem;
  color: var(--text-primary);
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  padding: 0.375rem 0.75rem;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.12s;
}
.example-btn:hover:not(:disabled) { background: var(--bg-hover); }
.goal-row { display: flex; gap: 0.5rem; margin-bottom: 1.25rem; }
.goal-input {
  flex: 1;
  font-size: 0.875rem;
  padding: 0.4rem 0.625rem;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  background: var(--bg-surface);
  color: var(--text-primary);
}
.run-btn {
  font-size: 0.8125rem;
  font-weight: 500;
  color: var(--bg-surface);
  background: var(--accent-color);
  border: none;
  padding: 0.4rem 1rem;
  border-radius: 6px;
  cursor: pointer;
}
.run-btn:disabled { opacity: 0.5; cursor: default; }
.result {
  background: var(--bg-surface);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 1rem 1.25rem;
}
.result-head { display: flex; align-items: center; gap: 0.625rem; margin-bottom: 0.875rem; }
.status-badge {
  font-size: 0.6875rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  padding: 0.125rem 0.5rem;
  border-radius: 9999px;
}
.status-badge.executed { background: #1b5e20; color: #fff; }
.status-badge.refused { background: #b71c1c; color: #fff; }
.status-badge.error { background: #e65100; color: #fff; }
.result h4 {
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--text-tertiary);
  margin: 1rem 0 0.5rem 0;
}
.plan-steps { margin: 0; padding-left: 1.25rem; line-height: 1.7; }
.step-tool { color: var(--accent-color); }
.step-bind { color: var(--text-tertiary); }
.verdict {
  display: flex;
  align-items: baseline;
  gap: 0.5rem;
  padding: 0.25rem 0;
  font-size: 0.8125rem;
}
.verdict-mark { font-weight: 700; width: 1rem; }
.verdict.ok .verdict-mark { color: #2e7d32; }
.verdict.bad .verdict-mark { color: #c62828; }
.verdict-name { font-family: ui-monospace, Menlo, monospace; }
.verdict-msg { color: var(--text-secondary); }
.violation {
  border-left: 3px solid #c62828;
  padding: 0.375rem 0.75rem;
  margin-bottom: 0.5rem;
  background: var(--bg-tertiary);
  border-radius: 0 6px 6px 0;
}
.viol-cat {
  font-size: 0.6875rem;
  font-weight: 700;
  text-transform: uppercase;
  color: #c62828;
  margin-right: 0.5rem;
}
.viol-path { color: var(--text-tertiary); }
.viol-msg { color: var(--text-secondary); margin-top: 0.25rem; }
.mono { font-family: ui-monospace, Menlo, monospace; }
.small { font-size: 0.8125rem; }
.error { color: #c62828; font-size: 0.875rem; padding: 0.5rem 0; }
</style>
