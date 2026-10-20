/*
 * Copyright 2011-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import type { AtmosphereRequest } from '../types';
import { logger } from './logger';
import { VERSION } from '../version';

/**
 * Result of processing a message through the Atmosphere protocol handler.
 */
export interface ProtocolResult {
  /** The remaining message after protocol data is extracted (empty string if handshake-only). */
  message: string;
  /** Whether this was the initial handshake message. */
  wasHandshake: boolean;
}

/** The most sessions a resume point names (the server reads at most 16). */
const MAX_RESUME_SESSIONS = 16;
/** The longest resume point the server reads. */
const MAX_RESUME_LENGTH = MAX_RESUME_SESSIONS * (64 + 1 + 18) + MAX_RESUME_SESSIONS - 1;
/** A session id the server accepts in a resume point. */
const RESUME_SESSION = /^[A-Za-z0-9_-]{1,64}$/;
/** One `sessionId:seq` pair of a resume point. */
const RESUME_PAIR = /^([A-Za-z0-9_-]{1,64}):([0-9]{1,15})$/;

/**
 * Manages the Atmosphere wire protocol: handshake, heartbeat, UUID tracking,
 * and framework header attachment.
 *
 * Ported from the legacy atmosphere.js `_handleProtocol`, `_attachHeaders`,
 * `_startHeartbeat`, and `_trackMessageSize` functions.
 */
export class AtmosphereProtocol {
  /** Server-assigned UUID for this connection. */
  uuid = '0';

  /** Durable session token for reconnection across server restarts. */
  sessionToken: string | null = null;

  /** Durable run id of the in-flight run, re-sent on reconnect to resume it. */
  runId: string | null = null;

  /**
   * The resume point: the `seq` of the last frame received of each AI
   * session (see {@link recordFrames}), the most recently received last, at
   * most {@link MAX_RESUME_SESSIONS} of them. Sent on every request as
   * `X-Atmosphere-Resume`, so a reconnect is written what this client missed,
   * and nothing it holds again.
   */
  private readonly resume = new Map<string, number>();

  /** Authentication token for the current connection. */
  authToken: string | null = null;

  /** Whether we have received the first (handshake) message. */
  private firstMessage = true;

  /** Server-specified heartbeat interval in ms. 0 = disabled. */
  heartbeatInterval = 0;

  /** Heartbeat padding character(s) sent to the server. */
  heartbeatPadding = 'X';

  /** Active heartbeat timer id. */
  private heartbeatTimer: ReturnType<typeof setTimeout> | null = null;

  /** Partial message accumulator for trackMessageLength mode. */
  private partialMessage = '';

  /** Callback to send a push message (heartbeat). */
  private pushFn: ((msg: string) => void) | null = null;

  /**
   * Register the push function used for heartbeat pings.
   */
  setPushFunction(fn: (msg: string) => void): void {
    this.pushFn = fn;
  }

  /**
   * Build the URL with Atmosphere framework headers attached as query parameters.
   * Mirrors legacy `_attachHeaders()`. The custom headers and the auth token are
   * query parameters too: this is the URL of a request that cannot set headers
   * (WebSocket, WebTransport, `EventSource`).
   */
  buildUrl(request: AtmosphereRequest): string {
    return this.buildRequest(request, false).url;
  }

  /**
   * Build one fetch request: the URL, and the headers it sends. With
   * `realHeaders`, the custom headers and the auth token that can be fetch
   * headers (see {@link isSendableHeader}) are returned as headers and left out
   * of the URL; the rest stay query parameters. Function-valued headers are
   * called, and every value read, once per call.
   */
  buildRequest(request: AtmosphereRequest, realHeaders: boolean): { url: string; headers: Record<string, string> } {
    const headers: Record<string, string> = {};
    const base = request.url;
    const sep = base.includes('?') ? '&' : '?';
    const params: string[] = [];

    params.push(`X-Atmosphere-tracking-id=${encodeURIComponent(this.uuid)}`);
    params.push(`X-Atmosphere-Framework=${encodeURIComponent(VERSION)}`);
    params.push(`X-Atmosphere-Transport=${encodeURIComponent(request.transport)}`);

    if (request.trackMessageLength) {
      params.push('X-Atmosphere-TrackMessageSize=true');
    }

    if (request.heartbeat?.server) {
      params.push(`X-Heartbeat-Server=${request.heartbeat.server}`);
    }

    if (request.contentType) {
      params.push(
        `Content-Type=${request.transport === 'websocket' ? request.contentType : encodeURIComponent(request.contentType)}`,
      );
    }

    if (request.enableProtocol) {
      params.push('X-atmo-protocol=true');
    }

    // Send durable session token if available
    const token = request.sessionToken ?? this.sessionToken;
    if (token) {
      params.push(`X-Atmosphere-Session-Token=${encodeURIComponent(token)}`);
    }

    // Send the durable run id if the transport captured one (the server
    // surfaces it as an X-Atmosphere-Run-Id metadata frame) — on a reconnect
    // this carries it back so the server resumes the in-flight run.
    const runId = request.runId ?? this.runId;
    if (runId) {
      params.push(`X-Atmosphere-Run-Id=${encodeURIComponent(runId)}`);
    }

    // What this client last received of each AI session: the server writes
    // a reconnect what it missed from there — frames written into a
    // connection that was gone before the server noticed included — and
    // nothing it already holds.
    const resume = this.resumePoint;
    if (resume) {
      params.push(`X-Atmosphere-Resume=${encodeURIComponent(resume)}`);
    }

    const custom: Array<[string, string]> = [];
    // Send authentication token if available
    const authToken = request.authToken ?? this.authToken;
    if (authToken) {
      custom.push(['X-Atmosphere-Auth', authToken]);
    }

    if (request.headers) {
      for (const [name, value] of Object.entries(request.headers)) {
        custom.push([name, String(typeof value === 'function' ? value() : value)]);
      }
    }

    for (const [name, value] of custom) {
      if (realHeaders && AtmosphereProtocol.isSendableHeader(name, value)) {
        headers[name] = value;
      } else {
        params.push(`${encodeURIComponent(name)}=${encodeURIComponent(value)}`);
      }
    }

    return { url: base + sep + params.join('&'), headers };
  }

  /**
   * Whether `name: value` can be sent as a fetch request header: a valid token
   * name that is neither forbidden (the browser would drop it silently) nor
   * `Content-Type` (the transport sets it), and a Latin-1 value without line
   * breaks (fetch would throw).
   */
  static isSendableHeader(name: string, value: string): boolean {
    const lower = name.toLowerCase();
    return /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/.test(name)
      && /^[\t\x20-\x7E\x80-\xFF]*$/.test(value)
      && lower !== 'content-type'
      && !AtmosphereProtocol.FORBIDDEN_HEADERS.has(lower)
      && !lower.startsWith('proxy-')
      && !lower.startsWith('sec-');
  }

  /** Request headers a browser does not let a script set (Fetch standard), besides `Proxy-*` and `Sec-*`. */
  private static readonly FORBIDDEN_HEADERS: ReadonlySet<string> = new Set([
    'accept-charset', 'accept-encoding', 'access-control-request-headers',
    'access-control-request-method', 'connection', 'content-length', 'cookie', 'cookie2',
    'date', 'dnt', 'expect', 'host', 'keep-alive', 'origin', 'referer', 'set-cookie', 'te',
    'trailer', 'transfer-encoding', 'upgrade', 'via', 'x-http-method', 'x-http-method-override',
    'x-method-override',
  ]);

  /**
   * The resume point to send, `sessionId:seq` pairs separated by commas, the
   * most recently received session last; `null` before any AI frame arrived.
   */
  get resumePoint(): string | null {
    if (this.resume.size === 0) return null;
    return Array.from(this.resume, ([session, seq]) => `${session}:${seq}`).join(',');
  }

  /**
   * Start from `value`, a resume point another connection recorded (one a
   * subscription closed and opened again carries over, see
   * `AtmosphereRequest.resume`). A malformed value is ignored.
   */
  seedResume(value: string | null | undefined): void {
    if (!value || this.resume.size > 0 || value.length > MAX_RESUME_LENGTH) return;
    const pairs = value.split(',');
    if (pairs.length > MAX_RESUME_SESSIONS) return;
    const seeded: Array<[string, number]> = [];
    for (const pair of pairs) {
      const match = RESUME_PAIR.exec(pair);
      if (!match) return;
      seeded.push([match[1], Number(match[2])]);
    }
    for (const [session, seq] of seeded) this.remember(session, seq);
  }

  /**
   * Record the AI frames in `body` (one frame, or several on lines of their
   * own: a long-polling reply) in the resume point: the last `seq` received
   * of each session. A frame names its session and number in its own
   * `sessionId` and `seq` fields, after its data.
   */
  recordFrames(body: string): void {
    if (body.indexOf('"sessionId":"') === -1) return;
    for (const line of body.indexOf('\n') === -1 ? [body] : body.split('\n')) {
      const s = line.lastIndexOf('"sessionId":"');
      const q = line.lastIndexOf('"seq":');
      if (s === -1 || q === -1) continue;
      const start = s + '"sessionId":"'.length;
      const end = line.indexOf('"', start);
      const digits = /^[0-9]{1,15}/.exec(line.substring(q + '"seq":'.length));
      if (end === -1 || !digits) continue;
      const session = line.substring(start, end);
      if (!RESUME_SESSION.test(session)) continue;
      const seq = Number(digits[0]);
      const last = this.resume.get(session);
      this.remember(session, last !== undefined && last > seq ? last : seq);
    }
  }

  private remember(session: string, seq: number): void {
    this.resume.delete(session);
    this.resume.set(session, seq);
    if (this.resume.size > MAX_RESUME_SESSIONS) {
      this.resume.delete(this.resume.keys().next().value as string);
    }
  }

  /**
   * Build the URL of Atmosphere's close message for this connection: the
   * parameters {@link buildUrl} sends (tracking id, custom `headers`, auth and
   * session tokens) with `X-Atmosphere-Transport=close`, which the server's
   * `OnDisconnectInterceptor` answers by releasing the `AtmosphereResource`
   * whose uuid is the tracking id. Everything rides in the query string
   * because `navigator.sendBeacon` cannot set request headers; the server
   * reads `X-Atmosphere-*` query parameters as headers on HTTP requests.
   *
   * With `goingAway`, the message also carries
   * `X-Atmosphere-Close-Reason=going-away`: the page is being hidden or
   * unloaded (a reload, a navigation) and may come back, which a WebSocket
   * tells the server with a 1001 close. Without it the client left on
   * purpose; a server that does not know the parameter treats both alike.
   */
  buildCloseUrl(request: AtmosphereRequest, goingAway = false): string {
    // `close` is a wire value of X-Atmosphere-Transport, not a transport the
    // client can open, so it is not part of the TransportType union.
    const transport = 'close' as unknown as AtmosphereRequest['transport'];
    const url = this.buildUrl({ ...request, transport, enableProtocol: false });
    // Last, so it wins over a request header of the same name.
    return goingAway ? `${url}&X-Atmosphere-Close-Reason=going-away` : url;
  }

  /**
   * Process an incoming message through the Atmosphere protocol layer.
   * Handles the initial handshake (UUID + heartbeat config extraction)
   * and subsequent messages.
   *
   * Mirrors legacy `_handleProtocol()`.
   */
  handleProtocol(request: AtmosphereRequest, message: string): ProtocolResult {
    if (request.enableProtocol && this.firstMessage && message.trim().length > 0) {
      const delimiter = request.messageDelimiter ?? '|';
      const pos = request.trackMessageLength ? 1 : 0;
      const parts = message.split(delimiter);

      if (parts.length <= pos + 1) {
        // Incomplete handshake — pass through
        return { message, wasHandshake: false };
      }

      this.firstMessage = false;
      this.uuid = parts[pos].trim();
      this.heartbeatInterval = parseInt(parts[pos + 1]?.trim() ?? '0', 10);
      this.heartbeatPadding = parts[pos + 2] ?? 'X';

      // Reconstruct trailing messages after the handshake data
      const trailingStart = request.trackMessageLength ? 4 : 3;
      let trailing = '';
      if (parts.length > trailingStart) {
        trailing = parts.slice(trailingStart).join(delimiter);
      }

      logger.debug(`Protocol handshake: uuid=${this.uuid}, heartbeat=${this.heartbeatInterval}ms`);
      return { message: trailing, wasHandshake: true };
    }

    return { message, wasHandshake: false };
  }

  /**
   * Process a raw message through protocol + trackMessageLength parsing.
   * Returns null if the message is incomplete and should be buffered.
   *
   * Mirrors legacy `_trackMessageSize()`.
   */
  processMessage(
    rawMessage: string,
    request: AtmosphereRequest,
  ): { body: string; messages: string[]; wasHandshake: boolean } | null {
    const { message, wasHandshake } = this.handleProtocol(request, rawMessage);

    if (message.length === 0) {
      return null; // Handshake-only or empty
    }

    if (!request.trackMessageLength) {
      return { body: message, messages: [message], wasHandshake };
    }

    // Length-delimited message parsing (mirrors legacy _trackMessageSize)
    const delimiter = request.messageDelimiter ?? '|';
    let data = this.partialMessage + message;
    const messages: string[] = [];

    let delimIdx = data.indexOf(delimiter);

    // No delimiter found — pass message through without buffering.
    // This matches the legacy atmosphere.js behavior: messages without
    // a delimiter (e.g. WebSocket padding) are returned as-is and do
    // NOT accumulate in partialMessage.
    if (delimIdx === -1) {
      return { body: message, messages: [message], wasHandshake };
    }

    while (delimIdx !== -1) {
      const lengthStr = data.substring(0, delimIdx);
      const msgLength = parseInt(lengthStr, 10);

      if (isNaN(msgLength)) {
        this.partialMessage = '';
        throw new Error(`Message length "${lengthStr}" is not a number`);
      }

      const contentStart = delimIdx + delimiter.length;
      if (contentStart + msgLength > data.length) {
        // Message not complete yet
        break;
      }

      messages.push(data.substring(contentStart, contentStart + msgLength));
      data = data.substring(contentStart + msgLength);
      delimIdx = data.indexOf(delimiter);
    }

    this.partialMessage = data;

    if (messages.length === 0) {
      return null; // Still buffering
    }

    return { body: messages.join(delimiter), messages, wasHandshake };
  }

  /**
   * Extract and store the session token from HTTP response headers.
   * Call this after receiving a response from transports that have access
   * to HTTP headers (long-polling, streaming, SSE POST responses).
   *
   * For WebSocket, the browser API does not expose HTTP upgrade response
   * headers, so the server must embed the token in the protocol handshake
   * or a dedicated message.
   */
  extractSessionToken(getHeader: (name: string) => string | null): void {
    const token = getHeader('X-Atmosphere-Session-Token');
    if (token) {
      this.sessionToken = token;
      logger.debug(`Session token captured: ${token.substring(0, 8)}...`);
    }
  }

  /**
   * Extract authentication-related headers from an HTTP response.
   * If a refresh token is found, updates `this.authToken` automatically.
   */
  extractAuthHeaders(
    getHeader: (name: string) => string | null,
  ): { refreshToken?: string; expired?: string } {
    const result: { refreshToken?: string; expired?: string } = {};

    const refreshToken = getHeader('X-Atmosphere-Auth-Refresh');
    if (refreshToken) {
      result.refreshToken = refreshToken;
      this.authToken = refreshToken;
      logger.debug(`Auth token refreshed: ${refreshToken.substring(0, 8)}...`);
    }

    const expired = getHeader('X-Atmosphere-Auth-Expired');
    if (expired) {
      result.expired = expired;
      logger.debug(`Auth expired: ${expired}`);
    }

    return result;
  }

  /**
   * Start the heartbeat timer. Sends `heartbeatPadding` at `heartbeatInterval` ms.
   */
  startHeartbeat(): void {
    this.stopHeartbeat();

    if (this.heartbeatInterval > 0 && this.pushFn) {
      const tick = () => {
        logger.debug('Sending heartbeat');
        this.pushFn?.(this.heartbeatPadding);
        this.heartbeatTimer = setTimeout(tick, this.heartbeatInterval);
      };
      this.heartbeatTimer = setTimeout(tick, this.heartbeatInterval);
    }
  }

  /**
   * Stop the heartbeat timer.
   */
  stopHeartbeat(): void {
    if (this.heartbeatTimer !== null) {
      clearTimeout(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  /**
   * Reset protocol state for a new connection.
   * Preserves the session token across reconnects.
   *
   * The server sends the protocol handshake only to a request whose tracking
   * id is `0`. A reconnect that sends the uuid this protocol already holds
   * gets no handshake, so the handshake parser is armed only while the uuid
   * is still `0`: otherwise the first message containing the delimiter would
   * be parsed as a handshake, replacing the uuid and eating the payload.
   * Every transport calls this right before the request that sends `uuid`.
   */
  reset(): void {
    this.firstMessage = this.uuid === '0';
    this.partialMessage = '';
    this.stopHeartbeat();
    // sessionToken is intentionally preserved across reconnects
  }
}
