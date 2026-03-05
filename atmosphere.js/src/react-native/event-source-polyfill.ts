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

type EventSourceHandler = ((event: MessageEvent) => void) | null;
type OpenHandler = ((event: Event) => void) | null;
type ErrorHandler = ((event: Event) => void) | null;

/**
 * Fetch-based EventSource polyfill for React Native.
 *
 * Two read strategies:
 * 1. **Streaming** — `response.body.getReader()` when ReadableStream is available
 *    (RN 0.73+ / Expo SDK 50+ / Hermes with streaming support).
 * 2. **Text fallback** — `response.text()` on older RN/Hermes where
 *    `response.body` is `null`. Degrades SSE to effectively long-polling
 *    but maintains API compatibility.
 */
export class FetchEventSource {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSED = 2;

  readonly url: string;
  readonly withCredentials: boolean;

  readyState: number = FetchEventSource.CONNECTING;

  onopen: OpenHandler = null;
  onmessage: EventSourceHandler = null;
  onerror: ErrorHandler = null;

  private _listeners: Map<string, Set<(event: MessageEvent | Event) => void>> = new Map();
  private _abortController: AbortController | null = null;
  private _retryMs = 3000;
  private _lastEventId = '';
  private _reconnectTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(url: string | URL, init?: EventSourceInit) {
    this.url = typeof url === 'string' ? url : url.toString();
    this.withCredentials = init?.withCredentials ?? false;
    this._connect();
  }

  addEventListener(type: string, listener: (event: MessageEvent | Event) => void): void {
    let set = this._listeners.get(type);
    if (!set) {
      set = new Set();
      this._listeners.set(type, set);
    }
    set.add(listener);
  }

  removeEventListener(type: string, listener: (event: MessageEvent | Event) => void): void {
    this._listeners.get(type)?.delete(listener);
  }

  close(): void {
    this.readyState = FetchEventSource.CLOSED;
    this._abortController?.abort();
    this._abortController = null;
    if (this._reconnectTimer) {
      clearTimeout(this._reconnectTimer);
      this._reconnectTimer = null;
    }
  }

  private _dispatch(type: string, event: MessageEvent | Event): void {
    if (type === 'open') this.onopen?.(event as Event);
    else if (type === 'message') this.onmessage?.(event as MessageEvent);
    else if (type === 'error') this.onerror?.(event as Event);

    const listeners = this._listeners.get(type);
    if (listeners) {
      for (const fn of listeners) fn(event);
    }
  }

  private async _connect(): Promise<void> {
    if (this.readyState === FetchEventSource.CLOSED) return;

    this._abortController = new AbortController();
    const headers: Record<string, string> = {
      'Accept': 'text/event-stream',
      'Cache-Control': 'no-cache',
    };
    if (this._lastEventId) {
      headers['Last-Event-ID'] = this._lastEventId;
    }

    try {
      const response = await fetch(this.url, {
        method: 'GET',
        headers,
        credentials: this.withCredentials ? 'include' : 'same-origin',
        signal: this._abortController.signal,
      });

      if (!response.ok) {
        this._dispatchError();
        return;
      }

      this.readyState = FetchEventSource.OPEN;
      this._dispatch('open', new Event('open'));

      if (response.body && typeof response.body.getReader === 'function') {
        await this._readStream(response.body);
      } else {
        await this._readText(response);
      }
    } catch (err) {
      if ((err as Error).name === 'AbortError') return;
      this._dispatchError();
    }
  }

  /** Streaming path — uses ReadableStream (RN 0.73+ / Expo SDK 50+). */
  private async _readStream(body: ReadableStream<Uint8Array>): Promise<void> {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        if (this.readyState === FetchEventSource.CLOSED) break;

        buffer += decoder.decode(value, { stream: true });
        buffer = this._processBuffer(buffer);
      }
    } catch (err) {
      if ((err as Error).name !== 'AbortError') {
        this._dispatchError();
        return;
      }
    }

    // Stream ended — reconnect if not explicitly closed
    if (this.readyState !== FetchEventSource.CLOSED) {
      this._scheduleReconnect();
    }
  }

  /** Text fallback path — for older RN/Hermes where response.body is null. */
  private async _readText(response: Response): Promise<void> {
    try {
      const text = await response.text();
      if (this.readyState === FetchEventSource.CLOSED) return;
      this._processBuffer(text);
    } catch (err) {
      if ((err as Error).name !== 'AbortError') {
        this._dispatchError();
        return;
      }
    }

    // Connection ended — reconnect if not explicitly closed
    if (this.readyState !== FetchEventSource.CLOSED) {
      this._scheduleReconnect();
    }
  }

  /**
   * Parse buffered SSE text into events.
   * Returns any remaining incomplete data (no trailing double-newline).
   */
  private _processBuffer(buffer: string): string {
    // SSE events are separated by blank lines
    const blocks = buffer.split(/\r?\n\r?\n/);
    // Last block may be incomplete
    const remainder = blocks.pop() ?? '';

    for (const block of blocks) {
      if (!block.trim()) continue;
      this._parseEvent(block);
    }

    return remainder;
  }

  /** Parse a single SSE event block. */
  private _parseEvent(block: string): void {
    let data = '';
    let eventType = 'message';
    let hasData = false;

    const lines = block.split(/\r?\n/);
    for (const line of lines) {
      if (line.startsWith(':')) continue; // comment

      const colonIdx = line.indexOf(':');
      let field: string;
      let value: string;

      if (colonIdx === -1) {
        field = line;
        value = '';
      } else {
        field = line.substring(0, colonIdx);
        // Skip optional single leading space after colon
        value = line[colonIdx + 1] === ' '
          ? line.substring(colonIdx + 2)
          : line.substring(colonIdx + 1);
      }

      switch (field) {
        case 'data':
          if (hasData) data += '\n';
          data += value;
          hasData = true;
          break;
        case 'event':
          eventType = value;
          break;
        case 'id':
          if (!value.includes('\0')) {
            this._lastEventId = value;
          }
          break;
        case 'retry': {
          const ms = parseInt(value, 10);
          if (!isNaN(ms) && ms >= 0) {
            this._retryMs = ms;
          }
          break;
        }
      }
    }

    if (!hasData) return;

    const messageEvent = new MessageEvent(eventType, {
      data,
      lastEventId: this._lastEventId,
      origin: this.url,
    });

    this._dispatch(eventType, messageEvent);
    if (eventType !== 'message') {
      // Also fire on onmessage for named events (matches browser behavior
      // where addEventListener('eventName') works but onmessage only fires for unnamed)
    }
  }

  private _dispatchError(): void {
    this.readyState = FetchEventSource.CLOSED;
    this._dispatch('error', new Event('error'));
    this._scheduleReconnect();
  }

  private _scheduleReconnect(): void {
    if (this.readyState === FetchEventSource.CLOSED && this._abortController === null) {
      // Explicitly closed via close() — do not reconnect
      return;
    }
    this.readyState = FetchEventSource.CONNECTING;
    this._reconnectTimer = setTimeout(() => {
      this._reconnectTimer = null;
      this._connect();
    }, this._retryMs);
  }
}
