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

import type { AtmosphereRequest } from '../types';
import { logger } from './logger';
import { VERSION } from '../version';

/**
 * Result of processing a message through the Atmosphere protocol handler.
 */
export interface ProtocolResult {
  /** The remaining message after protocol data is extracted (empty string if handshake-only). */
  message: string;
  /** Whether this was the initial handshake message. */
  wasHandshake: boolean;
}

/**
 * Manages the Atmosphere wire protocol: handshake, heartbeat, UUID tracking,
 * and framework header attachment.
 *
 * Ported from the legacy atmosphere.js `_handleProtocol`, `_attachHeaders`,
 * `_startHeartbeat`, and `_trackMessageSize` functions.
 */
export class AtmosphereProtocol {
  /** Server-assigned UUID for this connection. */
  uuid = '0';

  /** Whether we have received the first (handshake) message. */
  private firstMessage = true;

  /** Server-specified heartbeat interval in ms. 0 = disabled. */
  heartbeatInterval = 0;

  /** Heartbeat padding character(s) sent to the server. */
  heartbeatPadding = 'X';

  /** Active heartbeat timer id. */
  private heartbeatTimer: ReturnType<typeof setTimeout> | null = null;

  /** Partial message accumulator for trackMessageLength mode. */
  private partialMessage = '';

  /** Callback to send a push message (heartbeat). */
  private pushFn: ((msg: string) => void) | null = null;

  /**
   * Register the push function used for heartbeat pings.
   */
  setPushFunction(fn: (msg: string) => void): void {
    this.pushFn = fn;
  }

  /**
   * Build the URL with Atmosphere framework headers attached as query parameters.
   * Mirrors legacy `_attachHeaders()`.
   */
  buildUrl(request: AtmosphereRequest): string {
    const base = request.url;
    const sep = base.includes('?') ? '&' : '?';
    const params: string[] = [];

    params.push(`X-Atmosphere-tracking-id=${encodeURIComponent(this.uuid)}`);
    params.push(`X-Atmosphere-Framework=${encodeURIComponent(VERSION)}`);
    params.push(`X-Atmosphere-Transport=${encodeURIComponent(request.transport)}`);

    if (request.trackMessageLength) {
      params.push('X-Atmosphere-TrackMessageSize=true');
    }

    if (request.heartbeat?.server) {
      params.push(`X-Heartbeat-Server=${request.heartbeat.server}`);
    }

    if (request.contentType) {
      params.push(
        `Content-Type=${request.transport === 'websocket' ? request.contentType : encodeURIComponent(request.contentType)}`,
      );
    }

    if (request.enableProtocol) {
      params.push('X-atmo-protocol=true');
    }

    if (request.headers) {
      for (const [name, value] of Object.entries(request.headers)) {
        params.push(`${encodeURIComponent(name)}=${encodeURIComponent(value)}`);
      }
    }

    return base + sep + params.join('&');
  }

  /**
   * Process an incoming message through the Atmosphere protocol layer.
   * Handles the initial handshake (UUID + heartbeat config extraction)
   * and subsequent messages.
   *
   * Mirrors legacy `_handleProtocol()`.
   */
  handleProtocol(request: AtmosphereRequest, message: string): ProtocolResult {
    if (request.transport === 'polling') {
      return { message, wasHandshake: false };
    }

    if (request.enableProtocol && this.firstMessage && message.trim().length > 0) {
      const delimiter = request.messageDelimiter ?? '|';
      const pos = request.trackMessageLength ? 1 : 0;
      const parts = message.split(delimiter);

      if (parts.length <= pos + 1) {
        // Incomplete handshake — pass through
        return { message, wasHandshake: false };
      }

      this.firstMessage = false;
      this.uuid = parts[pos].trim();
      this.heartbeatInterval = parseInt(parts[pos + 1]?.trim() ?? '0', 10);
      this.heartbeatPadding = parts[pos + 2] ?? 'X';

      // Reconstruct trailing messages after the handshake data
      const trailingStart = request.trackMessageLength ? 4 : 3;
      let trailing = '';
      if (parts.length > trailingStart + 1) {
        trailing = parts.slice(trailingStart).join(delimiter);
      }

      logger.debug(`Protocol handshake: uuid=${this.uuid}, heartbeat=${this.heartbeatInterval}ms`);
      return { message: trailing, wasHandshake: true };
    }

    return { message, wasHandshake: false };
  }

  /**
   * Process a raw message through protocol + trackMessageLength parsing.
   * Returns null if the message is incomplete and should be buffered.
   *
   * Mirrors legacy `_trackMessageSize()`.
   */
  processMessage(
    rawMessage: string,
    request: AtmosphereRequest,
  ): { body: string; messages: string[] } | null {
    const { message } = this.handleProtocol(request, rawMessage);

    if (message.length === 0) {
      return null; // Handshake-only or empty
    }

    if (!request.trackMessageLength) {
      return { body: message, messages: [message] };
    }

    // Length-delimited message parsing
    const delimiter = request.messageDelimiter ?? '|';
    let data = this.partialMessage + message;
    const messages: string[] = [];

    let delimIdx = data.indexOf(delimiter);
    while (delimIdx !== -1) {
      const lengthStr = data.substring(0, delimIdx);
      const msgLength = parseInt(lengthStr, 10);

      if (isNaN(msgLength)) {
        this.partialMessage = '';
        throw new Error(`Message length "${lengthStr}" is not a number`);
      }

      const contentStart = delimIdx + delimiter.length;
      if (contentStart + msgLength > data.length) {
        // Message not complete yet
        break;
      }

      messages.push(data.substring(contentStart, contentStart + msgLength));
      data = data.substring(contentStart + msgLength);
      delimIdx = data.indexOf(delimiter);
    }

    this.partialMessage = data;

    if (messages.length === 0) {
      return null; // Still buffering
    }

    return { body: messages.join(delimiter), messages };
  }

  /**
   * Start the heartbeat timer. Sends `heartbeatPadding` at `heartbeatInterval` ms.
   */
  startHeartbeat(): void {
    this.stopHeartbeat();

    if (this.heartbeatInterval > 0 && this.pushFn) {
      const tick = () => {
        logger.debug('Sending heartbeat');
        this.pushFn?.(this.heartbeatPadding);
        this.heartbeatTimer = setTimeout(tick, this.heartbeatInterval);
      };
      this.heartbeatTimer = setTimeout(tick, this.heartbeatInterval);
    }
  }

  /**
   * Stop the heartbeat timer.
   */
  stopHeartbeat(): void {
    if (this.heartbeatTimer !== null) {
      clearTimeout(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  /**
   * Reset protocol state for a new connection.
   */
  reset(): void {
    this.firstMessage = true;
    this.partialMessage = '';
    this.stopHeartbeat();
  }
}
