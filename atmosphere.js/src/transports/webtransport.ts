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
  /**
   * Buffers partial frames between reads. Frames are byte-level: a 0x00
   * marker opens a length-prefixed binary frame (marker + 4-byte big-endian
   * length + payload); anything else is newline-delimited UTF-8 text.
   * Binary payloads must never pass through the text decode/split path —
   * that silently corrupted them. Mirrors the server's BidiFraming.
   */
  private incomingBytes = new Uint8Array(0);

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
    // QUIC streams don't preserve message boundaries, so every message is
    // framed: text gets a trailing \n; binary gets the 0x00-marker
    // length-prefixed frame (sending it raw would land in the server's
    // newline-splitting UTF-8 text path and corrupt it).
    let bytes: Uint8Array;
    if (typeof outgoing === 'string') {
      bytes = this.textEncoder.encode(outgoing + '\n');
    } else {
      const payload = new Uint8Array(outgoing);
      bytes = new Uint8Array(5 + payload.length);
      bytes[0] = 0x00;
      new DataView(bytes.buffer).setUint32(1, payload.length);
      bytes.set(payload, 5);
    }
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
    } else if (this.hasOpened) {
      // Server sends the protocol handshake only on the first connection;
      // subsequent reopens (with an existing tracking-id) won't repeat it,
      // so fire reopen + restart the heartbeat from the cached config (#294).
      this.notifyHandshakeOpen();
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
        const merged = new Uint8Array(this.incomingBytes.length + value.length);
        merged.set(this.incomingBytes);
        merged.set(value, this.incomingBytes.length);
        this.incomingBytes = merged;
        this.drainFrames(false);
      }
      // Flush any trailing text; an incomplete binary frame at close is
      // dropped — decode only complete framed messages.
      this.drainFrames(true);
    } catch (error) {
      if (!this.readLoopAborted) {
        this.handleError(error as Error);
      }
    }

    if (!this.readLoopAborted) {
      this.handleClose();
    }
  }

  /**
   * Deliver every complete frame buffered in {@code incomingBytes}. With
   * {@code final} set, trailing unterminated TEXT is flushed as a last
   * message (stream closed mid-line) while a partial binary frame is
   * discarded rather than misread.
   */
  private drainFrames(final_: boolean): void {
    for (;;) {
      if (this.incomingBytes.length === 0) {
        return;
      }
      if (this.incomingBytes[0] === 0x00) {
        if (this.incomingBytes.length < 5) {
          if (final_) {
            this.incomingBytes = new Uint8Array(0);
          }
          return;
        }
        const view = new DataView(
          this.incomingBytes.buffer,
          this.incomingBytes.byteOffset,
          this.incomingBytes.byteLength,
        );
        const length = view.getUint32(1);
        if (this.incomingBytes.length < 5 + length) {
          if (final_) {
            this.incomingBytes = new Uint8Array(0);
          }
          return;
        }
        const payload = this.incomingBytes.slice(5, 5 + length);
        this.incomingBytes = this.incomingBytes.slice(5 + length);
        this.handleBinaryMessage(payload.buffer);
        continue;
      }
      const nlIdx = this.incomingBytes.indexOf(0x0a);
      if (nlIdx < 0) {
        if (final_) {
          const message = this.textDecoder.decode(this.incomingBytes);
          this.incomingBytes = new Uint8Array(0);
          if (message.length > 0) {
            this.handleMessage(message);
          }
        }
        return;
      }
      const message = this.textDecoder.decode(this.incomingBytes.slice(0, nlIdx));
      this.incomingBytes = this.incomingBytes.slice(nlIdx + 1);
      if (message.length > 0) {
        this.handleMessage(message);
      }
    }
  }

  /** Binary frames pass through untouched, mirroring the WebSocket transport. */
  private handleBinaryMessage(data: ArrayBuffer): void {
    const response: AtmosphereResponse<T> = {
      status: 200,
      reasonPhrase: 'OK',
      responseBody: data as T,
      messages: [data],
      headers: {},
      state: 'messageReceived',
      transport: 'webtransport',
      error: null,
      request: this.request,
    };
    this.notifyMessage(response);
  }

  private handleMessage(data: string): void {
    const result = this.protocol.processMessage(data, this.request);

    if (result === null) {
      // Handshake processed or partial message buffered
      this.notifyHandshakeOpen();
      return;
    }

    // Handshake may also arrive with trailing payload (issue #294).
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
      this.incomingBytes = new Uint8Array(0);
      this.connect().catch((error) => {
        logger.error('Reconnection failed:', error);
        // Re-enter the close flow so the retry/quota logic runs — without
        // this the reconnect chain dies silently on a rejected connect().
        // WebSocket gets the equivalent for free (the browser fires onclose
        // after a failed connection attempt); a failed WebTransport
        // handshake fires no close event at all.
        this.handleClose();
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
