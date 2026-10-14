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
import { LongPollingTransport } from '../../src/transports/long-polling';
import { SSETransport } from '../../src/transports/sse';
import { StreamingTransport } from '../../src/transports/streaming';
import { Atmosphere } from '../../src/core/atmosphere';
import type { AtmosphereError, AtmosphereRequest, SubscriptionHandlers } from '../../src/types';

/** `[attempt, willRetry]` of every error the handler received, in order. */
function reported(h: SubscriptionHandlers | ReturnType<typeof vi.fn>): Array<[number, boolean]> {
  const errorFn = (typeof h === 'function' ? h : h.error) as ReturnType<typeof vi.fn>;
  return errorFn.mock.calls.map(([e]: [AtmosphereError]) => {
    expect(e).toBeInstanceOf(Error);
    return [e.attempt, e.willRetry];
  });
}

/**
 * First argument of every `console.error` / `console.warn` call. A retried
 * failure is expected while a server restarts, so it must not reach either:
 * in React Native dev mode each one becomes a LogBox toast over the UI.
 */
function logged(level: 'error' | 'warn'): string[] {
  return (console[level] as unknown as ReturnType<typeof vi.fn>).mock.calls.map(([line]) => String(line));
}

/** Only the terminal failure is logged at error level, and no retried one at warn. */
function expectOnlyTerminalFailureLoggedAsError(): void {
  expect(logged('error')).toEqual([expect.stringContaining('willRetry=false')]);
  expect(logged('warn').filter((line) => line.includes('willRetry=true'))).toEqual([]);
}

function handlers(): SubscriptionHandlers {
  return {
    open: vi.fn(),
    message: vi.fn(),
    close: vi.fn(),
    error: vi.fn(),
    reconnect: vi.fn(),
    failureToReconnect: vi.fn(),
    reopen: vi.fn(),
  };
}

describe('error handler: willRetry / attempt', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.spyOn(console, 'error').mockImplementation(() => {});
    vi.spyOn(console, 'warn').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  describe('websocket', () => {
    let sockets: any[];

    beforeEach(() => {
      sockets = [];
      global.WebSocket = vi.fn(function () {
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
        return ws;
      }) as any;
      (global.WebSocket as any).OPEN = 1;
    });

    function fail(ws: any) {
      ws.onerror?.();
      ws.onclose?.({ code: 1006, reason: '' });
    }

    it('marks retried failures willRetry=true and the last one willRetry=false', async () => {
      const h = handlers();
      const transport = new WebSocketTransport(
        { url: 'ws://localhost/chat', transport: 'websocket', reconnect: true, maxReconnectOnClose: 2, reconnectInterval: 10 },
        h,
      );
      const connecting = transport.connect();
      sockets[0].onopen?.();
      await connecting;

      fail(sockets[0]); // drop of the open connection
      await vi.advanceTimersByTimeAsync(1000);
      fail(sockets[1]); // reconnect attempt 1 fails
      await vi.advanceTimersByTimeAsync(1000);
      fail(sockets[2]); // reconnect attempt 2 fails: quota spent
      await vi.advanceTimersByTimeAsync(10_000);

      expect(reported(h)).toEqual([[0, true], [1, true], [2, false]]);
      expect(h.reconnect).toHaveBeenCalledTimes(2);
      expect(h.failureToReconnect).toHaveBeenCalledTimes(1);
      expect(sockets).toHaveLength(3);
      expectOnlyTerminalFailureLoggedAsError();
    });

    it('honours retryStrategy.maxAttempts for willRetry', async () => {
      const h = handlers();
      const transport = new WebSocketTransport(
        {
          url: 'ws://localhost/chat',
          transport: 'websocket',
          reconnect: true,
          maxReconnectOnClose: 5,
          retryStrategy: { strategy: 'custom', customCalculate: () => 10, maxAttempts: 1 },
        },
        h,
      );
      const connecting = transport.connect();
      sockets[0].onopen?.();
      await connecting;

      fail(sockets[0]);
      await vi.advanceTimersByTimeAsync(10);
      fail(sockets[1]);

      expect(reported(h)).toEqual([[0, true], [1, false]]);
    });

    it('is willRetry=false when reconnect is off', async () => {
      const h = handlers();
      const transport = new WebSocketTransport(
        { url: 'ws://localhost/chat', transport: 'websocket', reconnect: false },
        h,
      );
      const connecting = transport.connect();
      sockets[0].onopen?.();
      await connecting;

      fail(sockets[0]);
      expect(reported(h)).toEqual([[0, false]]);
    });

    it('reaches subscribe() handlers and on("error") listeners through the core', async () => {
      const h = handlers();
      // subscribe() wraps h.error in place, so keep the original mock.
      const errorFn = h.error as ReturnType<typeof vi.fn>;
      const listener = vi.fn();
      const atmosphere = new Atmosphere();
      const subscribing = atmosphere.subscribe(
        { url: 'ws://localhost/chat', transport: 'websocket', reconnect: true, maxReconnectOnClose: 1, reconnectInterval: 10 },
        h,
      );
      await vi.waitFor(() => expect(sockets).toHaveLength(1));
      sockets[0].onopen?.();
      const subscription = await subscribing;
      subscription.on('error', listener);

      fail(sockets[0]);
      await vi.advanceTimersByTimeAsync(1000);
      fail(sockets[1]);

      expect(reported(errorFn)).toEqual([[0, true], [1, false]]);
      expect(listener.mock.calls.map(([e]) => (e as AtmosphereError).willRetry)).toEqual([true, false]);
      await subscription.close();
    });
  });

  describe('long-polling', () => {
    let originalFetch: typeof global.fetch;

    beforeEach(() => {
      originalFetch = global.fetch;
    });

    afterEach(() => {
      global.fetch = originalFetch;
    });

    const ok = () => Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(''), headers: new Headers() });
    const serverError = () => Promise.resolve({ ok: false, status: 500, text: () => Promise.resolve(''), headers: new Headers() });

    it('reports a failed initial poll once, as terminal', async () => {
      global.fetch = vi.fn(serverError) as any;
      const h = handlers();
      const transport = new LongPollingTransport(
        { url: 'http://localhost/test', transport: 'long-polling', reconnect: true },
        h,
      );

      await expect(transport.connect()).rejects.toThrow('Long-polling connection failed');
      expect(reported(h)).toEqual([[0, false]]);
    });

    it('reports each failed reconnect attempt once, the last one as terminal', async () => {
      let calls = 0;
      global.fetch = vi.fn(() => (++calls === 1 ? ok() : serverError())) as any;
      const h = handlers();
      const transport = new LongPollingTransport(
        { url: 'http://localhost/test', transport: 'long-polling', reconnect: true, maxReconnectOnClose: 3, reconnectInterval: 10 },
        h,
      );

      await transport.connect();
      await vi.advanceTimersByTimeAsync(60_000);

      // The drop of the open poll (call 2) is a close, not an error; the three
      // reconnect polls each fail with a 500.
      expect(calls).toBe(5);
      expect(reported(h)).toEqual([[1, true], [2, true], [3, false]]);
      expect(h.failureToReconnect).toHaveBeenCalledTimes(1);
      expectOnlyTerminalFailureLoggedAsError();
    });
  });

  describe('streaming', () => {
    let originalFetch: typeof global.fetch;

    beforeEach(() => {
      originalFetch = global.fetch;
    });

    afterEach(() => {
      global.fetch = originalFetch;
    });

    it('marks failed reconnect attempts, the last one as terminal', async () => {
      let calls = 0;
      global.fetch = vi.fn(() => {
        if (++calls === 1) {
          const body = new ReadableStream<Uint8Array>({ start: (c) => c.close() });
          return Promise.resolve({ ok: true, status: 200, body, headers: new Headers() });
        }
        return Promise.reject(new TypeError('Failed to fetch'));
      }) as any;
      const h = handlers();
      const transport = new StreamingTransport(
        { url: 'http://localhost/test', transport: 'streaming', reconnect: true, maxReconnectOnClose: 2, reconnectInterval: 10 },
        h,
      );

      await transport.connect();
      await vi.advanceTimersByTimeAsync(60_000);

      expect(reported(h)).toEqual([[1, true], [2, false]]);
      expect(h.failureToReconnect).toHaveBeenCalledTimes(1);
      expectOnlyTerminalFailureLoggedAsError();
    });

    it('reports a failed initial connect as terminal', async () => {
      global.fetch = vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))) as any;
      const h = handlers();
      const transport = new StreamingTransport(
        { url: 'http://localhost/test', transport: 'streaming', reconnect: true } as AtmosphereRequest,
        h,
      );

      await expect(transport.connect()).rejects.toThrow('Streaming connection error');
      expect(reported(h)).toEqual([[0, false]]);
    });
  });

  describe('sse', () => {
    it('marks failed reconnect attempts, the last one as terminal', async () => {
      const sources: any[] = [];
      global.EventSource = vi.fn(function () {
        const es = { close: vi.fn(), onopen: null as any, onmessage: null as any, onerror: null as any };
        sources.push(es);
        return es;
      }) as any;
      const h = handlers();
      const transport = new SSETransport(
        { url: 'http://localhost/test', transport: 'sse', reconnect: true, maxReconnectOnClose: 2, reconnectInterval: 10 },
        h,
      );

      const connecting = transport.connect();
      sources[0].onopen?.();
      await connecting;
      sources[0].onerror?.(); // drop of the open stream: a close, not an error

      for (let attempt = 1; attempt <= 2; attempt++) {
        await vi.advanceTimersByTimeAsync(1000);
        sources[attempt].onerror?.();
        await vi.advanceTimersByTimeAsync(0);
      }

      expect(reported(h)).toEqual([[1, true], [2, false]]);
      expect(h.failureToReconnect).toHaveBeenCalledTimes(1);
      expectOnlyTerminalFailureLoggedAsError();
    });
  });
});
