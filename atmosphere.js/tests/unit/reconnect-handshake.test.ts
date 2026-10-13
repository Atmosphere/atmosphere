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

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { WebSocketTransport } from '../../src/transports/websocket';
import { StreamingTransport } from '../../src/transports/streaming';
import type { AtmosphereRequest, SubscriptionHandlers } from '../../src/types';

/**
 * The server (JavaScriptProtocol) sends the protocol handshake only to a
 * request whose X-Atmosphere-tracking-id is "0". A reconnect that sends the
 * uuid the client already holds gets no handshake, so its first ordinary
 * message must not be parsed as one (issue #2781).
 */

/** A length-delimited frame, as TrackMessageSizeFilter writes it. */
function frame(payload: string): string {
  return `${payload.length}|${payload}`;
}

const PAYLOAD = '{"a":"x|y|z|"}';

function trackingIdOf(url: string): string | null {
  return new URL(url, 'http://localhost').searchParams.get('X-Atmosphere-tracking-id');
}

function createHandlers(): SubscriptionHandlers {
  return {
    open: vi.fn(),
    message: vi.fn(),
    close: vi.fn(),
    error: vi.fn(),
    reconnect: vi.fn(),
    reopen: vi.fn(),
  };
}

describe('protocol handshake after a WebSocket reconnect (#2781)', () => {
  let sockets: any[];
  let urls: string[];
  let handlers: SubscriptionHandlers;

  beforeEach(() => {
    vi.useFakeTimers();
    sockets = [];
    urls = [];
    handlers = createHandlers();
    const WSCtor: any = vi.fn(function (url: string) {
      const ws = {
        send: vi.fn(),
        close: vi.fn(),
        readyState: 1,
        onopen: null as any,
        onmessage: null as any,
        onerror: null as any,
        onclose: null as any,
        binaryType: 'arraybuffer',
      };
      urls.push(url);
      sockets.push(ws);
      return ws;
    });
    WSCtor.OPEN = 1;
    WSCtor.CONNECTING = 0;
    WSCtor.CLOSED = 3;
    global.WebSocket = WSCtor;
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  function createTransport(): WebSocketTransport {
    const request: AtmosphereRequest = {
      url: 'ws://localhost:8080/chat',
      transport: 'websocket',
      enableProtocol: true,
      trackMessageLength: true,
      reconnect: true,
      reconnectInterval: 100,
      maxReconnectOnClose: 5,
    };
    return new WebSocketTransport(request, handlers);
  }

  it('delivers the first message after a reconnect intact and keeps the uuid', async () => {
    const transport = createTransport();
    const first = transport.connect();
    sockets[0].onopen?.();
    await first;

    sockets[0].onmessage?.({ data: frame('abc-123|30000|X|') });
    expect(transport.uuid).toBe('abc-123');
    expect(trackingIdOf(urls[0])).toBe('0');

    // The socket drops and the transport reconnects under its uuid.
    sockets[0].onclose?.({ code: 1006, reason: '' });
    await vi.advanceTimersByTimeAsync(1000);
    expect(sockets).toHaveLength(2);
    expect(trackingIdOf(urls[1])).toBe('abc-123');
    sockets[1].onopen?.();

    // No handshake comes back: the first push is ordinary application data.
    sockets[1].onmessage?.({ data: frame(PAYLOAD) });

    expect(handlers.message).toHaveBeenCalledTimes(1);
    expect(handlers.message).toHaveBeenCalledWith(
      expect.objectContaining({ responseBody: PAYLOAD, messages: [PAYLOAD] }),
    );
    expect(transport.uuid).toBe('abc-123');

    // The cached heartbeat config is still the handshake's.
    sockets[1].send.mockClear();
    vi.advanceTimersByTime(30000);
    expect(sockets[1].send).toHaveBeenCalledWith('X');
  });

  it('parses a handshake on reconnect when the dropped connection never got one', async () => {
    const transport = createTransport();
    const first = transport.connect();
    sockets[0].onopen?.();
    await first;

    // Dropped before the handshake: the reconnect still sends tracking id 0.
    sockets[0].onclose?.({ code: 1006, reason: '' });
    await vi.advanceTimersByTimeAsync(1000);
    expect(sockets).toHaveLength(2);
    expect(trackingIdOf(urls[1])).toBe('0');
    sockets[1].onopen?.();

    sockets[1].onmessage?.({ data: frame('def-456|30000|X|') });
    expect(transport.uuid).toBe('def-456');
    expect(handlers.open).toHaveBeenCalledTimes(1);
    expect(handlers.message).not.toHaveBeenCalled();

    sockets[1].onmessage?.({ data: frame(PAYLOAD) });
    expect(handlers.message).toHaveBeenCalledWith(
      expect.objectContaining({ responseBody: PAYLOAD }),
    );
    expect(transport.uuid).toBe('def-456');
  });
});

describe('protocol handshake after an HTTP streaming reconnect (#2781)', () => {
  let originalFetch: typeof global.fetch;
  let urls: string[];
  let streams: Array<{ push: (chunk: string) => void; close: () => void }>;
  let handlers: SubscriptionHandlers;

  beforeEach(() => {
    vi.useFakeTimers();
    originalFetch = global.fetch;
    urls = [];
    streams = [];
    handlers = createHandlers();
    const encoder = new TextEncoder();
    global.fetch = vi.fn(async (url: string) => {
      urls.push(url);
      let controller!: ReadableStreamDefaultController<Uint8Array>;
      const body = new ReadableStream<Uint8Array>({
        start(c) {
          controller = c;
        },
      });
      streams.push({
        push: (chunk) => controller.enqueue(encoder.encode(chunk)),
        close: () => controller.close(),
      });
      return { ok: true, status: 200, body, headers: new Headers() } as unknown as Response;
    }) as unknown as typeof fetch;
  });

  afterEach(() => {
    global.fetch = originalFetch;
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  function createTransport(): StreamingTransport {
    const request: AtmosphereRequest = {
      url: 'http://localhost:8080/chat',
      transport: 'streaming',
      enableProtocol: true,
      trackMessageLength: true,
      reconnect: true,
      reconnectInterval: 100,
      maxReconnectOnClose: 5,
    };
    return new StreamingTransport(request, handlers);
  }

  it('delivers the first message after a reconnect intact and keeps the uuid', async () => {
    const transport = createTransport();
    await transport.connect();
    streams[0].push(frame('abc-123|0|X|'));
    await vi.advanceTimersByTimeAsync(0);
    expect(transport.uuid).toBe('abc-123');
    expect(handlers.open).toHaveBeenCalledTimes(1);

    // The stream ends and the transport reconnects under its uuid.
    streams[0].close();
    await vi.advanceTimersByTimeAsync(1000);
    expect(streams).toHaveLength(2);
    expect(trackingIdOf(urls[1])).toBe('abc-123');

    streams[1].push(frame(PAYLOAD));
    await vi.advanceTimersByTimeAsync(0);

    expect(handlers.message).toHaveBeenCalledTimes(1);
    expect(handlers.message).toHaveBeenCalledWith(
      expect.objectContaining({ responseBody: PAYLOAD }),
    );
    expect(transport.uuid).toBe('abc-123');
    await transport.disconnect();
  });

  it('parses a fresh handshake on a reconnect that still sends tracking id 0', async () => {
    const transport = createTransport();
    await transport.connect();

    // The first stream ends before its handshake arrived.
    streams[0].close();
    await vi.advanceTimersByTimeAsync(1000);
    expect(streams).toHaveLength(2);
    expect(trackingIdOf(urls[1])).toBe('0');

    streams[1].push(frame('def-456|0|X|') + frame(PAYLOAD));
    await vi.advanceTimersByTimeAsync(0);

    expect(transport.uuid).toBe('def-456');
    expect(handlers.open).toHaveBeenCalledTimes(1);
    expect(handlers.message).toHaveBeenCalledTimes(1);
    expect(handlers.message).toHaveBeenCalledWith(
      expect.objectContaining({ responseBody: PAYLOAD }),
    );
    await transport.disconnect();
  });
});
