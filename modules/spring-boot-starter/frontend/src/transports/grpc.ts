import type { ChatTransport, ChatTransportHandlers, ChatTransportOptions } from './types'

/**
 * One decoded Connect-protocol envelope: flags byte + UTF-8 payload.
 * Flags bit 1 (0x02) marks the end-of-stream frame.
 */
export interface ConnectEnvelope {
  flags: number
  payload: string
}

/**
 * Incremental decoder for the Connect streaming envelope framing:
 * flags(1) + length(4, big-endian) + payload. Feed it raw byte chunks; it
 * returns completed envelopes and buffers partial frames. Pure state machine
 * so the framing rules are unit-testable without a live stream.
 */
export class ConnectEnvelopeReader {
  private buffer = new Uint8Array(0)
  private readonly decoder = new TextDecoder()

  push(chunk: Uint8Array): ConnectEnvelope[] {
    const merged = new Uint8Array(this.buffer.length + chunk.length)
    merged.set(this.buffer)
    merged.set(chunk, this.buffer.length)
    this.buffer = merged

    const envelopes: ConnectEnvelope[] = []
    while (this.buffer.length >= 5) {
      const flags = this.buffer[0]
      const len = ((this.buffer[1] << 24) | (this.buffer[2] << 16)
        | (this.buffer[3] << 8) | this.buffer[4]) >>> 0
      if (this.buffer.length < 5 + len) break
      envelopes.push({
        flags,
        payload: this.decoder.decode(this.buffer.slice(5, 5 + len)),
      })
      this.buffer = this.buffer.slice(5 + len)
    }
    return envelopes
  }
}

/**
 * Encode one Connect envelope around a UTF-8 JSON payload. Typed as
 * ArrayBuffer-backed so the result is directly usable as a fetch BodyInit
 * under TS's generic TypedArray split.
 */
export function encodeConnectEnvelope(flags: number, payload: string): Uint8Array<ArrayBuffer> {
  const bytes = new TextEncoder().encode(payload)
  const framed = new Uint8Array(5 + bytes.length)
  framed[0] = flags
  framed[1] = (bytes.length >>> 24) & 0xff
  framed[2] = (bytes.length >>> 16) & 0xff
  framed[3] = (bytes.length >>> 8) & 0xff
  framed[4] = bytes.length & 0xff
  framed.set(bytes, 5)
  return framed
}

/**
 * Split a gRPC chat payload ("author: text") into its broadcast parts —
 * same convention the grpc-chat sample uses on both directions of the wire.
 */
export function splitChatPayload(payload: string): { author?: string; message: string } {
  const idx = payload.indexOf(': ')
  if (idx > 0) {
    return { author: payload.slice(0, idx), message: payload.slice(idx + 2) }
  }
  return { message: payload }
}

/**
 * gRPC chat transport speaking the Connect protocol in JSON mode — the
 * server (ConnectProtocolServlet) accepts application/json (unary Send) and
 * application/connect+json (server-streaming Subscribe) via JsonFormat, so
 * the console needs no protobuf codegen or @connectrpc dependency. Message
 * fields follow proto-JSON naming: {type, topic, payload, trackingId}.
 *
 * options.endpoint carries the Connect service base path
 * (/org.atmosphere.grpc.AtmosphereService); the chat topic matches the
 * sample's ('/chat').
 */
export class GrpcChatTransport implements ChatTransport {
  readonly name = 'grpc' as const

  private static readonly TOPIC = '/chat'

  private readonly lifecycle
  private abort: AbortController | null = null
  private trackingId: string | null = null
  private reconnectAttempts = 0

  constructor(
    private readonly options: ChatTransportOptions,
    private readonly handlers: ChatTransportHandlers,
  ) {
    this.lifecycle = options.status.wrap<string>({})
  }

  async connect(): Promise<void> {
    this.abort = new AbortController()
    // The first subscribe must succeed (ACK received) before the console
    // reports "Connected" — runtime truth, not endpoint configuration.
    await this.subscribe(this.abort.signal, true)
  }

  disconnect(): void {
    this.abort?.abort()
    this.abort = null
    if (this.trackingId !== null) {
      this.trackingId = null
      this.lifecycle.close?.(undefined as never)
      this.handlers.onClose()
    }
  }

  send(text: string): void {
    if (!this.trackingId) return
    void this.unarySend(text)
  }

  sendControl(): void {
    // The Connect chat service has no control frames — documented no-op.
  }

  /**
   * Open the Subscribe stream and pump envelopes until it ends. When
   * `initial` is true the returned promise resolves once the server ACKs
   * (so connect() reports open truthfully) and the pump continues in the
   * background, auto-resubscribing on stream loss up to the same quota the
   * Atmosphere transport uses.
   */
  private async subscribe(signal: AbortSignal, initial: boolean): Promise<void> {
    const res = await fetch(`${this.options.endpoint}/Subscribe`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/connect+json' },
      body: encodeConnectEnvelope(0, JSON.stringify({
        type: 'SUBSCRIBE',
        topic: GrpcChatTransport.TOPIC,
      })),
      signal,
    })
    if (!res.ok || !res.body) {
      throw new Error(`gRPC subscribe failed: HTTP ${res.status}`)
    }

    const reader = res.body.getReader()
    const framing = new ConnectEnvelopeReader()

    const pump = async (): Promise<void> => {
      try {
        for (;;) {
          const chunk = await reader.read()
          if (chunk.done) break
          for (const env of framing.push(chunk.value)) {
            this.handleEnvelope(env)
          }
        }
      } finally {
        reader.releaseLock()
      }
      if (signal.aborted) return
      if (this.onAck) {
        // The initial subscribe closed before the server ever ACKed —
        // connect() must fail fast (the .then handler rejects), not retry
        // toward a server that refuses the handshake.
        return
      }
      // Stream ended server-side — resubscribe like the Atmosphere transport
      // reconnects, capped by the same quota, then report the terminal state.
      while (!signal.aborted && this.reconnectAttempts < this.options.maxReconnectOnClose) {
        this.reconnectAttempts += 1
        this.handlers.onReconnect()
        try {
          await new Promise(r => setTimeout(r, 3000))
          if (signal.aborted) return
          await this.subscribe(signal, false)
          return
        } catch {
          // next attempt
        }
      }
      if (!signal.aborted) {
        this.handlers.onFailureToReconnect()
      }
    }

    if (initial) {
      // Resolve connect() on the first ACK; keep pumping in the background.
      await new Promise<void>((resolve, reject) => {
        this.onAck = () => { this.onAck = null; resolve() }
        pump().then(() => {
          if (this.onAck) reject(new Error('gRPC subscribe closed before ACK'))
        }, reject)
      })
    } else {
      void pump()
    }
  }

  private onAck: (() => void) | null = null

  private handleEnvelope(env: ConnectEnvelope): void {
    if ((env.flags & 0x02) !== 0) {
      // End-of-stream frame: {} for clean end, {"error":{...}} otherwise.
      try {
        const end = JSON.parse(env.payload || '{}') as Record<string, unknown>
        const error = end.error as Record<string, unknown> | undefined
        if (error) {
          this.handlers.onEvent({ type: 'error', data: String(error.message ?? 'gRPC stream error') })
        }
      } catch { /* tolerate malformed trailers */ }
      return
    }

    let msg: Record<string, unknown>
    try {
      msg = JSON.parse(env.payload)
    } catch {
      return
    }
    const type = String(msg.type ?? '')
    if (type === 'ACK') {
      this.trackingId = String(msg.trackingId ?? '')
      this.reconnectAttempts = 0
      if (this.onAck) {
        this.lifecycle.open?.(undefined as never)
        this.handlers.onOpen()
        this.onAck()
      } else {
        this.lifecycle.reopen?.(undefined as never)
        this.handlers.onReopen()
      }
      return
    }
    if (type === 'MESSAGE' && typeof msg.payload === 'string' && msg.payload) {
      // Broadcast chat frame — render as its own bubble via the composable's
      // event-less {author, message} branch, like Atmosphere broadcast mode.
      const { author, message } = splitChatPayload(msg.payload)
      this.handlers.onEvent(author !== undefined ? { author, message } : { message })
    }
    // HEARTBEAT and other types need no rendering.
  }

  private async unarySend(text: string): Promise<void> {
    try {
      const res = await fetch(`${this.options.endpoint}/Send`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          type: 'MESSAGE',
          topic: GrpcChatTransport.TOPIC,
          payload: `console: ${text}`,
          trackingId: this.trackingId,
        }),
      })
      if (!res.ok) {
        this.handlers.onEvent({ type: 'error', data: `gRPC send failed: HTTP ${res.status}` })
      }
      // The reply renders via the Subscribe broadcast echo, matching the
      // sample's semantics — nothing to read from the unary ACK.
    } catch (e) {
      this.handlers.onEvent({ type: 'error', data: String(e) })
    }
  }
}
