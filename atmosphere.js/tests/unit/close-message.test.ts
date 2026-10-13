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
import type { AtmosphereRequest, Subscription } from '../../src/types';

/**
 * Subscription.close() and `pagehide` send Atmosphere's close message
 * (X-Atmosphere-Transport=close + X-Atmosphere-tracking-id) for the HTTP
 * transports, so the server's OnDisconnectInterceptor releases the
 * AtmosphereResource the aborted request leaves behind (issue #2783).
 */

const CLOSE = 'X-Atmosphere-Transport=close';

function params(url: string): URLSearchParams {
  return new URL(url, 'http://localhost').searchParams;
}

function ok(body = ''): unknown {
  return { ok: true, status: 200, text: () => Promise.resolve(body), headers: new Headers() };
}

describe('Atmosphere close message (#2783)', () => {
  let originalFetch: typeof global.fetch;
  let originalWebSocket: typeof global.WebSocket;
  let originalEventSource: typeof global.EventSource;
  let fetchMock: ReturnType<typeof vi.fn>;
  let beacon: ReturnType<typeof vi.fn>;
  let subs: Subscription[];

  beforeEach(() => {
    originalFetch = global.fetch;
    originalWebSocket = global.WebSocket;
    originalEventSource = global.EventSource;
    subs = [];
    beacon = vi.fn(() => true);
    Object.defineProperty(navigator, 'sendBeacon', { value: beacon, configurable: true, writable: true });
  });

  afterEach(async () => {
    for (const sub of subs) {
      await sub.close();
    }
    global.fetch = originalFetch;
    global.WebSocket = originalWebSocket;
    global.EventSource = originalEventSource;
    delete (navigator as unknown as Record<string, unknown>).sendBeacon;
    vi.restoreAllMocks();
  });

  /** fetch calls that carry the close message. */
  function closeCalls(): Array<[string, RequestInit]> {
    return fetchMock.mock.calls
      .filter((c: unknown[]) => String(c[0]).includes(CLOSE))
      .map((c: unknown[]) => [String(c[0]), c[1] as RequestInit]);
  }

  /** beacon calls that carry the close message. */
  function beaconCalls(): string[] {
    return beacon.mock.calls.map((c: unknown[]) => String(c[0])).filter((u: string) => u.includes(CLOSE));
  }

  /**
   * Long-polling server: the first poll answers `firstBody`, later polls hang
   * like a suspended request, the close message answers 200.
   */
  function installLongPolling(firstBody: string): void {
    let polls = 0;
    fetchMock = vi.fn((url: string) => {
      if (String(url).includes(CLOSE)) return Promise.resolve(ok());
      if (polls++ === 0) return Promise.resolve(ok(firstBody));
      return new Promise(() => { /* suspended */ });
    });
    global.fetch = fetchMock as unknown as typeof fetch;
  }

  async function subscribe(request: Partial<AtmosphereRequest>): Promise<Subscription> {
    const sub = await new Atmosphere().subscribe({ url: 'http://localhost/chat', ...request } as AtmosphereRequest);
    subs.push(sub);
    return sub;
  }

  async function longPolling(extra: Partial<AtmosphereRequest> = {}): Promise<Subscription> {
    installLongPolling('lp-uuid-1|0|X|');
    const sub = await subscribe({ transport: 'long-polling', enableProtocol: true, ...extra });
    expect(sub.uuid).toBe('lp-uuid-1');
    return sub;
  }

  function pagehide(): void {
    window.dispatchEvent(new Event('pagehide'));
  }

  describe('close()', () => {
    it('sends exactly one close message with the tracking id over long-polling', async () => {
      const sub = await longPolling();

      await sub.close();
      await sub.close();

      const calls = closeCalls();
      expect(calls).toHaveLength(1);
      const [url, init] = calls[0];
      expect(params(url).get('X-Atmosphere-Transport')).toBe('close');
      expect(params(url).get('X-Atmosphere-tracking-id')).toBe('lp-uuid-1');
      // The close message must not ask for a new handshake.
      expect(params(url).has('X-atmo-protocol')).toBe(false);
      expect(init).toMatchObject({ method: 'POST', keepalive: true, credentials: 'same-origin' });
      expect(beaconCalls()).toHaveLength(0);
    });

    it('carries the request URL parameters, custom headers and credentials', async () => {
      const sub = await longPolling({
        url: 'http://localhost/chat?room=lobby',
        headers: { 'X-Custom-Auth': 'secret token' },
        withCredentials: true,
      });

      await sub.close();

      const [url, init] = closeCalls()[0];
      expect(url.startsWith('http://localhost/chat?room=lobby&')).toBe(true);
      expect(params(url).get('X-Custom-Auth')).toBe('secret token');
      expect(init.credentials).toBe('include');
    });

    it('sends one close message over streaming', async () => {
      const encoder = new TextEncoder();
      let controller!: ReadableStreamDefaultController<Uint8Array>;
      const stream = new ReadableStream<Uint8Array>({ start(c) { controller = c; } });
      fetchMock = vi.fn((url: string) => {
        if (String(url).includes(CLOSE)) return Promise.resolve(ok());
        return Promise.resolve({ ok: true, status: 200, body: stream, headers: new Headers() });
      });
      global.fetch = fetchMock as unknown as typeof fetch;

      const sub = await subscribe({ transport: 'streaming', enableProtocol: true });
      controller.enqueue(encoder.encode('st-uuid-2|0|X|'));
      await vi.waitFor(() => expect(sub.uuid).toBe('st-uuid-2'));

      await sub.close();
      await sub.close();

      const calls = closeCalls();
      expect(calls).toHaveLength(1);
      expect(params(calls[0][0]).get('X-Atmosphere-tracking-id')).toBe('st-uuid-2');
    });

    it('sends one close message over SSE', async () => {
      const es: any = { close: vi.fn(), onopen: null, onmessage: null, onerror: null };
      global.EventSource = vi.fn(function () {
        setTimeout(() => es.onopen?.({}), 0);
        return es;
      }) as unknown as typeof EventSource;
      fetchMock = vi.fn(() => Promise.resolve(ok()));
      global.fetch = fetchMock as unknown as typeof fetch;

      const sub = await subscribe({ transport: 'sse', enableProtocol: true });
      es.onmessage({ data: 'sse-uuid-3|0|X|' });
      expect(sub.uuid).toBe('sse-uuid-3');

      await sub.close();
      await sub.close();

      const calls = closeCalls();
      expect(calls).toHaveLength(1);
      expect(params(calls[0][0]).get('X-Atmosphere-tracking-id')).toBe('sse-uuid-3');
    });

    it("sends nothing while the tracking id is '0'", async () => {
      installLongPolling('hello');
      const sub = await subscribe({ transport: 'long-polling' });
      expect(sub.uuid).toBe('0');

      pagehide();
      await sub.close();

      expect(closeCalls()).toHaveLength(0);
      expect(beaconCalls()).toHaveLength(0);
    });

    it('sends no HTTP request for WebSocket and registers no pagehide listener', async () => {
      const addSpy = vi.spyOn(window, 'addEventListener');
      const ws: any = { send: vi.fn(), close: vi.fn(), readyState: 1, onopen: null, onmessage: null, onerror: null, onclose: null };
      const WSCtor: any = vi.fn(function () {
        setTimeout(() => ws.onopen?.({}), 0);
        return ws;
      });
      WSCtor.OPEN = 1;
      WSCtor.CONNECTING = 0;
      global.WebSocket = WSCtor;
      fetchMock = vi.fn(() => Promise.resolve(ok()));
      global.fetch = fetchMock as unknown as typeof fetch;

      const sub = await subscribe({ transport: 'websocket', enableProtocol: true });
      ws.onmessage({ data: 'ws-uuid-4|0|X|' });
      expect(sub.uuid).toBe('ws-uuid-4');

      pagehide();
      await sub.close();

      expect(fetchMock).not.toHaveBeenCalled();
      expect(beacon).not.toHaveBeenCalled();
      expect(addSpy.mock.calls.filter((c) => c[0] === 'pagehide')).toHaveLength(0);
      expect(ws.close).toHaveBeenCalled();
    });

    it('never throws when the close message cannot be sent', async () => {
      const sub = await longPolling();
      fetchMock.mockImplementation(() => Promise.reject(new TypeError('Failed to fetch')));
      await expect(sub.close()).resolves.toBeUndefined();

      const sub2 = await longPolling();
      fetchMock.mockImplementation(() => { throw new TypeError('fetch blew up'); });
      await expect(sub2.close()).resolves.toBeUndefined();
      expect(sub2.state).toBe('disconnected');
    });
  });

  describe('pagehide', () => {
    it('sends the close message as a beacon', async () => {
      const sub = await longPolling();

      pagehide();

      const urls = beaconCalls();
      expect(urls).toHaveLength(1);
      expect(params(urls[0]).get('X-Atmosphere-tracking-id')).toBe('lp-uuid-1');
      expect(closeCalls()).toHaveLength(0);
      expect(sub.state).toBe('connected');
    });

    it('falls back to a keepalive fetch when sendBeacon refuses the request', async () => {
      await longPolling();
      beacon.mockReturnValue(false);

      pagehide();

      expect(beacon).toHaveBeenCalledTimes(1);
      const calls = closeCalls();
      expect(calls).toHaveLength(1);
      expect(calls[0][1]).toMatchObject({ method: 'POST', keepalive: true });
    });

    it('does not throw from the listener when sendBeacon throws', async () => {
      const addSpy = vi.spyOn(window, 'addEventListener');
      await longPolling();
      beacon.mockImplementation(() => { throw new TypeError('beacon blew up'); });
      fetchMock.mockImplementation(() => Promise.reject(new TypeError('Failed to fetch')));

      const registration = addSpy.mock.calls.find((c) => c[0] === 'pagehide');
      expect(registration).toBeDefined();
      const listener = registration![1] as () => void;
      expect(() => listener()).not.toThrow();
    });

    it('is removed by close()', async () => {
      const addSpy = vi.spyOn(window, 'addEventListener');
      const removeSpy = vi.spyOn(window, 'removeEventListener');
      const sub = await longPolling();

      await sub.close();
      pagehide();

      expect(beaconCalls()).toHaveLength(0);
      const added = addSpy.mock.calls.filter((c) => c[0] === 'pagehide').map((c) => c[1]);
      const removed = removeSpy.mock.calls.filter((c) => c[0] === 'pagehide').map((c) => c[1]);
      expect(added).toHaveLength(1);
      expect(removed).toEqual(added);
    });

    it('is not registered without a DOM event target (React Native, Node)', async () => {
      const descriptor = Object.getOwnPropertyDescriptor(window, 'addEventListener');
      Object.defineProperty(window, 'addEventListener', { value: undefined, configurable: true, writable: true });
      try {
        const sub = await longPolling();
        await expect(sub.close()).resolves.toBeUndefined();
        // close() still tells the server.
        expect(closeCalls()).toHaveLength(1);
      } finally {
        if (descriptor) Object.defineProperty(window, 'addEventListener', descriptor);
        else delete (window as unknown as Record<string, unknown>).addEventListener;
      }
      expect(typeof window.addEventListener).toBe('function');
    });
  });
});
