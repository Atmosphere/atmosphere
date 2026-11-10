/**
 * Wire shapes of the admin lineage routes (`LineageController` on the server)
 * and the reads the Lineage tab makes of them.
 */

export interface LineageToolCall {
  name: string
  invokedAt: string
  resultSummary: string
}

export interface LineageTokens {
  input: number
  output: number
  cachedInput: number
  total: number
  model?: string
}

export interface LineageEntry {
  requestId: string
  userId: string
  agentId: string
  conversationId: string
  sessionId: string
  timestamp: string
  userPrompt: string
  toolCalls: LineageToolCall[]
  ragSources: string[]
  tokens?: LineageTokens
  costUsd?: string
  terminalReason: string
  attributes: Record<string, string>
}

/** `GET /api/admin/ai/lineage/status`. */
export interface LineageStatus {
  recorder: string
  capacity?: number
  size?: number
  captureContent?: boolean
}

export interface Fingerprint {
  runtime?: string
  model?: string
  promptSha?: string
  toolsSha?: string
}

/** The `ai.agent.*` attributes (`AgentFingerprint`), or null when the turn carries none. */
export function fingerprintOf(entry: LineageEntry): Fingerprint | null {
  const a = entry.attributes ?? {}
  const fp: Fingerprint = {
    runtime: a['ai.agent.runtime'] || undefined,
    model: a['ai.agent.model'] || undefined,
    promptSha: a['ai.agent.system_prompt_sha256'] || undefined,
    toolsSha: a['ai.agent.tool_set_sha256'] || undefined,
  }
  return fp.runtime || fp.model || fp.promptSha || fp.toolsSha ? fp : null
}

/** First 12 hex characters of a SHA-256, enough to tell configurations apart on screen. */
export function shortHash(hash: string | undefined): string {
  return hash ? hash.slice(0, 12) : ''
}

/**
 * Whether the status names the one recorder `/api/admin/ai/lineage` reads
 * back. Any other answer (`none`, a persisting recorder's class name, a
 * malformed body) leaves the tab off: it would only ever show an empty list.
 */
export function servesEntries(status: unknown): status is LineageStatus {
  return typeof status === 'object' && status !== null
    && (status as { recorder?: unknown }).recorder === 'in-memory'
}
