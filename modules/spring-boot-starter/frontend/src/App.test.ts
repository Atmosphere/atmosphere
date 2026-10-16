// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, onMounted, onUnmounted } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import App from './App.vue'

/**
 * Every Console tab but Chat stays mounted (v-show) when another tab is
 * picked. Chat alone was v-if'd away, so leaving it closed its subscription,
 * dropped the message list, and coming back opened a brand-new subscription
 * with an empty conversation — and the server logged an unexpected
 * disconnect for every tab switch.
 */

const chatLifecycle = { mounted: 0, unmounted: 0 }

const ChatStub = defineComponent({
  props: ['endpoint'],
  setup(props) {
    onMounted(() => { chatLifecycle.mounted++ })
    onUnmounted(() => { chatLifecycle.unmounted++ })
    return () => h('div', { 'data-testid': 'chat-stub' }, String(props.endpoint))
  },
})

function consoleInfo(extra: Record<string, unknown> = {}) {
  return {
    subtitle: 'test', endpoint: '/atmosphere/ai-chat', mode: 'ai',
    hasInteractions: true, hasAdmin: false, ...extra,
  }
}

function stubFetch(info: Record<string, unknown>) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (url === '/api/console/info') {
      return new Response(JSON.stringify(info), { status: 200 })
    }
    return new Response('[]', { status: 200 })
  }))
}

const chatDisplay = (wrapper: ReturnType<typeof mount>) =>
  (wrapper.get('[data-testid="chat-stub"]').element as HTMLElement).style.display

function mountApp() {
  return mount(App, {
    global: {
      stubs: {
        ChatContainer: ChatStub,
        Interactions: { props: ['active'], template: '<div data-testid="ix-stub" />' },
      },
    },
  })
}

describe('Console Chat tab survives a tab switch', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    chatLifecycle.mounted = 0
    chatLifecycle.unmounted = 0
  })

  it('keeps the same chat mounted across Chat -> Interactions -> Chat', async () => {
    stubFetch(consoleInfo())
    const wrapper = mountApp()
    await flushPromises()
    expect(chatLifecycle.mounted).toBe(1)

    await wrapper.get('[data-testid="tab-interactions"]').trigger('click')
    await flushPromises()
    expect(chatLifecycle.unmounted).toBe(0)
    expect(chatDisplay(wrapper)).toBe('none')

    await wrapper.get('[data-testid="tab-chat"]').trigger('click')
    await flushPromises()
    expect(chatLifecycle.mounted).toBe(1)
    expect(chatLifecycle.unmounted).toBe(0)
    expect(chatDisplay(wrapper)).toBe('')
    wrapper.unmount()
  })

  it('still asks for a room first on a path-templated sample, and keeps the picked chat', async () => {
    stubFetch(consoleInfo({
      endpointOptions: [{ label: 'Math', endpoint: '/atmosphere/classroom/math' }],
    }))
    const wrapper = mountApp()
    await flushPromises()
    expect(wrapper.find('[data-testid="endpoint-picker"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="chat-stub"]').exists()).toBe(false)

    await wrapper.get('[data-testid="pick-math"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="endpoint-picker"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="chat-stub"]').text()).toBe('/atmosphere/classroom/math')

    await wrapper.get('[data-testid="tab-interactions"]').trigger('click')
    await wrapper.get('[data-testid="tab-chat"]').trigger('click')
    await flushPromises()
    expect(chatLifecycle.mounted).toBe(1)
    expect(chatLifecycle.unmounted).toBe(0)
    wrapper.unmount()
  })
})

const WebTransportChatStub = defineComponent({
  props: ['endpoint', 'webTransport'],
  setup(props) {
    return () => h('div', { 'data-testid': 'chat-stub' },
      props.webTransport ? `wt:${props.webTransport.port}` : 'ws')
  },
})

function stubFetchSequence(infos: Array<Record<string, unknown>>) {
  let call = 0
  const fetchMock = vi.fn(async (url: string) => {
    if (url === '/api/console/info') {
      const info = infos[Math.min(call++, infos.length - 1)]
      return new Response(JSON.stringify(info), { status: 200 })
    }
    return new Response('[]', { status: 200 })
  })
  vi.stubGlobal('fetch', fetchMock)
  return () => fetchMock.mock.calls.filter(([url]) => url === '/api/console/info').length
}

describe('Console waits for a WebTransport sidecar that is still starting', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  function mountWithWebTransportChat() {
    return mount(App, { global: { stubs: { ChatContainer: WebTransportChatStub } } })
  }

  it('re-reads console info until the sidecar is up, then connects WebTransport-first', async () => {
    vi.useFakeTimers()
    const infoReads = stubFetchSequence([
      consoleInfo({ webTransport: { state: 'starting' } }),
      consoleInfo({ webTransport: { state: 'starting' } }),
      consoleInfo({ webTransport: { enabled: true, state: 'running', port: 4443, certificateHash: 'aGFzaA==' } }),
    ])
    const wrapper = mountWithWebTransportChat()
    await flushPromises()
    // No chat yet: connecting now would pick WebSocket for the page's life.
    expect(wrapper.find('[data-testid="chat-stub"]').exists()).toBe(false)

    for (let i = 0; i < 2; i++) {
      await vi.advanceTimersByTimeAsync(500)
      await flushPromises()
    }

    expect(infoReads()).toBe(3)
    expect(wrapper.get('[data-testid="chat-stub"]').text()).toBe('wt:4443')
    wrapper.unmount()
  })

  it('goes with WebSocket when the sidecar never comes up, after a bounded wait', async () => {
    vi.useFakeTimers()
    const infoReads = stubFetchSequence([consoleInfo({ webTransport: { state: 'starting' } })])
    const wrapper = mountWithWebTransportChat()
    for (let i = 0; i < 40; i++) {
      await vi.advanceTimersByTimeAsync(500)
      await flushPromises()
    }

    expect(infoReads()).toBe(21)
    expect(wrapper.get('[data-testid="chat-stub"]').text()).toBe('ws')
    wrapper.unmount()
  })

  it('reads console info once when the server reports no sidecar', async () => {
    const infoReads = stubFetchSequence([consoleInfo()])
    const wrapper = mountWithWebTransportChat()
    await flushPromises()

    expect(infoReads()).toBe(1)
    expect(wrapper.get('[data-testid="chat-stub"]').text()).toBe('ws')
    wrapper.unmount()
  })
})
