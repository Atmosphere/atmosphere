import { Atmosphere } from 'atmosphere.js'
import type { Subscription, AtmosphereResponse } from 'atmosphere.js'
import { resolveAuthToken } from '../lib/authToken'
import type { ChatTransport, ChatTransportHandlers, ChatTransportOptions } from './types'

/**
 * One decoded unit of an Atmosphere payload: either a parsed JSON chat
 * event (rendered via the normalized event vocabulary) or a raw text
 * fragment (appended verbatim to the assistant bubble).
 */
export type AtmosphereFrame =
  | { kind: 'event'; msg: Record<string, unknown> }
  | { kind: 'raw'; text: string }

/**
 * Decode an Atmosphere response body into chat frames. Bodies arrive as
 * JSON objects, possibly length-prefixed by TrackMessageSizeInterceptor.
 * Format can be: plain JSON, newline-separated JSON, or
 * <len>|<json><len>|<json>... Pure function so the framing rules are
 * unit-testable without a live subscription.
 */
export function parseAtmosphereFrames(body: string): AtmosphereFrame[] {
  const frames: AtmosphereFrame[] = []
  const trimmed = body.trim()
  if (!trimmed) return frames

  // Length-prefixed format first: <digits>|<json>...
  if (/^\d+\|/.test(trimmed)) {
    let remaining = trimmed
    while (remaining.length > 0) {
      const m = remaining.match(/^(\d+)\|/)
      if (!m) break
      const len = parseInt(m[1], 10)
      const start = m[0].length
      const chunk = remaining.substring(start, start + len)
      remaining = remaining.substring(start + len)
      try {
        frames.push({ kind: 'event', msg: JSON.parse(chunk) })
      } catch {
        frames.push({ kind: 'raw', text: chunk })
      }
    }
    return frames
  }

  // Single JSON object
  try {
    frames.push({ kind: 'event', msg: JSON.parse(trimmed) })
    return frames
  } catch { /* not single JSON */ }

  // Newline-separated JSON objects (multiple per frame)
  for (const line of trimmed.split('\n').filter(l => l.trim())) {
    try {
      frames.push({ kind: 'event', msg: JSON.parse(line) })
    } catch {
      frames.push({ kind: 'raw', text: line })
    }
  }
  return frames
}

/**
 * Derive the HTTP/3 sidecar URL for an endpoint path: WebTransport binds
 * its own port on the same host, always over https. Pure for testability.
 */
export function deriveWebTransportUrl(endpoint: string, port: number, hostname: string): string {
  const path = endpoint.startsWith('/') ? endpoint : `/${endpoint}`
  return `https://${hostname}:${port}${path}`
}

/**
 * Append the auth token (if any) to the WebSocket URL as the
 * X-Atmosphere-Auth query parameter the server's TokenValidator reads.
 * The token is resolved by the shared {@link resolveAuthToken} helper
 * (also used by the admin REST surface), so the WS and REST paths can
 * never drift. Returns the URL unchanged when no token is available, so
 * anonymous backends connect exactly as before.
 */
function withAuthToken(wsUrl: string): string {
  const token = resolveAuthToken()
  if (!token) return wsUrl
  return wsUrl + (wsUrl.includes('?') ? '&' : '?') + 'X-Atmosphere-Auth=' + encodeURIComponent(token)
}

/**
 * The console's default transport: the Atmosphere AI-chat/broadcast wire
 * protocol over WebSocket with long-polling fallback, via atmosphere.js.
 * This is the exact connection behavior the console has always had —
 * extracted from useAtmosphereChat so foreign transports (grpc / a2a /
 * ag-ui) can plug in behind the same ChatTransport seam.
 */
export class AtmosphereChatTransport implements ChatTransport {
  readonly name = 'atmosphere' as const

  private atmosphere: Atmosphere | null = null
  private subscription: Subscription | null = null

  constructor(
    private readonly options: ChatTransportOptions,
    private readonly handlers: ChatTransportHandlers,
  ) {}

  async connect(): Promise<void> {
    const { options, handlers } = this
    this.atmosphere = new Atmosphere({ logLevel: 'debug' })

    // WT-first when /api/console/info confirmed a live HTTP/3 sidecar
    // (Runtime Truth) — WS remains the fallback, exactly like the former
    // bespoke sample UIs. Without the sidecar: WS with long-polling fallback.
    const wt = options.webTransport
    this.subscription = await this.atmosphere.subscribe<string>(
      {
        url: withAuthToken(options.endpoint),
        transport: wt ? 'webtransport' : 'websocket',
        fallbackTransport: wt ? 'websocket' : 'long-polling',
        ...(wt ? {
          webTransportUrl: deriveWebTransportUrl(options.endpoint, wt.port, window.location.hostname),
        } : {}),
        ...(wt?.certificateHash ? { serverCertificateHashes: [wt.certificateHash] } : {}),
        reconnect: true,
        reconnectInterval: 3000,
        maxReconnectOnClose: options.maxReconnectOnClose,
        trackMessageLength: true,
        enableProtocol: true,
      },
      options.status.wrap({
        open: () => handlers.onOpen(),
        close: () => handlers.onClose(),
        message: (response: AtmosphereResponse<string>) => {
          if (response.responseBody) {
            for (const frame of parseAtmosphereFrames(response.responseBody as string)) {
              if (frame.kind === 'event') {
                handlers.onEvent(frame.msg)
              } else {
                handlers.onRawText(frame.text)
              }
            }
          }
        },
        error: () => handlers.onError(),
        reconnect: () => handlers.onReconnect(),
        reopen: () => handlers.onReopen(),
        transportFailure: (reason) => {
          console.warn('[atmosphere] transport failure, falling back:', reason)
        },
        clientTimeout: () => handlers.onClientTimeout(),
        failureToReconnect: () => handlers.onFailureToReconnect(),
      })
    )
  }

  disconnect(): void {
    if (this.atmosphere) {
      this.atmosphere.closeAll()
      this.atmosphere = null
      this.subscription = null
    }
  }

  send(text: string): void {
    if (!this.subscription) return
    if (this.options.isBroadcast()) {
      // Broadcast rooms decode {author, message, time} (see the chat
      // sample's JacksonDecoder); a raw string would fail the decoder and
      // be dropped server-side. The echo renders via the event-less-frame
      // branch in handleStreamingEvent, confirming delivery.
      this.subscription.push(JSON.stringify({
        author: 'console',
        message: text,
        time: Date.now(),
      }))
      return
    }
    this.subscription.push(text)
  }

  sendControl(frame: string): void {
    this.subscription?.push(frame)
  }
}
