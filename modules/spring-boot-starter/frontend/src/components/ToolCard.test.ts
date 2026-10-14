// @vitest-environment jsdom
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ToolCard from './ToolCard.vue'
import { formatToolArg } from '../lib/toolArgs'
import type { ToolCall } from '../composables/useAtmosphereChat'

/**
 * The argument preview stringified every value with String(), so a
 * write_todos call carrying an array of todo objects rendered as
 * `todos:[object Object]`. Non-primitive values must be serialized.
 */

function argValues(tool: ToolCall): Record<string, string> {
  const wrapper = mount(ToolCard, { props: { tool } })
  const values: Record<string, string> = {}
  for (const row of wrapper.findAll('.tool-arg')) {
    values[row.find('.arg-key').text().replace(/:$/, '')] = row.find('.arg-val').text()
  }
  return values
}

describe('ToolCard argument preview', () => {
  it('serializes an array of objects instead of printing [object Object]', () => {
    const values = argValues({
      id: 't1',
      name: 'write_todos',
      done: true,
      args: {
        todos: [{ content: 'Check refund policy', status: 'completed' }],
        goal: 'refund',
      },
    })

    expect(values.todos).not.toContain('[object Object]')
    expect(values.todos).toBe('[{"content":"Check refund policy","status":"completed"}]')
    expect(values.goal).toBe('refund')
  })

  it('serializes a nested object argument', () => {
    const values = argValues({
      id: 't2', name: 'lookup', done: false,
      args: { filter: { customer: 'Alice', open: true } },
    })
    expect(values.filter).toBe('{"customer":"Alice","open":true}')
  })

  it('keeps primitives as they were and truncates long values', () => {
    expect(formatToolArg('plain')).toBe('plain')
    expect(formatToolArg(42)).toBe('42')
    expect(formatToolArg(false)).toBe('false')
    expect(formatToolArg(null)).toBe('null')
    const long = formatToolArg(Array.from({ length: 100 }, (_, i) => ({ i })))
    expect(long.length).toBe(121)
    expect(long.endsWith('…')).toBe(true)
  })

  it('shows a placeholder for a value JSON cannot serialize', () => {
    const cyclic: Record<string, unknown> = {}
    cyclic.self = cyclic
    expect(formatToolArg(cyclic)).toBe('{…}')
    const cyclicList: unknown[] = []
    cyclicList.push(cyclicList)
    expect(formatToolArg(cyclicList)).toBe('[…]')
    expect(formatToolArg(10n)).toBe('10')
  })
})
