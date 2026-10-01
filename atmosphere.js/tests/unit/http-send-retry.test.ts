import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { LongPollingTransport } from '../../src/transports/long-polling';
import { SSETransport } from '../../src/transports/sse';
import { StreamingTransport } from '../../src/transports/streaming';
import { BaseTransport } from '../../src/transports/base';
import type { AtmosphereRequest, SubscriptionHandlers } from '../../src/types';

/**
 * The HTTP transports send each message as its own POST. A server that cannot
 * take a message yet answers 503 — the Atmosphere AI endpoint does when a
 * long-polling client posts between two polls — and the message must be sent
 * again rather than dropped silently. A message the server keeps refusing is
 * reported to the `error` handler.
 */
describe('HTTP transport POST send retry', () => {
  let originalFetch: typeof global.fetch;
  let handlers: SubscriptionHandlers;
  let postStatuses: number[];
  let posts: RequestInit[];

  beforeEach(() => {
    originalFetch = global.fetch;
    handlers = { open: vi.fn(), message: vi.fn(), close: vi.fn(), error: vi.fn(), reconnect: vi.fn() };
    posts = [];
    postStatuses = [];
    let polls = 0;
    global.fetch = vi.fn().mockImplementation((_url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        posts.push(init);
        const status = postStatuses.shift() ?? 200;
        return Promise.resolve({
          ok: status >= 200 && status < 300,
          status,
          headers: new Headers(status === 503 ? { 'Retry-After': '1' } : {}),
          text: () => Promise.resolve(''),
        });
      }
      polls++;
      if (polls === 1) {
        // The first poll opens the long-polling transport.
        return Promise.resolve({ ok: true, status: 200, headers: new Headers(), text: () => Promise.resolve('') });
      }
      // Later polls stay suspended until the transport aborts them.
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
    vi.useRealTimers();
    global.fetch = originalFetch;
  });

  async function connectedLongPolling(): Promise<LongPollingTransport> {
    const request: AtmosphereRequest = { url: 'http://localhost/ai', transport: 'long-polling' };
    const transport = new LongPollingTransport(request, handlers);
    await transport.connect();
    return transport;
  }

  it('sends a prompt the server refused with 503 again after Retry-After, once accepted', async () => {
    const transport = await connectedLongPolling();
    vi.useFakeTimers();
    postStatuses = [503];

    transport.send('prompt');
    await vi.waitFor(() => expect(posts).toHaveLength(1));

    // Not before the server's Retry-After (1 s).
    await vi.advanceTimersByTimeAsync(999);
    expect(posts).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    await vi.waitFor(() => expect(posts).toHaveLength(2));

    expect(posts[1].body).toBe('prompt');
    await vi.advanceTimersByTimeAsync(10_000);
    expect(posts).toHaveLength(2);
    expect(handlers.error).not.toHaveBeenCalled();
    await transport.disconnect();
  });

  it('stops after the bounded number of attempts and reports the lost message', async () => {
    const transport = await connectedLongPolling();
    vi.useFakeTimers();
    postStatuses = [503, 503, 503, 503, 503];

    transport.send('prompt');
    await vi.advanceTimersByTimeAsync(30_000);

    expect(posts).toHaveLength(BaseTransport.SEND_MAX_ATTEMPTS);
    expect(handlers.error).toHaveBeenCalledTimes(1);
    const reported = (handlers.error as ReturnType<typeof vi.fn>).mock.calls[0][0];
    expect(reported.message).toContain('status 503');
    expect(reported.name).toBe('AtmosphereSendError');
    await transport.disconnect();
  });

  it('reports a POST the server rejects outright instead of dropping it silently', async () => {
    const transport = await connectedLongPolling();
    postStatuses = [400];

    transport.send('prompt');

    await vi.waitFor(() => expect(handlers.error).toHaveBeenCalledTimes(1));
    expect(posts).toHaveLength(1);
    await transport.disconnect();
  });

  it('does not retry once the transport is disconnected', async () => {
    const transport = await connectedLongPolling();
    vi.useFakeTimers();
    postStatuses = [503, 503];

    transport.send('prompt');
    await vi.waitFor(() => expect(posts).toHaveLength(1));
    await transport.disconnect();
    await vi.advanceTimersByTimeAsync(10_000);

    expect(posts).toHaveLength(1);
    expect(handlers.error).not.toHaveBeenCalled();
  });

  it('caps the wait a server Retry-After can impose', async () => {
    const transport = await connectedLongPolling();
    vi.useFakeTimers();
    (global.fetch as ReturnType<typeof vi.fn>).mockImplementation((_url: string, init?: RequestInit) => {
      posts.push(init!);
      const status = posts.length === 1 ? 503 : 200;
      return Promise.resolve({
        ok: status === 200,
        status,
        headers: new Headers({ 'Retry-After': '3600' }),
        text: () => Promise.resolve(''),
      });
    });

    transport.send('prompt');
    await vi.waitFor(() => expect(posts).toHaveLength(1));
    await vi.advanceTimersByTimeAsync(BaseTransport.SEND_RETRY_MAX_DELAY_MS);

    expect(posts).toHaveLength(2);
    await transport.disconnect();
  });

  it('retries the SSE and streaming POST sends the same way', async () => {
    for (const make of [
      () => new SSETransport({ url: 'http://localhost/ai', transport: 'sse' }, handlers),
      () => new StreamingTransport({ url: 'http://localhost/ai', transport: 'streaming' }, handlers),
    ]) {
      posts = [];
      postStatuses = [503];
      const transport = make();
      // Sends retry only while the transport is not disconnected.
      (transport as unknown as { _state: string })._state = 'connected';
      vi.useFakeTimers();

      transport.send('prompt');
      await vi.waitFor(() => expect(posts).toHaveLength(1));
      await vi.advanceTimersByTimeAsync(1_000);
      await vi.waitFor(() => expect(posts).toHaveLength(2));

      expect(posts[1].body).toBe('prompt');
      expect(handlers.error).not.toHaveBeenCalled();
      vi.useRealTimers();
    }
  });
});
