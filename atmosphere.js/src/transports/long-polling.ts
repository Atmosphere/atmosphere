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

import { BaseTransport } from './base';
import type { AtmosphereResponse } from '../types';
import { logger } from '../utils/logger';

/**
 * HTTP Long-Polling transport implementation.
 *
 * Opens a fetch request that the server holds open until data is available.
 * When a response arrives, the client processes it and immediately opens
 * a new request — creating a near-real-time server push channel.
 */
export class LongPollingTransport<T = unknown> extends BaseTransport<T> {
  private abortController: AbortController | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private _polling = false;
  private aborted = false;

  get name(): string {
    return 'long-polling';
  }

  async connect(): Promise<void> {
    this._state = 'connecting';
    this.aborted = false;
    this._polling = true;

    this.protocol.setPushFunction((msg) => this.send(msg));

    return this.poll(true);
  }

  async disconnect(): Promise<void> {
    this.aborted = true;
    this._polling = false;

    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.protocol.stopHeartbeat();

    if (this.abortController) {
      this.abortController.abort();
      this.abortController = null;
    }

    this._state = 'disconnected';
  }

  send(message: string | ArrayBuffer): void {
    const outgoing = this.applyOutgoing(message);

    this.postMessage(() => this.protocol.buildUrl(this.request), {
      method: 'POST',
      headers: { 'Content-Type': this.request.contentType ?? 'text/plain' },
      credentials: this.request.withCredentials ? 'include' : 'same-origin',
      body: outgoing instanceof ArrayBuffer ? new Blob([outgoing]) : outgoing,
    }).catch((error) => {
      logger.warn('Long-polling POST send failed:', error);
    });
  }

  private async poll(isFirst: boolean): Promise<void> {
    if (this.aborted) return;
    if (this.isMaxRequestReached()) {
      logger.info('Long-polling maxRequest reached');
      return;
    }

    const url = this.protocol.buildUrl(this.request);
    logger.debug(`Long-polling request: ${url}`);

    this.abortController = new AbortController();
    this._requestCount++;

    try {
      const response = await fetch(url, {
        headers: { 'Content-Type': this.request.contentType ?? 'text/plain' },
        credentials: this.request.withCredentials ? 'include' : 'same-origin',
        signal: this.abortController.signal,
      });

      if (!response.ok) {
        // A failed first poll is reported once, by the catch below.
        if (isFirst) throw new Error('Long-polling connection failed');
        this.handleDisconnect(false);
        return;
      }

      // With the protocol, a poll sent with tracking id 0 gets the
      // server-assigned id (and the heartbeat interval) in its body. Opening
      // before that body is parsed would let `open` handlers and the
      // offline-queue drain POST under id 0, which names no connection: open
      // once the body is parsed, as WebSocket, SSE and streaming open on the
      // handshake. A reconnect keeps its id, gets no handshake, and opens on
      // the response itself.
      const awaitHandshake = isFirst && this.request.enableProtocol === true
        && this.protocol.uuid === '0';
      if (isFirst) {
        this.reconnectAttempts = 0;
        if (!awaitHandshake) {
          this.openPoll();
        }
      }

      const responseText = await response.text();
      this.handleResponse(responseText, awaitHandshake);

      // Extract session token from response headers (for durable sessions)
      this.protocol.extractSessionToken((name) => response.headers.get(name));
      this.handleAuthHeaders((name) => response.headers.get(name));

      // Immediately re-poll
      if (this._polling && !this.aborted) {
        this.poll(false).catch((error) => {
          logger.error('Long-polling re-poll failed:', error);
        });
      }
    } catch (error) {
      if ((error as Error).name === 'AbortError') return;
      if (this.aborted) return;
      this.handleDisconnect(isFirst);
      if (isFirst) throw error;
    }
  }

  private openPoll(): void {
    const openResponse: AtmosphereResponse<T> = {
      status: 200,
      reasonPhrase: 'OK',
      responseBody: '' as T,
      messages: [],
      headers: {},
      state: 'open',
      transport: 'long-polling',
      error: null,
      request: this.request,
    };
    this.notifyOpen(openResponse);
    this.protocol.startHeartbeat();
  }

  /**
   * Process one poll's body. `openFirst` (a first poll waiting for the
   * handshake) opens the connection once the body is parsed, before any
   * message it carries: the handshake, when the body held one, has set the
   * uuid by then, and a body without one opens all the same.
   */
  private handleResponse(responseText: string, openFirst: boolean): void {
    const result = responseText.trim().length > 0
      ? this.protocol.processMessage(responseText, this.request)
      : null;

    if (openFirst && this._state !== 'connected' && !this.aborted) {
      this.openPoll();
    }

    if (result === null) {
      return; // Empty, handshake-only or partial
    }

    for (const msg of result.messages) {
      if (msg === this.protocol.heartbeatPadding) {
        continue;
      }

      const response: AtmosphereResponse<T> = {
        status: 200,
        reasonPhrase: 'OK',
        responseBody: this.applyIncoming(msg) as T,
        messages: [msg],
        headers: {},
        state: 'messageReceived',
        transport: 'long-polling',
        error: null,
        request: this.request,
      };
      this.notifyMessage(response);
    }
  }

  private handleDisconnect(isFirst: boolean): void {
    this.protocol.stopHeartbeat();

    if (isFirst) {
      const error = new Error('Long-polling connection failed');
      // A failed reconnect attempt re-enters handleDisconnect(false) from the
      // reconnect timer; the initial poll rejects to `subscribe()` instead.
      this.notifyError(error, this.reconnectAttempts > 0 && this._polling && this.reconnectAllowed());
      return;
    }

    const response: AtmosphereResponse<T> = {
      status: 0,
      reasonPhrase: 'Long-polling connection lost',
      responseBody: '' as T,
      messages: [],
      headers: {},
      state: 'closed',
      transport: 'long-polling',
      error: null,
      request: this.request,
    };
    this.notifyClose(response);

    if (
      this._polling &&
      this.request.reconnect &&
      this.reconnectAttempts < this.maxReconnectAttempts()
    ) {
      this.scheduleReconnect();
    } else if (this._polling && this.request.reconnect) {
      this.notifyFailureToReconnect(response);
    }
  }

  private scheduleReconnect(): void {
    const delay = this.nextReconnectDelay();
    this.reconnectAttempts++;

    logger.info(`Long-polling reconnect attempt ${this.reconnectAttempts} in ${delay}ms`);

    this.notifyReconnect(this.request, {
      status: 0,
      reasonPhrase: 'Reconnecting',
      responseBody: '' as T,
      messages: [],
      headers: {},
      state: 'reconnecting',
      transport: 'long-polling',
      error: null,
      request: this.request,
    });

    this.reconnectTimer = setTimeout(() => {
      this.protocol.reset();
      this.poll(true).catch((error) => {
        logger.error('Long-polling reconnection failed:', error);
        // Re-enter the close flow so the retry quota and delay policy apply to
        // the next attempt too; otherwise one failed attempt ends the chain.
        if (!this.aborted) {
          this.handleDisconnect(false);
        }
      });
    }, delay);
  }
}
