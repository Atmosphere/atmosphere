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
 * Server-Sent Events (SSE) transport implementation.
 *
 * Uses the browser EventSource API for server-to-client streaming.
 * Messages are sent via HTTP POST (XHR).
 */
export class SSETransport<T = unknown> extends BaseTransport<T> {
  private eventSource: EventSource | null = null;
  private reconnectAttempts = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;

  get name(): string {
    return 'sse';
  }

  static isAvailable(): boolean {
    return typeof EventSource !== 'undefined';
  }

  async connect(): Promise<void> {
    if (!SSETransport.isAvailable()) {
      throw new Error('SSE (EventSource) is not supported in this environment');
    }

    return new Promise((resolve, reject) => {
      try {
        this._state = 'connecting';
        const url = this.protocol.buildUrl(this.request);

        logger.debug(`Connecting SSE: ${url}`);

        this.eventSource = new EventSource(url, {
          withCredentials: this.request.withCredentials ?? false,
        });

        this.eventSource.onopen = () => {
          this.handleOpen();
          resolve();
        };

        this.eventSource.onmessage = (event) => {
          this.handleMessage(event);
        };

        this.eventSource.onerror = () => {
          if (this._state === 'connecting') {
            const error = new Error('SSE connection failed');
            this.handleError(error);
            reject(error);
          } else {
            this.handleClose();
          }
        };
      } catch (error) {
        this.handleError(error as Error);
        reject(error);
      }
    });
  }

  async disconnect(): Promise<void> {
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.protocol.stopHeartbeat();

    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }

    this._state = 'disconnected';
  }

  send(message: string | ArrayBuffer): void {
    // SSE is server-to-client only; send via HTTP POST
    const url = this.protocol.buildUrl(this.request);
    const outgoing = this.applyOutgoing(message);
    const data = outgoing instanceof ArrayBuffer
      ? new Blob([outgoing])
      : outgoing;

    const xhr = new XMLHttpRequest();
    xhr.open('POST', url, true);
    xhr.setRequestHeader('Content-Type', this.request.contentType ?? 'text/plain');
    if (this.request.withCredentials) {
      xhr.withCredentials = true;
    }

    // Extract session token from POST response headers (for durable sessions)
    xhr.onreadystatechange = () => {
      if (xhr.readyState === 4 && xhr.status >= 200 && xhr.status < 300) {
        if (typeof xhr.getResponseHeader === 'function') {
          this.protocol.extractSessionToken((name) => xhr.getResponseHeader(name));
        }
      }
    };

    xhr.send(data);
  }

  private handleOpen(): void {
    this.reconnectAttempts = 0;
    logger.info('SSE connection established');

    this.protocol.setPushFunction((msg) => this.send(msg));

    const response: AtmosphereResponse<T> = {
      status: 200,
      reasonPhrase: 'OK',
      responseBody: '' as T,
      messages: [],
      headers: {},
      state: 'open',
      transport: 'sse',
      error: null,
      request: this.request,
    };

    if (!this.request.enableProtocol) {
      this.notifyOpen(response);
      this.protocol.startHeartbeat();
    }
  }

  private handleMessage(event: MessageEvent): void {
    const result = this.protocol.processMessage(event.data, this.request);

    if (result === null) {
      // Handshake processed or partial message buffered
      if (this.request.enableProtocol && this._state !== 'connected') {
        const openResponse: AtmosphereResponse<T> = {
          status: 200,
          reasonPhrase: 'OK',
          responseBody: '' as T,
          messages: [],
          headers: {},
          state: 'open',
          transport: 'sse',
          error: null,
          request: this.request,
        };
        this.notifyOpen(openResponse);
        this.protocol.startHeartbeat();
      }
      return;
    }

    for (const msg of result.messages) {
      if (msg === this.protocol.heartbeatPadding) {
        continue; // Filter heartbeat padding
      }

      const response: AtmosphereResponse<T> = {
        status: 200,
        reasonPhrase: 'OK',
        responseBody: this.applyIncoming(msg) as T,
        messages: [msg],
        headers: {},
        state: 'messageReceived',
        transport: 'sse',
        error: null,
        request: this.request,
      };
      this.notifyMessage(response);
    }
  }

  private handleClose(): void {
    logger.info('SSE connection closed');
    this.protocol.stopHeartbeat();

    const response: AtmosphereResponse<T> = {
      status: 0,
      reasonPhrase: 'SSE connection lost',
      responseBody: '' as T,
      messages: [],
      headers: {},
      state: 'closed',
      transport: 'sse',
      error: null,
      request: this.request,
    };

    this.notifyClose(response);

    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }

    if (
      this.request.reconnect &&
      this.reconnectAttempts < (this.request.maxReconnectOnClose ?? 5)
    ) {
      this.scheduleReconnect();
    } else if (this.request.reconnect) {
      this.notifyFailureToReconnect(response);
    }
  }

  private handleError(error: Error): void {
    logger.error('SSE error:', error);
    this.protocol.stopHeartbeat();
    this.notifyError(error);
  }

  private scheduleReconnect(): void {
    const delay = this.calculateReconnectDelay();
    this.reconnectAttempts++;

    logger.info(`SSE reconnect attempt ${this.reconnectAttempts} in ${delay}ms`);

    this.notifyReconnect(this.request, {
      status: 0,
      reasonPhrase: 'Reconnecting',
      responseBody: '' as T,
      messages: [],
      headers: {},
      state: 'reconnecting',
      transport: 'sse',
      error: null,
      request: this.request,
    });

    this.reconnectTimer = setTimeout(() => {
      this.protocol.reset();
      this.connect().catch((error) => {
        logger.error('SSE reconnection failed:', error);
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
