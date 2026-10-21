// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { usePollingResource } from './useGovernance'

/**
 * A tab polling an admin resource the console may not read (401/403) asked
 * again every few seconds for as long as the tab stayed open. A refused
 * request stops the polling; opening the tab again or refreshing restarts it.
 */
function mountPoller(status: number) {
  const fetchMock = vi.fn(async () => new Response('[]', { status }))
  vi.stubGlobal('fetch', fetchMock)
  const enabled = ref(true)
  let api: ReturnType<typeof usePollingResource<unknown[]>> | undefined
  const wrapper = mount(defineComponent({
    setup() {
      api = usePollingResource<unknown[]>('/api/admin/tape/runs', [], 4000, enabled)
      return () => h('div')
    },
  }))
  return { fetchMock, enabled, api: api!, wrapper }
}

describe('usePollingResource', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('stops polling after a 401 and restarts on refresh', async () => {
    vi.useFakeTimers()
    const { fetchMock, api, wrapper } = mountPoller(401)
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(api.error.value).toContain('401')

    await vi.advanceTimersByTimeAsync(20000)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    api.refresh()
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('stops polling after a 403 and restarts when the tab is opened again', async () => {
    vi.useFakeTimers()
    const { fetchMock, enabled, wrapper } = mountPoller(403)
    await flushPromises()
    await vi.advanceTimersByTimeAsync(20000)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    enabled.value = false
    await flushPromises()
    enabled.value = true
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('keeps polling a resource it may read', async () => {
    vi.useFakeTimers()
    const { fetchMock, wrapper } = mountPoller(200)
    await flushPromises()
    await vi.advanceTimersByTimeAsync(8000)
    expect(fetchMock).toHaveBeenCalledTimes(3)
    wrapper.unmount()
  })
})
