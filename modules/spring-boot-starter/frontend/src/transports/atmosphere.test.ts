// @vitest-environment jsdom
import { describe, it, expect, vi, type Mock } from 'vitest'
import type { SubscriptionHandlers } from 'atmosphere.js'

/**
 * The Console frees a prompt the server refused only when the atmosphere.js
 * send error reaches useAtmosphereChat intact: its `name`
 * ('AtmosphereSendError') tells an undelivered message from a broken
 * connection. The composable's own tests mock the transport away, so this pins
 * the pass-through in AtmosphereChatTransport itself.
 */
const captured: {
  handlers: SubscriptionHandlers<string> | null
  requests: Array<Record<string, unknown>>
  subs: Array<{ close: Mock<() => Promise<void>>; abandon: Mock<() => Promise<void>> }>
} = { handlers: null, requests: [], subs: [] }

vi.mock('atmosphere.js', async (importOriginal) => {
  const actual = await importOriginal<typeof import('atmosphere.js')>()
  class FakeAtmosphere {
    async subscribe(request: Record<string, unknown>, handlers: SubscriptionHandlers<string>) {
      captured.handlers = handlers
      captured.requests.push(request)
      const sub = {
        push: vi.fn(), close: vi.fn(async () => {}), abandon: vi.fn(async () => {}), state: 'connected',
        uuid: `uuid-${captured.subs.length + 1}`, resumePoint: `s1:${7 + captured.subs.length}`,
      }
      captured.subs.push(sub)
      return sub
    }
    async closeAll() {
      for (const sub of captured.subs) await sub.close()
    }
  }
  return { ...actual, Atmosphere: FakeAtmosphere }
})

const { ConnectionStatus } = await import('atmosphere.js')
const { AtmosphereChatTransport } = await import('./atmosphere')

describe('AtmosphereChatTransport error pass-through', () => {
  it('hands the atmosphere.js send error to onError unchanged, name included', async () => {
    const onError = vi.fn()
    const transport = new AtmosphereChatTransport(
      {
        endpoint: '/atmosphere/ai-chat',
        isBroadcast: () => false,
        status: new ConnectionStatus({ initialTransport: 'websocket' }),
        maxReconnectOnClose: 10,
      },
      {
        onOpen() {}, onClose() {}, onError, onReconnect() {},
        onReopen() {}, onClientTimeout() {}, onFailureToReconnect() {},
        onEvent() {}, onRawText() {},
      },
    )
    await transport.connect()
    expect(captured.handlers?.error).toBeTypeOf('function')

    const sendError = new Error('long-polling POST send failed with status 503 after 3 attempts')
    sendError.name = 'AtmosphereSendError'
    captured.handlers!.error!(sendError)

    expect(onError).toHaveBeenCalledTimes(1)
    expect(onError.mock.calls[0][0]).toBe(sendError)
    expect(onError.mock.calls[0][0].name).toBe('AtmosphereSendError')
  })
})

/**
 * The WebTransport subscription gives up after its few retries (a drop longer
 * than ~11 s): the Console rebuilds it over WebSocket. That rebuild used to
 * reconnect as a brand-new client (tracking id 0, no resume point), so the
 * room's cache — keyed by the old tracking id — and the missed turns never
 * reached it. It now comes back as the same client, from what it received.
 */
describe('AtmosphereChatTransport WebTransport fallback', () => {
  it('rebuilds under the lost tracking id and resume point, without leaving', async () => {
    captured.requests = []
    captured.subs = []
    const transport = new AtmosphereChatTransport(
      {
        endpoint: '/atmosphere/classroom/math',
        isBroadcast: () => false,
        status: new ConnectionStatus({ initialTransport: 'webtransport' }),
        maxReconnectOnClose: 10,
        webTransport: { port: 4446 },
      },
      {
        onOpen() {}, onClose() {}, onError() {}, onReconnect() {},
        onReopen() {}, onClientTimeout() {}, onFailureToReconnect() {},
        onEvent() {}, onRawText() {},
      },
    )
    await transport.connect()
    expect(captured.requests[0].transport).toBe('webtransport')

    captured.handlers!.failureToReconnect!(captured.requests[0] as never, {} as never)
    await vi.waitFor(() => expect(captured.requests).toHaveLength(2))

    expect(captured.requests[1].transport).toBe('websocket')
    expect(captured.requests[1].trackingId).toBe('uuid-1')
    expect(captured.requests[1].resume).toBe('s1:7')
    // Lost, not left: no close (which ends the runs and forgets the run id).
    expect(captured.subs[0].abandon).toHaveBeenCalledTimes(1)
    expect(captured.subs[0].close).not.toHaveBeenCalled()
  })
})

/**
 * A broadcast endpoint echoes a message to its sender too; the page tells its
 * own echo from another member's by the author it sent, so the envelope
 * carries the page's name rather than one shared by every Console.
 */
describe('AtmosphereChatTransport broadcast envelope', () => {
  it("sends as the page's own author", async () => {
    captured.subs = []
    const transport = new AtmosphereChatTransport(
      {
        endpoint: '/atmosphere/chat',
        isBroadcast: () => true,
        author: 'console-1a2b',
        status: new ConnectionStatus({ initialTransport: 'websocket' }),
        maxReconnectOnClose: 10,
      },
      {
        onOpen() {}, onClose() {}, onError() {}, onReconnect() {},
        onReopen() {}, onClientTimeout() {}, onFailureToReconnect() {},
        onEvent() {}, onRawText() {},
      },
    )
    await transport.connect()
    transport.send('hello')
    const push = (captured.subs[0] as unknown as { push: Mock<(m: string) => void> }).push
    expect(JSON.parse(push.mock.calls[0][0])).toMatchObject({ author: 'console-1a2b', message: 'hello' })
  })
})
