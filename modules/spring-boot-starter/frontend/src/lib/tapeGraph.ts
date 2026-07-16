import type { Node, Edge } from '@vue-flow/core'

/**
 * Pure derivation of a Vue Flow node-graph from a replayed tape coordination
 * tree. Kept out of the component so the layout (which node sits where, which
 * edges connect) is unit-testable without mounting Vue Flow (whose rendering
 * needs a real DOM). The placement is a deterministic BFS-by-level: the root at
 * y=0, each level spread horizontally and centred, so reloads are stable.
 */
export interface ReplayedMessage { role: string; content: string }
export interface ReplayedTool { name: string; arguments: string }
export interface ReplayedRun {
  runId: string
  parentRunId: string | null
  status: string | null
  model: string | null
  runtime: string | null
  input: ReplayedMessage[]
  output: string
  tools: ReplayedTool[]
}
export interface ReplayTree { present: boolean; runCount: number; root: ReplayedRun; children: ReplayedRun[] }

export interface RunNodeData { run: ReplayedRun; role: 'coordinator' | 'agent' }

export const NODE_W = 240
export const X_GAP = 56
export const Y_GAP = 200

/** Depth of a run from the root, walking parentRunId (cycle-guarded). */
export function depthOf(runId: string, rootId: string, byId: Map<string, ReplayedRun>): number {
  let depth = 0
  let cur: string | null = runId
  const seen = new Set<string>()
  while (cur && cur !== rootId && byId.has(cur) && !seen.has(cur)) {
    seen.add(cur)
    cur = byId.get(cur)!.parentRunId
    depth++
  }
  return depth
}

export function buildTapeGraph(tree: ReplayTree): { nodes: Node<RunNodeData>[]; edges: Edge[] } {
  const all = [tree.root, ...tree.children]
  const byId = new Map(all.map((r) => [r.runId, r]))
  const rootId = tree.root.runId

  const level = new Map<string, number>()
  for (const r of all) level.set(r.runId, depthOf(r.runId, rootId, byId))

  const perLevel = new Map<number, string[]>()
  for (const [id, lv] of level) {
    if (!perLevel.has(lv)) perLevel.set(lv, [])
    perLevel.get(lv)!.push(id)
  }

  const nodes: Node<RunNodeData>[] = all.map((r) => {
    const lv = level.get(r.runId) ?? 0
    const peers = perLevel.get(lv)!
    const idx = peers.indexOf(r.runId)
    const rowWidth = peers.length * (NODE_W + X_GAP) - X_GAP
    return {
      id: r.runId,
      type: 'run',
      position: { x: idx * (NODE_W + X_GAP) - rowWidth / 2, y: lv * Y_GAP },
      data: { run: r, role: r.runId === rootId ? 'coordinator' : 'agent' },
      draggable: true,
      selectable: true,
    }
  })

  const edges: Edge[] = all
    .filter((r) => r.parentRunId && byId.has(r.parentRunId) && r.runId !== rootId)
    .map((r) => ({
      id: `${r.parentRunId}->${r.runId}`,
      source: r.parentRunId as string,
      target: r.runId,
      type: 'smoothstep',
    }))

  return { nodes, edges }
}

export function shortId(id: string | null): string {
  if (!id) return '—'
  return id.length > 8 ? id.slice(0, 8) : id
}

export function firstUser(run: ReplayedRun): string {
  const u = run.input.find((m) => m.role === 'user') ?? run.input[run.input.length - 1]
  return u ? u.content : ''
}
