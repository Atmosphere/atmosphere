// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ConnectionStatus } from 'atmosphere.js'
import {
  ConnectEnvelopeReader,
  GrpcChatTransport,
  encodeConnectEnvelope,
  splitChatPayload,
} from './grpc'
import type { ChatTransportHandlers, ChatTransportOptions } from './types'

/**
 * Pins the gRPC adapter against ConnectProtocolServlet's Connect-JSON wire:
 * 5-byte envelopes (flags + BE32 length) around proto-JSON AtmosphereMessage
 * frames ({type, topic, payload, trackingId}); first Subscribe frame is the
 * ACK carrying the server-assigned trackingId; MESSAGE payloads are
 * "author: text" broadcast strings.
 */

function handlers(events: Array<Record<string, unknown>>): ChatTransportHandlers {
  return {
    onOpen: vi.fn(), onClose: vi.fn(), onError: vi.fn(), onReconnect: vi.fn(),
    onReopen: vi.fn(), onClientTimeout: vi.fn(), onFailureToReconnect: vi.fn(),
    onEvent: (msg) => events.push(msg),
    onRawText: vi.fn(),
  }
}

function options(): ChatTransportOptions {
  return {
    endpoint: '/org.atmosphere.grpc.AtmosphereService',
    isBroadcast: () => true,
    status: new ConnectionStatus({ initialTransport: 'grpc' }),
    maxReconnectOnClose: 10,
  }
}

function envelopeStream(...envelopes: Uint8Array[]): Response {
  const total = envelopes.reduce((n, e) => n + e.length, 0)
  const body = new Uint8Array(total)
  let off = 0
  for (const e of envelopes) { body.set(e, off); off += e.length }
  return new Response(body, {
    status: 200,
    headers: { 'Content-Type': 'application/connect+json' },
  })
}

const ACK = encodeConnectEnvelope(0, JSON.stringify({ type: 'ACK', trackingId: 'conn-1' }))

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('ConnectEnvelopeReader', () => {
  it('decodes a single envelope', () => {
    const r = new ConnectEnvelopeReader()
    expect(r.push(encodeConnectEnvelope(0, '{"a":1}')))
      .toEqual([{ flags: 0, payload: '{"a":1}' }])
  })

  it('decodes multiple envelopes in one chunk', () => {
    const r = new ConnectEnvelopeReader()
    const a = encodeConnectEnvelope(0, 'one')
    const b = encodeConnectEnvelope(2, 'two')
    const merged = new Uint8Array(a.length + b.length)
    merged.set(a); merged.set(b, a.length)
    expect(r.push(merged)).toEqual([
      { flags: 0, payload: 'one' },
      { flags: 2, payload: 'two' },
    ])
  })

  it('buffers partial frames across chunk boundaries', () => {
    const r = new ConnectEnvelopeReader()
    const framed = encodeConnectEnvelope(0, 'split-payload')
    expect(r.push(framed.slice(0, 7))).toEqual([])
    expect(r.push(framed.slice(7))).toEqual([{ flags: 0, payload: 'split-payload' }])
  })

  it('round-trips multi-byte UTF-8 payloads', () => {
    const r = new ConnectEnvelopeReader()
    expect(r.push(encodeConnectEnvelope(0, 'héllo ✓')))
      .toEqual([{ flags: 0, payload: 'héllo ✓' }])
  })
})

describe('splitChatPayload', () => {
  it('splits "author: text" into broadcast parts', () => {
    expect(splitChatPayload('alice: hello')).toEqual({ author: 'alice', message: 'hello' })
  })

  it('passes through payloads without an author prefix', () => {
    expect(splitChatPayload('system notice')).toEqual({ message: 'system notice' })
  })

  it('splits on the first separator only', () => {
    expect(splitChatPayload('bob: a: b')).toEqual({ author: 'bob', message: 'a: b' })
  })
})

describe('GrpcChatTransport', () => {
  it('reports open only after the Subscribe ACK (runtime truth)', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(envelopeStream(
      ACK,
      encodeConnectEnvelope(0, JSON.stringify({ type: 'MESSAGE', payload: 'alice: hi all' })),
    ))
    vi.stubGlobal('fetch', fetchMock)

    const events: Array<Record<string, unknown>> = []
    const h = handlers(events)
    const t = new GrpcChatTransport(options(), h)
    await t.connect()

    expect(h.onOpen).toHaveBeenCalledOnce()
    await vi.waitFor(() => {
      expect(events).toContainEqual({ author: 'alice', message: 'hi all' })
    })
    // Subscribe request: enveloped connect+json SUBSCRIBE on the /chat topic.
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/org.atmosphere.grpc.AtmosphereService/Subscribe')
    expect(init.headers['Content-Type']).toBe('application/connect+json')
    const reqEnv = new ConnectEnvelopeReader().push(init.body as Uint8Array)
    expect(JSON.parse(reqEnv[0].payload)).toEqual({ type: 'SUBSCRIBE', topic: '/chat' })
  })

  it('rejects connect when the stream closes before an ACK', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(envelopeStream(
      encodeConnectEnvelope(2, '{}'),
    )))
    const t = new GrpcChatTransport(options(), handlers([]))
    await expect(t.connect()).rejects.toThrow('closed before ACK')
  })

  it('sends unary MESSAGE frames carrying the ACKed trackingId', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(envelopeStream(ACK))
      .mockResolvedValueOnce(new Response(JSON.stringify({ type: 'ACK' }), {
        status: 200, headers: { 'Content-Type': 'application/json' },
      }))
    vi.stubGlobal('fetch', fetchMock)

    const t = new GrpcChatTransport(options(), handlers([]))
    await t.connect()
    t.send('hello room')
    await vi.waitFor(() => {
      expect(fetchMock).toHaveBeenCalledTimes(2)
    })

    const [url, init] = fetchMock.mock.calls[1]
    expect(url).toBe('/org.atmosphere.grpc.AtmosphereService/Send')
    expect(init.headers['Content-Type']).toBe('application/json')
    expect(JSON.parse(init.body as string)).toEqual({
      type: 'MESSAGE',
      topic: '/chat',
      payload: 'console: hello room',
      trackingId: 'conn-1',
    })
  })

  it('surfaces an error trailer from the end-of-stream frame', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(envelopeStream(
      ACK,
      encodeConnectEnvelope(2, JSON.stringify({ error: { message: 'server going away' } })),
    ))
    vi.stubGlobal('fetch', fetchMock)

    const events: Array<Record<string, unknown>> = []
    const t = new GrpcChatTransport(options(), handlers(events))
    await t.connect()
    await vi.waitFor(() => {
      expect(events).toContainEqual({ type: 'error', data: 'server going away' })
    })
    t.disconnect()
  })
})
