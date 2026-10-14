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

import type {
  AtmosphereError,
  AtmosphereRequest,
  AtmosphereResponse,
  AtmosphereInterceptor,
  SubscriptionHandlers,
  ConnectionState,
} from '../types';
import type { OfflineQueue } from '../queue/offline-queue';
import { logger } from '../utils/logger';
import { AtmosphereProtocol } from '../utils/protocol';
import { SEND_ERROR_NAME } from '../utils/send-error';
import { computeReconnectDelay, maxReconnectAttempts } from '../utils/reconnect-delay';

/**
 * Base class for all transport implementations.
 *
 * Provides protocol handling (handshake, heartbeat, message tracking),
 * lifecycle notifications, connect/inactivity timeouts, suspend/resume,
 * interceptor pipeline, and URL building shared by all transports.
 */
export abstract class BaseTransport<T = unknown> {
  protected request: AtmosphereRequest;
  protected handlers: SubscriptionHandlers<T>;
  protected _state: ConnectionState = 'disconnected';
  protected protocol: AtmosphereProtocol;
  protected interceptors: AtmosphereInterceptor[];
  private _hasOpened = false;
  private _inactivityTimer: ReturnType<typeof setTimeout> | null = null;
  protected _requestCount = 0;
  private _offlineQueue: OfflineQueue | null = null;
  /**
   * Reconnect attempts made since the connection last opened. Each transport
   * resets it to 0 on open and increments it when it schedules a reconnect.
   */
  protected reconnectAttempts = 0;

  constructor(
    request: AtmosphereRequest,
    handlers: SubscriptionHandlers<T>,
    interceptors: AtmosphereInterceptor[] = [],
  ) {
    this.request = request;
    this.handlers = handlers;
    this.protocol = new AtmosphereProtocol();
    this.interceptors = interceptors;
  }

  abstract connect(): Promise<void>;
  abstract disconnect(): Promise<void>;
  abstract send(message: string | ArrayBuffer): void;
  abstract get name(): string;

  /** Attach an offline queue; queued messages will auto-drain on reconnect. */
  setOfflineQueue(queue: OfflineQueue): void {
    this._offlineQueue = queue;
  }

  get state(): ConnectionState {
    return this._state;
  }

  /** The server-assigned UUID for this connection. */
  get uuid(): string {
    return this.protocol.uuid;
  }

  /**
   * Whether the server learns that this connection is gone only from
   * Atmosphere's close message. True for the HTTP transports (long-polling,
   * streaming, SSE), whose aborted request the server cannot see; WebSocket
   * and WebTransport override it with `false`, since closing the socket or
   * session already tells the server.
   */
  get needsCloseMessage(): boolean {
    return true;
  }

  /**
   * URL of Atmosphere's close message for this connection, or `null` when
   * there is none to send: the transport does not need one
   * ({@link needsCloseMessage}), or no handshake has assigned a tracking id
   * yet (uuid `'0'`), so there is no server resource to name.
   */
  closeMessageUrl(): string | null {
    if (!this.needsCloseMessage || this.protocol.uuid === '0') {
      return null;
    }
    return this.protocol.buildCloseUrl(this.request);
  }

  /** Whether this connection has opened at least once. */
  get hasOpened(): boolean {
    return this._hasOpened;
  }

  /**
   * Wrap `connect()` with an optional `connectTimeout`.
   * Subclasses call the raw `connect()`. The Atmosphere core uses this.
   */
  async connectWithTimeout(): Promise<void> {
    const timeout = this.request.connectTimeout;
    if (!timeout || timeout <= 0) {
      return this.connect();
    }

    return new Promise<void>((resolve, reject) => {
      let settled = false;

      const timer = setTimeout(() => {
        if (!settled) {
          settled = true;
          // Abort the in-flight connection so it doesn't become an orphan
          // when the caller falls back to another transport.
          this.disconnect().catch(() => { /* best-effort cleanup */ });
          reject(new Error(`Connect timeout after ${timeout}ms`));
        }
      }, timeout);

      this.connect()
        .then(() => {
          if (!settled) {
            settled = true;
            clearTimeout(timer);
            resolve();
          } else {
            // Timeout already fired — disconnect the connection that just opened
            this.disconnect().catch(() => { /* best-effort cleanup */ });
          }
        })
        .catch((err) => {
          if (!settled) {
            settled = true;
            clearTimeout(timer);
            reject(err);
          }
        });
    });
  }

  /** Suspend the connection — stop receiving without disconnect. */
  suspend(): void {
    if (this._state === 'connected') {
      this._state = 'suspended';
      this.stopInactivityTimer();
      logger.info(`${this.name} suspended`);
    }
  }

  /** Resume a suspended connection. */
  async resume(): Promise<void> {
    if (this._state === 'suspended') {
      this._state = 'connected';
      this.resetInactivityTimer();
      logger.info(`${this.name} resumed`);
    }
  }

  /**
   * POST one outgoing message (the HTTP transports' send path).
   *
   * A `503` means the server did not take the message — for instance a
   * long-polling client posting between two polls, before its next poll
   * arrived — so it is sent again after the server's `Retry-After` (1 s when
   * absent, at most {@link SEND_RETRY_MAX_DELAY_MS}), up to
   * {@link SEND_MAX_ATTEMPTS} attempts in all, while the transport is not
   * disconnected. A message the server still refuses, or answers with any other
   * non-2xx status, is reported to the `error` handler instead of being dropped
   * silently. Network failures reject, as before. The URL and headers are
   * built for each attempt ({@link buildFetchRequest}), from the transport's
   * current tracking id and the current header values.
   */
  protected async postMessage(init: RequestInit & { headers: Record<string, string> }): Promise<Response> {
    for (let attempt = 1; ; attempt++) {
      const target = this.buildFetchRequest();
      const response = await fetch(target.url, { ...init, headers: { ...target.headers, ...init.headers } });
      if (response.status === 503 && attempt < BaseTransport.SEND_MAX_ATTEMPTS
          && !this.isDisconnected()) {
        const delay = BaseTransport.retryAfterMs(response.headers?.get('Retry-After') ?? null);
        logger.debug(`${this.name} POST refused with 503; retrying in ${delay}ms (attempt ${attempt + 1})`);
        await new Promise((resolve) => setTimeout(resolve, delay));
        if (this.isDisconnected()) {
          return response;
        }
        continue;
      }
      if (!response.ok && !this.isDisconnected()) {
        const error = new Error(`${this.name} POST send failed with status ${response.status}`
          + (response.status === 503 ? ` after ${attempt} attempts` : ''));
        // The connection itself is fine: the name lets a handler tell an
        // undelivered message from a broken transport.
        error.name = BaseTransport.SEND_ERROR_NAME;
        logger.warn(error.message);
        // Nothing is retried after this: neither the message nor (the
        // connection being up) a reconnect.
        this.handlers.error?.(this.toAtmosphereError(error, false));
      }
      return response;
    }
  }

  /**
   * URL and custom headers of one fetch request. The custom headers and the
   * auth token go as HTTP headers when {@link sendsRealHeaders} says so, as
   * query parameters otherwise; see `AtmosphereRequest.headerDelivery`.
   */
  protected buildFetchRequest(): { url: string; headers: Record<string, string> } {
    return this.protocol.buildRequest(this.request, this.sendsRealHeaders());
  }

  /** Whether this transport's fetch requests carry the custom headers as HTTP headers. */
  protected sendsRealHeaders(): boolean {
    switch (this.request.headerDelivery ?? 'auto') {
      case 'query':
        return false;
      case 'headers':
        return true;
      default:
        return BaseTransport.isSameOrigin(this.request.url);
    }
  }

  /**
   * Whether a fetch of `url` is same-origin, so custom headers trigger no CORS
   * preflight. Without a page origin (React Native, Node) there is no CORS:
   * `true`. An opaque origin (`'null'`) makes every request cross-origin.
   */
  static isSameOrigin(url: string): boolean {
    const page = typeof location !== 'undefined' ? location : undefined;
    if (!page?.origin) {
      return true;
    }
    if (page.origin === 'null') {
      return false;
    }
    try {
      return new URL(url, page.href).origin === page.origin;
    } catch {
      return false;
    }
  }

  /** Read through a call so the state is re-read after an `await`. */
  private isDisconnected(): boolean {
    return this._state === 'disconnected';
  }

  /** `Error.name` of the error {@link postMessage} reports for a message the server did not take. */
  static readonly SEND_ERROR_NAME = SEND_ERROR_NAME;

  /** Attempts {@link postMessage} makes in all while the server answers `503`. */
  static readonly SEND_MAX_ATTEMPTS = 3;

  /** Upper bound on the wait between two {@link postMessage} attempts. */
  static readonly SEND_RETRY_MAX_DELAY_MS = 5_000;

  private static retryAfterMs(header: string | null): number {
    const seconds = header !== null && /^\d+$/.test(header.trim()) ? Number(header.trim()) : 1;
    return Math.min(seconds * 1000, BaseTransport.SEND_RETRY_MAX_DELAY_MS);
  }

  /** Apply outgoing interceptors (in order). */
  protected applyOutgoing(data: string | ArrayBuffer): string | ArrayBuffer {
    let result = data;
    for (const interceptor of this.interceptors) {
      if (interceptor.onOutgoing) {
        result = interceptor.onOutgoing(result);
      }
    }
    return result;
  }

  /** Apply incoming interceptors (in reverse order). */
  protected applyIncoming(body: string): string {
    let result = body;
    for (let i = this.interceptors.length - 1; i >= 0; i--) {
      if (this.interceptors[i].onIncoming) {
        result = this.interceptors[i].onIncoming!(result);
      }
    }
    return result;
  }

  /**
   * Fire `open`/`reopen` and start the protocol heartbeat in response to an
   * Atmosphere handshake. Used by every transport that supports
   * `enableProtocol`. Idempotent — does nothing when the transport is already
   * `connected`, so it is safe to call from both the handshake-only branch
   * and the handshake-with-trailing-data branch (issue #294).
   */
  protected notifyHandshakeOpen(): void {
    if (!this.request.enableProtocol || this._state === 'connected') {
      return;
    }
    const openResponse: AtmosphereResponse<T> = {
      status: 200,
      reasonPhrase: 'OK',
      responseBody: '' as T,
      messages: [],
      headers: {},
      state: 'open',
      transport: this.name as AtmosphereResponse<T>['transport'],
      error: null,
      request: this.request,
    };
    this.notifyOpen(openResponse);
    this.protocol.startHeartbeat();
  }

  protected notifyOpen(response: AtmosphereResponse<T>): void {
    if (this._hasOpened) {
      // This is a reopen after reconnect
      this._state = 'connected';
      logger.debug(`${this.name} transport reopened`);
      this.handlers.reopen?.(response);
      this.drainOfflineQueue();
    } else {
      this._hasOpened = true;
      this._state = 'connected';
      logger.debug(`${this.name} transport opened`);
      this.handlers.open?.(response);
      // A pre-populated queue on first open means this subscription replaced
      // one that died with sends still buffered (e.g. a fallback resubscribe
      // after the previous transport exhausted its reconnect quota) — flush
      // it now, exactly like the reopen path above.
      this.drainOfflineQueue();
    }
    this.resetInactivityTimer();
  }

  protected notifyMessage(response: AtmosphereResponse<T>): void {
    if (this._state === 'suspended') return;
    logger.debug(`${this.name} message received`);
    this.resetInactivityTimer();
    this.captureRunId(response.responseBody);
    this.handlers.message?.(response);
  }

  /**
   * Capture the durable run id the server surfaces as an
   * {@code X-Atmosphere-Run-Id} metadata frame onto the protocol (which persists
   * across reconnects, like the session token) so {@code buildParams} re-sends it
   * and the server resumes the in-flight run; cleared on a terminal frame so a
   * later reconnect does not resume a finished run. Done here, at the transport,
   * so EVERY client benefits — the Atmosphere Console and other direct
   * {@code subscribe()} callers, not only the {@code subscribeStreaming()} helper.
   * A cheap string pre-check keeps every streaming chunk off the JSON.parse path.
   */
  private captureRunId(body: unknown): void {
    if (typeof body !== 'string') return;
    if (body.indexOf('X-Atmosphere-Run-Id') !== -1) {
      try {
        const msg = JSON.parse(body);
        if (msg && msg.type === 'metadata' && msg.key === 'X-Atmosphere-Run-Id'
            && typeof msg.value === 'string') {
          this.protocol.runId = msg.value;
        }
      } catch {
        /* not a JSON frame — ignore */
      }
    } else if (this.protocol.runId !== null
        && (body.indexOf('"complete"') !== -1 || body.indexOf('"error"') !== -1)) {
      try {
        const msg = JSON.parse(body);
        if (msg && (msg.type === 'complete' || msg.type === 'error')) {
          this.protocol.runId = null;
        }
      } catch {
        /* ignore */
      }
    }
  }

  protected notifyClose(response: AtmosphereResponse<T>): void {
    this._state = 'closed';
    this.stopInactivityTimer();
    logger.debug(`${this.name} transport closed`);
    this.handlers.close?.(response);
  }

  /**
   * Report a transport failure to the `error` handler as an
   * {@link AtmosphereError}. `willRetry` must say what the calling path does
   * next: `true` only when this failure is followed by an automatic reconnect
   * attempt. `attempt` is taken from {@link reconnectAttempts}.
   */
  protected notifyError(error: Error, willRetry: boolean): void {
    this._state = 'error';
    this.stopInactivityTimer();
    const summary = `${this.name} transport error (willRetry=${willRetry}, attempt=${this.reconnectAttempts}):`;
    // A failure the transport retries on its own is expected while a server
    // restarts; only the one it gives up on is an error. console.error (and
    // console.warn) become a React Native LogBox toast in dev mode.
    if (willRetry) {
      logger.info(summary, error);
    } else {
      logger.error(summary, error);
    }
    this.handlers.error?.(this.toAtmosphereError(error, willRetry));
  }

  /**
   * Whether `reconnect` is on and the attempt quota ({@link maxReconnectAttempts})
   * still allows another reconnect attempt.
   */
  protected reconnectAllowed(): boolean {
    return Boolean(this.request.reconnect) && this.reconnectAttempts < this.maxReconnectAttempts();
  }

  /** Attach `willRetry`/`attempt` to `error`, in place when the object is extensible. */
  private toAtmosphereError(error: Error, willRetry: boolean): AtmosphereError {
    const fields = { willRetry, attempt: this.reconnectAttempts };
    if (Object.isExtensible(error)) {
      return Object.assign(error, fields);
    }
    const copy = new Error(error.message);
    copy.name = error.name;
    copy.stack = error.stack;
    return Object.assign(copy, fields);
  }

  protected notifyReconnect(request: AtmosphereRequest, response: AtmosphereResponse<T>): void {
    this._state = 'reconnecting';
    logger.info(`${this.name} reconnecting...`);
    this.handlers.reconnect?.(request, response);
  }

  protected notifyFailureToReconnect(response: AtmosphereResponse<T>): void {
    logger.warn(`${this.name} all reconnect attempts exhausted`);
    this.handlers.failureToReconnect?.(this.request, response);
  }

  protected notifyAuthTokenRefresh(newToken: string): void {
    this.protocol.authToken = newToken;
    this.request = { ...this.request, authToken: newToken };
    this.handlers.authTokenRefresh?.(newToken);
  }

  protected notifyAuthExpired(reason: string): void {
    this.handlers.authExpired?.(reason);
  }

  protected handleAuthHeaders(getHeader: (name: string) => string | null): void {
    const result = this.protocol.extractAuthHeaders(getHeader);
    if (result.refreshToken) {
      this.notifyAuthTokenRefresh(result.refreshToken);
    }
    if (result.expired) {
      this.notifyAuthExpired(result.expired);
    }
  }

  /**
   * Reconnect attempts allowed before `failureToReconnect`:
   * `retryStrategy.maxAttempts`, else `maxReconnectOnClose`, else 5.
   */
  protected maxReconnectAttempts(): number {
    return maxReconnectAttempts(this.request);
  }

  /**
   * Delay before the next reconnect attempt (number `reconnectAttempts + 1`),
   * per `request.retryStrategy` or the default jittered exponential backoff.
   * Call it before incrementing {@link reconnectAttempts}.
   */
  protected nextReconnectDelay(): number {
    return computeReconnectDelay(this.request, this.reconnectAttempts + 1);
  }

  /** Returns true if maxRequest has been reached. */
  protected isMaxRequestReached(): boolean {
    const max = this.request.maxRequest;
    return max !== undefined && max > 0 && this._requestCount >= max;
  }

  private drainOfflineQueue(): void {
    if (this._offlineQueue?.drainOnReconnect && this._offlineQueue.size > 0) {
      logger.info(`Draining ${this._offlineQueue.size} offline messages`);
      this._offlineQueue.drain((data, _messageId) => {
        this.send(typeof data === 'string' || data instanceof ArrayBuffer ? data : JSON.stringify(data));
      });
    }
  }

  private resetInactivityTimer(): void {
    this.stopInactivityTimer();
    const timeout = this.request.timeout;
    if (timeout && timeout > 0) {
      this._inactivityTimer = setTimeout(() => {
        logger.warn(`${this.name} inactivity timeout after ${timeout}ms`);
        this.handlers.clientTimeout?.(this.request);
      }, timeout);
    }
  }

  private stopInactivityTimer(): void {
    if (this._inactivityTimer) {
      clearTimeout(this._inactivityTimer);
      this._inactivityTimer = null;
    }
  }
}
