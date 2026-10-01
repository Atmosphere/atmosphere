import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { LongPollingTransport } from '../../src/transports/long-polling';
import { SSETransport } from '../../src/transports/sse';
import { StreamingTransport } from '../../src/transports/streaming';
import { WebSocketTransport } from '../../src/transports/websocket';
import { AtmosphereProtocol } from '../../src/utils/protocol';
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
});
