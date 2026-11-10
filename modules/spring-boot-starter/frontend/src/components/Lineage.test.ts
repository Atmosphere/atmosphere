// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import App from '../App.vue'
import Lineage from './Lineage.vue'
import { fingerprintOf, servesEntries, type LineageEntry } from '../lib/lineage'

const entry: LineageEntry = {
  requestId: 'lin_1',
  userId: 'alice',
  agentId: 'support-agent',
  conversationId: 'c1',
  sessionId: 's1',
  timestamp: '2026-11-10T15:00:00Z',
  userPrompt: '',
  toolCalls: [{ name: 'lookup_order', invokedAt: '2026-11-10T15:00:01Z', resultSummary: '' }],
  ragSources: [],
  tokens: { input: 10, output: 5, cachedInput: 0, total: 15 },
  costUsd: '0.0012',
  terminalReason: 'OK',
  attributes: {
    'ai.agent.runtime': 'built-in',
    'ai.agent.model': 'gpt-4o-mini',
    'ai.agent.system_prompt_sha256': 'a'.repeat(64),
    'ai.agent.tool_set_sha256': 'b'.repeat(64),
  },
}

function stubFetch(routes: Record<string, { status: number; body: unknown }>) {
  const fetchMock = vi.fn(async (url: string) => {
    const route = routes[url]
    if (!route) return new Response('[]', { status: 200 })
    return new Response(JSON.stringify(route.body), { status: route.status })
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('servesEntries', () => {
  it('accepts only the in-memory recorder', () => {
    expect(servesEntries({ recorder: 'in-memory', capacity: 1000 })).toBe(true)
    expect(servesEntries({ recorder: 'none' })).toBe(false)
    expect(servesEntries({ recorder: 'com.example.KafkaLineageRecorder' })).toBe(false)
    expect(servesEntries(null)).toBe(false)
    expect(servesEntries('in-memory')).toBe(false)
  })
})

describe('fingerprintOf', () => {
  it('is null for a turn with no ai.agent.* attributes', () => {
    expect(fingerprintOf({ ...entry, attributes: { 'ai.custom': 'x' } })).toBeNull()
  })
})

describe('Lineage tab', () => {
  it('renders the agent configuration, tools, tokens and cost of each turn', async () => {
    stubFetch({ '/api/admin/ai/lineage?limit=200': { status: 200, body: [entry] } })
    const wrapper = mount(Lineage, {
      props: { active: true, status: { recorder: 'in-memory', capacity: 1000, size: 1, captureContent: false } },
    })
    await flushPromises()

    const row = wrapper.get('[data-testid="lineage-entry"]')
    expect(row.text()).toContain('OK')
    expect(row.text()).toContain('support-agent')
    expect(row.text()).toContain('15 tokens')
    expect(row.text()).toContain('$0.0012')
    expect(row.text()).toContain('lookup_order')
    const fp = wrapper.get('[data-testid="lineage-fingerprint"]').text()
    expect(fp).toContain('built-in')
    expect(fp).toContain('gpt-4o-mini')
    expect(fp).toContain('a'.repeat(12))
    expect(fp).not.toContain('a'.repeat(13))
    expect(wrapper.get('[data-testid="lineage-status"]').text()).toContain('no prompt or tool text')
    wrapper.unmount()
  })

  it('shows the prompt only when the server records content', async () => {
    const withPrompt = { ...entry, userPrompt: 'where is my order?' }
    stubFetch({ '/api/admin/ai/lineage?limit=200': { status: 200, body: [withPrompt] } })
    const off = mount(Lineage, { props: { active: true, status: { recorder: 'in-memory', captureContent: false } } })
    await flushPromises()
    expect(off.text()).not.toContain('where is my order?')
    off.unmount()

    const on = mount(Lineage, { props: { active: true, status: { recorder: 'in-memory', captureContent: true } } })
    await flushPromises()
    expect(on.text()).toContain('where is my order?')
    on.unmount()
  })

  it('reports a refused read instead of an empty list', async () => {
    stubFetch({ '/api/admin/ai/lineage?limit=200': { status: 401, body: {} } })
    const wrapper = mount(Lineage, { props: { active: true, status: { recorder: 'in-memory' } } })
    await flushPromises()
    expect(wrapper.text()).toContain('401')
    expect(wrapper.text()).not.toContain('No turns recorded yet')
    wrapper.unmount()
  })
})

describe('Console shows the Lineage tab only for a recorder it can read', () => {
  const info = { subtitle: 't', endpoint: '/atmosphere/ai-chat', mode: 'ai' }
  const stubs = { ChatContainer: defineComponent({ setup: () => () => h('div') }) }

  it('shows it when the status reports the in-memory recorder', async () => {
    const fetchMock = stubFetch({
      '/api/console/info': { status: 200, body: info },
      '/api/admin/ai/lineage/status': { status: 200, body: { recorder: 'in-memory', capacity: 1000 } },
    })
    const wrapper = mount(App, { global: { stubs } })
    await flushPromises()
    expect(wrapper.find('[data-testid="tab-lineage"]').exists()).toBe(true)
    const statusCall = fetchMock.mock.calls.find(([url]) => url === '/api/admin/ai/lineage/status')
    expect(statusCall).toBeDefined()
    wrapper.unmount()
  })

  it.each([
    ['no recorder', { status: 200, body: { recorder: 'none' } }],
    ['a recorder that persists elsewhere', { status: 200, body: { recorder: 'com.example.Sink' } }],
    ['no admin route', { status: 404, body: {} }],
  ])('hides it with %s', async (_label, statusRoute) => {
    stubFetch({
      '/api/console/info': { status: 200, body: info },
      '/api/admin/ai/lineage/status': statusRoute,
    })
    const wrapper = mount(App, { global: { stubs } })
    await flushPromises()
    expect(wrapper.find('[data-testid="tab-lineage"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('does not ask when the host declares no admin plane', async () => {
    const fetchMock = stubFetch({
      '/api/console/info': { status: 200, body: { ...info, hasAdmin: false } },
    })
    const wrapper = mount(App, { global: { stubs } })
    await flushPromises()
    expect(fetchMock.mock.calls.some(([url]) => url === '/api/admin/ai/lineage/status')).toBe(false)
    wrapper.unmount()
  })
})
