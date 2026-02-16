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
 * WebSocket transport implementation.
 *
 * Full-duplex communication via the browser WebSocket API.
 * Supports the Atmosphere protocol handshake, heartbeat, and
 * length-delimited message tracking.
 */
export class WebSocketTransport<T = unknown> extends BaseTransport<T> {
  private ws: WebSocket | null = null;
  private reconnectAttempts = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;

  get name(): string {
    return 'websocket';
  }

  async connect(): Promise<void> {
    return new Promise((resolve, reject) => {
      try {
        this._state = 'connecting';
        const wsUrl = this.buildWebSocketUrl(this.request.url);

        logger.debug(`Connecting to WebSocket: ${wsUrl}`);
        this.ws = new WebSocket(wsUrl);
        this.ws.binaryType = 'arraybuffer';

        this.ws.onopen = () => {
          this.handleOpen();
          resolve();
        };

        this.ws.onmessage = (event) => {
          this.handleMessage(event);
        };

        this.ws.onerror = () => {
          const error = new Error('WebSocket connection error');
          this.handleError(error);
          reject(error);
        };

        this.ws.onclose = (event) => {
          this.handleClose(event);
        };
      } catch (error) {
        this.handleError(error as Error);
        reject(error);
      }
    });
  }

  async disconnect(): Promise<void> {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.protocol.stopHeartbeat();

    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.close();
    }

    this.ws = null;
    this._state = 'disconnected';
  }

  send(message: string | ArrayBuffer): void {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      throw new Error('WebSocket is not connected');
    }
    this.ws.send(this.applyOutgoing(message));
  }

  private buildWebSocketUrl(_url: string): string {
    const fullUrl = new URL(this.protocol.buildUrl(this.request), window.location.href);
    fullUrl.protocol = fullUrl.protocol.replace('http', 'ws');
    return fullUrl.toString();
  }

  private handleOpen(): void {
    this.reconnectAttempts = 0;
    logger.info('WebSocket connection established');

    this.protocol.setPushFunction((msg) => this.send(msg));

    const response: AtmosphereResponse<T> = {
      status: 200,
      reasonPhrase: 'OK',
      responseBody: '' as T,
      messages: [],
      headers: {},
      state: 'open',
      transport: 'websocket',
      error: null,
      request: this.request,
    };

    if (!this.request.enableProtocol) {
      this.notifyOpen(response);
      this.protocol.startHeartbeat();
    }
  }

  private handleMessage(event: MessageEvent): void {
    // Binary messages pass through directly
    if (event.data instanceof ArrayBuffer) {
      const response: AtmosphereResponse<T> = {
        status: 200,
        reasonPhrase: 'OK',
        responseBody: event.data as T,
        messages: [event.data],
        headers: {},
        state: 'messageReceived',
        transport: 'websocket',
        error: null,
        request: this.request,
      };
      this.notifyMessage(response);
      return;
    }

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
          transport: 'websocket',
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
        transport: 'websocket',
        error: null,
        request: this.request,
      };
      this.notifyMessage(response);
    }
  }

  private handleClose(event: CloseEvent): void {
    if (this.request.closed) {
      return;
    }

    logger.info(`WebSocket closed: code=${event.code}, reason=${event.reason}`);
    this.protocol.stopHeartbeat();

    const response: AtmosphereResponse<T> = {
      status: event.code,
      reasonPhrase: event.reason || 'Connection closed',
      responseBody: '' as T,
      messages: [],
      headers: {},
      state: 'closed',
      transport: 'websocket',
      error: null,
      request: this.request,
    };

    this.notifyClose(response);

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
    logger.error('WebSocket error:', error);
    this.protocol.stopHeartbeat();
    this.notifyError(error);
  }

  private scheduleReconnect(): void {
    const delay = this.calculateReconnectDelay();
    this.reconnectAttempts++;

    logger.info(
      `Scheduling reconnection attempt ${this.reconnectAttempts} in ${delay}ms`,
    );

    this.notifyReconnect(this.request, {
      status: 0,
      reasonPhrase: 'Reconnecting',
      responseBody: '' as T,
      messages: [],
      headers: {},
      state: 'reconnecting',
      transport: 'websocket',
      error: null,
      request: this.request,
    });

    this.reconnectTimer = setTimeout(() => {
      this.protocol.reset();
      this.connect().catch((error) => {
        logger.error('Reconnection failed:', error);
      });
    }, delay);
  }

  private calculateReconnectDelay(): number {
    const baseDelay = this.request.reconnectInterval ?? 1000;
    const exponentialDelay =
      baseDelay * Math.pow(2, Math.min(this.reconnectAttempts, 5));
    const jitter = 0.5 + Math.random() * 0.5;
    return exponentialDelay * jitter;
  }
}
