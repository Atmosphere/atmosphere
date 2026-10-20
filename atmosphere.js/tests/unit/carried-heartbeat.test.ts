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
import { Atmosphere } from '../../src/core/atmosphere';

/**
 * The server announces the client heartbeat in the protocol handshake, and
 * sends that handshake only to a client that connects with tracking id 0. A
 * subscription that replaces a lost one (a fallback rebuild) connects under
 * the lost tracking id, so it is never told the interval again: it must keep
 * the one the first handshake announced, or a quiet client is closed by the
 * server's idle timeout.
 */
describe('heartbeat of a subscription under a carried tracking id', () => {
  let originalWebSocket: typeof global.WebSocket;
  let sockets: any[];

  beforeEach(() => {
    vi.useFakeTimers();
    originalWebSocket = global.WebSocket;
    sockets = [];
    const WSCtor: any = vi.fn(function (url: string) {
      const ws = {
        url,
        sent: [] as string[],
        send(data: string) { this.sent.push(data); },
        close: vi.fn(),
        readyState: 1,
        onopen: null as any,
        onmessage: null as any,
        onerror: null as any,
        onclose: null as any,
        binaryType: 'arraybuffer',
      };
      sockets.push(ws);
      setTimeout(() => ws.onopen?.({ type: 'open' }), 0);
      return ws;
    });
    WSCtor.OPEN = 1;
    WSCtor.CONNECTING = 0;
    WSCtor.CLOSED = 3;
    global.WebSocket = WSCtor;
  });

  afterEach(() => {
    global.WebSocket = originalWebSocket;
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  const request = {
    url: 'ws://localhost:8080/atmosphere/chat',
    transport: 'websocket' as const,
    enableProtocol: true,
    trackMessageLength: false,
    reconnect: false,
  };

  it('keeps sending the heartbeat the first handshake announced', async () => {
    const uuid = '0b5e1f2a-0000-4000-8000-00000000c0de';
    const first = new Atmosphere();
    const subscribing = first.subscribe({ ...request }, {});
    await vi.advanceTimersByTimeAsync(0);
    sockets[0].onmessage({ data: `${uuid}|30000|X|` });
    const lost = await subscribing;
    expect(lost.uuid).toBe(uuid);
    await vi.advanceTimersByTimeAsync(30_000);
    expect(sockets[0].sent).toEqual(['X']);
    await lost.abandon();

    // The rebuild: a new subscription under the lost tracking id. The server
    // knows the id, so it writes no handshake to this connection.
    const open = vi.fn();
    const rebuilt = await (async () => {
      const p = new Atmosphere().subscribe({ ...request, trackingId: uuid }, { open });
      await vi.advanceTimersByTimeAsync(0);
      return p;
    })();
    expect(sockets[1].url).toContain(`X-Atmosphere-tracking-id=${uuid}`);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(open).toHaveBeenCalledTimes(1);
    expect(sockets[1].sent).toEqual(['X', 'X']);
    await rebuilt.close();
  });

  it('sends nothing for a tracking id no handshake announced a heartbeat for', async () => {
    const rebuilt = await (async () => {
      const p = new Atmosphere().subscribe(
        { ...request, trackingId: 'never-handshaken-0000' }, {});
      await vi.advanceTimersByTimeAsync(0);
      return p;
    })();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(sockets[0].sent).toEqual([]);
    await rebuilt.close();
  });
});
