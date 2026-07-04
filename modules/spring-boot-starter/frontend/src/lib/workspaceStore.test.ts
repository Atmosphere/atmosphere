import { describe, it, expect, beforeEach } from 'vitest'
import { livePlan, recordPlanUpdate } from './workspaceStore'

/**
 * Pins the `plan-update` wire-event parsing the Workspace tab renders from:
 * `{steps: [{content, status, activeForm}], goal}` with full-list-replace
 * semantics, lenient on malformed entries (a bad step is dropped, never
 * crashes the stream handler).
 */
describe('recordPlanUpdate', () => {
  beforeEach(() => {
    livePlan.value = null
  })

  it('records steps, goal and defaults missing statuses to pending', () => {
    recordPlanUpdate({
      goal: 'Ship the feature',
      steps: [
        { content: 'Write code', status: 'completed' },
        { content: 'Run tests', status: 'in_progress', activeForm: 'Running tests' },
        { content: 'Update docs' },
      ],
    })
    expect(livePlan.value).not.toBeNull()
    expect(livePlan.value!.goal).toBe('Ship the feature')
    expect(livePlan.value!.steps).toHaveLength(3)
    expect(livePlan.value!.steps[0]).toMatchObject({ content: 'Write code', status: 'completed' })
    expect(livePlan.value!.steps[1].activeForm).toBe('Running tests')
    expect(livePlan.value!.steps[2].status).toBe('pending')
  })

  it('replaces the previous plan wholesale (full-list replace)', () => {
    recordPlanUpdate({ steps: [{ content: 'Old step', status: 'pending' }] })
    recordPlanUpdate({ steps: [{ content: 'New step', status: 'pending' }] })
    expect(livePlan.value!.steps).toHaveLength(1)
    expect(livePlan.value!.steps[0].content).toBe('New step')
  })

  it('drops malformed steps instead of crashing the stream handler', () => {
    recordPlanUpdate({
      steps: [
        { content: 'Good step', status: 'pending' },
        { status: 'pending' },
        'not-an-object',
        null,
      ],
    })
    expect(livePlan.value!.steps).toHaveLength(1)
    expect(livePlan.value!.steps[0].content).toBe('Good step')
  })

  it('treats a missing goal as null and missing steps as empty', () => {
    recordPlanUpdate({})
    expect(livePlan.value!.goal).toBeNull()
    expect(livePlan.value!.steps).toEqual([])
  })

  it('ignores non-object payloads entirely', () => {
    recordPlanUpdate('garbage')
    recordPlanUpdate(null)
    recordPlanUpdate(undefined)
    expect(livePlan.value).toBeNull()
  })
})
