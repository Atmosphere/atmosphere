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
  AtmosphereRequest,
  AtmosphereResponse,
  AtmosphereInterceptor,
  SubscriptionHandlers,
  ConnectionState,
} from '../types';
import type { OfflineQueue } from '../queue/offline-queue';
import { logger } from '../utils/logger';
import { AtmosphereProtocol } from '../utils/protocol';

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

  protected notifyError(error: Error): void {
    this._state = 'error';
    this.stopInactivityTimer();
    logger.error(`${this.name} transport error:`, error);
    this.handlers.error?.(error);
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
