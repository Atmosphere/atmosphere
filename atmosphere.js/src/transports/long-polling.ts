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
  private reconnectAttempts = 0;
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
    const url = this.protocol.buildUrl(this.request);
    const outgoing = this.applyOutgoing(message);

    fetch(url, {
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
        this.handleDisconnect(isFirst);
        if (isFirst) throw new Error('Long-polling connection failed');
        return;
      }

      if (isFirst) {
        this.reconnectAttempts = 0;

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

      const responseText = await response.text();
      if (responseText.trim().length > 0) {
        this.handleResponse(responseText);
      }

      // Extract session token from response headers (for durable sessions)
      this.protocol.extractSessionToken((name) => response.headers.get(name));

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

  private handleResponse(responseText: string): void {
    const result = this.protocol.processMessage(responseText, this.request);

    if (result === null) {
      return; // Handshake or partial
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
      this.notifyError(error);
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
      this.reconnectAttempts < (this.request.maxReconnectOnClose ?? 5)
    ) {
      this.scheduleReconnect();
    } else if (this._polling && this.request.reconnect) {
      this.notifyFailureToReconnect(response);
    }
  }

  private scheduleReconnect(): void {
    const delay = this.calculateReconnectDelay();
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
      });
    }, delay);
  }

  private calculateReconnectDelay(): number {
    const baseDelay = this.request.reconnectInterval ?? 1000;
    const exponentialDelay = baseDelay * Math.pow(2, Math.min(this.reconnectAttempts, 5));
    const jitter = 0.5 + Math.random() * 0.5;
    return exponentialDelay * jitter;
  }
}
