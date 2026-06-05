import { ref } from 'vue'

/** One stage of the verifier chain (allowlist, taint, capability, automaton, smt, …). */
export interface VerifierStage {
  name: string
  priority: number
}

/** A single policy violation surfaced by a verifier. */
export interface Violation {
  category: string
  message: string
  path?: string
}

/** Per-verifier verdict produced by a check run. */
export interface VerifierVerdict {
  name: string
  ok: boolean
  violations: Violation[]
}

export interface PolicySummary {
  name: string
  allowedTools: string[]
  taintRuleCount: number
  numericInvariants: string[]
}

export interface VerifierSummary {
  verifiers: VerifierStage[]
  smtSolver: string
  policy: PolicySummary
  hasExamples: boolean
}

export interface VerifierExample {
  id: string
  label: string
  goal: string
  description: string
}

export interface PlanStep {
  label: string
  tool?: string
  arguments?: Record<string, unknown>
  binding?: string
}

export interface PlanView {
  goal: string
  steps: PlanStep[]
}

export interface CheckResult {
  goal: string
  status: 'executed' | 'refused' | 'error'
  plan?: PlanView
  verifiers?: VerifierVerdict[]
  violations?: Violation[]
  env?: Record<string, unknown>
  error?: string
}

/**
 * Client for the read-only plan-and-verify admin surface
 * ({@code /api/admin/verifier/**}). The Validation tab calls {@link probe} on
 * mount to decide whether to render, then {@link check}s goals on demand. No
 * polling — verification is a user-triggered action, not a background gauge.
 */
export function useVerifier() {
  const summary = ref<VerifierSummary | null>(null)
  const examples = ref<VerifierExample[]>([])
  const available = ref(false)
  const error = ref<string | null>(null)
  const running = ref(false)
  const result = ref<CheckResult | null>(null)

  async function probe(): Promise<boolean> {
    try {
      const res = await fetch('/api/admin/verifier/summary', {
        headers: { Accept: 'application/json' },
      })
      if (!res.ok) return false
      summary.value = (await res.json()) as VerifierSummary
      available.value = true
      if (summary.value.hasExamples) {
        const exRes = await fetch('/api/admin/verifier/examples', {
          headers: { Accept: 'application/json' },
        })
        if (exRes.ok) {
          examples.value = (await exRes.json()) as VerifierExample[]
        }
      }
      return true
    } catch {
      // atmosphere-verifier not wired on this host — hide the tab.
      return false
    }
  }

  async function check(goal: string): Promise<void> {
    running.value = true
    error.value = null
    try {
      const res = await fetch('/api/admin/verifier/check', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ goal }),
      })
      if (!res.ok) {
        throw new Error(`HTTP ${res.status} ${res.statusText}`)
      }
      result.value = (await res.json()) as CheckResult
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      result.value = null
    } finally {
      running.value = false
    }
  }

  return { summary, examples, available, error, running, result, probe, check }
}
