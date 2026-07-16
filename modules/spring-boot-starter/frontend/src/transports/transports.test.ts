// @vitest-environment jsdom
import { describe, it, expect } from 'vitest'
import { ConnectionStatus } from 'atmosphere.js'
import { parseAtmosphereFrames, AtmosphereChatTransport, createChatTransport } from './index'
import type { ChatTransportHandlers, ChatTransportOptions } from './index'

/**
 * Pins the ChatTransport seam extracted from useAtmosphereChat: the
 * Atmosphere wire framing rules (TrackMessageSizeInterceptor length
 * prefixes, single/newline-separated JSON, raw-text fallback) and the
 * factory contract (foreign transports fail loud until their adapter
 * ships — never a silent downgrade to the WS transport, which would
 * reconnect-loop against a Connect/SSE endpoint).
 */

function noopHandlers(): ChatTransportHandlers {
  return {
    onOpen() {}, onClose() {}, onError() {}, onReconnect() {},
    onReopen() {}, onClientTimeout() {}, onFailureToReconnect() {},
    onEvent() {}, onRawText() {},
  }
}

function options(): ChatTransportOptions {
  return {
    endpoint: '/atmosphere/ai-chat',
    isBroadcast: () => false,
    status: new ConnectionStatus({ initialTransport: 'websocket' }),
    maxReconnectOnClose: 10,
  }
}

describe('parseAtmosphereFrames', () => {
  it('returns nothing for empty or whitespace-only bodies', () => {
    expect(parseAtmosphereFrames('')).toEqual([])
    expect(parseAtmosphereFrames('   \n  ')).toEqual([])
  })

  it('decodes a single JSON event', () => {
    const frames = parseAtmosphereFrames('{"type":"streaming-text","data":"hi"}')
    expect(frames).toEqual([
      { kind: 'event', msg: { type: 'streaming-text', data: 'hi' } },
    ])
  })

  it('decodes length-prefixed chunks (TrackMessageSizeInterceptor)', () => {
    const a = '{"type":"streaming-text","data":"a"}'
    const b = '{"type":"complete"}'
    const frames = parseAtmosphereFrames(`${a.length}|${a}${b.length}|${b}`)
    expect(frames).toEqual([
      { kind: 'event', msg: { type: 'streaming-text', data: 'a' } },
      { kind: 'event', msg: { type: 'complete' } },
    ])
  })

  it('falls back to raw text for a non-JSON length-prefixed chunk', () => {
    const frames = parseAtmosphereFrames('5|hello')
    expect(frames).toEqual([{ kind: 'raw', text: 'hello' }])
  })

  it('decodes newline-separated JSON objects', () => {
    const frames = parseAtmosphereFrames(
      '{"event":"text-delta","data":{"text":"x"}}\n{"type":"complete"}')
    expect(frames).toEqual([
      { kind: 'event', msg: { event: 'text-delta', data: { text: 'x' } } },
      { kind: 'event', msg: { type: 'complete' } },
    ])
  })

  it('emits raw frames for non-JSON lines in a multi-line body', () => {
    const frames = parseAtmosphereFrames('plain text\n{"type":"complete"}')
    expect(frames).toEqual([
      { kind: 'raw', text: 'plain text' },
      { kind: 'event', msg: { type: 'complete' } },
    ])
  })
})

describe('createChatTransport', () => {
  it('builds the Atmosphere transport by default', async () => {
    const t = await createChatTransport('atmosphere', options(), noopHandlers())
    expect(t).toBeInstanceOf(AtmosphereChatTransport)
    expect(t.name).toBe('atmosphere')
  })

  it('resolves every server-advertised foreign transport to its adapter', async () => {
    // detectTransport validates to exactly {atmosphere, grpc, a2a, ag-ui} —
    // each foreign name must map to a real lazy-loaded adapter so the
    // console never advertises a protocol it cannot speak (Runtime Truth).
    for (const name of ['grpc', 'a2a', 'ag-ui'] as const) {
      const t = await createChatTransport(name, options(), noopHandlers())
      expect(t.name).toBe(name)
    }
  })
})

describe('AtmosphereChatTransport lifecycle guards', () => {
  it('send and sendControl are safe no-ops before connect', () => {
    const t = new AtmosphereChatTransport(options(), noopHandlers())
    expect(() => t.send('hello')).not.toThrow()
    expect(() => t.sendControl('/__approval/x/approve')).not.toThrow()
  })

  it('disconnect is idempotent and safe before connect', () => {
    const t = new AtmosphereChatTransport(options(), noopHandlers())
    expect(() => { t.disconnect(); t.disconnect() }).not.toThrow()
  })
})
