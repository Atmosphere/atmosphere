// Typed client for the Atmosphere Interactions API (/api/interactions).
// Mirrors the org.atmosphere.interactions records: an Interaction carries a
// durable steps[] log and chains via parentId (previous_interaction_id).

export type InteractionStatus =
  | 'CREATED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

export interface InteractionStep {
  seq: number
  type: string
  text: string | null
  toolName: string | null
  data: Record<string, unknown>
  usage: TokenUsage | null
  createdAt: string
}

export interface TokenUsage {
  input: number
  output: number
  cachedInput: number
  total: number
  model: string | null
}

export interface Interaction {
  id: string
  parentId: string | null
  conversationId: string
  agentId: string | null
  userId: string
  model: string | null
  status: InteractionStatus
  background: boolean
  store: boolean
  steps: InteractionStep[]
  finalText: string | null
  usage: TokenUsage | null
  errorMessage: string | null
  createdAt: string
  updatedAt: string
}

export interface CreateBody {
  message: string
  background?: boolean
  previousInteractionId?: string
}

const BASE = '/api/interactions'
const JSON_HEADERS = { 'Content-Type': 'application/json', Accept: 'application/json' }

async function asJson<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let detail = `${res.status} ${res.statusText}`
    try {
      const body = await res.json()
      if (body && typeof body.error === 'string') detail = body.error
    } catch {
      // non-JSON error body — keep the status line
    }
    throw new Error(detail)
  }
  return (await res.json()) as T
}

/** Launch an interaction. {@code background:true} returns immediately as RUNNING. */
export async function createInteraction(body: CreateBody): Promise<Interaction> {
  const res = await fetch(BASE, {
    method: 'POST', headers: JSON_HEADERS, credentials: 'same-origin',
    body: JSON.stringify(body),
  })
  return asJson<Interaction>(res)
}

/** Continue an existing interaction with a new turn (chains via previous_interaction_id). */
export async function continueInteraction(id: string, body: CreateBody): Promise<Interaction> {
  const res = await fetch(`${BASE}/${encodeURIComponent(id)}/continue`, {
    method: 'POST', headers: JSON_HEADERS, credentials: 'same-origin',
    body: JSON.stringify(body),
  })
  return asJson<Interaction>(res)
}

/** Retrieve one interaction (with its full durable steps[]). */
export async function getInteraction(id: string): Promise<Interaction> {
  const res = await fetch(`${BASE}/${encodeURIComponent(id)}`, {
    headers: { Accept: 'application/json' }, credentials: 'same-origin',
  })
  return asJson<Interaction>(res)
}

/** List the caller's interactions (ownership-scoped server-side). */
export async function listInteractions(): Promise<Interaction[]> {
  const res = await fetch(BASE, {
    headers: { Accept: 'application/json' }, credentials: 'same-origin',
  })
  return asJson<Interaction[]>(res)
}

/** Request cancellation of an in-flight background interaction. */
export async function cancelInteraction(id: string): Promise<boolean> {
  const res = await fetch(`${BASE}/${encodeURIComponent(id)}/cancel`, {
    method: 'POST', headers: { Accept: 'application/json' }, credentials: 'same-origin',
  })
  return res.ok
}
