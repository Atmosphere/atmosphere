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
 * WebSocket transport implementation
 */
export class WebSocketTransport<T = unknown> extends BaseTransport<T> {
  private ws: WebSocket | null = null;
  private reconnectAttempts = 0;
  private reconnectTimer: number | null = null;

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

        this.ws.onopen = (event) => {
          this.handleOpen(event);
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
    this.ws.send(message);
  }

  private buildWebSocketUrl(url: string): string {
    const fullUrl = new URL(this.buildUrl(url), window.location.href);
    fullUrl.protocol = fullUrl.protocol.replace('http', 'ws');
    return fullUrl.toString();
  }

  private handleOpen(_event: Event): void {
    this.reconnectAttempts = 0;
    logger.info('WebSocket connection established');

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

    this.notifyOpen(response);
  }

  private handleMessage(event: MessageEvent): void {
    const messages = this.request.trackMessageLength
      ? this.splitMessages(event.data)
      : [event.data];

    messages.forEach((msg) => {
      if (msg) {
        const response: AtmosphereResponse<T> = {
          status: 200,
          reasonPhrase: 'OK',
          responseBody: msg as T,
          messages: [msg],
          headers: {},
          state: 'messageReceived',
          transport: 'websocket',
          error: null,
          request: this.request,
        };
        this.notifyMessage(response);
      }
    });
  }

  private handleClose(event: CloseEvent): void {
    if (this.request.closed) {
      return;
    }

    logger.info(`WebSocket closed: code=${event.code}, reason=${event.reason}`);

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
    }
  }

  private handleError(error: Error): void {
    logger.error('WebSocket error:', error);
    this.notifyError(error);
  }

  private scheduleReconnect(): void {
    const delay = this.calculateReconnectDelay();
    this.reconnectAttempts++;

    logger.info(
      `Scheduling reconnection attempt ${this.reconnectAttempts} in ${delay}ms`,
    );

    this.reconnectTimer = window.setTimeout(() => {
      this.connect().catch((error) => {
        logger.error('Reconnection failed:', error);
      });
    }, delay);
  }

  private calculateReconnectDelay(): number {
    const baseDelay = this.request.reconnectInterval ?? 1000;
    // Exponential backoff with jitter
    const exponentialDelay =
      baseDelay * Math.pow(2, Math.min(this.reconnectAttempts, 5));
    const jitter = 0.5 + Math.random() * 0.5;
    return exponentialDelay * jitter;
  }

  private splitMessages(data: string): string[] {
    const delimiter = this.request.messageDelimiter ?? '\n';
    return data.split(delimiter).filter((msg) => msg.length > 0);
  }
}
