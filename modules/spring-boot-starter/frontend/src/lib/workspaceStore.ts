import { ref } from 'vue'

/**
 * One step of the agent's plan, in the exact wire shape the server emits for
 * `plan-update` streaming events and `/api/admin/agents/{name}/plan` snapshots
 * (AgentPlan.toWireSteps(): lower-cased status, optional activeForm).
 */
export interface PlanStep {
  content: string
  status: 'pending' | 'in_progress' | 'completed' | 'abandoned' | string
  activeForm?: string
}

/** The latest plan received over the live chat connection. */
export interface LivePlan {
  goal: string | null
  steps: PlanStep[]
  /**
   * The conversation scope the plan store keyed this plan on — carried on the
   * `plan-update` event itself (the frame's top-level sessionId is the
   * per-prompt streaming id, not the store key). Null on events from servers
   * that do not send it.
   */
  conversationId: string | null
  /** The plan owner (agent / endpoint name) the store keyed this plan on. */
  agentId: string | null
  updatedAt: number
}

/**
 * Module-scoped reactive holder for the live plan. `useAtmosphereChat`
 * writes into it on every `plan-update` event; the Workspace tab renders it.
 * Shared this way (rather than through props) because the chat composable is
 * owned by ChatContainer while the Workspace tab is a sibling component.
 */
export const livePlan = ref<LivePlan | null>(null)

/** Normalize one wire step object; returns null when the shape is off. */
function parseStep(raw: unknown): PlanStep | null {
  if (!raw || typeof raw !== 'object') return null
  const step = raw as Record<string, unknown>
  if (typeof step.content !== 'string' || !step.content) return null
  return {
    content: step.content,
    status: typeof step.status === 'string' && step.status ? step.status : 'pending',
    activeForm: typeof step.activeForm === 'string' && step.activeForm
      ? step.activeForm
      : undefined,
  }
}

/**
 * Record a `plan-update` event payload (`{steps, goal, conversationId,
 * agentId}` — full-list replace semantics, so each event overwrites the
 * previous plan wholesale). `conversationId`/`agentId` are the store scope
 * the Workspace tab uses for one-click correlation with the stored browser.
 */
export function recordPlanUpdate(data: unknown): void {
  if (!data || typeof data !== 'object') return
  const obj = data as Record<string, unknown>
  const steps = Array.isArray(obj.steps)
    ? obj.steps.map(parseStep).filter((s): s is PlanStep => s !== null)
    : []
  livePlan.value = {
    goal: typeof obj.goal === 'string' && obj.goal ? obj.goal : null,
    steps,
    conversationId: typeof obj.conversationId === 'string' && obj.conversationId
      ? obj.conversationId
      : null,
    agentId: typeof obj.agentId === 'string' && obj.agentId ? obj.agentId : null,
    updatedAt: Date.now(),
  }
}
