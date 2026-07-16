// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ConnectionStatus } from 'atmosphere.js'
import { A2aChatTransport, extractTaskText, parseA2aData } from './a2a'
import { createChatTransport } from './index'
import type { ChatTransportHandlers, ChatTransportOptions } from './types'

/**
 * Pins the A2A adapter against the SERVER wire shape
 * (A2aHandler#writeStreamChunk): JSON-RPC envelopes wrapping
 * result.artifactUpdate.artifact.parts[].text, terminated by [DONE].
 * The shipped a2a sample frontend read `parsed.artifact.parts[0].text`
 * (one level too shallow) and rendered nothing — the shallow-shape
 * regression below documents that bug class.
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
    endpoint: '/atmosphere/a2a',
    isBroadcast: () => false,
    status: new ConnectionStatus({ initialTransport: 'a2a' }),
    maxReconnectOnClose: 10,
  }
}

function sseResponse(...dataLines: string[]): Response {
  const body = dataLines.map(d => `data: ${d}\n\n`).join('')
  return new Response(new TextEncoder().encode(body), {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
  })
}

function jsonResponse(payload: unknown): Response {
  return new Response(JSON.stringify(payload), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

const SERVER_CHUNK = JSON.stringify({
  jsonrpc: '2.0',
  id: 1,
  result: { artifactUpdate: { artifact: { artifactId: 'u1', parts: [{ text: 'hello ' }] } } },
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('parseA2aData', () => {
  it('extracts text from the real server envelope (result.artifactUpdate.artifact.parts)', () => {
    expect(parseA2aData(SERVER_CHUNK)).toEqual([{ kind: 'text', text: 'hello ' }])
  })

  it('yields nothing for the shallow shape the old sample frontend expected', () => {
    // {artifact:{parts:[{text}]}} without the result.artifactUpdate wrapper
    // is NOT what the server sends — reading it was the sample's render-nothing
    // bug. The adapter must not resurrect that shape.
    const shallow = JSON.stringify({ artifact: { parts: [{ text: 'ghost' }] } })
    expect(parseA2aData(shallow)).toEqual([])
  })

  it('maps [DONE] to a done frame', () => {
    expect(parseA2aData('[DONE]')).toEqual([{ kind: 'done' }])
    expect(parseA2aData('  [DONE]  ')).toEqual([{ kind: 'done' }])
  })

  it('maps a JSON-RPC error envelope to an error frame', () => {
    const err = JSON.stringify({ jsonrpc: '2.0', id: 1, error: { code: -32001, message: 'task not found' } })
    expect(parseA2aData(err)).toEqual([{ kind: 'error', message: 'task not found' }])
  })

  it('collects every text part in a multi-part artifact', () => {
    const multi = JSON.stringify({
      result: { artifactUpdate: { artifact: { parts: [{ text: 'a' }, { text: 'b' }] } } },
    })
    expect(parseA2aData(multi)).toEqual([
      { kind: 'text', text: 'a' },
      { kind: 'text', text: 'b' },
    ])
  })

  it('surfaces non-JSON payloads verbatim instead of dropping them', () => {
    expect(parseA2aData('plain token')).toEqual([{ kind: 'text', text: 'plain token' }])
  })

  it('returns nothing for empty payloads', () => {
    expect(parseA2aData('   ')).toEqual([])
  })
})

describe('extractTaskText', () => {
  it('reads result.artifacts[0].parts[0].text from a unary Task', () => {
    expect(extractTaskText({ artifacts: [{ parts: [{ text: 'sync reply' }] }] })).toBe('sync reply')
  })

  it('returns null when the task carries no text', () => {
    expect(extractTaskText({ artifacts: [] })).toBeNull()
    expect(extractTaskText(null)).toBeNull()
  })
})

describe('A2aChatTransport', () => {
  it('connects only after the agent card answers (runtime truth)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ jsonrpc: '2.0', id: 1, result: { name: 'weather' } }))
    vi.stubGlobal('fetch', fetchMock)

    const events: Array<Record<string, unknown>> = []
    const h = handlers(events)
    const t = new A2aChatTransport(options(), h)
    await t.connect()

    expect(h.onOpen).toHaveBeenCalledOnce()
    const body = JSON.parse(fetchMock.mock.calls[0][1].body as string)
    expect(body.method).toBe('agent/authenticatedExtendedCard')
  })

  it('refuses to open when the agent card errors', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      jsonResponse({ jsonrpc: '2.0', id: 1, error: { message: 'no card' } })))

    const h = handlers([])
    const t = new A2aChatTransport(options(), h)
    await expect(t.connect()).rejects.toThrow('no card')
    expect(h.onOpen).not.toHaveBeenCalled()
  })

  it('streams tokens then completes on [DONE]', async () => {
    const fetchMock = vi.fn()
      // connect: agent card
      .mockResolvedValueOnce(jsonResponse({ jsonrpc: '2.0', id: 1, result: {} }))
      // send: SSE stream
      .mockResolvedValueOnce(sseResponse(SERVER_CHUNK, '[DONE]'))
    vi.stubGlobal('fetch', fetchMock)

    const events: Array<Record<string, unknown>> = []
    const t = new A2aChatTransport(options(), handlers(events))
    await t.connect()
    t.send('hi')
    await vi.waitFor(() => {
      expect(events).toContainEqual({ type: 'complete' })
    })

    expect(events).toEqual([
      { type: 'streaming-text', data: 'hello ' },
      { type: 'complete' },
    ])
    const streamBody = JSON.parse(fetchMock.mock.calls[1][1].body as string)
    expect(streamBody.method).toBe('message/stream')
    expect(streamBody.params.message.parts[0].text).toBe('hi')
  })

  it('falls back to unary message/send when streaming fails', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ jsonrpc: '2.0', id: 1, result: {} })) // card
      .mockRejectedValueOnce(new Error('stream down'))                            // stream
      .mockResolvedValueOnce(jsonResponse({                                       // message/send
        jsonrpc: '2.0', id: 3,
        result: { artifacts: [{ parts: [{ text: 'sync reply' }] }] },
      }))
    vi.stubGlobal('fetch', fetchMock)

    const events: Array<Record<string, unknown>> = []
    const t = new A2aChatTransport(options(), handlers(events))
    await t.connect()
    t.send('hi')
    await vi.waitFor(() => {
      expect(events).toContainEqual({ type: 'complete' })
    })

    expect(events).toEqual([
      { type: 'streaming-text', data: 'sync reply' },
      { type: 'complete' },
    ])
    expect(JSON.parse(fetchMock.mock.calls[2][1].body as string).method).toBe('message/send')
  })

  it('closes the bubble when the stream ends without [DONE]', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ jsonrpc: '2.0', id: 1, result: {} }))
      .mockResolvedValueOnce(sseResponse(SERVER_CHUNK)) // no [DONE]
    vi.stubGlobal('fetch', fetchMock)

    const events: Array<Record<string, unknown>> = []
    const t = new A2aChatTransport(options(), handlers(events))
    await t.connect()
    t.send('hi')
    await vi.waitFor(() => {
      expect(events).toContainEqual({ type: 'complete' })
    })
  })

  it('is created by the factory for the a2a transport name', async () => {
    const t = await createChatTransport('a2a', options(), handlers([]))
    expect(t).toBeInstanceOf(A2aChatTransport)
    expect(t.name).toBe('a2a')
  })
})
