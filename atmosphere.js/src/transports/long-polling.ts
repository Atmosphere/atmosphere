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
 * Opens an XHR request that the server holds open until data is available.
 * When a response arrives, the client processes it and immediately opens
 * a new request — creating a near-real-time server push channel.
 */
export class LongPollingTransport<T = unknown> extends BaseTransport<T> {
  private xhr: XMLHttpRequest | null = null;
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

    if (this.xhr) {
      this.xhr.abort();
      this.xhr = null;
    }

    this._state = 'disconnected';
  }

  send(message: string | ArrayBuffer): void {
    const url = this.protocol.buildUrl(this.request);
    const data = message instanceof ArrayBuffer
      ? new Blob([message])
      : message;

    const xhr = new XMLHttpRequest();
    xhr.open('POST', url, true);
    xhr.setRequestHeader('Content-Type', this.request.contentType ?? 'text/plain');
    if (this.request.withCredentials) {
      xhr.withCredentials = true;
    }
    xhr.send(data);
  }

  private async poll(isFirst: boolean): Promise<void> {
    if (this.aborted) return;

    return new Promise<void>((resolve, reject) => {
      const url = this.protocol.buildUrl(this.request);
      logger.debug(`Long-polling request: ${url}`);

      const xhr = new XMLHttpRequest();
      this.xhr = xhr;

      xhr.open('GET', url, true);
      xhr.setRequestHeader('Content-Type', this.request.contentType ?? 'text/plain');

      if (this.request.withCredentials) {
        xhr.withCredentials = true;
      }

      xhr.onreadystatechange = () => {
        if (this.aborted) return;

        if (xhr.readyState === 4) {
          if (xhr.status >= 200 && xhr.status < 300) {
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

            const responseText = xhr.responseText;
            if (responseText.trim().length > 0) {
              this.handleResponse(responseText);
            }

            resolve();

            // Immediately re-poll
            if (this._polling && !this.aborted) {
              this.poll(false).catch((error) => {
                logger.error('Long-polling re-poll failed:', error);
              });
            }
          } else if (xhr.status === 0 && this.aborted) {
            // Aborted intentionally
            resolve();
          } else {
            // Server error or disconnect
            this.handleDisconnect(isFirst, resolve, reject);
          }
        }
      };

      xhr.onerror = () => {
        if (this.aborted) {
          resolve();
          return;
        }
        this.handleDisconnect(isFirst, resolve, reject);
      };

      xhr.send(null);
    });
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
        responseBody: msg as T,
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

  private handleDisconnect(
    isFirst: boolean,
    resolve: () => void,
    reject: (error: Error) => void,
  ): void {
    this.protocol.stopHeartbeat();

    if (isFirst) {
      const error = new Error('Long-polling connection failed');
      this.notifyError(error);
      reject(error);
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

    resolve();

    if (
      this._polling &&
      this.request.reconnect &&
      this.reconnectAttempts < (this.request.maxReconnectOnClose ?? 5)
    ) {
      this.scheduleReconnect();
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
