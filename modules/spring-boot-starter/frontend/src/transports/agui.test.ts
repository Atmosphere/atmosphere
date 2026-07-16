// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ConnectionStatus } from 'atmosphere.js'
import { AgUiChatTransport, AgUiEventTranslator, AgUiSseParser } from './agui'
import { createChatTransport } from './index'
import type { ChatTransportHandlers, ChatTransportOptions } from './types'

/**
 * Pins the AG-UI adapter against the server's named-event SSE framing
 * (AgUiHandler#SseWriter: `event: TYPE\ndata: {json}\n\n`) and the
 * event mapping that inverts the server's AgUiEventMapper.
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
    endpoint: '/atmosphere/agent/assistant/agui',
    isBroadcast: () => false,
    status: new ConnectionStatus({ initialTransport: 'ag-ui' }),
    maxReconnectOnClose: 10,
  }
}

function sse(type: string, data: unknown): string {
  return `event: ${type}\ndata: ${JSON.stringify(data)}\n\n`
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('AgUiSseParser', () => {
  it('parses event/data pairs', () => {
    const p = new AgUiSseParser()
    expect(p.push(sse('TEXT_MESSAGE_CONTENT', { messageId: 'm1', delta: 'hi' }))).toEqual([
      { type: 'TEXT_MESSAGE_CONTENT', data: { messageId: 'm1', delta: 'hi' } },
    ])
  })

  it('buffers partial frames across chunk boundaries', () => {
    const p = new AgUiSseParser()
    const frame = sse('RUN_FINISHED', { runId: 'r1' })
    const first = p.push(frame.slice(0, 12))
    expect(first).toEqual([])
    expect(p.push(frame.slice(12))).toEqual([
      { type: 'RUN_FINISHED', data: { runId: 'r1' } },
    ])
  })

  it('drops malformed data lines without killing the stream', () => {
    const p = new AgUiSseParser()
    const events = p.push('event: RUN_STARTED\ndata: {broken\n\n'
      + sse('TEXT_MESSAGE_CONTENT', { delta: 'ok' }))
    expect(events).toEqual([
      { type: 'TEXT_MESSAGE_CONTENT', data: { delta: 'ok' } },
    ])
  })

  it('ignores data lines with no preceding event name', () => {
    const p = new AgUiSseParser()
    expect(p.push('data: {"orphan":true}\n\n')).toEqual([])
  })
})

describe('AgUiEventTranslator', () => {
  it('maps TEXT_MESSAGE_CONTENT deltas to streaming-text', () => {
    const t = new AgUiEventTranslator()
    expect(t.translate({ type: 'TEXT_MESSAGE_CONTENT', data: { delta: 'tok' } }))
      .toEqual([{ type: 'streaming-text', data: 'tok' }])
  })

  it('correlates TOOL_CALL_RESULT to the name from TOOL_CALL_START', () => {
    const t = new AgUiEventTranslator()
    expect(t.translate({ type: 'TOOL_CALL_START', data: { toolCallId: 'tc1', name: 'getWeather' } }))
      .toEqual([{ event: 'tool-start', data: { toolName: 'getWeather', arguments: {} } }])
    expect(t.translate({ type: 'TOOL_CALL_RESULT', data: { toolCallId: 'tc1', result: '22C' } }))
      .toEqual([{ event: 'tool-result', data: { toolName: 'getWeather', result: '22C' } }])
  })

  it('maps RUN_FINISHED to complete and RUN_ERROR to error', () => {
    const t = new AgUiEventTranslator()
    expect(t.translate({ type: 'RUN_FINISHED', data: {} })).toEqual([{ type: 'complete' }])
    expect(t.translate({ type: 'RUN_ERROR', data: { message: 'boom' } }))
      .toEqual([{ type: 'error', data: 'boom' }])
  })

  it('drops lifecycle-only events with no Console mapping', () => {
    const t = new AgUiEventTranslator()
    for (const type of ['RUN_STARTED', 'TEXT_MESSAGE_START', 'TEXT_MESSAGE_END',
                        'TOOL_CALL_ARGS', 'TOOL_CALL_END', 'STEP_STARTED', 'STEP_FINISHED']) {
      expect(t.translate({ type, data: {} })).toEqual([])
    }
  })
})

describe('AgUiChatTransport', () => {
  it('reports open when the endpoint is reachable (any HTTP status)', async () => {
    // A HEAD may 405 on the AG-UI endpoint — still a live server.
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 405 })))
    const h = handlers([])
    const t = new AgUiChatTransport(options(), h)
    await t.connect()
    expect(h.onOpen).toHaveBeenCalledOnce()
  })

  it('refuses to open on network failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('network down')))
    const h = handlers([])
    const t = new AgUiChatTransport(options(), h)
    await expect(t.connect()).rejects.toThrow('network down')
    expect(h.onOpen).not.toHaveBeenCalled()
  })

  it('streams a full demo run: deltas, tool cards, completion', async () => {
    const body = sse('RUN_STARTED', { runId: 'r1' })
      + sse('TEXT_MESSAGE_START', { messageId: 'm1', role: 'assistant' })
      + sse('TEXT_MESSAGE_CONTENT', { messageId: 'm1', delta: 'The weather ' })
      + sse('TOOL_CALL_START', { toolCallId: 'tc1', name: 'getWeather' })
      + sse('TOOL_CALL_RESULT', { toolCallId: 'tc1', result: 'sunny' })
      + sse('TEXT_MESSAGE_CONTENT', { messageId: 'm1', delta: 'is sunny' })
      + sse('TEXT_MESSAGE_END', { messageId: 'm1' })
      + sse('RUN_FINISHED', { runId: 'r1' })
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(null, { status: 405 })) // connect HEAD
      .mockResolvedValueOnce(new Response(new TextEncoder().encode(body), {
        status: 200, headers: { 'Content-Type': 'text/event-stream' },
      }))
    vi.stubGlobal('fetch', fetchMock)

    const events: Array<Record<string, unknown>> = []
    const t = new AgUiChatTransport(options(), handlers(events))
    await t.connect()
    t.send('weather?')
    await vi.waitFor(() => {
      expect(events).toContainEqual({ type: 'complete' })
    })

    expect(events).toEqual([
      { type: 'streaming-text', data: 'The weather ' },
      { event: 'tool-start', data: { toolName: 'getWeather', arguments: {} } },
      { event: 'tool-result', data: { toolName: 'getWeather', result: 'sunny' } },
      { type: 'streaming-text', data: 'is sunny' },
      { type: 'complete' },
    ])
    const runBody = JSON.parse(fetchMock.mock.calls[1][1].body as string)
    expect(runBody.messages).toEqual([{ role: 'user', content: 'weather?' }])
    expect(runBody.threadId).toBeTruthy()
    expect(runBody.runId).toBeTruthy()
  })

  it('closes the bubble when the stream ends without RUN_FINISHED', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(null, { status: 405 }))
      .mockResolvedValueOnce(new Response(
        new TextEncoder().encode(sse('TEXT_MESSAGE_CONTENT', { delta: 'x' })),
        { status: 200, headers: { 'Content-Type': 'text/event-stream' } }))
    vi.stubGlobal('fetch', fetchMock)

    const events: Array<Record<string, unknown>> = []
    const t = new AgUiChatTransport(options(), handlers(events))
    await t.connect()
    t.send('hi')
    await vi.waitFor(() => {
      expect(events).toContainEqual({ type: 'complete' })
    })
  })

  it('surfaces a failed run as an error event', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(null, { status: 405 }))
      .mockResolvedValueOnce(new Response('nope', { status: 500 }))
    vi.stubGlobal('fetch', fetchMock)

    const events: Array<Record<string, unknown>> = []
    const t = new AgUiChatTransport(options(), handlers(events))
    await t.connect()
    t.send('hi')
    await vi.waitFor(() => {
      expect(events.some(e => e.type === 'error')).toBe(true)
    })
  })

  it('is created by the factory for the ag-ui transport name', async () => {
    vi.stubGlobal('fetch', vi.fn())
    const t = await createChatTransport('ag-ui', options(), handlers([]))
    expect(t).toBeInstanceOf(AgUiChatTransport)
    expect(t.name).toBe('ag-ui')
  })
})
