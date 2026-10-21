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

const { ConnectionStatus, computeReconnectDelay, maxReconnectAttempts } = await import('atmosphere.js')
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

/**
 * A durable-sessions page lost the messages broadcast during an outage of a
 * minute or more: the broadcaster's UUIDBroadcasterCache keeps a disconnected
 * client's messages for 60 s (clientIdleTime), while the atmosphere.js default
 * backoff the Console used grew to 48-96 s between attempts from the sixth
 * one, so the Console came back after the cache had dropped it. The schedule
 * is read through atmosphere.js's own computeReconnectDelay, from the request
 * the Console subscribes with.
 */
describe('AtmosphereChatTransport reconnect schedule', () => {
  it('never waits more than 25 s between attempts, growing exponentially up to that', async () => {
    captured.requests = []
    captured.subs = []
    const transport = new AtmosphereChatTransport(
      {
        endpoint: '/atmosphere/chat',
        isBroadcast: () => true,
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
    const request = captured.requests[0] as Parameters<typeof computeReconnectDelay>[0]
      & Parameters<typeof maxReconnectAttempts>[0]

    // The quota is still the Console's own.
    expect(maxReconnectAttempts(request)).toBe(10)

    // Worst-case jitter: the longest wait the schedule can produce.
    const random = vi.spyOn(Math, 'random').mockReturnValue(0.9999)
    try {
      const waits = Array.from({ length: 10 }, (_, i) => computeReconnectDelay(request, i + 1, () => 0.9999))
      for (const wait of waits) expect(wait).toBeLessThanOrEqual(25_000)
      // Exponential below the cap: 3 s, 6 s, 12 s, 24 s, then the 25 s cap.
      expect(waits.slice(0, 4).map(w => Math.round(w / 1000))).toEqual([3, 6, 12, 24])
      expect(Math.round(waits[9] / 1000)).toBe(25)
      for (let i = 1; i < waits.length; i++) expect(waits[i]).toBeGreaterThanOrEqual(waits[i - 1])

      // The jitter still spreads the tabs reconnecting at the cap: the lowest
      // jitter halves the capped wait.
      random.mockReturnValue(0)
      expect(computeReconnectDelay(request, 10, () => 0)).toBe(12_500)
      expect(computeReconnectDelay(request, 1, () => 0)).toBe(1_500)
    } finally {
      random.mockRestore()
    }
  })
})
