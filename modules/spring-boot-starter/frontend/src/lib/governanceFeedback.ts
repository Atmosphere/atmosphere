/**
 * Governance guidance the server injected into the current turn's system
 * prompt, reported by GovernanceFeedbackInterceptor as two `metadata` frames
 * sent just before the turn's `complete` frame:
 *   - `ai.governance.feedback.injected` — how many lines were injected
 *   - `ai.governance.feedback.lines`    — the lines themselves
 * The server sends neither frame when it injected nothing, so a turn without
 * guidance leaves this state null.
 */
export interface GovernanceFeedback {
  injected: number
  lines: string[]
}

export const GOVERNANCE_FEEDBACK_INJECTED_KEY = 'ai.governance.feedback.injected'
export const GOVERNANCE_FEEDBACK_LINES_KEY = 'ai.governance.feedback.lines'

// The server already caps both (maxItems lines, 512 chars each); these bounds
// only keep a misbehaving server from growing the DOM without limit.
const MAX_LINES = 50
const MAX_LINE_CHARS = 1024

/**
 * Merge one normalized metadata payload into the turn's governance feedback.
 * Pure — returns the current value untouched when the payload carries neither
 * key, so routing and other metadata frames pass through without effect.
 */
export function mergeGovernanceFeedback(
  current: GovernanceFeedback | null,
  data: Record<string, unknown> | undefined | null,
): GovernanceFeedback | null {
  if (!data) return current
  const count = data[GOVERNANCE_FEEDBACK_INJECTED_KEY]
  const rawLines = data[GOVERNANCE_FEEDBACK_LINES_KEY]
  const hasCount = typeof count === 'number' && Number.isFinite(count) && count > 0
  const hasLines = Array.isArray(rawLines)
  if (!hasCount && !hasLines) return current
  const next: GovernanceFeedback = current
    ? { injected: current.injected, lines: [...current.lines] }
    : { injected: 0, lines: [] }
  if (hasCount) {
    next.injected = Math.floor(count as number)
  }
  if (hasLines) {
    next.lines = (rawLines as unknown[])
      .filter((l): l is string => typeof l === 'string' && l.trim() !== '')
      .slice(0, MAX_LINES)
      .map(l => (l.length > MAX_LINE_CHARS ? `${l.slice(0, MAX_LINE_CHARS - 1)}…` : l))
  }
  return next.injected > 0 || next.lines.length > 0 ? next : current
}
