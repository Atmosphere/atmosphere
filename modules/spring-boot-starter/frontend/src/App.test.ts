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
