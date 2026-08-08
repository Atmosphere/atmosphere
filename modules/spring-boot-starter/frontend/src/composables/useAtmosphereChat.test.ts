// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import type { ChatTransportHandlers } from '../transports'

// Capture the handlers the composable wires into its transport so the test can
// feed it the exact frames a server emits, without a socket.
let handlers: ChatTransportHandlers
const sent: string[] = []

vi.mock('../transports', () => ({
  createChatTransport: vi.fn(async (
    _name: unknown,
    _opts: unknown,
    h: ChatTransportHandlers,
  ) => {
    handlers = h
    queueMicrotask(() => h.onOpen?.())
    return {
      connect: async () => {},
      send: (text: string) => { sent.push(text) },
      close: () => {},
    }
  }),
}))

import { useAtmosphereChat } from './useAtmosphereChat'

/**
 * Mount the composable inside a component that actually renders the messages.
 *
 * Asserting on `messages.value` instead would prove nothing: chunks are
 * appended by mutating the message object, so the reactive data is correct even
 * when Vue never re-renders. The defect is exactly that missing re-render — a
 * first version of this suite read the composable state, passed against the
 * broken build, and had to be rewritten. Read the DOM.
 */
async function mountChat() {
  const state: { chat?: ReturnType<typeof useAtmosphereChat> } = {}
  const wrapper = mount(defineComponent({
    setup() {
      const chat = useAtmosphereChat('/atmosphere/test')
      state.chat = chat
      return () => h('div', chat.messages.value
        .filter(m => m.role === 'assistant')
        .map(m => h('p', { class: 'assistant', key: m.id }, m.content)))
    },
  }))
  await nextTick()
  await new Promise(resolve => queueMicrotask(() => resolve(null)))
  return { chat: state.chat!, wrapper }
}

/** What the user can actually see. */
const rendered = (wrapper: { findAll: (s: string) => { text(): string }[] }) =>
  wrapper.findAll('p.assistant').map(p => p.text()).join('')

describe('assistant streaming is fully rendered', () => {
  beforeEach(() => { sent.length = 0 })

  it('renders every chunk when the stream completes faster than the throttle', async () => {
    // The regression. appendToAssistant accumulates on the message object but
    // publishes to Vue on a 50ms throttle, and the throttle callback bails on
    // `if (currentAssistantMessage)`. finalizeAssistant nulled that field
    // without flushing, so anything appended after the last timer fire was
    // never rendered. A handler that calls session.send() three times and
    // completes — every deterministic sample does — showed only chunk one.
    const { chat, wrapper } = await mountChat()
    chat.send('hello')
    await nextTick()

    // A render must happen between the chunks. Frames arrive as separate
    // message events, so Vue paints chunk one before chunk two is appended;
    // emitting all three inside one tick lets the single deferred render see
    // the finished object and passes against the broken build.
    handlers.onEvent?.({ type: 'streaming-text', data: 'one ' })
    await nextTick()
    handlers.onEvent?.({ type: 'streaming-text', data: 'two ' })
    handlers.onEvent?.({ type: 'streaming-text', data: 'three' })
    handlers.onEvent?.({ type: 'complete' })
    await nextTick()

    expect(rendered(wrapper)).toBe('one two three')
  })

  it('does not lose the tail of a slow stream', async () => {
    // Same defect, the shape a real model produces: chunks spread past the
    // throttle so earlier ones publish, then the last few land inside the
    // final 50ms window and complete arrives. The answer renders truncated
    // mid-sentence, which reads as the model trailing off.
    const { chat, wrapper } = await mountChat()
    chat.send('hello')
    await nextTick()

    handlers.onEvent?.({ type: 'streaming-text', data: 'The answer ' })
    await new Promise(resolve => setTimeout(resolve, 80))
    handlers.onEvent?.({ type: 'streaming-text', data: 'is forty' })
    handlers.onEvent?.({ type: 'streaming-text', data: '-two.' })
    handlers.onEvent?.({ type: 'complete' })
    await nextTick()

    expect(rendered(wrapper)).toBe('The answer is forty-two.')
  })

  it('renders the tail when the connection closes instead of completing', async () => {
    // onClose is the other terminal path into finalizeAssistant. A stream cut
    // short must still show what did arrive rather than only its first chunk.
    const { chat, wrapper } = await mountChat()
    chat.send('hello')
    await nextTick()

    handlers.onEvent?.({ type: 'streaming-text', data: 'partial ' })
    await nextTick()
    handlers.onEvent?.({ type: 'streaming-text', data: 'answer' })
    handlers.onClose?.()
    await nextTick()

    expect(rendered(wrapper)).toBe('partial answer')
  })

  it('counts every chunk in the session stats', async () => {
    // The counter was already right while the render was wrong — "3 tokens"
    // beside one rendered chunk is what exposed the bug. Pin them together so
    // they cannot drift apart again.
    const { chat, wrapper } = await mountChat()
    chat.send('hello')
    await nextTick()

    handlers.onEvent?.({ type: 'streaming-text', data: 'a' })
    await nextTick()
    handlers.onEvent?.({ type: 'streaming-text', data: 'b' })
    handlers.onEvent?.({ type: 'streaming-text', data: 'c' })
    handlers.onEvent?.({ type: 'complete' })
    await nextTick()

    expect(chat.stats.value?.totalStreamingTexts).toBe(3)
    expect(rendered(wrapper)).toHaveLength(3)
  })
})
