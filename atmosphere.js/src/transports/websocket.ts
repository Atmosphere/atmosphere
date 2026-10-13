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
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  /** Set by disconnect(): the close is deliberate, so it neither reports nor reconnects. */
  private aborted = false;

  get name(): string {
    return 'websocket';
  }

  async connect(): Promise<void> {
    return new Promise((resolve, reject) => {
      try {
        this._state = 'connecting';
        this.aborted = false;
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
          if (!this.aborted) {
            this.handleError(error);
          }
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
    this.aborted = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.protocol.stopHeartbeat();

    // A socket still connecting (a reconnect in flight) is closed too, or it
    // would open after the subscription was closed.
    if (this.ws?.readyState === WebSocket.OPEN || this.ws?.readyState === WebSocket.CONNECTING) {
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
    const builtUrl = this.protocol.buildUrl(this.request);
    let base: string | undefined;
    if (typeof window !== 'undefined' && window.location?.href) {
      base = window.location.href;
    }
    if (!base) {
      // React Native or non-browser environment — URL must be absolute
      try {
        new URL(builtUrl);
      } catch {
        throw new Error(
          'In React Native or non-browser environments, request.url must be an absolute URL ' +
          `(e.g. "https://example.com/chat"). Got: "${this.request.url}"`,
        );
      }
    }
    const fullUrl = new URL(builtUrl, base);
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
    } else if (this.hasOpened) {
      // Server sends the protocol handshake only on the first connection;
      // subsequent reopens (with an existing tracking-id) won't repeat it,
      // so fire reopen + restart the heartbeat from the cached config (#294).
      this.notifyHandshakeOpen();
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
      this.notifyHandshakeOpen();
      return;
    }

    // Handshake may also arrive with trailing payload — issue #294.
    if (result.wasHandshake) {
      this.notifyHandshakeOpen();
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
    if (this.aborted || this.request.closed) {
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
      this.reconnectAttempts < this.maxReconnectAttempts()
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
    const delay = this.nextReconnectDelay();
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
}
