/**
 * Cost/latency routing readout extracted from the server's `metadata`
 * events. The routing layer publishes `routing.model` / `routing.cost` /
 * `routing.latency` keys (see atmosphere.js RoutingInfo); a plain `model`
 * key from the runtime is used when no routed model was reported.
 */
export interface RoutingMetadata {
  model?: string
  cost?: number
  latency?: number
}

/**
 * Normalize a `metadata` event into a keyed payload map. The Atmosphere wire
 * frame carries the pair top-level — `{type:'metadata', key:'routing.model',
 * value:..., sessionId, seq}` (see DefaultStreamingSession) — so the key/value
 * pair is folded into a single-entry record. Frames without a top-level key
 * fall back to a `data` payload object when one is present.
 */
export function normalizeMetadataFrame(
  msg: Record<string, unknown>,
): Record<string, unknown> | undefined {
  if (typeof msg.key === 'string' && msg.key) {
    return { [msg.key]: msg.value }
  }
  return typeof msg.data === 'object' && msg.data !== null
    ? (msg.data as Record<string, unknown>)
    : undefined
}

/**
 * Merge one metadata event payload into the current routing readout.
 * Pure — returns a new object (Vue reactivity replaces the ref value).
 */
export function mergeRoutingMetadata(
  current: RoutingMetadata,
  data: Record<string, unknown> | undefined | null,
): RoutingMetadata {
  if (!data) return current
  const next = { ...current }
  const routedModel = data['routing.model']
  if (typeof routedModel === 'string' && routedModel) {
    next.model = routedModel
  } else if (typeof data.model === 'string' && data.model && !next.model) {
    next.model = data.model
  }
  const cost = data['routing.cost']
  if (typeof cost === 'number' && Number.isFinite(cost)) {
    next.cost = cost
  }
  const latency = data['routing.latency']
  if (typeof latency === 'number' && Number.isFinite(latency)) {
    next.latency = latency
  }
  return next
}

/** $0.000123-style display — sub-cent costs keep enough precision to read. */
export function formatCost(cost: number): string {
  return cost < 0.01 ? `$${cost.toFixed(6)}` : `$${cost.toFixed(4)}`
}
