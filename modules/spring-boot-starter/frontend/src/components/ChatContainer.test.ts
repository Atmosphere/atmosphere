// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest'
import { ref, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import type { ChatMessage, ToolCall } from '../composables/useAtmosphereChat'

const messages = ref<ChatMessage[]>([])
const toolCalls = ref<ToolCall[]>([])

vi.mock('../composables/useAtmosphereChat', () => ({
  useAtmosphereChat: () => ({
    messages,
    toolCalls,
    isConnected: ref(true),
    isStreaming: ref(false),
    connectionState: ref('connected'),
    connectionStatus: ref({ transport: 'websocket', state: 'connected' }),
    send: vi.fn(),
    clearMessages: vi.fn(),
    respondToApproval: vi.fn(),
    stats: ref(null),
    routing: ref({}),
    agentSteps: ref({}),
    presenceCount: ref(0),
    offlineSize: ref(0),
    canQueueOffline: ref(false),
  }),
}))

import ChatContainer from './ChatContainer.vue'

const turn = (role: 'user' | 'assistant', content: string): ChatMessage =>
  ({ id: `${role}-${content}`, role, content, timestamp: 0 })

function mountContainer() {
  return mount(ChatContainer, {
    props: { endpoint: '/atmosphere/test' },
    global: {
      stubs: {
        ChatMessage: { props: ['message'], template: '<div class="m">{{ message.content }}</div>' },
        ChatInput: true,
        ConnectionStatus: true,
        ToolCard: { props: ['tool'], template: '<div class="tc">{{ tool.name }}</div>' },
      },
    },
  })
}

describe('tool cards belong to the turn that made them', () => {
  it('does not attach the current turn\'s tools to earlier turns', async () => {
    // The regression. `toolCalls` is per-turn state, reset on every send, so it
    // never describes an earlier turn — but the section was keyed off "any user
    // message followed by an assistant one", which matches every completed turn
    // in the conversation. Ask a plain question, then one that calls a tool, and
    // the first turn retroactively grew tool cards it never made. Every sweep
    // assertion drove a single turn, which is why it survived.
    messages.value = [
      turn('user', 'plain question'),
      turn('assistant', 'plain answer'),
      turn('user', 'call a tool'),
    ]
    toolCalls.value = [
      { id: 't1', name: 'get_invoice', args: {}, done: true },
      { id: 't2', name: 'process_refund', args: {}, done: true },
    ]
    const wrapper = mountContainer()
    await nextTick()

    expect(wrapper.findAll('[data-testid="tool-activity"]')).toHaveLength(1)
    expect(wrapper.findAll('.tc').map(c => c.text()))
      .toEqual(['get_invoice', 'process_refund'])
  })

  it('still renders the tools of the turn in flight', async () => {
    // The section must not disappear in the process — a tool turn with no
    // assistant reply yet is the normal mid-stream state.
    messages.value = [turn('user', 'call a tool')]
    toolCalls.value = [{ id: 't1', name: 'get_invoice', args: {}, done: false }]
    const wrapper = mountContainer()
    await nextTick()

    expect(wrapper.findAll('[data-testid="tool-activity"]')).toHaveLength(1)
    expect(wrapper.findAll('.tc').map(c => c.text())).toEqual(['get_invoice'])
  })

  it('renders no tool section when the turn called no tools', async () => {
    messages.value = [turn('user', 'plain question'), turn('assistant', 'plain answer')]
    toolCalls.value = []
    const wrapper = mountContainer()
    await nextTick()

    expect(wrapper.findAll('[data-testid="tool-activity"]')).toHaveLength(0)
  })
})
