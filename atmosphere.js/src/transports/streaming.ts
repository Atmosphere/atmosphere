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
 * HTTP Streaming transport implementation.
 *
 * Opens a long-lived fetch connection and reads the response body
 * incrementally via ReadableStream. Unlike long-polling, the connection
 * stays open and data is read progressively.
 */
export class StreamingTransport<T = unknown> extends BaseTransport<T> {
  private abortController: AbortController | null = null;
  private reconnectAttempts = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private aborted = false;
  private opened = false;

  get name(): string {
    return 'streaming';
  }

  async connect(): Promise<void> {
    this._state = 'connecting';
    this.aborted = false;
    this.opened = false;

    this.protocol.setPushFunction((msg) => this.send(msg));

    const url = this.protocol.buildUrl(this.request);
    logger.debug(`Streaming connect: ${url}`);

    this.abortController = new AbortController();

    let response: Response;
    try {
      response = await fetch(url, {
        headers: { 'Content-Type': this.request.contentType ?? 'text/plain' },
        credentials: this.request.withCredentials ? 'include' : 'same-origin',
        signal: this.abortController.signal,
      });
    } catch (error) {
      if (this.aborted || (error as Error).name === 'AbortError') return;
      const connectError = new Error('Streaming connection error');
      this.handleError(connectError);
      throw connectError;
    }

    if (!response.ok || !response.body) {
      const error = new Error(`Streaming connection failed: ${response.status}`);
      this.handleError(error);
      throw error;
    }

    // Connection established
    this.opened = true;
    this.reconnectAttempts = 0;
    this.protocol.extractSessionToken((name) => response.headers.get(name));

    const openResponse: AtmosphereResponse<T> = {
      status: 200,
      reasonPhrase: 'OK',
      responseBody: '' as T,
      messages: [],
      headers: {},
      state: 'open',
      transport: 'streaming',
      error: null,
      request: this.request,
    };

    if (!this.request.enableProtocol) {
      this.notifyOpen(openResponse);
      this.protocol.startHeartbeat();
    }

    // Read stream incrementally in the background
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    this.readStream(reader, decoder);
  }

  async disconnect(): Promise<void> {
    this.aborted = true;

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
      logger.warn('Streaming POST send failed:', error);
    });
  }

  private async readStream(
    reader: ReadableStreamDefaultReader<Uint8Array>,
    decoder: TextDecoder,
  ): Promise<void> {
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done || this.aborted) break;
        const chunk = decoder.decode(value, { stream: true });
        this.handleChunk(chunk);
      }
    } catch (error) {
      if (this.aborted || (error as Error).name === 'AbortError') return;
      if (this.opened) {
        this.handleClose();
      } else {
        this.handleError(error as Error);
      }
      return;
    }
    // Stream ended normally
    if (this.opened && !this.aborted) {
      this.handleClose();
    }
  }

  private handleChunk(chunk: string): void {
    const result = this.protocol.processMessage(chunk, this.request);

    if (result === null) {
      // Handshake or partial message
      if (this.request.enableProtocol && this._state !== 'connected') {
        const openResponse: AtmosphereResponse<T> = {
          status: 200,
          reasonPhrase: 'OK',
          responseBody: '' as T,
          messages: [],
          headers: {},
          state: 'open',
          transport: 'streaming',
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
        continue;
      }

      const response: AtmosphereResponse<T> = {
        status: 200,
        reasonPhrase: 'OK',
        responseBody: this.applyIncoming(msg) as T,
        messages: [msg],
        headers: {},
        state: 'messageReceived',
        transport: 'streaming',
        error: null,
        request: this.request,
      };
      this.notifyMessage(response);
    }
  }

  private handleClose(): void {
    logger.info('Streaming connection closed');
    this.protocol.stopHeartbeat();

    const response: AtmosphereResponse<T> = {
      status: 0,
      reasonPhrase: 'Streaming connection closed',
      responseBody: '' as T,
      messages: [],
      headers: {},
      state: 'closed',
      transport: 'streaming',
      error: null,
      request: this.request,
    };
    this.notifyClose(response);

    if (
      !this.aborted &&
      this.request.reconnect &&
      this.reconnectAttempts < (this.request.maxReconnectOnClose ?? 5)
    ) {
      this.scheduleReconnect();
    } else if (!this.aborted && this.request.reconnect) {
      this.notifyFailureToReconnect(response);
    }
  }

  private handleError(error: Error): void {
    logger.error('Streaming error:', error);
    this.protocol.stopHeartbeat();
    this.notifyError(error);
  }

  private scheduleReconnect(): void {
    const delay = this.calculateReconnectDelay();
    this.reconnectAttempts++;

    logger.info(`Streaming reconnect attempt ${this.reconnectAttempts} in ${delay}ms`);

    this.notifyReconnect(this.request, {
      status: 0,
      reasonPhrase: 'Reconnecting',
      responseBody: '' as T,
      messages: [],
      headers: {},
      state: 'reconnecting',
      transport: 'streaming',
      error: null,
      request: this.request,
    });

    this.reconnectTimer = setTimeout(() => {
      this.protocol.reset();
      this.opened = false;
      this.connect().catch((error) => {
        logger.error('Streaming reconnection failed:', error);
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
