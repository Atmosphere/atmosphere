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

  it('fails loud for foreign transports whose adapter has not shipped', async () => {
    // a2a and ag-ui ship adapters (see a2a.test.ts / agui.test.ts); grpc is pending.
    await expect(createChatTransport('grpc', options(), noopHandlers()))
      .rejects.toThrow("Console transport 'grpc' adapter is not available")
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
