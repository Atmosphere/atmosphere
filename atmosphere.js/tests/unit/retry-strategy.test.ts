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
import { computeReconnectDelay, maxReconnectAttempts } from '../../src/utils/reconnect-delay';
import { WebSocketTransport } from '../../src/transports/websocket';
import { LongPollingTransport } from '../../src/transports/long-polling';
import { SSETransport } from '../../src/transports/sse';
import { StreamingTransport } from '../../src/transports/streaming';
import type { AtmosphereRequest, RetryStrategy, SubscriptionHandlers } from '../../src/types';

const CONSTANT_5S: RetryStrategy = { strategy: 'custom', customCalculate: () => 5000 };

describe('computeReconnectDelay', () => {
  describe('default (no retryStrategy) is the historical jittered exponential backoff', () => {
    // base * 2^min(attempt - 1, 5) * (0.5 + random * 0.5), attempt 1-based
    const cases: Array<[number, number, number]> = [
      // [attempt, random, expected]
      [1, 0, 500],
      [1, 1, 1000],
      [2, 0, 1000],
      [3, 0.5, 3000],
      [6, 0, 16000],
      [6, 1, 32000],
      [7, 1, 32000],
      [20, 0, 16000],
    ];
    for (const [attempt, random, expected] of cases) {
      it(`attempt ${attempt}, random ${random} -> ${expected}ms`, () => {
        expect(computeReconnectDelay({}, attempt, () => random)).toBe(expected);
      });
    }

    it('scales with reconnectInterval', () => {
      expect(computeReconnectDelay({ reconnectInterval: 100 }, 3, () => 1)).toBe(400);
    });

    it('uses Math.random for the jitter', () => {
      const spy = vi.spyOn(Math, 'random').mockReturnValue(0);
      expect(computeReconnectDelay({ reconnectInterval: 200 }, 2)).toBe(200);
      expect(spy).toHaveBeenCalled();
      spy.mockRestore();
    });
  });

  it('custom constant: every attempt waits exactly the returned delay, no jitter', () => {
    const random = vi.fn(() => 0);
    for (let attempt = 1; attempt <= 10; attempt++) {
      expect(computeReconnectDelay({ retryStrategy: CONSTANT_5S, reconnectInterval: 100 }, attempt, random))
        .toBe(5000);
    }
    expect(random).not.toHaveBeenCalled();
  });

  it('custom receives the 1-based attempt number', () => {
    const customCalculate = vi.fn((attempt: number) => attempt * 7);
    const request = { retryStrategy: { strategy: 'custom', customCalculate } as RetryStrategy };
    expect(computeReconnectDelay(request, 1)).toBe(7);
    expect(computeReconnectDelay(request, 4)).toBe(28);
    expect(customCalculate.mock.calls.map((c) => c[0])).toEqual([1, 4]);
  });

  it('linear grows by initialDelay per attempt', () => {
    const request = { retryStrategy: { strategy: 'linear', initialDelay: 2000 } as RetryStrategy };
    expect([1, 2, 3, 10].map((a) => computeReconnectDelay(request, a))).toEqual([2000, 4000, 6000, 20000]);
  });

  it('linear with maxDelay equal to initialDelay is constant', () => {
    const request = {
      retryStrategy: { strategy: 'linear', initialDelay: 5000, maxDelay: 5000 } as RetryStrategy,
    };
    expect([1, 2, 3, 50].map((a) => computeReconnectDelay(request, a))).toEqual([5000, 5000, 5000, 5000]);
  });

  it('exponential doubles from initialDelay and is capped at maxDelay', () => {
    const request = {
      retryStrategy: { strategy: 'exponential', initialDelay: 1000, maxDelay: 5000 } as RetryStrategy,
    };
    expect([1, 2, 3, 4, 30].map((a) => computeReconnectDelay(request, a)))
      .toEqual([1000, 2000, 4000, 5000, 5000]);
  });

  it('exponential without maxDelay is capped at 32x initialDelay', () => {
    const request = { retryStrategy: { strategy: 'exponential', initialDelay: 100 } as RetryStrategy };
    expect([1, 6, 7, 100].map((a) => computeReconnectDelay(request, a))).toEqual([100, 3200, 3200, 3200]);
  });

  it('maxDelay caps a custom delay too', () => {
    const request = {
      retryStrategy: { strategy: 'custom', customCalculate: () => 60_000, maxDelay: 10_000 } as RetryStrategy,
    };
    expect(computeReconnectDelay(request, 1)).toBe(10_000);
  });

  it('initialDelay wins over reconnectInterval, which is the fallback', () => {
    const linear = (initialDelay?: number): RetryStrategy => ({ strategy: 'linear', initialDelay });
    expect(computeReconnectDelay({ reconnectInterval: 300, retryStrategy: linear(700) }, 1)).toBe(700);
    expect(computeReconnectDelay({ reconnectInterval: 300, retryStrategy: linear() }, 2)).toBe(600);
    expect(computeReconnectDelay({ retryStrategy: linear() }, 1)).toBe(1000);
  });

  it('clamps a negative, NaN or oversized custom delay to a schedulable value', () => {
    const custom = (value: number) => ({ retryStrategy: { strategy: 'custom', customCalculate: () => value } as RetryStrategy });
    expect(computeReconnectDelay(custom(-50), 1)).toBe(0);
    expect(computeReconnectDelay(custom(Number.NaN), 1)).toBe(0);
    expect(computeReconnectDelay(custom(Number.POSITIVE_INFINITY), 1)).toBe(2_147_483_647);
  });
});

describe('maxReconnectAttempts', () => {
  it('defaults to 5', () => {
    expect(maxReconnectAttempts({})).toBe(5);
  });

  it('uses maxReconnectOnClose when no retryStrategy.maxAttempts', () => {
    expect(maxReconnectAttempts({ maxReconnectOnClose: 9, retryStrategy: CONSTANT_5S })).toBe(9);
  });

  it('retryStrategy.maxAttempts overrides maxReconnectOnClose, smaller or larger', () => {
    expect(maxReconnectAttempts({ maxReconnectOnClose: 3, retryStrategy: { ...CONSTANT_5S, maxAttempts: 1 } })).toBe(1);
    expect(maxReconnectAttempts({ maxReconnectOnClose: 3, retryStrategy: { ...CONSTANT_5S, maxAttempts: 12 } })).toBe(12);
    expect(maxReconnectAttempts({ retryStrategy: { ...CONSTANT_5S, maxAttempts: 0 } })).toBe(0);
  });
});

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

describe('WebSocketTransport honours retryStrategy', () => {
  let sockets: any[];

  beforeEach(() => {
    vi.useFakeTimers();
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

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  async function open(request: Partial<AtmosphereRequest>, h: SubscriptionHandlers) {
    const transport = new WebSocketTransport(
      { url: 'ws://localhost:8080/chat', transport: 'websocket', reconnect: true, ...request },
      h,
    );
    const connecting = transport.connect();
    sockets[0].onopen?.();
    await connecting;
    return transport;
  }

  it('waits the constant custom delay instead of the reconnectInterval backoff', async () => {
    const h = handlers();
    await open({ reconnectInterval: 100, retryStrategy: CONSTANT_5S }, h);

    sockets[0].onclose?.({ code: 1006, reason: '' });
    expect(h.reconnect).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(4999);
    expect(sockets).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(sockets).toHaveLength(2);

    // The second attempt fails before opening: still exactly 5 s, not doubled.
    sockets[1].onerror?.();
    sockets[1].onclose?.({ code: 1006, reason: '' });
    await vi.advanceTimersByTimeAsync(4999);
    expect(sockets).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(sockets).toHaveLength(3);
  });

  it('retryStrategy.maxAttempts overrides a larger maxReconnectOnClose', async () => {
    const h = handlers();
    await open({ maxReconnectOnClose: 3, retryStrategy: { ...CONSTANT_5S, maxAttempts: 1 } }, h);

    sockets[0].onclose?.({ code: 1006, reason: '' });
    await vi.advanceTimersByTimeAsync(5000);
    sockets[1].onclose?.({ code: 1006, reason: '' });
    await vi.advanceTimersByTimeAsync(60_000);

    expect(h.reconnect).toHaveBeenCalledTimes(1);
    expect(h.failureToReconnect).toHaveBeenCalledTimes(1);
    expect(sockets).toHaveLength(2);
  });

  it('retryStrategy.maxAttempts overrides a smaller maxReconnectOnClose', async () => {
    const h = handlers();
    await open({ maxReconnectOnClose: 1, retryStrategy: { ...CONSTANT_5S, maxAttempts: 3 } }, h);

    sockets[0].onclose?.({ code: 1006, reason: '' });
    for (let i = 1; i <= 3; i++) {
      await vi.advanceTimersByTimeAsync(5000);
      sockets[i].onclose?.({ code: 1006, reason: '' });
    }

    expect(h.reconnect).toHaveBeenCalledTimes(3);
    expect(h.failureToReconnect).toHaveBeenCalledTimes(1);
    expect(sockets).toHaveLength(4);
  });
});

describe('HTTP transports keep retrying on the configured strategy after a failed reconnect attempt', () => {
  let originalFetch: typeof global.fetch;

  beforeEach(() => {
    vi.useFakeTimers();
    originalFetch = global.fetch;
  });

  afterEach(() => {
    global.fetch = originalFetch;
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  const retryStrategy: RetryStrategy = { ...CONSTANT_5S, maxAttempts: 3 };

  it('long-polling', async () => {
    let calls = 0;
    global.fetch = vi.fn(() => {
      calls++;
      if (calls === 1) {
        return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(''), headers: new Headers() });
      }
      return Promise.reject(new TypeError('Failed to fetch'));
    }) as any;
    const h = handlers();
    const transport = new LongPollingTransport(
      { url: 'http://localhost/test', transport: 'long-polling', reconnect: true, reconnectInterval: 100, retryStrategy },
      h,
    );

    await transport.connect();
    await vi.advanceTimersByTimeAsync(0);
    // poll 1 opened, re-poll (2) dropped and scheduled reconnect attempt 1
    expect(calls).toBe(2);
    expect(h.reconnect).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(4999);
    expect(calls).toBe(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(calls).toBe(3);

    await vi.advanceTimersByTimeAsync(5000);
    expect(calls).toBe(4);
    await vi.advanceTimersByTimeAsync(5000);
    expect(calls).toBe(5);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(calls).toBe(5);
    expect(h.reconnect).toHaveBeenCalledTimes(3);
    expect(h.failureToReconnect).toHaveBeenCalledTimes(1);
  });

  it('streaming', async () => {
    let calls = 0;
    global.fetch = vi.fn(() => {
      calls++;
      if (calls === 1) {
        const body = new ReadableStream<Uint8Array>({ start: (c) => c.close() });
        return Promise.resolve({ ok: true, status: 200, body, headers: new Headers() });
      }
      return Promise.reject(new TypeError('Failed to fetch'));
    }) as any;
    const h = handlers();
    const transport = new StreamingTransport(
      { url: 'http://localhost/test', transport: 'streaming', reconnect: true, reconnectInterval: 100, retryStrategy },
      h,
    );

    await transport.connect();
    await vi.advanceTimersByTimeAsync(0);
    expect(h.reconnect).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(4999);
    expect(calls).toBe(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(calls).toBe(2);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(calls).toBe(4);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(calls).toBe(4);
    expect(h.reconnect).toHaveBeenCalledTimes(3);
    expect(h.failureToReconnect).toHaveBeenCalledTimes(1);
  });

  it('sse', async () => {
    const sources: any[] = [];
    global.EventSource = vi.fn(function () {
      const es = { close: vi.fn(), onopen: null as any, onmessage: null as any, onerror: null as any };
      sources.push(es);
      return es;
    }) as any;
    const h = handlers();
    const transport = new SSETransport(
      { url: 'http://localhost/test', transport: 'sse', reconnect: true, reconnectInterval: 100, retryStrategy },
      h,
    );

    const connecting = transport.connect();
    sources[0].onopen?.();
    await connecting;
    sources[0].onerror?.(); // drop of an open stream
    expect(h.reconnect).toHaveBeenCalledTimes(1);

    for (let attempt = 1; attempt <= 3; attempt++) {
      await vi.advanceTimersByTimeAsync(4999);
      expect(sources).toHaveLength(attempt);
      await vi.advanceTimersByTimeAsync(1);
      expect(sources).toHaveLength(attempt + 1);
      sources[attempt].onerror?.(); // reconnect attempt fails while connecting
      await vi.advanceTimersByTimeAsync(0);
    }
    await vi.advanceTimersByTimeAsync(60_000);
    expect(sources).toHaveLength(4);
    expect(h.reconnect).toHaveBeenCalledTimes(3);
    expect(h.failureToReconnect).toHaveBeenCalledTimes(1);
  });
});
