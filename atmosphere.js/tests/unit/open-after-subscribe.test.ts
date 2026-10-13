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
import { LongPollingTransport } from '../../src/transports/long-polling';
import { OfflineQueue } from '../../src/queue/offline-queue';
import type { Subscription } from '../../src/types';

/**
 * Issue #2784: every transport could fire `open` from inside `connect()`, i.e.
 * before `subscribe()` resolved, so an `open` handler could not push on the
 * subscription it was about to receive. Long-polling also fired `open` on the
 * first response's headers, before the protocol handshake in its body was
 * parsed, so the tracking id was still 0.
 */

function trackingId(url: string): string | null {
  return new URL(url, 'http://localhost').searchParams.get('X-Atmosphere-tracking-id');
}

function okResponse(body: string): Response {
  return { ok: true, status: 200, headers: new Headers(), text: () => Promise.resolve(body) } as unknown as Response;
}

/** A poll the server holds open; it rejects when the client aborts it. */
function heldPoll(init?: RequestInit): Promise<Response> {
  return new Promise((_resolve, reject) => {
    init?.signal?.addEventListener('abort', () => {
      const error = new Error('aborted');
      error.name = 'AbortError';
      reject(error);
    });
  });
}

describe('open fires after subscribe() resolved (#2784)', () => {
  let originalFetch: typeof global.fetch;
  let polls: string[];
  let posts: string[];

  beforeEach(() => {
    originalFetch = global.fetch;
    polls = [];
    posts = [];
  });

  afterEach(() => {
    global.fetch = originalFetch;
  });

  /** First poll answers `firstBody`; later polls are held open; POSTs answer 200 (400 under id 0). */
  function mockLongPolling(firstBody: string): void {
    global.fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        posts.push(url);
        return Promise.resolve(trackingId(url) === '0'
          ? { ok: false, status: 400, headers: new Headers(), text: () => Promise.resolve('') }
          : okResponse(''));
      }
      polls.push(url);
      return polls.length === 1 ? Promise.resolve(okResponse(firstBody)) : heldPoll(init);
    });
  }

  it('long-polling with the protocol: an open handler pushes on the resolved subscription, under the handshake id', async () => {
    mockLongPolling('server-uuid-1|0|X|');
    const error = vi.fn();
    const openFailures: unknown[] = [];
    let resolved = false;
    let resolvedAtOpen: boolean | null = null;

    const sub: Subscription = await new Atmosphere().subscribe(
      { url: 'http://localhost/chat', transport: 'long-polling', enableProtocol: true },
      {
        open: () => {
          resolvedAtOpen = resolved;
          try {
            sub.push('hello');
          } catch (e) {
            openFailures.push(e);
          }
        },
        error,
      },
    );
    resolved = true;

    await vi.waitFor(() => expect(posts).toHaveLength(1));
    expect(resolvedAtOpen).toBe(true);
    expect(openFailures).toEqual([]);
    expect(posts.map(trackingId)).toEqual(['server-uuid-1']);
    expect(error).not.toHaveBeenCalled();
    // The poll after a handshake-only first response goes out at once, under the new id.
    expect(polls.map(trackingId)).toEqual(['0', 'server-uuid-1']);
    await sub.close();
  });

  it('long-polling with the protocol: the transport opens, and drains the offline queue, under the handshake id', async () => {
    // The drain runs inside the transport as it opens, so this holds even
    // without subscribe() holding the open event back.
    mockLongPolling('server-uuid-1|0|X|');
    const queue = new OfflineQueue<string | object | ArrayBuffer>();
    queue.enqueue('queued prompt');
    let idAtOpen: string | null = null;
    const transport = new LongPollingTransport<string>(
      { url: 'http://localhost/chat', transport: 'long-polling', enableProtocol: true },
      { open: () => { idAtOpen = transport.uuid; } },
    );
    transport.setOfflineQueue(queue);
    await transport.connect();

    expect(idAtOpen).toBe('server-uuid-1');
    await vi.waitFor(() => expect(posts).toHaveLength(1));
    expect(posts.map(trackingId)).toEqual(['server-uuid-1']);
    expect(queue.size).toBe(0);
    await transport.disconnect();
  });

  it('long-polling with the protocol: open precedes a message trailing the handshake', async () => {
    mockLongPolling('server-uuid-1|0|X|hello');
    const order: string[] = [];
    const sub = await new Atmosphere().subscribe(
      { url: 'http://localhost/chat', transport: 'long-polling', enableProtocol: true },
      {
        open: () => order.push('open'),
        message: (response) => order.push(`message:${String(response.responseBody)}`),
      },
    );
    expect(order).toEqual([]);
    await vi.waitFor(() => expect(order).toEqual(['open', 'message:hello']));
    await sub.close();
  });

  it('long-polling without the protocol still opens, after subscribe() resolved and before its message', async () => {
    mockLongPolling('hello');
    const order: string[] = [];
    const sub = await new Atmosphere().subscribe(
      { url: 'http://localhost/chat', transport: 'long-polling' },
      {
        open: () => order.push('open'),
        message: (response) => order.push(`message:${String(response.responseBody)}`),
      },
    );
    expect(order).toEqual([]);
    expect(sub.state).toBe('connected');
    await vi.waitFor(() => expect(order).toEqual(['open', 'message:hello']));
    await sub.close();
  });

  it('long-polling reconnecting under its id reopens on the first response: no handshake comes', async () => {
    global.fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      polls.push(url);
      switch (polls.length) {
        case 1: return Promise.resolve(okResponse('server-uuid-1|0|X|'));
        case 2: return Promise.resolve({ ok: false, status: 502, headers: new Headers(), text: () => Promise.resolve('') });
        case 3: return Promise.resolve(okResponse('')); // the reconnect poll: no handshake
        default: return heldPoll(init);
      }
    });
    const open = vi.fn();
    const reopen = vi.fn();
    const sub = await new Atmosphere().subscribe(
      { url: 'http://localhost/chat', transport: 'long-polling', enableProtocol: true, reconnect: true, reconnectInterval: 1 },
      { open, reopen },
    );
    await vi.waitFor(() => expect(reopen).toHaveBeenCalledTimes(1));
    expect(open).toHaveBeenCalledTimes(1);
    expect(polls.slice(0, 3).map(trackingId)).toEqual(['0', 'server-uuid-1', 'server-uuid-1']);
    expect(sub.state).toBe('connected');
    await sub.close();
  });

  it('long-polling: a subscription closed right after subscribe() resolved gets no late open', async () => {
    mockLongPolling('hello');
    const open = vi.fn();
    const sub = await new Atmosphere().subscribe(
      { url: 'http://localhost/chat', transport: 'long-polling' }, { open });
    await sub.close();
    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(open).not.toHaveBeenCalled();
  });

  it('a failed subscribe() has reported its error by the time it rejects', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: false, status: 500, headers: new Headers(), text: () => Promise.resolve('') });
    const error = vi.fn();
    await expect(new Atmosphere().subscribe(
      { url: 'http://localhost/chat', transport: 'long-polling', reconnect: false }, { error },
    )).rejects.toThrow();
    expect(error).toHaveBeenCalledTimes(1);
  });

  it('websocket without the protocol: an open handler pushes on the resolved subscription', async () => {
    const sent: unknown[] = [];
    const ws = {
      send: (data: unknown) => sent.push(data), close: vi.fn(), readyState: WebSocket.OPEN,
      onopen: null as ((ev: unknown) => void) | null, onmessage: null, onerror: null, onclose: null,
      binaryType: 'arraybuffer',
    };
    const originalWebSocket = global.WebSocket;
    global.WebSocket = Object.assign(function () {
      setTimeout(() => ws.onopen?.({ type: 'open' }), 0);
      return ws;
    }, { CONNECTING: 0, OPEN: 1, CLOSING: 2, CLOSED: 3 }) as unknown as typeof WebSocket;
    try {
      const openFailures: unknown[] = [];
      const sub: Subscription = await new Atmosphere().subscribe(
        { url: 'ws://localhost/chat', transport: 'websocket' },
        { open: () => { try { sub.push('hello'); } catch (e) { openFailures.push(e); } } },
      );
      await vi.waitFor(() => expect(sent).toEqual(['hello']));
      expect(openFailures).toEqual([]);
      await sub.close();
    } finally {
      global.WebSocket = originalWebSocket;
    }
  });

  it('streaming without the protocol: open fires after subscribe() resolved', async () => {
    global.fetch = vi.fn().mockImplementation((_url: string, init?: RequestInit) => Promise.resolve({
      ok: true, status: 200, headers: new Headers(),
      body: new ReadableStream<Uint8Array>({
        start(controller) {
          init?.signal?.addEventListener('abort', () => controller.error(Object.assign(new Error('aborted'), { name: 'AbortError' })));
        },
      }),
    }));
    let resolved = false;
    let resolvedAtOpen: boolean | null = null;
    const sub = await new Atmosphere().subscribe(
      { url: 'http://localhost/chat', transport: 'streaming' },
      { open: () => { resolvedAtOpen = resolved; } },
    );
    resolved = true;
    await vi.waitFor(() => expect(resolvedAtOpen).toBe(true));
    await sub.close();
  });
});
