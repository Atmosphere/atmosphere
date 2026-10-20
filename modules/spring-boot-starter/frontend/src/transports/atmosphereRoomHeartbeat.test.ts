// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { ConnectionStatus } from 'atmosphere.js'
import { AtmosphereChatTransport } from './atmosphere'

/**
 * A server that closes connections it has not heard from (MAX_INACTIVE) names
 * the client heartbeat interval in its protocol handshake, the first thing it
 * writes. A Console joined to a Room Protocol room (atmosphere.console-room)
 * used to subscribe with the protocol off: it never read that handshake, sent
 * no heartbeat, and was closed as idle every MAX_INACTIVE ms, coming back
 * under a new tracking id. This drives the real atmosphere.js over a fake
 * WebSocket with the frames such a server writes.
 */
interface FakeSocket {
  url: string
  readyState: number
  sent: string[]
  binaryType: string
  onopen: (() => void) | null
  onmessage: ((event: { data: string }) => void) | null
  onerror: (() => void) | null
  onclose: ((event: { code: number; reason: string; wasClean: boolean }) => void) | null
  send: (data: string) => void
  close: () => void
}

describe('AtmosphereChatTransport in room mode', () => {
  let sockets: FakeSocket[]
  const originalWebSocket = globalThis.WebSocket

  beforeEach(() => {
    vi.useFakeTimers()
    sockets = []
    const Ctor = vi.fn(function (this: unknown, url: string) {
      const socket: FakeSocket = {
        url, readyState: 0, sent: [], binaryType: 'blob',
        onopen: null, onmessage: null, onerror: null, onclose: null,
        send(data: string) { this.sent.push(data) },
        close() { this.readyState = 3 },
      }
      sockets.push(socket)
      return socket
    }) as unknown as typeof WebSocket
    Object.assign(Ctor, { CONNECTING: 0, OPEN: 1, CLOSING: 2, CLOSED: 3 })
    globalThis.WebSocket = Ctor
  })

  afterEach(() => {
    globalThis.WebSocket = originalWebSocket
    vi.useRealTimers()
  })

  it('reads the handshake, joins, renders room frames and heartbeats at the announced interval', async () => {
    const onEvent = vi.fn()
    const onRawText = vi.fn()
    const onOpen = vi.fn()
    const transport = new AtmosphereChatTransport(
      {
        endpoint: 'http://localhost:8080/atmosphere/chat',
        isBroadcast: () => true,
        status: new ConnectionStatus({ initialTransport: 'websocket' }),
        maxReconnectOnClose: 10,
        rooms: { room: 'lobby', memberId: 'console-abcd', sinceId: () => 0 },
      },
      {
        onOpen, onClose() {}, onError() {}, onReconnect() {},
        onReopen() {}, onClientTimeout() {}, onFailureToReconnect() {},
        onEvent, onRawText,
      },
    )

    const connected = transport.connect()
    await vi.waitFor(() => expect(sockets).toHaveLength(1))
    const socket = sockets[0]
    expect(socket.url).toContain('X-atmo-protocol=true')
    // Room frames are written unframed: the length decoder stays off.
    expect(socket.url).not.toContain('X-Atmosphere-TrackMessageSize')

    socket.readyState = 1
    socket.onopen!()
    await connected
    // The server's first write: tracking id, heartbeat interval, padding.
    socket.onmessage!({ data: '6f1c2d3e-0000-4000-8000-000000000001|30000|X|' })
    await vi.waitFor(() => expect(onOpen).toHaveBeenCalledTimes(1))

    expect(transport.uuid).toBe('6f1c2d3e-0000-4000-8000-000000000001')
    expect(onRawText).not.toHaveBeenCalled()
    const join = socket.sent.map(s => JSON.parse(s)).find(m => m.type === 'join')
    expect(join).toMatchObject({ type: 'join', room: 'lobby', memberId: 'console-abcd' })

    socket.onmessage!({ data: '{"type":"join_ack","room":"lobby","members":[{"id":"console-abcd"}]}' })
    socket.onmessage!({ data: '{"type":"message","room":"lobby","from":"console-ef01","data":"hi","id":1}' })
    expect(onEvent.mock.calls.map(([msg]) => msg.type)).toEqual(['join_ack', 'message'])

    // The server's own heartbeat is padding, not a chat frame.
    socket.onmessage!({ data: 'X' })
    expect(onRawText).not.toHaveBeenCalled()

    const before = socket.sent.length
    vi.advanceTimersByTime(30_000)
    expect(socket.sent.slice(before)).toEqual(['X'])
    vi.advanceTimersByTime(30_000)
    expect(socket.sent.slice(before)).toEqual(['X', 'X'])

    transport.disconnect()
  })
})
