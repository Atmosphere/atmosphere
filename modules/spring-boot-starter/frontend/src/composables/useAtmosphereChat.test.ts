// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import type { ChatTransportHandlers } from '../transports'

// Capture the handlers the composable wires into its transport so the test can
// feed it the exact frames a server emits, without a socket.
let handlers: ChatTransportHandlers
const sent: string[] = []
// The tracking id the mocked transport reports; undefined for one that has none.
let transportUuid: string | undefined = 'tab-self'

vi.mock('../transports', () => ({
  createChatTransport: vi.fn(async (
    _name: unknown,
    _opts: unknown,
    h: ChatTransportHandlers,
  ) => {
    handlers = h
    queueMicrotask(() => h.onOpen?.())
    return {
      get uuid() { return transportUuid },
      connect: async () => {},
      send: (text: string) => { sent.push(text) },
      close: () => {},
    }
  }),
}))

import { useAtmosphereChat } from './useAtmosphereChat'
import ChatInput from '../components/ChatInput.vue'
import ChatMessage from '../components/ChatMessage.vue'

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

  it('ends a cancelled run cleanly, keeping its text and saying it was cut short', async () => {
    // The server ends a run it cut short with one `cancelled` frame. The bubble
    // must stop streaming and say why — not stay "streaming" waiting for a
    // complete that never comes, and not read as an error.
    const { chat, wrapper } = await mountChat()
    chat.send('hello')
    await nextTick()

    handlers.onEvent?.({ type: 'streaming-text', data: 'partial ' })
    await nextTick()
    handlers.onEvent?.({ type: 'cancelled', data: 'disconnect' })
    await nextTick()

    expect(chat.isStreaming.value).toBe(false)
    expect(rendered(wrapper)).toMatch(/^partial /)
    expect(rendered(wrapper)).toContain('Run cancelled: the client disconnected before it finished.')
    expect(rendered(wrapper)).not.toContain('Error')
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

describe('governance feedback frames', () => {
  it('collects the injected-guidance frames for the turn and resets on the next send', async () => {
    const { chat } = await mountChat()
    chat.send('how do I deploy to production?')
    await nextTick()

    handlers.onEvent?.({ type: 'metadata', key: 'ai.governance.feedback.injected', value: 1 })
    handlers.onEvent?.({
      type: 'metadata',
      key: 'ai.governance.feedback.lines',
      value: ['Prefer: run release-bot (change management)'],
    })
    handlers.onEvent?.({ type: 'complete' })
    await nextTick()

    expect(chat.governanceFeedback.value).toEqual({
      injected: 1,
      lines: ['Prefer: run release-bot (change management)'],
    })

    // Per-turn state: a new prompt must not inherit the previous turn's guidance.
    chat.send('and the weather?')
    await nextTick()
    expect(chat.governanceFeedback.value).toBeNull()
  })
})

describe('a prompt the server never took', () => {
  /** User bubbles plus the real ChatInput, wired the way ChatContainer wires it. */
  async function mountWithInput() {
    const state: { chat?: ReturnType<typeof useAtmosphereChat> } = {}
    const wrapper = mount(defineComponent({
      setup() {
        const chat = useAtmosphereChat('/atmosphere/test')
        state.chat = chat
        return () => h('div', [
          ...chat.messages.value
            .filter(m => m.role === 'user')
            .map(m => h('p', { class: 'user', key: m.id }, m.content)),
          h(ChatInput, {
            disabled: !chat.isConnected.value,
            isStreaming: chat.isStreaming.value,
            onSend: (text: string) => chat.send(text),
          }),
        ])
      },
    }))
    await nextTick()
    await new Promise(resolve => queueMicrotask(() => resolve(null)))
    return { chat: state.chat!, wrapper }
  }

  const sendError = () => Object.assign(new Error('long-polling POST send failed with status 503 after 3 attempts'),
    { name: 'AtmosphereSendError' })

  it('frees the input and marks the prompt undelivered so it can be sent again', async () => {
    sent.length = 0
    const { chat, wrapper } = await mountWithInput()
    await wrapper.find('[data-testid="chat-input"]').setValue('what is 2+2?')
    await wrapper.find('[data-testid="chat-send"]').trigger('click')
    await nextTick()
    expect(sent).toEqual(['what is 2+2?'])
    expect(chat.isStreaming.value).toBe(true)

    // atmosphere.js gave up resending the POST.
    handlers.onError(sendError())
    await nextTick()

    expect(chat.isStreaming.value).toBe(false)
    expect(chat.connectionState.value).toBe('connected')
    expect(wrapper.find('p.user').text()).toContain('not delivered')

    await wrapper.find('[data-testid="chat-input"]').setValue('what is 2+2?')
    expect(wrapper.find('[data-testid="chat-send"]').attributes('disabled')).toBeUndefined()
    await wrapper.find('[data-testid="chat-send"]').trigger('click')
    expect(sent).toEqual(['what is 2+2?', 'what is 2+2?'])
  })

  it('leaves a reply already under way alone when another frame is refused', async () => {
    const { chat, wrapper } = await mountWithInput()
    chat.send('hello')
    await nextTick()
    handlers.onEvent({ type: 'streaming-text', data: 'partial' })
    await nextTick()

    handlers.onError(sendError())
    await nextTick()

    expect(chat.isStreaming.value).toBe(true)
    expect(wrapper.find('p.user').text()).not.toContain('not delivered')
  })

  it('does not blame a prompt already answered for a later refused frame', async () => {
    const { chat, wrapper } = await mountWithInput()
    chat.send('hello')
    await nextTick()
    handlers.onEvent({ type: 'streaming-text', data: 'hi there' })
    handlers.onEvent({ type: 'complete' })
    await nextTick()
    expect(chat.isStreaming.value).toBe(false)

    handlers.onError(sendError())
    await nextTick()

    expect(wrapper.find('p.user').text()).toBe('hello')
  })

  it('keeps waiting when an approval is refused before any reply text streamed', async () => {
    const { chat, wrapper } = await mountWithInput()
    chat.send('delete the temp files')
    await nextTick()
    // The prompt arrived: the server asks to approve a tool call, no text yet.
    handlers.onEvent({
      type: 'approval-required',
      data: { approvalId: 'approval-1', toolName: 'delete_files', message: 'Approve delete_files?' },
    })
    await nextTick()

    // The approval answer's POST is the frame that was refused.
    handlers.onError(sendError())
    await nextTick()

    expect(chat.isStreaming.value).toBe(true)
    expect(wrapper.find('p.user').text()).not.toContain('not delivered')
  })

  it('still reports a transport error as a connection error', async () => {
    const { chat } = await mountWithInput()
    handlers.onError(new Error('connection lost'))
    await nextTick()
    expect(chat.connectionState.value).toBe('error')
  })
})

describe('a reply this client did not send for', () => {
  beforeEach(() => { sent.length = 0 })

  // After a reload the Console reattaches to the run it was receiving: the
  // server replays it and streams the rest, with no send() on this page. The
  // Console must be in the same streaming state as for a turn it started, and
  // idle again on the run's terminal frame.
  for (const terminal of [
    { type: 'complete' },
    { type: 'error', data: 'boom' },
    { type: 'cancelled', data: 'disconnect' },
  ]) {
    it(`streams like a turn of its own until the ${terminal.type} frame`, async () => {
      const { chat, wrapper } = await mountChat()
      expect(chat.isStreaming.value).toBe(false)

      handlers.onEvent?.({ type: 'metadata', key: 'X-Atmosphere-Run-Id', value: 'run-1' })
      expect(chat.isStreaming.value).toBe(false)
      handlers.onEvent?.({ type: 'streaming-text', data: 't00 ' })
      await nextTick()
      expect(chat.isStreaming.value).toBe(true)
      handlers.onEvent?.({ type: 'streaming-text', data: 't01' })
      expect(chat.isStreaming.value).toBe(true)

      handlers.onEvent?.(terminal)
      await nextTick()
      expect(chat.isStreaming.value).toBe(false)
      expect(rendered(wrapper)).toMatch(/^t00 t01/)
    })
  }

  it('disables sending while the reattached reply streams, as for a turn it started', async () => {
    const { chat } = await mountChat()
    const input = mount(ChatInput, { props: { disabled: false, isStreaming: chat.isStreaming.value } })
    await input.find('textarea').setValue('next question')

    handlers.onEvent?.({ type: 'streaming-text', data: 'replayed ' })
    await input.setProps({ isStreaming: chat.isStreaming.value })
    expect(input.find('[data-testid="chat-send"]').attributes('disabled')).toBeDefined()

    handlers.onEvent?.({ type: 'complete' })
    await input.setProps({ isStreaming: chat.isStreaming.value })
    expect(input.find('[data-testid="chat-send"]').attributes('disabled')).toBeUndefined()
  })

  it('does not stream again for the late text of a conversation the user cleared', async () => {
    const { chat } = await mountChat()
    chat.send('hello')
    handlers.onEvent?.({ type: 'streaming-text', data: 'partial ' })
    chat.clearMessages()
    expect(chat.isStreaming.value).toBe(false)

    handlers.onEvent?.({ type: 'streaming-text', data: 'late' })
    expect(chat.isStreaming.value).toBe(false)
  })
})

describe('session stats describe one turn', () => {
  // 2026-10-19 sweep (classroom): a page that received another member's turn
  // showed "236 chunks · 34765ms", its own 27-chunk turn plus the member's 209,
  // timed from its own send. Each turn's stats count that turn alone.
  let now = 0
  beforeEach(() => {
    sent.length = 0
    transportUuid = 'tab-self'
    sessionStorage.clear()
    now = 1_000
    vi.spyOn(Date, 'now').mockImplementation(() => now)
  })
  afterEach(() => { vi.restoreAllMocks() })

  it("counts another member's turn on its own, timed from its prompt", async () => {
    const { chat } = await mountChat()
    chat.send('mine')
    handlers.onEvent({ type: 'streaming-text', data: 'a', sessionId: 's1' })
    handlers.onEvent({ type: 'streaming-text', data: 'b', sessionId: 's1' })
    now = 2_000
    handlers.onEvent({ type: 'complete', sessionId: 's1' })
    expect(chat.stats.value).toMatchObject({ totalStreamingTexts: 2, elapsedMs: 1_000 })

    now = 30_000
    handlers.onEvent({ type: 'prompt', text: 'theirs?', from: 'member-b-uuid', sessionId: 's2', seq: 1 })
    now = 30_100
    for (const t of ['x', 'y', 'z']) handlers.onEvent({ type: 'streaming-text', data: t, sessionId: 's2' })
    now = 30_400
    handlers.onEvent({ type: 'complete', sessionId: 's2' })

    expect(chat.stats.value).toMatchObject({ totalStreamingTexts: 3, elapsedMs: 400 })
  })

  it('counts a reply it did not send for from its first chunk', async () => {
    // A run reattached after a reload, or a turn whose prompt echo never came.
    const { chat } = await mountChat()
    chat.send('mine')
    handlers.onEvent({ type: 'streaming-text', data: 'a', sessionId: 's1' })
    now = 2_000
    handlers.onEvent({ type: 'complete', sessionId: 's1' })

    now = 50_000
    handlers.onEvent({ type: 'streaming-text', data: 'p', sessionId: 's2' })
    handlers.onEvent({ type: 'streaming-text', data: 'q', sessionId: 's2' })
    now = 50_250
    handlers.onEvent({ type: 'complete', sessionId: 's2' })

    expect(chat.stats.value).toMatchObject({ totalStreamingTexts: 2, elapsedMs: 250 })
  })
})

describe('a broadcastReply room', () => {
  // roomMemberId('tab-self') and roomMemberId('tab-before-reload'): the room
  // names members by these, never by their tracking ids.
  const SELF = '8b41605f4f4f611de1d4907b3de0e97f'
  const BEFORE_RELOAD = 'b9474c500e478a9a54e86202cb07a7c8'

  beforeEach(() => {
    transportUuid = 'tab-self'
    sessionStorage.clear()
  })

  /** Every message through the real ChatMessage bubble, as ChatContainer renders it. */
  async function mountRoom() {
    const state: { chat?: ReturnType<typeof useAtmosphereChat> } = {}
    const wrapper = mount(defineComponent({
      setup() {
        const chat = useAtmosphereChat('/atmosphere/classroom/math')
        state.chat = chat
        return () => h('div', chat.messages.value.map(m => h(ChatMessage, { message: m, key: m.id })))
      },
    }))
    await nextTick()
    await new Promise(resolve => queueMicrotask(() => resolve(null)))
    return { chat: state.chat!, wrapper }
  }

  type Bubble = { classes(): string[]; find(s: string): { text(): string } }
  const bubbles = (wrapper: { findAll: (s: string) => Bubble[] }) =>
    wrapper.findAll('[data-testid="message-bubble"]').map(b => ({
      user: b.classes().includes('message--user'),
      text: b.find('.message-content').text(),
    }))

  it("renders another member's prompt as a user bubble, and its reply in a bubble of its own", async () => {
    const { wrapper } = await mountRoom()

    handlers.onEvent({ type: 'prompt', text: 'what is 2+2?', from: 'member-b-uuid', sessionId: 's1', seq: 2 })
    handlers.onEvent({ type: 'streaming-text', data: 'four', sessionId: 's1', seq: 3 })
    handlers.onEvent({ type: 'complete', sessionId: 's1', seq: 4 })
    await nextTick()

    expect(bubbles(wrapper)).toEqual([
      { user: true, text: 'what is 2+2?' },
      { user: false, text: 'four' },
    ])
    expect(wrapper.find('[data-testid="message-author"]').text()).toContain('Member member-b')
  })

  it('a second prompt ends the reply in progress, so the next reply does not run into it', async () => {
    const { wrapper } = await mountRoom()

    handlers.onEvent({ type: 'prompt', text: 'first?', from: 'member-b-uuid', sessionId: 's1', seq: 1 })
    handlers.onEvent({ type: 'streaming-text', data: 'one', sessionId: 's1', seq: 2 })
    handlers.onEvent({ type: 'prompt', text: 'second?', from: 'member-c-uuid', sessionId: 's2', seq: 1 })
    handlers.onEvent({ type: 'streaming-text', data: 'two', sessionId: 's2', seq: 2 })
    await new Promise(resolve => setTimeout(resolve, 60))
    await nextTick()

    expect(bubbles(wrapper)).toEqual([
      { user: true, text: 'first?' },
      { user: false, text: 'one' },
      { user: true, text: 'second?' },
      { user: false, text: 'two' },
    ])
  })

  it('skips the echo of its own prompt: the optimistic bubble is already there', async () => {
    const { chat, wrapper } = await mountRoom()
    chat.send('my question')
    await nextTick()

    handlers.onEvent({ type: 'prompt', text: 'my question', from: SELF, sessionId: 's1', seq: 2 })
    handlers.onEvent({ type: 'streaming-text', data: 'answer', sessionId: 's1', seq: 3 })
    handlers.onEvent({ type: 'complete', sessionId: 's1', seq: 4 })
    await nextTick()

    expect(bubbles(wrapper)).toEqual([
      { user: true, text: 'my question' },
      { user: false, text: 'answer' },
    ])
  })

  it('renders a prompt replayed on a reattach once, without splitting the reply under way', async () => {
    const { wrapper } = await mountRoom()

    handlers.onEvent({ type: 'metadata', key: 'X-Atmosphere-Run-Id', value: 'run-1', sessionId: 's1', seq: 1 })
    handlers.onEvent({ type: 'prompt', text: 'q?', from: 'member-b', sessionId: 's1', seq: 2 })
    handlers.onEvent({ type: 'streaming-text', data: 'par', sessionId: 's1', seq: 3 })
    // The reattach replays the run under its run id.
    handlers.onEvent({ type: 'prompt', text: 'q?', from: 'member-b', sessionId: 'run-1', seq: 1 })
    handlers.onEvent({ type: 'streaming-text', data: 'tial', sessionId: 's1', seq: 4 })
    handlers.onEvent({ type: 'complete', sessionId: 's1', seq: 5 })
    await nextTick()

    expect(bubbles(wrapper)).toEqual([
      { user: true, text: 'q?' },
      { user: false, text: 'partial' },
    ])
  })

  it('renders its own prompt replayed after a reload as its own bubble, with no member label', async () => {
    sessionStorage.setItem('atmosphere.console.roomMemberIds', JSON.stringify([BEFORE_RELOAD]))
    const { wrapper } = await mountRoom()

    handlers.onEvent({ type: 'prompt', text: 'asked before the reload', from: BEFORE_RELOAD, sessionId: 'run-9', seq: 1 })
    await nextTick()

    expect(bubbles(wrapper)).toEqual([{ user: true, text: 'asked before the reload' }])
    expect(wrapper.find('[data-testid="message-author"]').exists()).toBe(false)
  })

  it('renders every room prompt when its transport has no tracking id', async () => {
    transportUuid = undefined
    const { chat, wrapper } = await mountRoom()
    chat.send('mine')
    await nextTick()

    handlers.onEvent({ type: 'prompt', text: 'theirs', from: SELF, sessionId: 's1', seq: 1 })
    await nextTick()

    expect(bubbles(wrapper)).toEqual([
      { user: true, text: 'mine' },
      { user: true, text: 'theirs' },
    ])
  })

  it('a Clear before the first token of the next turn discards that turn', async () => {
    const { chat, wrapper } = await mountRoom()
    chat.send('one')
    handlers.onEvent({ type: 'streaming-text', data: 'first answer', sessionId: 's1', seq: 1 })
    handlers.onEvent({ type: 'complete', sessionId: 's1', seq: 2 })
    await nextTick()
    chat.send('two')
    chat.clearMessages()

    handlers.onEvent({ type: 'streaming-text', data: 'leak', sessionId: 's2', seq: 1 })
    handlers.onEvent({ type: 'complete', sessionId: 's2', seq: 2 })
    await nextTick()

    expect(bubbles(wrapper)).toEqual([])
  })

  it("a Clear before the first token discards this client's turn, not another member's that overlaps it", async () => {
    const { chat, wrapper } = await mountRoom()
    chat.send('mine')
    chat.clearMessages()

    handlers.onEvent({ type: 'prompt', text: 'theirs', from: 'member-b', sessionId: 'sB', seq: 1 })
    handlers.onEvent({ type: 'streaming-text', data: 'their answer', sessionId: 'sB', seq: 2 })
    handlers.onEvent({ type: 'complete', sessionId: 'sB', seq: 3 })
    handlers.onEvent({ type: 'streaming-text', data: 'MY-LEAK', sessionId: 'sA', seq: 1 })
    handlers.onEvent({ type: 'complete', sessionId: 'sA', seq: 2 })
    await nextTick()

    expect(bubbles(wrapper)).toEqual([
      { user: true, text: 'theirs' },
      { user: false, text: 'their answer' },
    ])
  })

  it("a Clear before the first token discards this client's turn named by its own echo, whichever turn ends first", async () => {
    const { chat, wrapper } = await mountRoom()
    chat.send('mine')
    chat.clearMessages()

    handlers.onEvent({ type: 'prompt', text: 'mine', from: SELF, sessionId: 'sA', seq: 1 })
    handlers.onEvent({ type: 'streaming-text', data: 'MY-LEAK', sessionId: 'sA', seq: 2 })
    handlers.onEvent({ type: 'prompt', text: 'theirs', from: 'member-b', sessionId: 'sB', seq: 1 })
    handlers.onEvent({ type: 'complete', sessionId: 'sA', seq: 3 })
    handlers.onEvent({ type: 'streaming-text', data: 'their answer', sessionId: 'sB', seq: 2 })
    handlers.onEvent({ type: 'complete', sessionId: 'sB', seq: 3 })
    await nextTick()

    expect(bubbles(wrapper)).toEqual([
      { user: true, text: 'theirs' },
      { user: false, text: 'their answer' },
    ])
  })

  it("does not bring a cleared turn's late text back when another member's prompt arrives", async () => {
    const { chat, wrapper } = await mountRoom()
    chat.send('mine')
    handlers.onEvent({ type: 'streaming-text', data: 'cleared ', sessionId: 's1', seq: 1 })
    await nextTick()
    chat.clearMessages()

    handlers.onEvent({ type: 'prompt', text: 'theirs?', from: 'member-b', sessionId: 's2', seq: 1 })
    handlers.onEvent({ type: 'streaming-text', data: 'late', sessionId: 's1', seq: 2 })
    handlers.onEvent({ type: 'streaming-text', data: 'reply', sessionId: 's2', seq: 2 })
    handlers.onEvent({ type: 'complete', sessionId: 's2', seq: 3 })
    await nextTick()

    expect(bubbles(wrapper)).toEqual([
      { user: true, text: 'theirs?' },
      { user: false, text: 'reply' },
    ])
  })
})
