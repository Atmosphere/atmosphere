import { describe, expect, it } from 'vitest'
import { formatCost, mergeRoutingMetadata, normalizeMetadataFrame } from './routingMetadata'

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

/**
 * Pins the Atmosphere wire shape: DefaultStreamingSession.sendMetadata emits
 * {"type":"metadata","key":"routing.model","value":...,"sessionId":...,"seq":N}
 * — the key/value pair is TOP-LEVEL, not nested under `data`. The Console
 * must fold that pair into a keyed record before merging, or server metadata
 * never reaches the routing chips.
 */
describe('normalizeMetadataFrame', () => {
  it('folds the real wire frame (top-level key/value) into a keyed record', () => {
    const frame: Record<string, unknown> = {
      type: 'metadata',
      key: 'routing.model',
      value: 'demo-model',
      sessionId: 'abc-123',
      seq: 3,
    }
    expect(normalizeMetadataFrame(frame)).toEqual({ 'routing.model': 'demo-model' })
  })

  it('feeds mergeRoutingMetadata end-to-end from a sequence of real frames', () => {
    const frames: Record<string, unknown>[] = [
      { type: 'metadata', key: 'routing.model', value: 'demo-model', sessionId: 's', seq: 1 },
      { type: 'metadata', key: 'routing.cost', value: 0.000123, sessionId: 's', seq: 2 },
      { type: 'metadata', key: 'routing.latency', value: 42, sessionId: 's', seq: 3 },
    ]
    let routing = {}
    for (const frame of frames) {
      routing = mergeRoutingMetadata(routing, normalizeMetadataFrame(frame))
    }
    expect(routing).toEqual({ model: 'demo-model', cost: 0.000123, latency: 42 })
  })

  it('falls back to a data payload map when no top-level key is present', () => {
    expect(normalizeMetadataFrame({ type: 'metadata', data: { 'routing.cost': 0.5 } }))
      .toEqual({ 'routing.cost': 0.5 })
  })

  it('returns undefined when neither shape is present', () => {
    expect(normalizeMetadataFrame({ type: 'metadata' })).toBeUndefined()
    expect(normalizeMetadataFrame({ type: 'metadata', key: '', data: 'oops' }))
      .toBeUndefined()
  })
})

describe('formatCost', () => {
  it('keeps precision for sub-cent costs', () => {
    expect(formatCost(0.000123)).toBe('$0.000123')
    expect(formatCost(0.25)).toBe('$0.2500')
  })
})
