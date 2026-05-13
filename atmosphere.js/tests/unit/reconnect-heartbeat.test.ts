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
import type { AtmosphereRequest, SubscriptionHandlers } from '../../src/types';

describe('WebSocket heartbeat after reconnect (issue #294)', () => {
  let mocks: any[];
  let mockHandlers: SubscriptionHandlers;

  beforeEach(() => {
    vi.useFakeTimers();
    mocks = [];
    mockHandlers = {
      open: vi.fn(),
      message: vi.fn(),
      close: vi.fn(),
      error: vi.fn(),
      reconnect: vi.fn(),
      reopen: vi.fn(),
    };
    const WSCtor: any = vi.fn(function () {
      const ws = {
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        send: vi.fn(),
        close: vi.fn(),
        readyState: 1,
        onopen: null as any,
        onmessage: null as any,
        onerror: null as any,
        onclose: null as any,
        binaryType: 'arraybuffer',
      };
      mocks.push(ws);
      return ws;
    });
    WSCtor.OPEN = 1;
    WSCtor.CLOSED = 3;
    global.WebSocket = WSCtor;
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('restarts heartbeat after a reconnect with enableProtocol', async () => {
    const transport = new WebSocketTransport(
      {
        url: 'ws://localhost:8080/chat',
        transport: 'websocket',
        enableProtocol: true,
        trackMessageLength: false,
        reconnect: true,
        reconnectInterval: 100,
        maxReconnectOnClose: 5,
      },
      mockHandlers,
    );
    const p1 = transport.connect();
    mocks[0].onopen?.();
    await p1;

    // Initial handshake → uuid + 1000ms heartbeat
    mocks[0].onmessage?.({ data: 'uuid-1|1000|X|' });
    expect(mockHandlers.open).toHaveBeenCalledTimes(1);

    // Heartbeat fires
    vi.advanceTimersByTime(1000);
    expect(mocks[0].send).toHaveBeenCalledWith('X');
    mocks[0].send.mockClear();

    // Connection drops
    mocks[0].onclose?.({ code: 1006, reason: '' });
    expect(mockHandlers.reconnect).toHaveBeenCalled();

    // Reconnect timer fires → new WebSocket
    await vi.advanceTimersByTimeAsync(500);
    expect(mocks.length).toBe(2);
    mocks[1].onopen?.();

    // New handshake with different heartbeat
    mocks[1].onmessage?.({ data: 'uuid-2|2000|Y|' });
    expect(mockHandlers.reopen).toHaveBeenCalled();

    // Heartbeat MUST fire on the new connection
    vi.advanceTimersByTime(2000);
    expect(mocks[1].send).toHaveBeenCalledWith('Y');
  });

  it('restarts heartbeat after reconnect when handshake carries trailing data', async () => {
    const transport = new WebSocketTransport(
      {
        url: 'ws://localhost:8080/chat',
        transport: 'websocket',
        enableProtocol: true,
        trackMessageLength: false,
        reconnect: true,
        reconnectInterval: 100,
        maxReconnectOnClose: 5,
      },
      mockHandlers,
    );
    const p1 = transport.connect();
    mocks[0].onopen?.();
    await p1;

    // Initial handshake includes a trailing payload
    mocks[0].onmessage?.({ data: 'uuid-1|1000|X|first-payload' });
    expect(mockHandlers.open).toHaveBeenCalledTimes(1);
    expect(mockHandlers.message).toHaveBeenCalledWith(
      expect.objectContaining({ responseBody: 'first-payload' }),
    );

    vi.advanceTimersByTime(1000);
    expect(mocks[0].send).toHaveBeenCalledWith('X');
    mocks[0].send.mockClear();

    // Drop and reconnect
    mocks[0].onclose?.({ code: 1006, reason: '' });
    await vi.advanceTimersByTimeAsync(500);
    expect(mocks.length).toBe(2);
    mocks[1].onopen?.();

    // New handshake also carries trailing data — the regression case
    mocks[1].onmessage?.({ data: 'uuid-2|2000|Y|second-payload' });
    expect(mockHandlers.reopen).toHaveBeenCalled();

    vi.advanceTimersByTime(2000);
    expect(mocks[1].send).toHaveBeenCalledWith('Y');
  });
});
