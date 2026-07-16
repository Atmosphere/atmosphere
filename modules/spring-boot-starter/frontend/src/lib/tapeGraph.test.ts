import { describe, it, expect } from 'vitest'
import { buildTapeGraph, depthOf, shortId, firstUser, NODE_W, Y_GAP, type ReplayedRun, type ReplayTree } from './tapeGraph'

function run(runId: string, parentRunId: string | null, extra: Partial<ReplayedRun> = {}): ReplayedRun {
  return {
    runId,
    parentRunId,
    status: 'COMPLETED',
    model: 'demo-model',
    runtime: 'demo',
    input: [{ role: 'user', content: `prompt for ${runId}` }],
    output: `output of ${runId}`,
    tools: [],
    ...extra,
  }
}

function tree(root: ReplayedRun, children: ReplayedRun[]): ReplayTree {
  return { present: true, runCount: 1 + children.length, root, children }
}

describe('buildTapeGraph', () => {
  it('places the coordinator at level 0 and its fan-out agents on level 1, linked by edges', () => {
    const t = tree(run('root', null), [run('a', 'root'), run('b', 'root')])
    const { nodes, edges } = buildTapeGraph(t)

    expect(nodes).toHaveLength(3)
    const byId = new Map(nodes.map((n) => [n.id, n]))
    expect(byId.get('root')!.data!.role).toBe('coordinator')
    expect(byId.get('a')!.data!.role).toBe('agent')
    expect(byId.get('b')!.data!.role).toBe('agent')

    // root at y=0, children one level below
    expect(byId.get('root')!.position.y).toBe(0)
    expect(byId.get('a')!.position.y).toBe(Y_GAP)
    expect(byId.get('b')!.position.y).toBe(Y_GAP)
    // two siblings are centred: their node-centres mirror around x=0
    const aCentre = byId.get('a')!.position.x + NODE_W / 2
    const bCentre = byId.get('b')!.position.x + NODE_W / 2
    expect(aCentre).toBe(-bCentre)

    // one dispatch edge per child, parent -> child
    expect(edges.map((e) => e.id).sort()).toEqual(['root->a', 'root->b'])
    expect(edges.every((e) => e.source === 'root')).toBe(true)
  })

  it('nests a grand-child on a third level and edges it to its real parent', () => {
    const t = tree(run('root', null), [run('a', 'root'), run('a1', 'a')])
    const { nodes, edges } = buildTapeGraph(t)

    const byId = new Map(nodes.map((n) => [n.id, n]))
    expect(byId.get('root')!.position.y).toBe(0)
    expect(byId.get('a')!.position.y).toBe(Y_GAP)
    expect(byId.get('a1')!.position.y).toBe(2 * Y_GAP)
    expect(edges.map((e) => e.id).sort()).toEqual(['a->a1', 'root->a'])
  })

  it('does not emit an edge for a parentRunId that is not in the tree', () => {
    const t = tree(run('root', null), [run('orphan', 'missing-parent')])
    const { edges } = buildTapeGraph(t)
    expect(edges).toHaveLength(0)
  })

  it('is cycle-safe when parent links form a loop', () => {
    // pathological: a<->b point at each other; depthOf must terminate
    const byId = new Map<string, ReplayedRun>([
      ['a', run('a', 'b')],
      ['b', run('b', 'a')],
    ])
    expect(() => depthOf('a', 'root', byId)).not.toThrow()
    expect(Number.isFinite(depthOf('a', 'root', byId))).toBe(true)
  })
})

describe('helpers', () => {
  it('shortId truncates to 8 chars and renders a dash for null', () => {
    expect(shortId('0123456789abcdef')).toBe('01234567')
    expect(shortId('short')).toBe('short')
    expect(shortId(null)).toBe('—')
  })

  it('firstUser prefers the user message, else the last input', () => {
    expect(firstUser(run('r', null, { input: [{ role: 'system', content: 's' }, { role: 'user', content: 'u' }] }))).toBe('u')
    expect(firstUser(run('r', null, { input: [{ role: 'assistant', content: 'only' }] }))).toBe('only')
    expect(firstUser(run('r', null, { input: [] }))).toBe('')
  })
})
