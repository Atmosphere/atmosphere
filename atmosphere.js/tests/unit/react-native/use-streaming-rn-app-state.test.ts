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

import { describe, it, expect, vi, afterEach } from 'vitest';
import { createElement, type ReactNode } from 'react';
import { renderHook, act } from '@testing-library/react';
// Resolved to tests/stubs/react-native.ts by vitest.config.ts.
import { __emitAppState } from 'react-native';
import { AtmosphereProvider, useStreamingRN } from '../../../src/hooks/react-native';
import type { AtmosphereRequest, Subscription } from '../../../src/types';
import type { Atmosphere } from '../../../src/core/atmosphere';

/**
 * `useStreamingRN` documented "pauses streaming when the app moves to
 * background / resumes when it returns", but its AppState handler only
 * flipped a ref nobody read: the transport was never suspended, resumed or
 * closed. These tests drive AppState and assert what reaches the
 * subscription for each `backgroundBehavior`.
 */

function createMockAtmosphere() {
  let handlers: Record<string, (...args: unknown[]) => void> = {};
  const subs: Array<{ suspend: ReturnType<typeof vi.fn>; resume: ReturnType<typeof vi.fn>; close: ReturnType<typeof vi.fn> }> = [];
  const atmosphere = {
    subscribe: vi.fn(async (_req: AtmosphereRequest, h: Record<string, unknown>) => {
      handlers = h as typeof handlers;
      const sub = {
        id: `sub-${subs.length}`, state: 'connected', push: vi.fn(),
        close: vi.fn(async () => {}), suspend: vi.fn(), resume: vi.fn(async () => {}),
        on: vi.fn(), off: vi.fn(),
      };
      subs.push(sub);
      handlers.open?.({ transport: 'websocket' });
      return sub as unknown as Subscription;
    }),
  } as unknown as Atmosphere;
  return { atmosphere, subs, handlers: () => handlers };
}

const request: AtmosphereRequest = { url: 'http://localhost/ai', transport: 'websocket' };

function wrapper(atmosphere: Atmosphere) {
  return ({ children }: { children: ReactNode }) =>
    createElement(AtmosphereProvider, { instance: atmosphere }, children);
}

describe('useStreamingRN applies backgroundBehavior on AppState transitions', () => {
  afterEach(() => {
    act(() => __emitAppState('active'));
  });

  it('suspend: suspends the subscription in background and resumes it on foreground', async () => {
    const mock = createMockAtmosphere();
    renderHook(() => useStreamingRN({ request, backgroundBehavior: 'suspend' }),
      { wrapper: wrapper(mock.atmosphere) });
    await vi.waitFor(() => expect(mock.subs).toHaveLength(1));
    await act(async () => { await Promise.resolve(); });

    act(() => __emitAppState('background'));
    expect(mock.subs[0].suspend).toHaveBeenCalledTimes(1);
    expect(mock.subs[0].resume).not.toHaveBeenCalled();

    // inactive -> background must not suspend twice
    act(() => __emitAppState('inactive'));
    expect(mock.subs[0].suspend).toHaveBeenCalledTimes(1);

    act(() => __emitAppState('active'));
    expect(mock.subs[0].resume).toHaveBeenCalledTimes(1);
    expect(mock.subs[0].close).not.toHaveBeenCalled();
    expect(mock.subs).toHaveLength(1);
  });

  it('disconnect: closes the stream in background and opens a new one on foreground', async () => {
    const mock = createMockAtmosphere();
    const { result } = renderHook(() => useStreamingRN({ request, backgroundBehavior: 'disconnect' }),
      { wrapper: wrapper(mock.atmosphere) });
    await vi.waitFor(() => expect(mock.subs).toHaveLength(1));
    await act(async () => { await Promise.resolve(); });
    expect(result.current.canSend).toBe(true);

    act(() => __emitAppState('background'));
    expect(mock.subs[0].close).toHaveBeenCalledTimes(1);
    expect(result.current.canSend).toBe(false);

    act(() => __emitAppState('active'));
    await vi.waitFor(() => expect(mock.subs).toHaveLength(2));
    await act(async () => { await Promise.resolve(); });
    expect(mock.subs[1].close).not.toHaveBeenCalled();
    expect(result.current.canSend).toBe(true);
  });

  it('keep-alive (default): leaves the stream alone so an in-flight answer keeps arriving', async () => {
    const mock = createMockAtmosphere();
    renderHook(() => useStreamingRN({ request }), { wrapper: wrapper(mock.atmosphere) });
    await vi.waitFor(() => expect(mock.subs).toHaveLength(1));
    await act(async () => { await Promise.resolve(); });

    act(() => __emitAppState('background'));
    act(() => __emitAppState('active'));

    expect(mock.subs[0].suspend).not.toHaveBeenCalled();
    expect(mock.subs[0].resume).not.toHaveBeenCalled();
    expect(mock.subs[0].close).not.toHaveBeenCalled();
    expect(mock.subs).toHaveLength(1);
  });
});

/**
 * Leaving the foreground in `suspend` or `disconnect` mode cuts an in-flight
 * answer short: suspended frames are dropped (the `complete` frame among
 * them) and a closed stream never completes its turn. Nothing else ends the
 * turn, so `isStreaming` must be reset when the behavior is applied, or the
 * typing indicator stays on forever after the app returns.
 */
describe('useStreamingRN ends an in-flight turn when it leaves the foreground', () => {
  afterEach(() => {
    act(() => __emitAppState('active'));
  });

  for (const backgroundBehavior of ['suspend', 'disconnect'] as const) {
    it(`${backgroundBehavior}: isStreaming and progress are reset after a background -> active cycle mid-answer`, async () => {
      const mock = createMockAtmosphere();
      const { result } = renderHook(() => useStreamingRN({ request, backgroundBehavior }),
        { wrapper: wrapper(mock.atmosphere) });
      await vi.waitFor(() => expect(mock.subs).toHaveLength(1));
      await act(async () => { await Promise.resolve(); });

      act(() => { result.current.send('hello'); });
      act(() => {
        mock.handlers().message?.({
          status: 200, transport: 'websocket', state: 'messageReceived',
          responseBody: JSON.stringify({ type: 'progress', data: 'thinking', sessionId: 's1', seq: 1 }),
        });
      });
      expect(result.current.isStreaming).toBe(true);
      expect(result.current.progress).toBe('thinking');

      act(() => __emitAppState('background'));
      act(() => __emitAppState('active'));
      await act(async () => { await Promise.resolve(); });

      expect(result.current.isStreaming).toBe(false);
      expect(result.current.progress).toBeNull();
    });
  }

  it('keep-alive: an in-flight answer keeps streaming across a background -> active cycle', async () => {
    const mock = createMockAtmosphere();
    const { result } = renderHook(() => useStreamingRN({ request }), { wrapper: wrapper(mock.atmosphere) });
    await vi.waitFor(() => expect(mock.subs).toHaveLength(1));
    await act(async () => { await Promise.resolve(); });

    act(() => { result.current.send('hello'); });
    act(() => __emitAppState('background'));
    act(() => __emitAppState('active'));

    expect(result.current.isStreaming).toBe(true);
  });
});

/**
 * `disconnect` tears the subscription down, and the hook used to unbind the
 * status tracker before closing the handle, so `connectionStatus` froze at
 * `open`. On return `canSend` then read true for the whole reconnect while no
 * handle existed, and a send in that window was rejected with "connection is
 * open". The second subscribe is held open here so that window is observable.
 */
describe('useStreamingRN disconnect does not advertise a closed stream as open', () => {
  afterEach(() => {
    act(() => __emitAppState('active'));
  });

  it('connectionStatus leaves open in background and canSend stays false until the new stream is bound', async () => {
    let releaseSecond: () => void = () => {};
    const secondGate = new Promise<void>((resolve) => { releaseSecond = resolve; });
    let handlers: Record<string, (...args: unknown[]) => void> = {};
    const subs: Array<{ close: ReturnType<typeof vi.fn> }> = [];
    const atmosphere = {
      subscribe: vi.fn(async (_req: AtmosphereRequest, h: Record<string, unknown>) => {
        handlers = h as typeof handlers;
        if (subs.length === 1) await secondGate;
        const sub = {
          id: `sub-${subs.length}`, state: 'connected', push: vi.fn(),
          close: vi.fn(async () => {}), suspend: vi.fn(), resume: vi.fn(async () => {}),
          on: vi.fn(), off: vi.fn(),
        };
        subs.push(sub);
        handlers.open?.({ transport: 'websocket' });
        return sub as unknown as Subscription;
      }),
    } as unknown as Atmosphere;

    const { result, unmount } = renderHook(
      () => useStreamingRN({ request, backgroundBehavior: 'disconnect' }),
      { wrapper: wrapper(atmosphere) });
    await vi.waitFor(() => expect(subs).toHaveLength(1));
    await act(async () => { await Promise.resolve(); });
    expect(result.current.connectionStatus.phase).toBe('open');

    act(() => __emitAppState('background'));
    expect(subs[0].close).toHaveBeenCalledTimes(1);
    expect(result.current.connectionStatus.phase).not.toBe('open');

    act(() => __emitAppState('active'));
    await vi.waitFor(() => expect(atmosphere.subscribe).toHaveBeenCalledTimes(2));
    // Reconnect in flight: no handle is bound yet.
    expect(result.current.connectionStatus.phase).not.toBe('open');
    expect(result.current.canSend).toBe(false);
    let outcome: ReturnType<typeof result.current.send> | undefined;
    act(() => { outcome = result.current.send('too early'); });
    expect(outcome?.outcome).toBe('rejected');
    expect(outcome?.reason).not.toContain('connection is open');

    await act(async () => { releaseSecond(); await secondGate; await Promise.resolve(); });
    await vi.waitFor(() => expect(subs).toHaveLength(2));
    await act(async () => { await Promise.resolve(); });
    expect(result.current.connectionStatus.phase).toBe('open');
    expect(result.current.canSend).toBe(true);
    unmount();
  });
});
