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
import { WebTransportTransport } from '../../src/transports/webtransport';
import type { AtmosphereRequest, SubscriptionHandlers } from '../../src/types';

/**
 * An explicit close (subscription.close() → transport.disconnect()) is
 * terminal: no reconnect and no close notification describing it as a lost
 * connection (issue #2782).
 */

function createHandlers(): SubscriptionHandlers {
  return {
    open: vi.fn(),
    message: vi.fn(),
    close: vi.fn(),
    error: vi.fn(),
    reconnect: vi.fn(),
    reopen: vi.fn(),
    failureToReconnect: vi.fn(),
  };
}

describe('WebSocketTransport explicit close (#2782)', () => {
  let sockets: any[];
  let WSCtor: any;
  let handlers: SubscriptionHandlers;

  beforeEach(() => {
    vi.useFakeTimers();
    sockets = [];
    handlers = createHandlers();
    WSCtor = vi.fn(function () {
      const ws = {
        send: vi.fn(),
        close: vi.fn(),
        readyState: 0,
        onopen: null as any,
        onmessage: null as any,
        onerror: null as any,
        onclose: null as any,
        binaryType: 'arraybuffer',
      };
      sockets.push(ws);
      return ws;
    });
    WSCtor.CONNECTING = 0;
    WSCtor.OPEN = 1;
    WSCtor.CLOSED = 3;
    global.WebSocket = WSCtor;
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  function createTransport(overrides: Partial<AtmosphereRequest> = {}): WebSocketTransport {
    return new WebSocketTransport(
      {
        url: 'ws://localhost:8080/chat',
        transport: 'websocket',
        reconnect: true,
        reconnectInterval: 100,
        maxReconnectOnClose: 5,
        ...overrides,
      },
      handlers,
    );
  }

  async function open(transport: WebSocketTransport, index: number): Promise<void> {
    const connecting = index === 0 ? transport.connect() : null;
    sockets[index].readyState = 1;
    sockets[index].onopen?.();
    await connecting;
  }

  it('does not reconnect or report a lost connection after disconnect()', async () => {
    const transport = createTransport();
    await open(transport, 0);
    const ws = sockets[0];

    await transport.disconnect();
    expect(ws.close).toHaveBeenCalledTimes(1);
    // 1000: the server reports a coded clean close to @Disconnect as the client
    // leaving; a close with no code arrives as 1005 and reads as a lost client.
    expect(ws.close).toHaveBeenCalledWith(1000, 'Client disconnect');

    // The browser then delivers the close event of the socket we closed.
    ws.readyState = 3;
    ws.onclose?.({ code: 1000, reason: '' });

    expect(handlers.close).not.toHaveBeenCalled();
    expect(handlers.reconnect).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(60000);
    expect(WSCtor).toHaveBeenCalledTimes(1);
    expect(transport.state).toBe('disconnected');
  });

  it('closes a reconnect still in flight, which then neither opens nor reconnects', async () => {
    const transport = createTransport();
    await open(transport, 0);

    // The connection drops; the reconnect timer opens a new socket.
    sockets[0].readyState = 3;
    sockets[0].onclose?.({ code: 1006, reason: '' });
    expect(handlers.close).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1000);
    expect(sockets).toHaveLength(2);
    const inFlight = sockets[1];
    expect(inFlight.readyState).toBe(0);

    await transport.disconnect();
    expect(inFlight.close).toHaveBeenCalledTimes(1);

    // Closing a connecting socket fails it: error, then close.
    inFlight.readyState = 3;
    inFlight.onerror?.();
    inFlight.onclose?.({ code: 1006, reason: '' });

    expect(handlers.error).not.toHaveBeenCalled();
    expect(handlers.close).toHaveBeenCalledTimes(1);
    expect(handlers.reconnect).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(60000);
    expect(WSCtor).toHaveBeenCalledTimes(2);
    expect(transport.state).toBe('disconnected');
  });

  it('reconnects again after connect() follows a disconnect()', async () => {
    const transport = createTransport();
    await open(transport, 0);
    await transport.disconnect();

    const reconnecting = transport.connect();
    await open(transport, 1);
    await reconnecting;

    sockets[1].readyState = 3;
    sockets[1].onclose?.({ code: 1006, reason: '' });
    expect(handlers.close).toHaveBeenCalledTimes(1);
    expect(handlers.reconnect).toHaveBeenCalledTimes(1);
  });
});

describe('WebTransportTransport explicit close (#2782)', () => {
  let handlers: SubscriptionHandlers;
  let instances: any[];

  beforeEach(() => {
    vi.useFakeTimers();
    handlers = createHandlers();
    instances = [];
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    delete (globalThis as any).WebTransport;
  });

  /** A WebTransport whose `ready` the test settles, with one bidirectional stream. */
  function createInstance() {
    let resolveReady!: () => void;
    let rejectReady!: (error: Error) => void;
    const ready = new Promise<void>((resolve, reject) => {
      resolveReady = resolve;
      rejectReady = reject;
    });
    let endRead: (() => void) | null = null;
    const reader = {
      read: vi.fn(() => new Promise((resolve) => {
        endRead = () => resolve({ value: undefined, done: true });
      })),
      cancel: vi.fn().mockResolvedValue(undefined),
      releaseLock: vi.fn(),
    };
    const writer = {
      write: vi.fn().mockResolvedValue(undefined),
      close: vi.fn().mockResolvedValue(undefined),
      releaseLock: vi.fn(),
    };
    return {
      ready,
      closed: new Promise(() => {}),
      close: vi.fn(),
      createBidirectionalStream: vi.fn().mockResolvedValue({
        readable: { getReader: () => reader },
        writable: { getWriter: () => writer },
      }),
      resolveReady,
      rejectReady,
      endStream: () => endRead?.(),
      reader,
    };
  }

  it('does not reconnect when disconnect() lands while a reconnect is in flight', async () => {
    (globalThis as any).WebTransport = vi.fn(function () {
      const instance = createInstance();
      instances.push(instance);
      return instance;
    });
    const request: AtmosphereRequest = {
      url: 'https://localhost:8080/chat',
      transport: 'webtransport',
      reconnect: true,
      reconnectInterval: 100,
      maxReconnectOnClose: 5,
    };
    const transport = new WebTransportTransport(request, handlers);

    const connecting = transport.connect();
    instances[0].resolveReady();
    await connecting;
    await vi.advanceTimersByTimeAsync(0);
    expect(instances[0].reader.read).toHaveBeenCalled();

    // The stream ends; the reconnect timer starts a new handshake.
    instances[0].endStream();
    await vi.advanceTimersByTimeAsync(0);
    expect(handlers.close).toHaveBeenCalledTimes(1);
    expect(handlers.reconnect).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1000);
    expect(instances).toHaveLength(2);

    await transport.disconnect();
    expect(instances[1].close).toHaveBeenCalled();

    // The abandoned handshake now fails.
    instances[1].rejectReady(new Error('Opening handshake failed'));
    await vi.advanceTimersByTimeAsync(0);

    expect(handlers.close).toHaveBeenCalledTimes(1);
    expect(handlers.reconnect).toHaveBeenCalledTimes(1);
    expect(handlers.error).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(60000);
    expect((globalThis as any).WebTransport).toHaveBeenCalledTimes(2);
    expect(transport.state).toBe('disconnected');
  });

  it('does not open when disconnect() lands while the stream is being created', async () => {
    let resolveStream!: (stream: unknown) => void;
    (globalThis as any).WebTransport = vi.fn(function () {
      const instance = createInstance();
      instance.createBidirectionalStream = vi.fn(() => new Promise((resolve) => {
        resolveStream = resolve;
      }));
      instances.push(instance);
      return instance;
    });
    const transport = new WebTransportTransport(
      { url: 'https://localhost:8080/chat', transport: 'webtransport', reconnect: true },
      handlers,
    );

    const connecting = transport.connect();
    instances[0].resolveReady();
    await vi.advanceTimersByTimeAsync(0);
    expect(instances[0].createBidirectionalStream).toHaveBeenCalled();

    await transport.disconnect();
    resolveStream({
      readable: { getReader: () => instances[0].reader },
      writable: { getWriter: () => ({ write: vi.fn(), close: vi.fn(), releaseLock: vi.fn() }) },
    });
    await expect(connecting).rejects.toThrow();

    expect(handlers.open).not.toHaveBeenCalled();
    expect(handlers.error).not.toHaveBeenCalled();
    expect(transport.state).toBe('disconnected');
  });
});
