/**
 * A member of a coordinator's agent fleet, as reported by the runtime-truth
 * admin API (/api/admin/coordinators/{name}/fleet — AgentProxy state).
 */
export interface FleetAgent {
  name: string
  version?: string
  isAvailable?: boolean
  isLocal?: boolean
  weight?: number
}

/** A coordinator and its live fleet roster. */
export interface FleetInfo {
  name: string
  agents: FleetAgent[]
}

/**
 * Deterministic display hue for an agent name — stable across sessions and
 * agents without any per-sample configuration. Spread across the wheel via
 * a small string hash; saturation/lightness fixed for legibility on the
 * console's surfaces.
 */
export function agentColor(name: string): string {
  let hash = 0
  for (let i = 0; i < name.length; i++) {
    hash = ((hash << 5) - hash + name.charCodeAt(i)) | 0
  }
  const hue = ((hash % 360) + 360) % 360
  return `hsl(${hue}, 62%, 46%)`
}
