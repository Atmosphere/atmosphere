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
import type { AtmosphereRequest, Subscription, TransportType } from '../../src/types';
import { clearRunId, loadRunId, runIdStorageKey, saveRunId } from '../../src/utils/run-id-store';

/**
 * A page reloaded while an AI answer streams reattaches to its run: the run id
 * the server surfaces (`X-Atmosphere-Run-Id` metadata frame) is kept in the
 * tab's sessionStorage until the run's terminal frame or a deliberate close(),
 * and a subscription created after the reload connects with it. Over every
 * transport (mode parity): WebSocket, SSE, long-polling and streaming.
 */

const URL_ = 'http://localhost/atmosphere/agent/chat';
const RUN_ID_FRAME = (id: string) =>
  JSON.stringify({ type: 'metadata', key: 'X-Atmosphere-Run-Id', value: id, sessionId: 's1', seq: 1 });
const TERMINAL = {
  complete: '{"type":"complete","sessionId":"s1","seq":9}',
  error: '{"type":"error","data":"boom","sessionId":"s1","seq":9}',
  cancelled: '{"type":"cancelled","data":"disconnect","sessionId":"s1","seq":9}',
} as const;

function runIdParam(url: string): string | null {
  return new URL(url, 'http://localhost').searchParams.get('X-Atmosphere-Run-Id');
}

function ok(body = ''): unknown {
  return { ok: true, status: 200, text: () => Promise.resolve(body), headers: new Headers() };
}

/** A fake server for one transport: the URLs the client connected with, and a way to send it a body. */
interface FakeServer {
  connectUrls: string[];
  deliver(body: string): Promise<void>;
}

/**
 * Install a fake of `transport` on the globals, answering the handshake with
 * `uuid`. Returns what the client sent and a way to push a body to it.
 */
function install(transport: TransportType, uuid: string): FakeServer {
  const connectUrls: string[] = [];
  const handshake = `${uuid}|0|X|`;
  switch (transport) {
    case 'websocket': {
      let ws: any = null;
      const WSCtor: any = vi.fn(function (url: string) {
        connectUrls.push(url);
        ws = { send: vi.fn(), close: vi.fn(), readyState: 1, onopen: null, onmessage: null, onerror: null, onclose: null };
        setTimeout(() => {
          ws.onopen?.({});
          ws.onmessage?.({ data: handshake });
        }, 0);
        return ws;
      });
      WSCtor.OPEN = 1;
      WSCtor.CONNECTING = 0;
      global.WebSocket = WSCtor;
      global.fetch = vi.fn(() => Promise.resolve(ok())) as unknown as typeof fetch;
      return { connectUrls, deliver: async (body) => { ws.onmessage({ data: body }); } };
    }
    case 'sse': {
      let es: any = null;
      global.EventSource = vi.fn(function (url: string) {
        connectUrls.push(url);
        es = { close: vi.fn(), onopen: null, onmessage: null, onerror: null };
        setTimeout(() => {
          es.onopen?.({});
          es.onmessage?.({ data: handshake });
        }, 0);
        return es;
      }) as unknown as typeof EventSource;
      global.fetch = vi.fn(() => Promise.resolve(ok())) as unknown as typeof fetch;
      return { connectUrls, deliver: async (body) => { es.onmessage({ data: body }); } };
    }
    case 'long-polling': {
      const waiting: Array<(body: string) => void> = [];
      let polls = 0;
      global.fetch = vi.fn((url: string, init?: RequestInit) => {
        if (String(url).includes('X-Atmosphere-Transport=close') || init?.method === 'POST') {
          return Promise.resolve(ok());
        }
        if (polls++ === 0) {
          connectUrls.push(String(url));
          return Promise.resolve(ok(handshake));
        }
        return new Promise((resolve) => waiting.push((body) => resolve(ok(body))));
      }) as unknown as typeof fetch;
      return {
        connectUrls,
        deliver: async (body) => {
          await vi.waitFor(() => expect(waiting.length).toBeGreaterThan(0));
          waiting.shift()!(body);
          await new Promise((r) => setTimeout(r, 0));
        },
      };
    }
    case 'streaming': {
      const encoder = new TextEncoder();
      let controller!: ReadableStreamDefaultController<Uint8Array>;
      global.fetch = vi.fn((url: string, init?: RequestInit) => {
        if (String(url).includes('X-Atmosphere-Transport=close') || init?.method === 'POST') {
          return Promise.resolve(ok());
        }
        connectUrls.push(String(url));
        const stream = new ReadableStream<Uint8Array>({ start(c) { controller = c; } });
        setTimeout(() => controller.enqueue(encoder.encode(handshake)), 0);
        return Promise.resolve({ ok: true, status: 200, body: stream, headers: new Headers() });
      }) as unknown as typeof fetch;
      return {
        connectUrls,
        deliver: async (body) => {
          controller.enqueue(encoder.encode(body));
          await new Promise((r) => setTimeout(r, 0));
        },
      };
    }
    default:
      throw new Error(`no fake for ${transport}`);
  }
}

describe('run id persistence across a reload', () => {
  let originalFetch: typeof global.fetch;
  let originalWebSocket: typeof global.WebSocket;
  let originalEventSource: typeof global.EventSource;
  let subs: Subscription[];

  beforeEach(() => {
    originalFetch = global.fetch;
    originalWebSocket = global.WebSocket;
    originalEventSource = global.EventSource;
    subs = [];
    sessionStorage.clear();
    Object.defineProperty(navigator, 'sendBeacon', { value: vi.fn(() => true), configurable: true, writable: true });
  });

  afterEach(async () => {
    for (const sub of subs) {
      await sub.close();
    }
    global.fetch = originalFetch;
    global.WebSocket = originalWebSocket;
    global.EventSource = originalEventSource;
    delete (navigator as unknown as Record<string, unknown>).sendBeacon;
    sessionStorage.clear();
    vi.restoreAllMocks();
  });

  /** Subscribe as a freshly loaded page would: a new Atmosphere client. */
  async function load(transport: TransportType, extra: Partial<AtmosphereRequest> = {}): Promise<Subscription> {
    const sub = await new Atmosphere().subscribe({
      url: `${URL_}?X-Atmosphere-Auth=secret`, transport, enableProtocol: true, reconnect: false, ...extra,
    } as AtmosphereRequest);
    subs.push(sub);
    await vi.waitFor(() => expect(sub.uuid).not.toBe('0'));
    return sub;
  }

  const TRANSPORTS: TransportType[] = ['websocket', 'sse', 'long-polling', 'streaming'];

  for (const transport of TRANSPORTS) {
    describe(transport, () => {
      it('keeps the run id for the tab and a reloaded page connects with it', async () => {
        const first = install(transport, 'uuid-1');
        await load(transport);
        expect(runIdParam(first.connectUrls[0])).toBeNull();

        await first.deliver(RUN_ID_FRAME('run-41'));
        expect(loadRunId(URL_)).toBe('run-41');

        // The page reloads: nothing is closed, a new client subscribes.
        const second = install(transport, 'uuid-2');
        await load(transport);
        expect(runIdParam(second.connectUrls[0])).toBe('run-41');

        // The replay ends with the terminal frame: the run is over, the id goes.
        await second.deliver(TERMINAL.complete);
        expect(loadRunId(URL_)).toBeNull();

        const third = install(transport, 'uuid-3');
        await load(transport);
        expect(runIdParam(third.connectUrls[0])).toBeNull();
      });

      it('forgets the run id when the subscription is closed on purpose', async () => {
        const first = install(transport, 'uuid-1');
        const sub = await load(transport);
        await first.deliver(RUN_ID_FRAME('run-42'));
        expect(loadRunId(URL_)).toBe('run-42');

        await sub.close();
        expect(loadRunId(URL_)).toBeNull();

        const second = install(transport, 'uuid-2');
        await load(transport);
        expect(runIdParam(second.connectUrls[0])).toBeNull();
      });
    });
  }

  for (const [type, frame] of Object.entries(TERMINAL)) {
    it(`clears the stored run id on a ${type} frame`, async () => {
      const server = install('websocket', 'uuid-1');
      await load('websocket');
      await server.deliver(RUN_ID_FRAME('run-43'));
      expect(loadRunId(URL_)).toBe('run-43');
      await server.deliver(frame);
      expect(loadRunId(URL_)).toBeNull();
    });
  }

  it('reads a run id and its terminal frame out of one multi-frame long-polling reply', async () => {
    const server = install('long-polling', 'uuid-1');
    await load('long-polling');
    await server.deliver(`${RUN_ID_FRAME('run-44')}\n{"type":"streaming-text","data":"hi","seq":2}`);
    expect(loadRunId(URL_)).toBe('run-44');
    await server.deliver(`{"type":"streaming-text","data":"there","seq":3}\n${TERMINAL.complete}`);
    expect(loadRunId(URL_)).toBeNull();
  });

  it('sends an explicit request.runId instead of the stored one', async () => {
    saveRunId(URL_, 'stored-run');
    const server = install('websocket', 'uuid-1');
    await load('websocket', { runId: 'explicit-run' });
    expect(runIdParam(server.connectUrls[0])).toBe('explicit-run');
  });

  it('keeps one entry per endpoint, apart from the query string', async () => {
    saveRunId('http://localhost/atmosphere/agent/other', 'other-run');
    const server = install('websocket', 'uuid-1');
    await load('websocket');
    expect(runIdParam(server.connectUrls[0])).toBeNull();
    await server.deliver(RUN_ID_FRAME('run-45'));
    expect(loadRunId(`${URL_}?another=query`)).toBe('run-45');
    expect(loadRunId('http://localhost/atmosphere/agent/other')).toBe('other-run');
  });

  it('keeps the auth token out of the storage key', () => {
    expect(runIdStorageKey(`${URL_}?X-Atmosphere-Auth=secret#frag`))
      .toBe('atmosphere.js:run-id:http://localhost/atmosphere/agent/chat');
    expect(runIdStorageKey('/atmosphere/agent/chat?X-Atmosphere-Auth=secret'))
      .toBe(`atmosphere.js:run-id:${location.origin}/atmosphere/agent/chat`);
  });

  describe('when storage fails', () => {
    it('keeps the run id in memory and never throws when sessionStorage throws', async () => {
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('quota', 'QuotaExceededError'); });
      vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new DOMException('denied', 'SecurityError'); });
      vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => { throw new DOMException('denied', 'SecurityError'); });

      const server = install('websocket', 'uuid-1');
      const sub = await load('websocket');
      await server.deliver(RUN_ID_FRAME('run-46'));
      expect(sub.state).toBe('connected');
      expect(loadRunId(URL_)).toBeNull();
      expect(() => clearRunId(URL_)).not.toThrow();
      await server.deliver(TERMINAL.complete);
      await expect(sub.close()).resolves.toBeUndefined();
    });

    it('works without sessionStorage at all', () => {
      const descriptor = Object.getOwnPropertyDescriptor(window, 'sessionStorage');
      Object.defineProperty(window, 'sessionStorage', {
        configurable: true,
        get() { throw new DOMException('denied', 'SecurityError'); },
      });
      try {
        expect(() => saveRunId(URL_, 'run-47')).not.toThrow();
        expect(loadRunId(URL_)).toBeNull();
        expect(() => clearRunId(URL_)).not.toThrow();
      } finally {
        Object.defineProperty(window, 'sessionStorage', descriptor!);
      }
    });
  });
});
