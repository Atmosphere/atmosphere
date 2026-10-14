// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import Observability from './Observability.vue'

/**
 * The Observability tab is offered for the actuator health plane alone. On a
 * host whose console info says `hasMetrics: false` (no metricsPath) the tab
 * still told the user "No atmosphere.* meters have recorded anything yet.
 * Send a message, then check back." — but no message can ever populate a
 * meters panel the server does not expose.
 */

function stubFetch(routes: Record<string, unknown>) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (url in routes) return new Response(JSON.stringify(routes[url]), { status: 200 })
    return new Response('', { status: 404 })
  }))
}

describe('Observability without a metrics plane', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows Health and says the meters are not exposed instead of asking for traffic', async () => {
    stubFetch({ '/actuator/health': { status: 'UP' } })
    const wrapper = mount(Observability, {
      props: { active: true, healthPath: '/actuator/health' },
    })
    await flushPromises()

    expect(wrapper.get('[data-testid="health-status"]').text()).toContain('UP')
    expect(wrapper.find('[data-testid="metrics-empty"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Send a message')
    expect(wrapper.get('[data-testid="metrics-not-exposed"]').text())
      .toContain('does not expose a metrics endpoint')
    wrapper.unmount()
  })

  it('keeps the send-a-message hint when the metrics plane exists but is still empty', async () => {
    stubFetch({
      '/actuator/health': { status: 'UP' },
      '/actuator/metrics': { names: [] },
    })
    const wrapper = mount(Observability, {
      props: {
        active: true, healthPath: '/actuator/health',
        metricsPath: '/actuator/metrics', metricsFormat: 'actuator',
      },
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="metrics-not-exposed"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="metrics-empty"]').text()).toContain('Send a message')
    wrapper.unmount()
  })
})
