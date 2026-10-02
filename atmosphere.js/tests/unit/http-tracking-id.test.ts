import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { LongPollingTransport } from '../../src/transports/long-polling';
import { SSETransport } from '../../src/transports/sse';
import { StreamingTransport } from '../../src/transports/streaming';
import { WebSocketTransport } from '../../src/transports/websocket';
import { AtmosphereProtocol } from '../../src/utils/protocol';
import { OfflineQueue } from '../../src/queue/offline-queue';
import type { AtmosphereRequest, SubscriptionHandlers } from '../../src/types';

/**
 * An HTTP transport sends each message as its own POST, and the server routes
 * it to the subscription the `X-Atmosphere-tracking-id` names. Without the
 * protocol handshake (`enableProtocol` unset, the default) nothing assigned
 * that id, so every POST carried `0` and named no connection: an Atmosphere AI
 * endpoint answers such a prompt `400`, and the prompt was lost. The client now
 * picks the id itself and sends it on the subscription and on every POST.
 */

/** What the server accepts as a tracking id (AtmosphereResourceImpl.isValidTrackingId). */
const SERVER_VALID_ID = /^[A-Za-z0-9_-]{1,128}$/;

function trackingId(url: string): string | null {
  return new URL(url).searchParams.get('X-Atmosphere-tracking-id');
}

describe('HTTP transport tracking id without the protocol handshake', () => {
  let originalFetch: typeof global.fetch;
  let handlers: SubscriptionHandlers;
  let gets: string[];
  let posts: string[];

  beforeEach(() => {
    originalFetch = global.fetch;
    handlers = { open: vi.fn(), message: vi.fn(), close: vi.fn(), error: vi.fn(), reconnect: vi.fn() };
    gets = [];
    posts = [];
    let polls = 0;
    global.fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        posts.push(url);
        return Promise.resolve({ ok: true, status: 200, headers: new Headers(), text: () => Promise.resolve('') });
      }
      gets.push(url);
      polls++;
      if (polls === 1) {
        return Promise.resolve({ ok: true, status: 200, headers: new Headers(), text: () => Promise.resolve('') });
      }
      return new Promise((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => {
          const error = new Error('aborted');
          error.name = 'AbortError';
          reject(error);
        });
      });
    });
  });

  afterEach(() => {
    global.fetch = originalFetch;
  });

  it('long-polling with default options names its subscription on every poll and every POST', async () => {
    const request: AtmosphereRequest = { url: 'http://localhost/ai', transport: 'long-polling' };
    const transport = new LongPollingTransport(request, handlers);
    await transport.connect();
    transport.send('prompt');
    await vi.waitFor(() => expect(posts).toHaveLength(1));
    await vi.waitFor(() => expect(gets.length).toBeGreaterThanOrEqual(2));

    const id = trackingId(gets[0]);
    expect(id).not.toBe('0');
    expect(id).toMatch(SERVER_VALID_ID);
    for (const url of [...gets, ...posts]) {
      expect(trackingId(url)).toBe(id);
    }
    expect(handlers.error).not.toHaveBeenCalled();
    await transport.disconnect();
  });

  it('gives SSE and streaming POSTs an id the server accepts, one per transport', async () => {
    const ids = new Set<string>();
    for (const make of [
      () => new SSETransport({ url: 'http://localhost/ai', transport: 'sse' }, handlers),
      () => new StreamingTransport({ url: 'http://localhost/ai', transport: 'streaming' }, handlers),
    ]) {
      posts = [];
      const transport = make();
      (transport as unknown as { _state: string })._state = 'connected';
      transport.send('prompt');
      await vi.waitFor(() => expect(posts).toHaveLength(1));
      const id = trackingId(posts[0])!;
      expect(id).not.toBe('0');
      expect(id).toMatch(SERVER_VALID_ID);
      expect(id).toBe(transport.uuid);
      ids.add(id);
    }
    expect(ids.size).toBe(2);
  });

  it('leaves the id to the server handshake when the protocol is on, and WebSocket untouched', () => {
    const lp = new LongPollingTransport(
      { url: 'http://localhost/ai', transport: 'long-polling', enableProtocol: true }, handlers);
    expect(lp.uuid).toBe('0');
    const ws = new WebSocketTransport({ url: 'http://localhost/ai', transport: 'websocket' }, handlers);
    expect(ws.uuid).toBe('0');
  });

  it('still produces a valid id on a runtime without crypto', () => {
    vi.stubGlobal('crypto', undefined);
    try {
      const id = AtmosphereProtocol.clientTrackingId();
      expect(id).toMatch(SERVER_VALID_ID);
      expect(id).toHaveLength(32);
      expect(AtmosphereProtocol.clientTrackingId()).not.toBe(id);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('draws the id from crypto.getRandomValues where randomUUID is missing (a polyfilled React Native)', async () => {
    const { logger } = await import('../../src/utils/logger');
    const warn = vi.spyOn(logger, 'warn').mockImplementation(() => {});
    (AtmosphereProtocol as unknown as { weakIdWarned: boolean }).weakIdWarned = false;
    const random = vi.spyOn(Math, 'random');
    const getRandomValues = vi.fn((bytes: Uint8Array) => {
      bytes.forEach((_b, i) => { bytes[i] = (i * 17 + 5) & 0xff; });
      return bytes;
    });
    vi.stubGlobal('crypto', { getRandomValues });
    try {
      const id = AtmosphereProtocol.clientTrackingId();
      expect(getRandomValues).toHaveBeenCalledTimes(1);
      expect(id).toMatch(/^[0-9a-f]{32}$/);
      expect(id.slice(0, 6)).toBe('051627');
      expect(random).not.toHaveBeenCalled();
      expect(warn).not.toHaveBeenCalled();
    } finally {
      vi.unstubAllGlobals();
      random.mockRestore();
      warn.mockRestore();
    }
  });
});

/**
 * With `enableProtocol` the server names a long-polling subscription in the
 * body of its first poll. The transport used to fire `open` (and drain the
 * offline queue) as soon as that poll's headers arrived, before the body was
 * read, so every message sent from `open` or drained from the queue was POSTed
 * with tracking id 0 — which an Atmosphere AI endpoint refuses with 400.
 */
describe('long-polling with the protocol handshake', () => {
  let originalFetch: typeof global.fetch;
  let handlers: SubscriptionHandlers;
  let posts: string[];
  let firstBody: string;

  beforeEach(() => {
    originalFetch = global.fetch;
    handlers = { open: vi.fn(), message: vi.fn(), close: vi.fn(), error: vi.fn(), reconnect: vi.fn() };
    posts = [];
    firstBody = 'server-uuid-1|0|X|';
    let polls = 0;
    global.fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        posts.push(url);
        const status = trackingId(url) === '0' ? 400 : 200;
        return Promise.resolve({ ok: status === 200, status, headers: new Headers(), text: () => Promise.resolve('') });
      }
      polls++;
      if (polls === 1) {
        return Promise.resolve({ ok: true, status: 200, headers: new Headers(), text: () => Promise.resolve(firstBody) });
      }
      return new Promise((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => {
          const error = new Error('aborted');
          error.name = 'AbortError';
          reject(error);
        });
      });
    });
  });

  afterEach(() => {
    global.fetch = originalFetch;
  });

  function protocolRequest(): AtmosphereRequest {
    return { url: 'http://localhost/ai', transport: 'long-polling', enableProtocol: true };
  }

  it('drains the offline queue and serves open handlers with the handshake id, never 0', async () => {
    const queue = new OfflineQueue<string | object | ArrayBuffer>();
    queue.enqueue('queued prompt');
    let idAtOpen: string | null = null;
    const transport = new LongPollingTransport<string>(protocolRequest(), {
      ...handlers,
      open: () => {
        idAtOpen = transport.uuid;
        transport.send('prompt from open');
      },
    });
    transport.setOfflineQueue(queue);
    await transport.connect();

    await vi.waitFor(() => expect(posts).toHaveLength(2));
    expect(idAtOpen).toBe('server-uuid-1');
    expect(posts.map(trackingId)).toEqual(['server-uuid-1', 'server-uuid-1']);
    expect(queue.size).toBe(0);
    expect(handlers.error).not.toHaveBeenCalled();
    await transport.disconnect();
  });

  it('opens before a message that trails the handshake', async () => {
    firstBody = 'server-uuid-1|0|X|hello';
    const order: string[] = [];
    const transport = new LongPollingTransport<string>(protocolRequest(), {
      ...handlers,
      open: () => order.push(`open:${transport.uuid}`),
      message: (response) => order.push(`message:${String(response.responseBody)}`),
    });
    await transport.connect();
    expect(order).toEqual(['open:server-uuid-1', 'message:hello']);
    await transport.disconnect();
  });

  it('reopens a reconnect at once: it keeps its id, so the server sends no new handshake', async () => {
    let polls = 0;
    const pollIds: (string | null)[] = [];
    global.fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      polls++;
      pollIds.push(trackingId(url));
      if (polls === 1) {
        return Promise.resolve({ ok: true, status: 200, headers: new Headers(), text: () => Promise.resolve(firstBody) });
      }
      if (polls === 2) {
        return Promise.resolve({ ok: false, status: 502, headers: new Headers(), text: () => Promise.resolve('') });
      }
      if (polls === 3) {
        // The reconnect poll: answered without any handshake.
        return Promise.resolve({ ok: true, status: 200, headers: new Headers(), text: () => Promise.resolve('') });
      }
      return new Promise(() => { /* held open by the server */ });
    });
    const reopen = vi.fn();
    const transport = new LongPollingTransport<string>(
      { ...protocolRequest(), reconnect: true, reconnectInterval: 1 }, { ...handlers, reopen });
    await transport.connect();
    await vi.waitFor(() => expect(reopen).toHaveBeenCalledTimes(1));
    expect(pollIds.slice(0, 3)).toEqual(['0', 'server-uuid-1', 'server-uuid-1']);
    expect(transport.state).toBe('connected');
    await transport.disconnect();
  });

  it('still opens once when the server answers the first poll without a handshake', async () => {
    firstBody = '';
    const transport = new LongPollingTransport<string>(protocolRequest(), handlers);
    await transport.connect();
    expect(handlers.open).toHaveBeenCalledTimes(1);
    expect(transport.state).toBe('connected');
    await transport.disconnect();
  });
});

/**
 * The server unregisters a closing connection by its tracking id alone. A
 * client that came back under the same id while the server still held its
 * previous connection (a dropped SSE stream not yet noticed, an abandoned
 * poll) had its live connection unregistered when that stale one was finally
 * closed, and received nothing from then on. A client-chosen id therefore
 * names one subscription: every connect and reconnect picks a new one, while a
 * long-polling re-poll, which continues the subscription, keeps it.
 */
describe('client-chosen tracking id per subscription', () => {
  let originalFetch: typeof global.fetch;
  let handlers: SubscriptionHandlers;

  beforeEach(() => {
    originalFetch = global.fetch;
    handlers = { open: vi.fn(), message: vi.fn(), close: vi.fn(), error: vi.fn(), reconnect: vi.fn() };
  });

  afterEach(() => {
    global.fetch = originalFetch;
    vi.unstubAllGlobals();
  });

  it('long-polling keeps its id across re-polls and takes a new one on reconnect', async () => {
    const gets: string[] = [];
    const posts: string[] = [];
    global.fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        posts.push(url);
        return Promise.resolve({ ok: true, status: 200, headers: new Headers(), text: () => Promise.resolve('') });
      }
      gets.push(url);
      if (gets.length <= 2) {
        // Two completed polls of the first subscription.
        return Promise.resolve({ ok: true, status: 200, headers: new Headers(), text: () => Promise.resolve('') });
      }
      if (gets.length === 3) {
        // The connection is lost: the transport reconnects.
        return Promise.resolve({ ok: false, status: 502, headers: new Headers(), text: () => Promise.resolve('') });
      }
      return new Promise(() => { /* held open by the server */ });
    });
    const transport = new LongPollingTransport(
      { url: 'http://localhost/ai', transport: 'long-polling', reconnect: true, reconnectInterval: 1 }, handlers);
    await transport.connect();
    await vi.waitFor(() => expect(gets).toHaveLength(4));

    const [first, second, third, reconnect] = gets.map(trackingId);
    expect(second).toBe(first);
    expect(third).toBe(first);
    expect(reconnect).not.toBe(first);
    expect(reconnect).toMatch(SERVER_VALID_ID);
    expect(transport.uuid).toBe(reconnect);

    transport.send('after reconnect');
    await vi.waitFor(() => expect(posts).toHaveLength(1));
    expect(trackingId(posts[0])).toBe(reconnect);
    await transport.disconnect();
  });

  it('SSE takes a new id for each connection, reconnects included', async () => {
    const urls: string[] = [];
    const sources: { onopen: (() => void) | null; onerror: (() => void) | null; close: () => void }[] = [];
    vi.stubGlobal('EventSource', vi.fn(function (url: string) {
      urls.push(url);
      const source = { onopen: null, onmessage: null, onerror: null, close: vi.fn() };
      sources.push(source);
      return source;
    }));
    const transport = new SSETransport(
      { url: 'http://localhost/ai', transport: 'sse', reconnect: true, reconnectInterval: 1 }, handlers);
    const connected = transport.connect();
    sources[0].onopen?.();
    await connected;

    // The stream drops: the transport reconnects on its own.
    sources[0].onerror?.();
    await vi.waitFor(() => expect(urls).toHaveLength(2));
    sources[1].onopen?.();

    const ids = urls.map(trackingId);
    expect(ids[0]).toMatch(SERVER_VALID_ID);
    expect(ids[1]).toMatch(SERVER_VALID_ID);
    expect(ids[1]).not.toBe(ids[0]);
    expect(transport.uuid).toBe(ids[1]);

    // A later connect of the same transport is a new subscription too.
    await transport.disconnect();
    const again = transport.connect();
    sources[2].onopen?.();
    await again;
    expect(new Set(urls.map(trackingId)).size).toBe(3);
    await transport.disconnect();
  });

  it('streaming takes a new id for each connection, reconnects included', async () => {
    const gets: string[] = [];
    const posts: string[] = [];
    global.fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        posts.push(url);
        return Promise.resolve({ ok: true, status: 200, headers: new Headers(), text: () => Promise.resolve('') });
      }
      gets.push(url);
      // The first stream ends at once, so the transport reconnects; the next is held open.
      const ended = gets.length === 1;
      const reader = {
        read: () => (ended
          ? Promise.resolve({ done: true, value: undefined })
          : new Promise<never>(() => { /* held open by the server */ })),
        cancel: () => Promise.resolve(),
        releaseLock: () => {},
      };
      return Promise.resolve({
        ok: true, status: 200, headers: new Headers(), body: { getReader: () => reader },
      });
    });
    const transport = new StreamingTransport(
      { url: 'http://localhost/ai', transport: 'streaming', reconnect: true, reconnectInterval: 1 }, handlers);
    await transport.connect();
    await vi.waitFor(() => expect(gets).toHaveLength(2));

    const [first, reconnect] = gets.map(trackingId);
    expect(first).toMatch(SERVER_VALID_ID);
    expect(reconnect).toMatch(SERVER_VALID_ID);
    expect(reconnect).not.toBe(first);
    expect(transport.uuid).toBe(reconnect);

    transport.send('after reconnect');
    await vi.waitFor(() => expect(posts).toHaveLength(1));
    expect(trackingId(posts[0])).toBe(reconnect);
    await transport.disconnect();
  });

  it('resends a refused message under the id of the current subscription', async () => {
    const posts: string[] = [];
    let transport: SSETransport | null = null;
    global.fetch = vi.fn().mockImplementation((url: string) => {
      posts.push(url);
      if (posts.length === 1) {
        // The subscription reconnected under a new id while this POST was refused.
        (transport as unknown as { protocol: AtmosphereProtocol }).protocol.uuid = 'current-subscription';
        return Promise.resolve({
          ok: false, status: 503, headers: new Headers({ 'Retry-After': '0' }), text: () => Promise.resolve(''),
        });
      }
      return Promise.resolve({ ok: true, status: 200, headers: new Headers(), text: () => Promise.resolve('') });
    });
    transport = new SSETransport({ url: 'http://localhost/ai', transport: 'sse' }, handlers);
    (transport as unknown as { _state: string })._state = 'connected';
    const before = transport.uuid;
    transport.send('prompt');

    await vi.waitFor(() => expect(posts).toHaveLength(2));
    expect(trackingId(posts[0])).toBe(before);
    expect(trackingId(posts[1])).toBe('current-subscription');
    expect(handlers.error).not.toHaveBeenCalled();
  });

  it('warns once when it has to fall back to Math.random', async () => {
    const { logger } = await import('../../src/utils/logger');
    const warn = vi.spyOn(logger, 'warn').mockImplementation(() => {});
    (AtmosphereProtocol as unknown as { weakIdWarned: boolean }).weakIdWarned = false;
    vi.stubGlobal('crypto', undefined);
    try {
      AtmosphereProtocol.clientTrackingId();
      AtmosphereProtocol.clientTrackingId();
      expect(warn).toHaveBeenCalledTimes(1);
      expect(String(warn.mock.calls[0][0])).toContain('Math.random');
    } finally {
      warn.mockRestore();
    }
  });
});
