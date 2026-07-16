import { describe, expect, it } from 'vitest'
import { formatCost, mergeRoutingMetadata } from './routingMetadata'

/**
 * Pins the routing-chip data extraction: the server's metadata events carry
 * routing.model / routing.cost / routing.latency keys (mirroring
 * atmosphere.js RoutingInfo); a bare `model` only fills the gap when no
 * routed model has been reported.
 */
describe('mergeRoutingMetadata', () => {
  it('extracts the routing.* keys', () => {
    expect(mergeRoutingMetadata({}, {
      'routing.model': 'gemini-2.5-flash',
      'routing.cost': 0.0021,
      'routing.latency': 840,
    })).toEqual({ model: 'gemini-2.5-flash', cost: 0.0021, latency: 840 })
  })

  it('falls back to the bare model key only when no routed model exists', () => {
    expect(mergeRoutingMetadata({}, { model: 'built-in' })).toEqual({ model: 'built-in' })
    expect(mergeRoutingMetadata({ model: 'routed' }, { model: 'other' }))
      .toEqual({ model: 'routed' })
  })

  it('accumulates across events without losing earlier fields', () => {
    const first = mergeRoutingMetadata({}, { 'routing.model': 'm1' })
    const second = mergeRoutingMetadata(first, { 'routing.cost': 0.5 })
    expect(second).toEqual({ model: 'm1', cost: 0.5 })
  })

  it('ignores malformed values and empty payloads', () => {
    expect(mergeRoutingMetadata({ cost: 1 }, { 'routing.cost': 'free' })).toEqual({ cost: 1 })
    expect(mergeRoutingMetadata({ cost: 1 }, null)).toEqual({ cost: 1 })
  })
})

describe('formatCost', () => {
  it('keeps precision for sub-cent costs', () => {
    expect(formatCost(0.000123)).toBe('$0.000123')
    expect(formatCost(0.25)).toBe('$0.2500')
  })
})
