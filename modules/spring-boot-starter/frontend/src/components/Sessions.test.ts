// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import Sessions from './Sessions.vue'

const UUID = '4779888b-0000-4000-8000-00000000b1ed'

type Reply = { status: number; body?: unknown }

/** Serve the Sessions tab's reads, and answer the cancel POST with `cancel`. */
function serve(cancel: Reply) {
  const calls: { url: string; init?: RequestInit }[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({ url, init })
    const reply: Reply = url === '/api/admin/agents'
      ? { status: 200, body: [{ name: 'ceo' }] }
      : url.endsWith('/sessions')
        ? { status: 200, body: [{ sessionId: UUID }] }
        : cancel
    return new Response(reply.body === undefined ? null : JSON.stringify(reply.body), {
      status: reply.status,
      headers: { 'Content-Type': 'application/json' },
    })
  }))
  return calls
}

async function clickCancel() {
  const wrapper = mount(Sessions, { props: { active: true } })
  await flushPromises()
  await wrapper.get('[data-testid="cancel-inflight"]').trigger('click')
  await flushPromises()
  return wrapper
}

/**
 * Admin writes need `atmosphere.admin.http-write-enabled=true` and a signed-in
 * caller. Out of the box neither holds, and the tab showed only
 * `→ error 403` while the run kept going.
 */
describe('Sessions cancel-inflight refused by the admin write guard', () => {
  beforeEach(() => { localStorage.clear() })
  afterEach(() => { vi.unstubAllGlobals() })

  it('says admin writes are switched off and names the property to switch them on', async () => {
    serve({ status: 403, body: {
      error: 'Admin write operations disabled',
      hint: 'Set atmosphere.admin.http-write-enabled=true to enable',
    } })
    const result = (await clickCancel()).get('.action-result').text()
    expect(result).toContain('atmosphere.admin.http-write-enabled=true')
    expect(result).toContain('sign')
    expect(result).not.toMatch(/error 403$/)
  })

  it('says the action needs an admin sign-in when the caller is anonymous', async () => {
    serve({ status: 401, body: { error: 'Authentication required for admin write operations' } })
    const result = (await clickCancel()).get('.action-result').text()
    expect(result).toMatch(/admin sign-in/i)
    expect(result).toContain('?token=')
  })

  it('says the signed-in caller is not allowed when a role or authorizer refuses it', async () => {
    serve({ status: 403, body: { error: 'Forbidden' } })
    const result = (await clickCancel()).get('.action-result').text()
    expect(result).toMatch(/not allowed/i)
  })

  it('sends the Console auth token with the cancel, as the other admin calls do', async () => {
    localStorage.setItem('atmosphere-auth-token', 'admin-token')
    const calls = serve({ status: 200, body: { status: 'in-flight LLM call cancelled' } })
    const result = (await clickCancel()).get('.action-result').text()
    const post = calls.find(c => c.init?.method === 'POST')
    expect(new Headers(post?.init?.headers).get('X-Atmosphere-Auth')).toBe('admin-token')
    expect(result).toContain('cancelled')
  })
})
