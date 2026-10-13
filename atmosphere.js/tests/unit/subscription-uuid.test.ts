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
 * `Subscription.uuid` exposes the server's tracking id: '0' before the
 * protocol handshake, the handshake's uuid afterwards, read live from the
 * transport in use (issue #2787).
 */
describe('Subscription.uuid (#2787)', () => {
  let originalFetch: typeof global.fetch;
  let originalWebSocket: typeof global.WebSocket;
  let sockets: any[];

  beforeEach(() => {
    originalFetch = global.fetch;
    originalWebSocket = global.WebSocket;
    sockets = [];
  });

  afterEach(() => {
    global.fetch = originalFetch;
    global.WebSocket = originalWebSocket;
    vi.restoreAllMocks();
  });

  function installWebSocket(behavior: 'open' | 'fail'): void {
    const WSCtor: any = vi.fn(function () {
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
      sockets.push(ws);
      setTimeout(() => {
        if (behavior === 'open') ws.onopen?.({ type: 'open' });
        else ws.onerror?.({ type: 'error' });
      }, 0);
      return ws;
    });
    WSCtor.OPEN = 1;
    WSCtor.CONNECTING = 0;
    WSCtor.CLOSED = 3;
    global.WebSocket = WSCtor;
  }

  /** First long-poll answers with `body`; later polls hang like a suspended request. */
  function installLongPolling(body: string): void {
    let polls = 0;
    global.fetch = vi.fn((url: string) => {
      if (String(url).includes('X-Atmosphere-Transport=close')) {
        return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(''), headers: new Headers() });
      }
      if (polls++ === 0) {
        return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(body), headers: new Headers() });
      }
      return new Promise(() => { /* suspended */ });
    }) as any;
  }

  it("is '0' until the handshake arrives, then the handshake uuid", async () => {
    installWebSocket('open');
    const sub = await new Atmosphere().subscribe({
      url: 'http://localhost/chat',
      transport: 'websocket',
      enableProtocol: true,
    });

    expect(sub.uuid).toBe('0');

    sockets[0].onmessage({ data: 'ws-uuid-1|0|X|' });

    // A getter, not a snapshot taken at subscribe() time.
    expect(sub.uuid).toBe('ws-uuid-1');
    await sub.close();
  });

  it("stays '0' without enableProtocol (no handshake is requested)", async () => {
    installLongPolling('hello');
    const sub = await new Atmosphere().subscribe({
      url: 'http://localhost/chat',
      transport: 'long-polling',
    });

    expect(sub.uuid).toBe('0');
    await sub.close();
  });

  it('is the long-polling handshake uuid', async () => {
    installLongPolling('lp-uuid-7|0|X|');
    const sub = await new Atmosphere().subscribe({
      url: 'http://localhost/chat',
      transport: 'long-polling',
      enableProtocol: true,
    });

    expect(sub.uuid).toBe('lp-uuid-7');
    // It is the id the client sends back on its next request.
    const urls = (global.fetch as any).mock.calls.map((c: unknown[]) => String(c[0]));
    expect(new URL(urls[urls.length - 1]).searchParams.get('X-Atmosphere-tracking-id')).toBe('lp-uuid-7');
    await sub.close();
  });

  it('is the fallback transport uuid when the fallback took over', async () => {
    installWebSocket('fail');
    installLongPolling('fallback-uuid-3|0|X|');

    const sub = await new Atmosphere().subscribe({
      url: 'http://localhost/chat',
      transport: 'websocket',
      fallbackTransport: 'long-polling',
      enableProtocol: true,
    });

    expect(sockets).toHaveLength(1);
    expect(sub.uuid).toBe('fallback-uuid-3');
    await sub.close();
  });
});
