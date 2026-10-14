// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { Interaction } from '../composables/useInteractions'

/**
 * The history list polls /api/interactions and the detail pane follows the
 * selected run on its own (live socket, then a fetch on the terminal frame).
 * Until the list's next poll the row beside a COMPLETED detail pane still
 * read RUNNING. The detail pane's fresh copy must update the matching row,
 * and a list poll must never move a finished run back to RUNNING.
 */

const api = vi.hoisted(() => ({
  listInteractions: vi.fn(),
  getInteraction: vi.fn(),
  createInteraction: vi.fn(),
  continueInteraction: vi.fn(),
  cancelInteraction: vi.fn(),
}))
vi.mock('../composables/useInteractions', () => api)

const live = vi.hoisted(() => ({
  handlers: null as null | { onTerminal: () => Promise<void> | void },
}))
vi.mock('atmosphere.js/interactions', () => ({
  InteractionsClient: class {
    async subscribe(_id: string, handlers: { onTerminal: () => Promise<void> | void }) {
      live.handlers = handlers
      return { close: () => {} }
    }
  },
}))

import Interactions from './Interactions.vue'

function interaction(status: Interaction['status'], finalText: string | null = null): Interaction {
  return {
    id: 'int-82d7e4fd-0000-0000-0000-000000000000',
    parentId: null,
    conversationId: 'conv-1',
    agentId: null,
    userId: 'anonymous',
    model: null,
    status,
    background: true,
    store: true,
    steps: [],
    finalText,
    usage: null,
    errorMessage: null,
    createdAt: '2026-10-14T10:00:00Z',
    updatedAt: '2026-10-14T10:00:00Z',
  }
}

const rowStatus = (w: ReturnType<typeof mount>) =>
  w.get('[data-testid="interaction-item-status"]').text()
const detailStatus = (w: ReturnType<typeof mount>) =>
  w.get('[data-testid="interaction-status"]').text()

describe('Interactions history row follows the detail pane', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    live.handlers = null
    api.listInteractions.mockResolvedValue([interaction('RUNNING')])
    api.getInteraction.mockResolvedValue(interaction('RUNNING'))
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('flips the row to COMPLETED when the detail pane sees the run finish', async () => {
    const wrapper = mount(Interactions, { props: { active: true } })
    await flushPromises()
    expect(rowStatus(wrapper)).toBe('RUNNING')

    await wrapper.get('[data-testid="interaction-item"]').trigger('click')
    await flushPromises()
    expect(live.handlers).not.toBeNull()

    // The run finishes; the server's next list poll has not happened yet.
    api.getInteraction.mockResolvedValue(interaction('COMPLETED', 'done'))
    await live.handlers!.onTerminal()
    await flushPromises()

    expect(detailStatus(wrapper)).toBe('COMPLETED')
    expect(rowStatus(wrapper)).toBe('COMPLETED')
    expect(wrapper.text()).not.toContain('running…')
    wrapper.unmount()
  })

  it('does not move a finished run back to RUNNING on a list poll that raced the finish', async () => {
    const wrapper = mount(Interactions, { props: { active: true } })
    await flushPromises()
    await wrapper.get('[data-testid="interaction-item"]').trigger('click')
    await flushPromises()

    api.getInteraction.mockResolvedValue(interaction('COMPLETED', 'done'))
    await live.handlers!.onTerminal()
    await flushPromises()

    // A list response read before the run finished lands afterwards.
    await vi.advanceTimersByTimeAsync(2500)
    await flushPromises()

    expect(api.listInteractions.mock.calls.length).toBeGreaterThan(1)
    expect(rowStatus(wrapper)).toBe('COMPLETED')
    wrapper.unmount()
  })
})
