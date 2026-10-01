import { describe, it, expect } from 'vitest'
import {
  GOVERNANCE_FEEDBACK_INJECTED_KEY,
  GOVERNANCE_FEEDBACK_LINES_KEY,
  mergeGovernanceFeedback,
} from './governanceFeedback'

describe('mergeGovernanceFeedback', () => {
  it('folds the count and lines frames into one per-turn record', () => {
    let state = mergeGovernanceFeedback(null, { [GOVERNANCE_FEEDBACK_INJECTED_KEY]: 1 })
    state = mergeGovernanceFeedback(state, {
      [GOVERNANCE_FEEDBACK_LINES_KEY]: ['Prefer: run release-bot (change management)'],
    })
    expect(state).toEqual({
      injected: 1,
      lines: ['Prefer: run release-bot (change management)'],
    })
  })

  it('ignores metadata frames that carry neither key', () => {
    const current = { injected: 1, lines: ['x'] }
    expect(mergeGovernanceFeedback(current, { 'routing.model': 'qwen' })).toBe(current)
    expect(mergeGovernanceFeedback(null, { 'routing.model': 'qwen' })).toBeNull()
    expect(mergeGovernanceFeedback(null, undefined)).toBeNull()
  })

  it('treats a zero count and an empty list as no guidance', () => {
    expect(mergeGovernanceFeedback(null, { [GOVERNANCE_FEEDBACK_INJECTED_KEY]: 0 })).toBeNull()
    expect(mergeGovernanceFeedback(null, { [GOVERNANCE_FEEDBACK_LINES_KEY]: [] })).toBeNull()
  })

  it('drops non-string and blank lines and bounds what it keeps', () => {
    const lines = [42, ' ', 'kept', ...Array.from({ length: 80 }, () => 'y'.repeat(5000))]
    const state = mergeGovernanceFeedback(null, { [GOVERNANCE_FEEDBACK_LINES_KEY]: lines })
    expect(state?.lines[0]).toBe('kept')
    expect(state?.lines).toHaveLength(50)
    expect(state?.lines[1]).toHaveLength(1024)
  })
})
