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
 * WebTransport transport implementation.
 *
 * Full-duplex communication via the browser WebTransport API over HTTP/3.
 * Uses a single bidirectional stream for the Atmosphere protocol handshake,
 * heartbeat, and length-delimited message tracking.
 */
export class WebTransportTransport<T = unknown> extends BaseTransport<T> {
  private transport: WebTransport | null = null;
  private writer: WritableStreamDefaultWriter<Uint8Array> | null = null;
  private reader: ReadableStreamDefaultReader<Uint8Array> | null = null;
  private readLoopAborted = false;
  private reconnectAttempts = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private textDecoder = new TextDecoder();
  private textEncoder = new TextEncoder();
  /** Buffers partial lines from server between newline delimiters. */
  private incomingBuffer = '';

  get name(): string {
    return 'webtransport';
  }

  async connect(): Promise<void> {
    try {
      this._state = 'connecting';
      const url = this.buildWebTransportUrl(this.request.url);

      logger.debug(`Connecting to WebTransport: ${url}`);
      const options: Record<string, unknown> = {};
      if (this.request.serverCertificateHashes?.length) {
        options.serverCertificateHashes = this.request.serverCertificateHashes.map(
          (hash) => ({
            algorithm: 'sha-256',
            value: Uint8Array.from(atob(hash), (c) => c.charCodeAt(0)).buffer,
          }),
        );
      }
      this.transport = new WebTransport(url, options);
      this.readLoopAborted = false;

      // Race ready against closed to detect early failures
      const result = await Promise.race([
        this.transport.ready.then(() => 'ready' as const),
        this.transport.closed.then(() => 'closed' as const),
      ]);

      if (result === 'closed') {
        const error = new Error('WebTransport connection closed before ready');
        this.handleError(error);
        throw error;
      }

      const stream = await this.transport.createBidirectionalStream();
      this.writer = stream.writable.getWriter();
      this.reader = stream.readable.getReader();

      this.handleOpen();
      this.startReadLoop();
    } catch (error) {
      if ((error as Error).message !== 'WebTransport connection closed before ready') {
        this.handleError(error as Error);
      }
      throw error;
    }
  }

  async disconnect(): Promise<void> {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.protocol.stopHeartbeat();
    this.readLoopAborted = true;

    if (this.writer) {
      try {
        await this.writer.close();
      } catch {
        // Best-effort cleanup — writer may already be closed
      }
      this.writer = null;
    }

    if (this.reader) {
      try {
        await this.reader.cancel();
      } catch {
        // Best-effort cleanup — reader may already be closed
      }
      this.reader = null;
    }

    if (this.transport) {
      try {
        this.transport.close({ closeCode: 0, reason: 'Client disconnect' });
      } catch {
        // Best-effort cleanup — transport may already be closed
      }
      this.transport = null;
    }

    this._state = 'disconnected';
  }

  send(message: string | ArrayBuffer): void {
    if (!this.writer) {
      throw new Error('WebTransport is not connected');
    }
    const outgoing = this.applyOutgoing(message);
    // Append newline delimiter — QUIC streams don't preserve message
    // boundaries, so the server splits on \n to reconstruct messages.
    const delimited =
      typeof outgoing === 'string' ? outgoing + '\n' : outgoing;
    const bytes =
      typeof delimited === 'string'
        ? this.textEncoder.encode(delimited)
        : new Uint8Array(delimited);
    this.writer.write(bytes).catch((error: Error) => {
      logger.error('WebTransport write failed:', error);
      this.handleError(error);
    });
  }

  private buildWebTransportUrl(_url: string): string {
    // If an explicit WebTransport URL is configured, use it directly
    // (useful when the HTTP/3 server runs on a different port than the servlet container)
    if (this.request.webTransportUrl) {
      // Merge Atmosphere protocol params (auth, tracking ID, etc.) into the explicit URL
      const builtUrl = this.protocol.buildUrl(this.request);
      let base: string | undefined;
      if (typeof window !== 'undefined' && window.location?.href) {
        base = window.location.href;
      }
      const paramsUrl = new URL(builtUrl, base || this.request.webTransportUrl);
      const explicit = new URL(this.request.webTransportUrl);
      explicit.protocol = 'https:';
      // Copy query params from the protocol-built URL
      paramsUrl.searchParams.forEach((value, key) => {
        explicit.searchParams.set(key, value);
      });
      return explicit.toString();
    }

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
    // WebTransport uses HTTPS, not a custom scheme
    fullUrl.protocol = 'https:';
    return fullUrl.toString();
  }

  private handleOpen(): void {
    this.reconnectAttempts = 0;
    logger.info('WebTransport connection established');

    this.protocol.setPushFunction((msg) => this.send(msg));

    const response: AtmosphereResponse<T> = {
      status: 200,
      reasonPhrase: 'OK',
      responseBody: '' as T,
      messages: [],
      headers: {},
      state: 'open',
      transport: 'webtransport',
      error: null,
      request: this.request,
    };

    if (!this.request.enableProtocol) {
      this.notifyOpen(response);
      this.protocol.startHeartbeat();
    }
  }

  private startReadLoop(): void {
    // Fire-and-forget; errors are handled inside the loop
    this.readLoop().catch(() => {
      // Handled in readLoop
    });
  }

  private async readLoop(): Promise<void> {
    try {
      while (!this.readLoopAborted) {
        const { value, done } = await this.reader!.read();
        if (done || this.readLoopAborted) {
          break;
        }
        const data = this.textDecoder.decode(value, { stream: true });
        // Server sends newline-delimited messages — buffer and split
        this.incomingBuffer += data;
        let nlIdx: number;
        while ((nlIdx = this.incomingBuffer.indexOf('\n')) >= 0) {
          const message = this.incomingBuffer.slice(0, nlIdx);
          this.incomingBuffer = this.incomingBuffer.slice(nlIdx + 1);
          if (message.length > 0) {
            this.handleMessage(message);
          }
        }
      }
      // Flush any remaining decoder and buffer state
      this.textDecoder.decode(new Uint8Array(), { stream: false });
      if (this.incomingBuffer.length > 0) {
        this.handleMessage(this.incomingBuffer);
        this.incomingBuffer = '';
      }
    } catch (error) {
      if (!this.readLoopAborted) {
        this.handleError(error as Error);
      }
    }

    if (!this.readLoopAborted) {
      this.handleClose();
    }
  }

  private handleMessage(data: string): void {
    const result = this.protocol.processMessage(data, this.request);

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
          transport: 'webtransport',
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
        transport: 'webtransport',
        error: null,
        request: this.request,
      };
      this.notifyMessage(response);
    }
  }

  private handleClose(): void {
    if (this.request.closed) {
      return;
    }

    logger.info('WebTransport closed');
    this.protocol.stopHeartbeat();

    const response: AtmosphereResponse<T> = {
      status: 0,
      reasonPhrase: 'Connection closed',
      responseBody: '' as T,
      messages: [],
      headers: {},
      state: 'closed',
      transport: 'webtransport',
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
    logger.error('WebTransport error:', error);
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
      transport: 'webtransport',
      error: null,
      request: this.request,
    });

    this.reconnectTimer = setTimeout(() => {
      this.protocol.reset();
      this.incomingBuffer = '';
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
