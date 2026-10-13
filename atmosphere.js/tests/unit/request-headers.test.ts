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
import { LongPollingTransport } from '../../src/transports/long-polling';
import { StreamingTransport } from '../../src/transports/streaming';
import { SSETransport } from '../../src/transports/sse';
import { WebSocketTransport } from '../../src/transports/websocket';
import { BaseTransport } from '../../src/transports/base';
import { AtmosphereProtocol } from '../../src/utils/protocol';
import type { AtmosphereRequest } from '../../src/types';

/**
 * Issue #2789: `request.headers` (and the auth token) went only into the URL,
 * where they land in access and proxy logs, even on the transports that use
 * fetch, and header values could not be computed per request.
 *
 * jsdom's page origin is http://localhost:3000, so `/chat` is a same-origin
 * URL and `http://api.example.com/chat` a cross-origin one.
 */

const SAME_ORIGIN = '/chat';
const CROSS_ORIGIN = 'http://api.example.com/chat';

interface Call { url: string; headers: Record<string, string>; method: string }

function query(url: string, name: string): string | null {
  return new URL(url, 'http://localhost:3000').searchParams.get(name);
}

describe('request headers on fetch requests (#2789)', () => {
  let originalFetch: typeof global.fetch;
  let calls: Call[];

  beforeEach(() => {
    originalFetch = global.fetch;
    calls = [];
    let polls = 0;
    global.fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      calls.push({ url, headers: { ...(init?.headers as Record<string, string>) }, method });
      if (method === 'POST' || ++polls <= 2) {
        return Promise.resolve({ ok: true, status: 200, headers: new Headers(), text: () => Promise.resolve('') });
      }
      return new Promise((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' })));
      });
    });
  });

  afterEach(() => {
    global.fetch = originalFetch;
  });

  function longPolling(request: Partial<AtmosphereRequest>): LongPollingTransport<string> {
    return new LongPollingTransport<string>({ url: SAME_ORIGIN, transport: 'long-polling', ...request }, {});
  }

  it('same-origin: custom headers and the auth token are HTTP headers, not query parameters', async () => {
    const transport = longPolling({ headers: { 'X-Custom': 'value' }, authToken: 'secret-token' });
    await transport.connect();
    transport.send('hello');
    await vi.waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true));

    for (const call of calls) {
      expect(call.headers['X-Custom']).toBe('value');
      expect(call.headers['X-Atmosphere-Auth']).toBe('secret-token');
      expect(query(call.url, 'X-Custom')).toBeNull();
      expect(query(call.url, 'X-Atmosphere-Auth')).toBeNull();
      // The framework parameters stay in the URL.
      expect(query(call.url, 'X-Atmosphere-Transport')).toBe('long-polling');
    }
    await transport.disconnect();
  });

  it('a function-valued header is called for every request', async () => {
    let syncId = 0;
    const transport = longPolling({ headers: { 'X-Sync-Id': () => String(syncId++) } });
    await transport.connect();
    await vi.waitFor(() => expect(calls).toHaveLength(3));
    transport.send('hello');
    await vi.waitFor(() => expect(calls).toHaveLength(4));

    expect(calls.map((c) => c.headers['X-Sync-Id'])).toEqual(['0', '1', '2', '3']);
    await transport.disconnect();
  });

  it('a getter is re-read for every request', async () => {
    let syncId = 10;
    const headers = { get 'X-Sync-Id'() { return String(syncId++); } };
    const transport = longPolling({ headers });
    await transport.connect();
    await vi.waitFor(() => expect(calls).toHaveLength(3));

    expect(calls.map((c) => c.headers['X-Sync-Id'])).toEqual(['10', '11', '12']);
    await transport.disconnect();
  });

  it('cross-origin: they stay query parameters, still evaluated per request', async () => {
    let syncId = 0;
    const transport = longPolling({
      url: CROSS_ORIGIN, headers: { 'X-Sync-Id': () => String(syncId++) }, authToken: 'secret-token',
    });
    await transport.connect();
    await vi.waitFor(() => expect(calls).toHaveLength(3));

    expect(calls.map((c) => query(c.url, 'X-Sync-Id'))).toEqual(['0', '1', '2']);
    for (const call of calls) {
      expect(call.headers['X-Sync-Id']).toBeUndefined();
      expect(call.headers['X-Atmosphere-Auth']).toBeUndefined();
      expect(query(call.url, 'X-Atmosphere-Auth')).toBe('secret-token');
    }
    await transport.disconnect();
  });

  it("headerDelivery 'headers' sends them cross-origin", async () => {
    const opted = longPolling({ url: CROSS_ORIGIN, headerDelivery: 'headers', headers: { 'X-Custom': 'value' } });
    await opted.connect();
    expect(calls[0].headers['X-Custom']).toBe('value');
    expect(query(calls[0].url, 'X-Custom')).toBeNull();
    await opted.disconnect();
  });

  it("headerDelivery 'query' keeps them in the URL same-origin", async () => {
    const legacy = longPolling({ headerDelivery: 'query', headers: { 'X-Custom': 'value' } });
    await legacy.connect();
    expect(calls[0].headers['X-Custom']).toBeUndefined();
    expect(query(calls[0].url, 'X-Custom')).toBe('value');
    await legacy.disconnect();
  });

  it('a header fetch cannot carry stays a query parameter', async () => {
    const transport = longPolling({
      headers: { 'X-Line': 'a\nb', 'X-Wide': '日本', Cookie: 'c=1', 'Content-Type': 'text/x-custom', 'X-Ok': 'ok' },
    });
    await transport.connect();

    const [poll] = calls;
    expect(poll.headers['X-Ok']).toBe('ok');
    expect(query(poll.url, 'X-Ok')).toBeNull();
    expect(query(poll.url, 'X-Line')).toBe('a\nb');
    expect(query(poll.url, 'X-Wide')).toBe('日本');
    expect(query(poll.url, 'Cookie')).toBe('c=1');
    expect(query(poll.url, 'Content-Type')).toBe('text/x-custom');
    expect(Object.keys(poll.headers).sort()).toEqual(['Content-Type', 'X-Ok']);
    expect(poll.headers['Content-Type']).toBe('text/plain');
    await transport.disconnect();
  });

  it('streaming connects and sends with the headers as HTTP headers', async () => {
    global.fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      calls.push({ url, headers: { ...(init?.headers as Record<string, string>) }, method: init?.method ?? 'GET' });
      return Promise.resolve({
        ok: true, status: 200, headers: new Headers(), text: () => Promise.resolve(''),
        body: new ReadableStream<Uint8Array>({
          start(controller) {
            init?.signal?.addEventListener('abort', () => controller.error(Object.assign(new Error('aborted'), { name: 'AbortError' })));
          },
        }),
      });
    });
    const transport = new StreamingTransport<string>(
      { url: SAME_ORIGIN, transport: 'streaming', headers: { 'X-Custom': () => 'value' } }, {});
    await transport.connect();
    transport.send('hello');
    await vi.waitFor(() => expect(calls).toHaveLength(2));

    for (const call of calls) {
      expect(call.headers['X-Custom']).toBe('value');
      expect(query(call.url, 'X-Custom')).toBeNull();
    }
    await transport.disconnect();
  });

  it('SSE: the EventSource URL carries them (it cannot set headers), its POSTs send them as headers', async () => {
    const urls: string[] = [];
    const originalEventSource = global.EventSource;
    const source = { close: vi.fn(), onopen: null as (() => void) | null, onmessage: null, onerror: null };
    global.EventSource = function (url: string) {
      urls.push(url);
      setTimeout(() => source.onopen?.(), 0);
      return source;
    } as unknown as typeof EventSource;
    try {
      const transport = new SSETransport<string>(
        { url: SAME_ORIGIN, transport: 'sse', headers: { 'X-Custom': () => 'value' } }, {});
      await transport.connect();
      transport.send('hello');
      await vi.waitFor(() => expect(calls).toHaveLength(1));

      expect(query(urls[0], 'X-Custom')).toBe('value');
      expect(calls[0].headers['X-Custom']).toBe('value');
      expect(query(calls[0].url, 'X-Custom')).toBeNull();
      await transport.disconnect();
    } finally {
      global.EventSource = originalEventSource;
    }
  });
});

describe('request headers on WebSocket (#2789)', () => {
  it('stay query parameters, with function values resolved', async () => {
    const urls: string[] = [];
    const originalWebSocket = global.WebSocket;
    const ws = { close: vi.fn(), send: vi.fn(), readyState: 1, onopen: null as (() => void) | null, onmessage: null, onerror: null, onclose: null, binaryType: '' };
    global.WebSocket = Object.assign(function (url: string) {
      urls.push(url);
      setTimeout(() => ws.onopen?.(), 0);
      return ws;
    }, { CONNECTING: 0, OPEN: 1, CLOSING: 2, CLOSED: 3 }) as unknown as typeof WebSocket;
    try {
      const transport = new WebSocketTransport<string>(
        { url: SAME_ORIGIN, transport: 'websocket', headers: { 'X-Custom': () => 'value' }, authToken: 'secret-token' }, {});
      await transport.connect();

      expect(query(urls[0], 'X-Custom')).toBe('value');
      expect(query(urls[0], 'X-Atmosphere-Auth')).toBe('secret-token');
      await transport.disconnect();
    } finally {
      global.WebSocket = originalWebSocket;
    }
  });
});

describe('request headers on the close message (#2789)', () => {
  it('stay query parameters, re-evaluated, even same-origin: sendBeacon cannot set headers', () => {
    let syncId = 0;
    const protocol = new AtmosphereProtocol();
    protocol.uuid = 'server-uuid-1';
    const request: AtmosphereRequest = {
      url: SAME_ORIGIN, transport: 'long-polling', headers: { 'X-Sync-Id': () => String(syncId++) }, authToken: 'secret-token',
    };
    const first = protocol.buildCloseUrl(request);
    const second = protocol.buildCloseUrl(request);

    expect(query(first, 'X-Sync-Id')).toBe('0');
    expect(query(second, 'X-Sync-Id')).toBe('1');
    expect(query(first, 'X-Atmosphere-Auth')).toBe('secret-token');
    expect(query(first, 'X-Atmosphere-Transport')).toBe('close');
  });
});

describe('BaseTransport.isSameOrigin', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('compares the request origin with the page origin', () => {
    expect(BaseTransport.isSameOrigin('/chat')).toBe(true);
    expect(BaseTransport.isSameOrigin('http://localhost:3000/chat')).toBe(true);
    expect(BaseTransport.isSameOrigin('http://localhost/chat')).toBe(false);
    expect(BaseTransport.isSameOrigin('https://api.example.com/chat')).toBe(false);
  });

  it('treats a context without a page origin (React Native) as same-origin, an opaque one as cross-origin', () => {
    vi.stubGlobal('location', undefined);
    expect(BaseTransport.isSameOrigin('https://api.example.com/chat')).toBe(true);
    vi.stubGlobal('location', { origin: 'null', href: 'about:blank' });
    expect(BaseTransport.isSameOrigin('https://api.example.com/chat')).toBe(false);
  });
});
