// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import App from './App.vue'
import { pickedRoomKey } from './lib/pickedRoom'

/**
 * A Console whose chat offers several rooms (path-templated samples such as
 * spring-boot-ai-classroom) opened on the room picker after every reload, so a
 * room member reloaded mid-answer could not reattach to the room's run until
 * it picked the room again. The picked room is kept for the tab and restored
 * before the chat connects.
 */

const ChatStub = defineComponent({
  props: ['endpoint'],
  setup(props) {
    return () => h('div', { 'data-testid': 'chat-stub' }, String(props.endpoint))
  },
})

const ROOMS = [
  { label: 'Math', endpoint: '/atmosphere/classroom/math' },
  { label: 'Code', endpoint: '/atmosphere/classroom/code' },
]

function stubFetch(endpointOptions: Array<{ label: string; endpoint: string }>) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (url === '/api/console/info') {
      return new Response(JSON.stringify({
        subtitle: 'test', endpoint: '/atmosphere/classroom/{room}', mode: 'ai',
        hasAdmin: false, endpointOptions,
      }), { status: 200 })
    }
    return new Response('[]', { status: 200 })
  }))
}

async function mountApp() {
  const wrapper = mount(App, { global: { stubs: { ChatContainer: ChatStub } } })
  await flushPromises()
  return wrapper
}

describe('Console keeps the picked room across a reload', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    sessionStorage.clear()
  })

  it('a reloaded page comes back to the room it picked, before the chat connects', async () => {
    stubFetch(ROOMS)
    const first = await mountApp()
    expect(first.find('[data-testid="endpoint-picker"]').exists()).toBe(true)
    await first.get('[data-testid="pick-code"]').trigger('click')
    expect(first.get('[data-testid="chat-stub"]').text()).toBe('/atmosphere/classroom/code')
    expect(sessionStorage.getItem(pickedRoomKey())).toBe('/atmosphere/classroom/code')
    first.unmount()

    // The reload: a new App in the same tab.
    const reloaded = await mountApp()
    expect(reloaded.find('[data-testid="endpoint-picker"]').exists()).toBe(false)
    expect(reloaded.get('[data-testid="chat-stub"]').text()).toBe('/atmosphere/classroom/code')
    reloaded.unmount()
  })

  it('leaving the room forgets it: the reloaded page shows the picker', async () => {
    stubFetch(ROOMS)
    const first = await mountApp()
    await first.get('[data-testid="pick-math"]').trigger('click')
    await first.get('[data-testid="leave-room"]').trigger('click')
    expect(first.find('[data-testid="endpoint-picker"]').exists()).toBe(true)
    expect(first.find('[data-testid="chat-stub"]').exists()).toBe(false)
    expect(sessionStorage.getItem(pickedRoomKey())).toBeNull()
    first.unmount()

    const reloaded = await mountApp()
    expect(reloaded.find('[data-testid="endpoint-picker"]').exists()).toBe(true)
    reloaded.unmount()
  })

  it('picking another room replaces the one the tab comes back to', async () => {
    stubFetch(ROOMS)
    const first = await mountApp()
    await first.get('[data-testid="pick-math"]').trigger('click')
    await first.get('[data-testid="leave-room"]').trigger('click')
    await first.get('[data-testid="pick-code"]').trigger('click')
    first.unmount()

    const reloaded = await mountApp()
    expect(reloaded.get('[data-testid="chat-stub"]').text()).toBe('/atmosphere/classroom/code')
    reloaded.unmount()
  })

  it('a room the server no longer offers is forgotten and the picker shows', async () => {
    sessionStorage.setItem(pickedRoomKey(), '/atmosphere/classroom/gone')
    stubFetch(ROOMS)
    const reloaded = await mountApp()
    expect(reloaded.find('[data-testid="endpoint-picker"]').exists()).toBe(true)
    expect(reloaded.find('[data-testid="chat-stub"]').exists()).toBe(false)
    expect(sessionStorage.getItem(pickedRoomKey())).toBeNull()
    reloaded.unmount()
  })
})
