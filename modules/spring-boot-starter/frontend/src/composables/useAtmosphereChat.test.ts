// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import type { ChatTransportHandlers } from '../transports'

// Capture the handlers the composable wires into its transport so the test can
// feed it the exact frames a server emits, without a socket.
let handlers: ChatTransportHandlers
// The options the composable opened its transport with.
let transportOptions: { author?: string; rooms?: { memberId: string } }
const sent: string[] = []
// The tracking id the mocked transport reports; undefined for one that has none.
let transportUuid: string | undefined = 'tab-self'
// The run id the mocked transport mints for a prompt (null: it mints none), and
// the one it still holds unacknowledged.
let mintedRunId: string | null = null
let mockPendingRunId: string | null = null

vi.mock('../transports', () => ({
  createChatTransport: vi.fn(async (
    _name: unknown,
    opts: typeof transportOptions,
    h: ChatTransportHandlers,
  ) => {
    handlers = h
    transportOptions = opts
    queueMicrotask(() => h.onOpen?.())
    return {
      get uuid() { return transportUuid },
      connect: async () => {},
      send: (text: string) => { sent.push(text); return mintedRunId },
      get pendingRunId() { return mockPendingRunId },
      close: () => {},
      disconnect: () => {},
    }
  }),
}))

import { useAtmosphereChat } from './useAtmosphereChat'
import ChatInput from '../components/ChatInput.vue'
import ToolCard from '../components/ToolCard.vue'
import ChatMessage from '../components/ChatMessage.vue'
import { loadMath } from '../lib/markdown'
import { AgUiEventTranslator } from '../transports/agui'

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

describe('a tool whose approval was not given', () => {
  // 2026-10-20 re-test (spring-boot-ai-tools): an approval that ran out with
  // its turn left its card reading "✓ Reset City Data Awaiting approval",
  // the tool never having run.
  const TIMEOUT = '{"status":"timeout","message":"Approval for tool \'reset_city_data\' was not given before the turn\'s time limit; the turn ended."}'

  async function cardAfter(result: string) {
    const { chat } = await mountChat()
    chat.send('reset Osaka')
    handlers.onEvent({ event: 'tool-start', data: { toolName: 'reset_city_data', arguments: { city: 'Osaka' } }, sessionId: 's-tool', seq: 1 })
    handlers.onEvent({ event: 'approval-required', data: { approvalId: 'apr_1', toolName: 'reset_city_data', arguments: { city: 'Osaka' }, message: 'Reset?', expiresIn: 120 }, sessionId: 's-tool', seq: 2 })
    handlers.onEvent({ event: 'tool-result', data: { toolName: 'reset_city_data', result }, sessionId: 's-tool', seq: 3 })
    handlers.onEvent({ type: 'error', data: 'Approval for tool \'reset_city_data\' was not given before the turn\'s time limit; the turn ended.', sessionId: 's-tool', seq: 4 })
    await nextTick()
    const card = mount(ToolCard, { props: { tool: chat.toolCalls.value[0] } })
    return {
      icon: card.find('.tool-icon').text(),
      status: card.find('.tool-status').text(),
      buttons: card.findAll('[data-testid="approval-approve"]').length,
    }
  }

  it('shows the card timed out, not done and not awaiting approval', async () => {
    const card = await cardAfter(TIMEOUT)
    expect(card.status).toBe('Timed out')
    expect(card.icon).not.toBe('✓')
    expect(card.buttons).toBe(0)
  })

  it('shows a tool the approval refused as denied', async () => {
    const card = await cardAfter('{"status":"cancelled","message":"Action cancelled by user"}')
    expect(card.status).toBe('Denied')
    expect(card.icon).not.toBe('✓')
  })

  it('still shows a tool that ran after its approval as done', async () => {
    const card = await cardAfter('All cached data for Osaka has been reset.')
    expect(card.status).toBe('Done')
    expect(card.icon).toBe('✓')
  })
})

describe('a tool that failed, or never reported back', () => {
  // 2026-10-20 sweep (round 10, lane B): a fleet agent whose task failed sent
  // {"event":"tool-error","data":{"toolName":"writer","error":"…"}} and its card
  // kept reading "● Writer Working..." after the turn had ended.
  beforeEach(() => { sent.length = 0 })

  function card(tool: unknown) {
    const c = mount(ToolCard, { props: { tool: tool as never } })
    return {
      icon: c.find('.tool-icon').text(),
      status: c.find('.tool-status').text(),
      text: c.text(),
    }
  }

  it('shows a tool-error as failed, with its error', async () => {
    const { chat } = await mountChat()
    chat.send('write the article')
    handlers.onEvent({ event: 'tool-start', data: { toolName: 'writer', arguments: { input: 'x' } }, sessionId: 's-f', seq: 4 })
    handlers.onEvent({ event: 'tool-error', data: { toolName: 'writer', error: 'writer deliberately failed for the lane-B test' }, sessionId: 's-f', seq: 5 })
    handlers.onEvent({ type: 'complete', sessionId: 's-f', seq: 6 })
    await nextTick()
    const c = card(chat.toolCalls.value[0])
    expect(c.status).toBe('Failed')
    expect(c.icon).toBe('✕')
    expect(c.text).toContain('writer deliberately failed for the lane-B test')
  })

  it('shows a tool-error that arrives after the turn ended as failed', async () => {
    const { chat } = await mountChat()
    chat.send('write the article')
    handlers.onEvent({ event: 'tool-start', data: { toolName: 'writer', arguments: {} }, sessionId: 's-l', seq: 1 })
    handlers.onEvent({ type: 'complete', sessionId: 's-l', seq: 2 })
    handlers.onEvent({ event: 'tool-error', data: { toolName: 'writer', error: 'late failure' }, sessionId: 's-l', seq: 3 })
    await nextTick()
    const c = card(chat.toolCalls.value[0])
    expect(c.status).toBe('Failed')
    expect(c.text).toContain('late failure')
  })

  it('keeps the error of a tool reported failed then answered, and leaves other open cards alone', async () => {
    // The built-in client reports an unknown tool as ToolStart, ToolError, then
    // a ToolResult carrying the error JSON for the model.
    const { chat } = await mountChat()
    chat.send('go')
    handlers.onEvent({ event: 'tool-start', data: { toolName: 'search', arguments: {} }, sessionId: 's-t', seq: 1 })
    handlers.onEvent({ event: 'tool-start', data: { toolName: 'nope', arguments: {} }, sessionId: 's-t', seq: 2 })
    handlers.onEvent({ event: 'tool-error', data: { toolName: 'nope', error: 'Tool not found' }, sessionId: 's-t', seq: 3 })
    handlers.onEvent({ event: 'tool-result', data: { toolName: 'nope', result: '{"error":"Tool not found: nope"}' }, sessionId: 's-t', seq: 4 })
    await nextTick()
    const [search, nope] = chat.toolCalls.value
    expect(card(nope).status).toBe('Failed')
    expect(card(nope).text).toContain('Tool not found')
    expect(search.done).toBe(false)
    expect(card(search).status).toBe('Working...')
  })

  it('shows a tool-error with no tool-start before it as its own failed card', async () => {
    const { chat } = await mountChat()
    chat.send('go')
    handlers.onEvent({ event: 'tool-error', data: { toolName: 'writer', error: null }, sessionId: 's-n', seq: 1 })
    await nextTick()
    expect(chat.toolCalls.value).toHaveLength(1)
    const c = card(chat.toolCalls.value[0])
    expect(c.status).toBe('Failed')
    expect(c.text).toContain('Writer')
  })

  it('rebuilds a failed card from a replayed run this page did not send for', async () => {
    // After a reload the server replays the run's tool frames as they were written.
    const { chat } = await mountChat()
    handlers.onEvent({ event: 'tool-start', data: { toolName: 'writer', arguments: {} }, sessionId: 's-r', seq: 1 })
    handlers.onEvent({ event: 'tool-error', data: { toolName: 'writer', error: 'replayed failure' }, sessionId: 's-r', seq: 2 })
    handlers.onEvent({ type: 'complete', sessionId: 's-r', seq: 3 })
    await nextTick()
    const c = card(chat.toolCalls.value[0])
    expect(c.status).toBe('Failed')
    expect(c.text).toContain('replayed failure')
  })

  for (const terminal of [
    { type: 'complete' },
    { type: 'error', data: 'boom' },
    { type: 'cancelled', data: 'disconnect' },
  ]) {
    it(`does not leave a card working once the turn ends with ${terminal.type}`, async () => {
      const { chat } = await mountChat()
      chat.send('go')
      handlers.onEvent({ event: 'tool-start', data: { toolName: 'writer', arguments: {} }, sessionId: 's-e', seq: 1 })
      handlers.onEvent({ ...terminal, sessionId: 's-e', seq: 2 })
      await nextTick()
      const c = card(chat.toolCalls.value[0])
      expect(c.status).toBe('No result')
      expect(c.icon).toBe('✕')
    })
  }

  it('still shows a result that arrives after the turn ended', async () => {
    const { chat } = await mountChat()
    chat.send('go')
    handlers.onEvent({ event: 'tool-start', data: { toolName: 'writer', arguments: {} }, sessionId: 's-a', seq: 1 })
    handlers.onEvent({ type: 'complete', sessionId: 's-a', seq: 2 })
    handlers.onEvent({ event: 'tool-result', data: { toolName: 'writer', result: 'the article' }, sessionId: 's-a', seq: 3 })
    await nextTick()
    const c = card(chat.toolCalls.value[0])
    expect(c.status).toBe('Done')
    expect(c.icon).toBe('✓')
  })

  it('leaves a card of another session working when one session ends', async () => {
    // A handed-off turn: the first agent's run ends while the target's tool runs.
    const { chat } = await mountChat()
    chat.send('go')
    handlers.onEvent({ event: 'tool-start', data: { toolName: 'writer', arguments: {} }, sessionId: 's-target', seq: 1 })
    handlers.onEvent({ type: 'complete', sessionId: 's-first', seq: 9 })
    await nextTick()
    expect(card(chat.toolCalls.value[0]).status).toBe('Working...')
  })

  it('does not end a card when the connection drops in the middle of a turn', async () => {
    const { chat } = await mountChat()
    chat.send('go')
    handlers.onEvent({ event: 'tool-start', data: { toolName: 'writer', arguments: {} }, sessionId: 's-d', seq: 1 })
    handlers.onClose?.()
    await nextTick()
    expect(card(chat.toolCalls.value[0]).status).toBe('Working...')
  })

  it('shows an AG-UI tool call that failed as failed, with its error', async () => {
    // The server's AgUiEventMapper reports a failed call as CUSTOM tool-error,
    // then TOOL_CALL_RESULT carrying the same error text, then TOOL_CALL_END.
    const { chat } = await mountChat()
    chat.send('go')
    const t = new AgUiEventTranslator()
    for (const ev of [
      { type: 'TOOL_CALL_START', data: { toolCallId: 'tc1', toolCallName: 'get_weather' } },
      { type: 'CUSTOM', data: { name: 'tool-error', value: { toolCallId: 'tc1', toolName: 'get_weather', error: 'weather service down' } } },
      { type: 'TOOL_CALL_RESULT', data: { messageId: 'msg-2', toolCallId: 'tc1', content: 'weather service down' } },
      { type: 'TOOL_CALL_END', data: { toolCallId: 'tc1' } },
      { type: 'RUN_FINISHED', data: {} },
    ]) {
      for (const frame of t.translate(ev)) handlers.onEvent(frame)
    }
    await nextTick()
    expect(chat.toolCalls.value).toHaveLength(1)
    const failed = card(chat.toolCalls.value[0])
    expect(failed.status).toBe('Failed')
    expect(failed.icon).not.toBe('✓')
    expect(failed.text).toContain('weather service down')
  })

  it('ends an AG-UI tool call whose run finished without a result', async () => {
    // A call that only gets TOOL_CALL_END (no result, no tool-error): only the
    // run's end closes its card.
    const { chat } = await mountChat()
    chat.send('go')
    const t = new AgUiEventTranslator()
    for (const ev of [
      { type: 'TOOL_CALL_START', data: { toolCallId: 'tc1', toolCallName: 'writer' } },
      { type: 'TOOL_CALL_END', data: { toolCallId: 'tc1' } },
      { type: 'RUN_FINISHED', data: {} },
    ]) {
      for (const frame of t.translate(ev)) handlers.onEvent(frame)
    }
    await nextTick()
    expect(card(chat.toolCalls.value[0]).status).toBe('No result')
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

describe('a broadcast endpoint', () => {
  // 2026-10-19 sweep (spring-boot-low-level-handlers): two `posted` broadcasts,
  // bare text frames, rendered as one bubble "postedposted". Each broadcast is
  // a message of its own; only an AI reply streams into one bubble.
  async function mountBroadcast() {
    const state: { chat?: ReturnType<typeof useAtmosphereChat> } = {}
    const wrapper = mount(defineComponent({
      setup() {
        const chat = useAtmosphereChat('/atmosphere/managed/ops', 'broadcast')
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

  it('renders each bare text broadcast as its own bubble', async () => {
    const { chat, wrapper } = await mountBroadcast()
    chat.send('hello')
    handlers.onRawText('posted')
    await nextTick()
    handlers.onRawText('posted')
    await new Promise(resolve => setTimeout(resolve, 60))
    await nextTick()

    expect(wrapper.findAll('p.assistant').map(p => p.text())).toEqual(['posted', 'posted'])
  })

  it('shows a sent broadcast once: its echo is not another bubble, a member saying the same is', async () => {
    // A broadcast endpoint writes a message to every subscriber, the sender
    // included: the page showed its own bubble on send and then the echo as
    // "console: …" again. The page's sends carry its own author name.
    const state: { chat?: ReturnType<typeof useAtmosphereChat> } = {}
    const wrapper = mount(defineComponent({
      setup() {
        const chat = useAtmosphereChat('/atmosphere/chat', 'broadcast')
        state.chat = chat
        return () => h('div', chat.messages.value.map(m => h('p', { class: m.role, key: m.id }, m.content)))
      },
    }))
    await nextTick()
    await new Promise(resolve => queueMicrotask(() => resolve(null)))
    const self = transportOptions.author
    expect(self).toMatch(/^console-[0-9a-f]+$/)

    state.chat!.send('same words')
    handlers.onEvent({ author: self, message: 'same words', time: 1 })
    handlers.onEvent({ author: 'console-0ther', message: 'same words', time: 2 })
    await nextTick()

    expect(wrapper.findAll('p').map(p => `${p.classes()[0]}|${p.text()}`)).toEqual([
      'user|same words',
      'assistant|console-0ther: same words',
    ])
  })

  it('names the page alike as a broadcast author and as a room member', async () => {
    const state: { chat?: ReturnType<typeof useAtmosphereChat> } = {}
    mount(defineComponent({
      setup() {
        state.chat = useAtmosphereChat('/atmosphere/chat', 'broadcast', 'atmosphere', undefined, 'lobby')
        return () => h('div')
      },
    }))
    await nextTick()
    await new Promise(resolve => queueMicrotask(() => resolve(null)))
    expect(transportOptions.rooms?.memberId).toBe(transportOptions.author)
  })

  it("shows a member's message as typed: math is rendered in AI replies only", async () => {
    await loadMath()
    const state: { chat?: ReturnType<typeof useAtmosphereChat> } = {}
    const wrapper = mount(defineComponent({
      setup() {
        const chat = useAtmosphereChat('/atmosphere/managed/ops', 'broadcast')
        state.chat = chat
        return () => h('div', chat.messages.value.map(m => h(ChatMessage, { message: m, key: m.id })))
      },
    }))
    await nextTick()
    handlers.onEvent({ author: 'alice', message: 'try $$x^2$$ here', time: 0 })
    await nextTick()

    const content = wrapper.get('[data-testid="message-bubble"] .message-content')
    expect(content.find('math').exists()).toBe(false)
    expect(content.text()).toBe('alice: try $$x^2$$ here')
  })

  it('still streams bare text deltas of an AI reply into one bubble', async () => {
    const { chat, wrapper } = await mountChat()
    chat.send('hello')
    handlers.onRawText('one ')
    await nextTick()
    handlers.onRawText('two')
    handlers.onEvent({ type: 'complete' })
    await nextTick()

    expect(wrapper.findAll('p.assistant').map(p => p.text())).toEqual(['one two'])
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

  it('renders the frames a reconnect replays under the room session once', async () => {
    const { wrapper } = await mountRoom()

    handlers.onEvent({ type: 'metadata', key: 'X-Atmosphere-Run-Id', value: 'run-1', sessionId: 's1', seq: 1 })
    handlers.onEvent({ type: 'prompt', text: 'q?', from: 'member-b', sessionId: 's1', seq: 2 })
    handlers.onEvent({ type: 'streaming-text', data: 'par', sessionId: 's1', seq: 3 })
    // Reconnected mid-answer: the run's replay writes the turn from its prompt,
    // under the room session's id and numbering the room sent it with.
    handlers.onEvent({ type: 'prompt', text: 'q?', from: 'member-b', sessionId: 's1', seq: 2 })
    handlers.onEvent({ type: 'streaming-text', data: 'par', sessionId: 's1', seq: 3 })
    handlers.onEvent({ type: 'streaming-text', data: 'tial', sessionId: 's1', seq: 4 })
    handlers.onEvent({ type: 'complete', sessionId: 's1', seq: 5 })
    await nextTick()

    expect(bubbles(wrapper)).toEqual([
      { user: true, text: 'q?' },
      { user: false, text: 'partial' },
    ])
  })

  it('keeps one bubble when the connection drops mid-answer and the reconnect resumes the same turn', async () => {
    const { wrapper } = await mountRoom()

    handlers.onEvent({ type: 'prompt', text: 'list?', from: 'member-b', sessionId: 's1', seq: 1 })
    handlers.onEvent({ type: 'streaming-text', data: '1. one\n', sessionId: 's1', seq: 2 })
    // The transport drops, then reconnects; the room writes the rest of the turn.
    handlers.onClose()
    handlers.onReconnect()
    handlers.onReopen()
    handlers.onEvent({ type: 'streaming-text', data: '2. two', sessionId: 's1', seq: 3 })
    handlers.onEvent({ type: 'complete', sessionId: 's1', seq: 4 })
    await nextTick()

    expect(bubbles(wrapper)).toEqual([
      { user: true, text: 'list?' },
      { user: false, text: expect.stringContaining('two') },
    ])
    expect(bubbles(wrapper)[1].text).toContain('one')
  })

  it('starts a new bubble after a drop when what follows is another turn', async () => {
    const { wrapper } = await mountRoom()

    handlers.onEvent({ type: 'prompt', text: 'q1?', from: 'member-b', sessionId: 's1', seq: 1 })
    handlers.onEvent({ type: 'streaming-text', data: 'cut', sessionId: 's1', seq: 2 })
    handlers.onClose()
    handlers.onReopen()
    handlers.onEvent({ type: 'streaming-text', data: 'other', sessionId: 's2', seq: 2 })
    handlers.onEvent({ type: 'complete', sessionId: 's2', seq: 3 })
    await nextTick()

    expect(bubbles(wrapper).filter(b => !b.user).map(b => b.text)).toEqual(['cut', 'other'])
  })

  it('renders the catch-up of a reconnect from its resume point: the rest of the turn, then a turn missed whole', async () => {
    const { wrapper } = await mountRoom()

    handlers.onEvent({ type: 'metadata', key: 'X-Atmosphere-Run-Id', value: 'run-1', sessionId: 's1', seq: 1 })
    handlers.onEvent({ type: 'prompt', text: 'q?', from: 'member-b', sessionId: 's1', seq: 2 })
    handlers.onEvent({ type: 'streaming-text', data: 'par', sessionId: 's1', seq: 3 })
    // Gone unnoticed, back with X-Atmosphere-Resume=s1:3: the server writes
    // what followed, once, then the turn another member asked meanwhile.
    handlers.onEvent({ type: 'streaming-text', data: 'tial', sessionId: 's1', seq: 4 })
    handlers.onEvent({ type: 'complete', sessionId: 's1', seq: 5 })
    handlers.onEvent({ type: 'prompt', text: 'next?', from: 'member-c', sessionId: 's2', seq: 2 })
    handlers.onEvent({ type: 'streaming-text', data: 'two', sessionId: 's2', seq: 3 })
    handlers.onEvent({ type: 'complete', sessionId: 's2', seq: 4 })
    await nextTick()

    expect(bubbles(wrapper)).toEqual([
      { user: true, text: 'q?' },
      { user: false, text: 'partial' },
      { user: true, text: 'next?' },
      { user: false, text: 'two' },
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

describe('the prompt of a turn reattached after a reload', () => {
  // 2026-10-19 re-test (spring-boot-ai-chat): a reload in the middle of an
  // answer brought the reply back but not the prompt that asked it (2 bubbles
  // before, 1 after). The server records a prompt with the run for a
  // broadcastReply room only, so a per-client run replays its reply alone.
  beforeEach(() => {
    transportUuid = 'tab-self'
    sessionStorage.clear()
  })

  async function mountPage() {
    const state: { chat?: ReturnType<typeof useAtmosphereChat> } = {}
    const wrapper = mount(defineComponent({
      setup() {
        const chat = useAtmosphereChat('/atmosphere/ai-chat')
        state.chat = chat
        return () => h('div', chat.messages.value.map(m => h(ChatMessage, { message: m, key: m.id })))
      },
    }))
    await nextTick()
    await new Promise(resolve => queueMicrotask(() => resolve(null)))
    return { chat: state.chat!, wrapper }
  }

  const bubbles = (wrapper: { findAll: (s: string) => { classes(): string[]; find(s: string): { text(): string } }[] }) =>
    wrapper.findAll('[data-testid="message-bubble"]').map(b => ({
      user: b.classes().includes('message--user'),
      text: b.find('.message-content').text(),
    }))

  /** The page before the reload: it sent the prompt and received the start of the reply. */
  async function askThenReload() {
    const before = await mountPage()
    before.chat.send('list ten primes')
    handlers.onEvent({ type: 'metadata', key: 'X-Atmosphere-Run-Id', value: 'run-1', sessionId: 's1', seq: 1 })
    handlers.onEvent({ type: 'streaming-text', data: '2, 3', sessionId: 's1', seq: 2 })
    await nextTick()
    expect(bubbles(before.wrapper).map(b => b.user)).toEqual([true, false])
    before.wrapper.unmount()
    // The reloaded page connects under a new tracking id.
    transportUuid = 'tab-after-reload'
    return mountPage()
  }

  it('shows the prompt again before the replayed reply of a per-client run', async () => {
    const { wrapper } = await askThenReload()
    handlers.onEvent({ type: 'streaming-text', data: '2, 3', sessionId: 's1', seq: 2 })
    handlers.onEvent({ type: 'streaming-text', data: ', 5', sessionId: 's1', seq: 3 })
    handlers.onEvent({ type: 'complete', sessionId: 's1', seq: 4 })
    await nextTick()

    expect(bubbles(wrapper)).toEqual([
      { user: true, text: 'list ten primes' },
      { user: false, text: '2, 3, 5' },
    ])
  })

  it('shows it once when the run replays its own prompt frame (a broadcastReply room)', async () => {
    // roomMemberId('tab-self'), the member this tab was before the reload.
    sessionStorage.setItem('atmosphere.console.roomMemberIds', JSON.stringify(['8b41605f4f4f611de1d4907b3de0e97f']))
    const { wrapper } = await askThenReload()
    handlers.onEvent({ type: 'prompt', text: 'list ten primes', from: '8b41605f4f4f611de1d4907b3de0e97f', sessionId: 's1', seq: 1 })
    handlers.onEvent({ type: 'streaming-text', data: '2, 3', sessionId: 's1', seq: 2 })
    await nextTick()

    expect(bubbles(wrapper).filter(b => b.user)).toEqual([{ user: true, text: 'list ten primes' }])
  })

  it('follows the run a handoff started: a reload before its first frame shows the prompt with its reply', async () => {
    // 2026-10-20 re-test (orchestration-demo): the support agent handed the
    // turn off to billing, whose run the client reattached after a reload;
    // the prompt kept under the support run was never shown again.
    const before = await mountPage()
    before.chat.send('refund my order')
    handlers.onEvent({ type: 'metadata', key: 'X-Atmosphere-Run-Id', value: 'run-support', sessionId: 's-support', seq: 1 })
    handlers.onEvent({ event: 'handoff', data: { fromAgent: 'support', toAgent: 'billing', reason: 'refund' }, sessionId: 's-support' })
    handlers.onEvent({ type: 'metadata', key: 'X-Atmosphere-Run-Id', value: 'run-billing', sessionId: 's-billing', seq: 1 })
    // The support run ends once it handed the turn off; the turn goes on.
    handlers.onEvent({ type: 'complete', sessionId: 's-support', seq: 2 })
    await nextTick()
    expect(JSON.parse(sessionStorage.getItem('atmosphere.console.inFlightPrompt') ?? 'null'))
      .toMatchObject({ sessionId: 's-billing', runId: 'run-billing', text: 'refund my order' })
    before.wrapper.unmount()

    // Reloaded before billing's first frame: atmosphere.js reattaches billing's run.
    transportUuid = 'tab-after-reload'
    const { wrapper } = await mountPage()
    handlers.onEvent({ type: 'streaming-text', data: 'Refund issued', sessionId: 's-billing', seq: 2 })
    handlers.onEvent({ type: 'complete', sessionId: 's-billing', seq: 3 })
    await nextTick()

    expect(bubbles(wrapper)).toEqual([
      { user: true, text: 'refund my order' },
      { user: false, text: 'Refund issued' },
    ])
    expect(sessionStorage.getItem('atmosphere.console.inFlightPrompt')).toBeNull()
  })

  describe('sent under a run id the transport minted', () => {
    const RUN = '3f2c1a9e-4b7d-4c1e-9a2b-0f6e5d4c3b2a'
    beforeEach(() => {
      mintedRunId = RUN
      mockPendingRunId = RUN
    })
    afterEach(() => {
      mintedRunId = null
      mockPendingRunId = null
    })

    it('keeps the prompt under that id before the server answers anything', async () => {
      const { chat } = await mountPage()
      chat.send('list ten primes')
      expect(JSON.parse(sessionStorage.getItem('atmosphere.console.inFlightPrompt') ?? 'null'))
        .toMatchObject({ runId: RUN, text: 'list ten primes' })
    })

    it('shows the prompt at once after a reload that came before any answer, then its reply', async () => {
      const before = await mountPage()
      before.chat.send('list ten primes')
      before.wrapper.unmount()

      transportUuid = 'tab-after-reload'
      const { wrapper } = await mountPage()
      // Nothing from the server yet: the prompt is shown already.
      expect(bubbles(wrapper)).toEqual([{ user: true, text: 'list ten primes' }])

      // The prompt sent again runs; its frames come under a session not seen before.
      handlers.onEvent({ type: 'metadata', key: 'X-Atmosphere-Run-Id', value: RUN, sessionId: 's7', seq: 1 })
      handlers.onEvent({ type: 'streaming-text', data: '2, 3, 5', sessionId: 's7', seq: 2 })
      handlers.onEvent({ type: 'complete', sessionId: 's7', seq: 3 })
      await nextTick()
      expect(bubbles(wrapper)).toEqual([
        { user: true, text: 'list ten primes' },
        { user: false, text: '2, 3, 5' },
      ])
      expect(sessionStorage.getItem('atmosphere.console.inFlightPrompt')).toBeNull()
    })

    it('takes the session of the reattached run from its first frame when no id frame named it', async () => {
      const before = await mountPage()
      before.chat.send('list ten primes')
      before.wrapper.unmount()

      transportUuid = 'tab-after-reload'
      const { wrapper } = await mountPage()
      handlers.onEvent({ type: 'metadata', key: 'X-Atmosphere-Run-Id', value: RUN, sessionId: RUN, seq: 1 })
      handlers.onEvent({ type: 'streaming-text', data: '2, 3', sessionId: 's8', seq: 4 })
      handlers.onEvent({ type: 'complete', sessionId: 's8', seq: 5 })
      await nextTick()
      expect(bubbles(wrapper).filter(b => b.user)).toEqual([{ user: true, text: 'list ten primes' }])
      expect(sessionStorage.getItem('atmosphere.console.inFlightPrompt')).toBeNull()
    })

    it('ends the prompt with the turn when a reattach followed a handoff the server named no run for', async () => {
      // 2026-10-20 re-test (orchestration-demo): a page reloaded before the
      // handoff reached it reattached the support run, which the server
      // followed to billing's run without an id frame for it. Billing's
      // terminal frame left the prompt kept, and the next page load showed it
      // again with no reply.
      const before = await mountPage()
      before.chat.send('refund my order')
      before.wrapper.unmount()

      transportUuid = 'tab-after-reload'
      const reloaded = await mountPage()
      handlers.onEvent({ type: 'metadata', key: 'X-Atmosphere-Run-Id', value: RUN, sessionId: RUN, seq: 1 })
      handlers.onEvent({ event: 'handoff', data: { fromAgent: 'support', toAgent: 'billing', reason: 'refund' }, sessionId: 's-support', seq: 2 })
      handlers.onEvent({ type: 'streaming-text', data: 'Refund issued', sessionId: 's-billing', seq: 1 })
      handlers.onEvent({ type: 'complete', sessionId: 's-billing', seq: 2 })
      await nextTick()
      expect(bubbles(reloaded.wrapper)).toEqual([
        { user: true, text: 'refund my order' },
        { user: false, text: 'Refund issued' },
      ])
      expect(sessionStorage.getItem('atmosphere.console.inFlightPrompt')).toBeNull()
      reloaded.wrapper.unmount()

      // The turn is over: a later load shows no prompt of it.
      transportUuid = 'tab-after-second-reload'
      const again = await mountPage()
      expect(bubbles(again.wrapper)).toEqual([])
    })

    it('withdraws the prompt when the server lost its run and the transport is not sending it again', async () => {
      const before = await mountPage()
      before.chat.send('list ten primes')
      before.wrapper.unmount()

      transportUuid = 'tab-after-reload'
      mockPendingRunId = null
      const { wrapper } = await mountPage()
      expect(bubbles(wrapper)).toEqual([{ user: true, text: 'list ten primes' }])
      handlers.onEvent({ type: 'metadata', key: 'X-Atmosphere-Run-Unknown', value: RUN, sessionId: RUN, seq: 1 })
      await nextTick()
      expect(bubbles(wrapper)).toEqual([])
      expect(sessionStorage.getItem('atmosphere.console.inFlightPrompt')).toBeNull()
    })
  })

  it('does not show a finished turn\'s prompt again', async () => {
    const before = await mountPage()
    before.chat.send('hello')
    handlers.onEvent({ type: 'metadata', key: 'X-Atmosphere-Run-Id', value: 'run-2', sessionId: 's2', seq: 1 })
    handlers.onEvent({ type: 'streaming-text', data: 'hi', sessionId: 's2', seq: 2 })
    handlers.onEvent({ type: 'complete', sessionId: 's2', seq: 3 })
    await nextTick()
    expect(bubbles(before.wrapper)).toEqual([{ user: true, text: 'hello' }, { user: false, text: 'hi' }])
    before.wrapper.unmount()

    const { wrapper } = await mountPage()
    handlers.onEvent({ type: 'streaming-text', data: 'hi', sessionId: 's2', seq: 2 })
    await nextTick()
    expect(bubbles(wrapper).filter(b => b.user)).toEqual([])
  })
})
