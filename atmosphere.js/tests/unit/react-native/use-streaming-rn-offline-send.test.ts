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

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createElement, type ReactNode } from 'react';
import { renderHook, act } from '@testing-library/react';
import {
  AtmosphereProvider,
  setupReactNative,
  useStreamingRN,
  type StreamingSendResult,
} from '../../../src/hooks/react-native';
import { OfflineQueue } from '../../../src/queue/offline-queue';
import type {
  AtmosphereRequest,
  AtmosphereResponse,
  ConnectionState,
  Subscription,
  SubscriptionHandlers,
} from '../../../src/types';
import type { Atmosphere } from '../../../src/core/atmosphere';

/**
 * Regression coverage for the `./react-native` export path: a prompt handed
 * to {@code useStreamingRN.send} while the stream cannot carry it must be
 * queued or surfaced — never dropped on the floor.
 *
 * The defect this pins was found by driving the Expo client in the iOS
 * simulator: `send()` early-returned on `!isConnected`, returned `void`, set
 * no error and fired no callback, so a message typed while offline vanished
 * with the input box still full and nothing on screen to explain it.
 *
 * Two independent gates are asserted here, matching the two layers of that
 * defect:
 *   1. the *outcome* contract — every send reports sent | queued | rejected;
 *   2. the *gate* itself — `canSend` consults transport state, not just
 *      NetInfo, because the observed failure had a live radio and a dead
 *      socket.
 */

// --- NetInfo double -------------------------------------------------------

type NetInfoListener = (state: { isConnected: boolean | null; isInternetReachable: boolean | null }) => void;

const netInfoListeners = new Set<NetInfoListener>();

const netInfo = {
  addEventListener(listener: NetInfoListener): () => void {
    netInfoListeners.add(listener);
    return () => netInfoListeners.delete(listener);
  },
};

function emitNetInfo(isConnected: boolean): void {
  for (const listener of [...netInfoListeners]) {
    listener({ isConnected, isInternetReachable: isConnected });
  }
}

setupReactNative({ netInfo });

// --- Atmosphere double ----------------------------------------------------

interface FakeWire {
  atmosphere: Atmosphere;
  /** Everything that actually reached the transport. */
  pushed: unknown[];
  /** Deliver a server frame to the subscription. */
  deliver: (frame: Record<string, unknown>) => void;
  /** Drop the socket the way a stopped server does. */
  dropConnection: () => void;
  /** Flush the offline queue the way BaseTransport does on reopen. */
  reopenAndDrain: (queue: OfflineQueue) => void;
}

function createFakeWire(): FakeWire {
  const pushed: unknown[] = [];
  let handlers: SubscriptionHandlers<string> = {};
  let state: ConnectionState = 'connected';

  const subscription: Subscription = {
    id: 'fake-sub',
    get state(): ConnectionState {
      return state;
    },
    push: (message) => {
      pushed.push(message);
    },
    close: async () => { state = 'disconnected'; },
    suspend: () => {},
    resume: async () => {},
    on: () => {},
    off: () => {},
  };

  const openResponse = { transport: 'websocket' } as AtmosphereResponse<string>;

  const atmosphere = {
    subscribe: async (_request: AtmosphereRequest, h: SubscriptionHandlers<string>) => {
      handlers = h;
      // The real client awaits connectWithTimeout() inside subscribe(), so
      // `open` has already fired by the time the promise resolves. Mirror
      // that ordering — the hook's status binding depends on it.
      state = 'connected';
      handlers.open?.(openResponse);
      return subscription;
    },
  } as unknown as Atmosphere;

  return {
    atmosphere,
    pushed,
    deliver: (frame) => handlers.message?.({ responseBody: JSON.stringify(frame) } as AtmosphereResponse<string>),
    dropConnection: () => {
      state = 'disconnected';
      handlers.error?.(new Error('WebSocket connection error'));
      handlers.close?.({ transport: 'websocket' } as AtmosphereResponse<string>);
    },
    reopenAndDrain: (queue) => {
      state = 'connected';
      handlers.reopen?.(openResponse);
      // BaseTransport.drainOfflineQueue: straight onto the transport,
      // bypassing StreamingHandle.send().
      queue.drain((data) => {
        pushed.push(typeof data === 'string' ? data : JSON.stringify(data));
      });
    },
  };
}

function wrapper(atmosphere: Atmosphere) {
  return ({ children }: { children: ReactNode }) =>
    createElement(AtmosphereProvider, { instance: atmosphere }, children);
}

const request: AtmosphereRequest = {
  url: 'https://example.com/atmosphere/classroom/math',
  transport: 'websocket',
};

describe('useStreamingRN — a send that cannot go out is never silently dropped', () => {
  beforeEach(() => {
    emitNetInfo(true);
  });

  it('rejects and surfaces the send when the device is offline and no queue is configured', async () => {
    const wire = createFakeWire();
    const onError = vi.fn();
    const { result } = renderHook(
      () => useStreamingRN({ request, onError }),
      { wrapper: wrapper(wire.atmosphere) },
    );

    await act(async () => { await Promise.resolve(); });
    expect(result.current.canSend).toBe(true);

    act(() => emitNetInfo(false));
    expect(result.current.isConnected).toBe(false);
    expect(result.current.canSend).toBe(false);

    let outcome: StreamingSendResult | undefined;
    act(() => { outcome = result.current.send('are we there yet'); });

    // The message did not reach the wire — that part was never in doubt.
    expect(wire.pushed).toHaveLength(0);
    // ...but it must not have gone quiet either. THIS is the defect: the old
    // `send` early-returned, so nothing was queued, `error` stayed null, no
    // callback fired, and the return type was void. Assert the silence first
    // — it is the symptom a user actually experiences.
    expect(result.current.error).not.toBeNull();
    expect(onError).toHaveBeenCalledTimes(1);
    expect(outcome?.outcome).toBe('rejected');
    expect(outcome?.reason).toContain('offline');
    expect(result.current.error).toBe(outcome?.reason);
    expect(onError).toHaveBeenCalledWith(outcome?.reason);
    expect(result.current.isStreaming).toBe(false);
  });

  it('queues the send when the device is offline and request.offlineQueue is configured', async () => {
    const wire = createFakeWire();
    const queue = new OfflineQueue();
    const onError = vi.fn();
    const { result } = renderHook(
      () => useStreamingRN({ request: { ...request, offlineQueue: queue }, onError }),
      { wrapper: wrapper(wire.atmosphere) },
    );

    await act(async () => { await Promise.resolve(); });

    act(() => emitNetInfo(false));

    let outcome: StreamingSendResult | undefined;
    act(() => { outcome = result.current.send('what is a derivative'); });

    // The message is somewhere. Before the fix it was nowhere: not on the
    // wire, not in the queue, not in an error — just gone.
    expect(wire.pushed).toHaveLength(0);
    expect(queue.size).toBe(1);
    expect(queue.messages[0].data).toBe('what is a derivative');
    expect(outcome?.outcome).toBe('queued');
    expect(outcome?.queueSize).toBe(1);
    expect(outcome?.messageId).toBeTruthy();
    // Queued, not failed: no error banner, no onError.
    expect(result.current.error).toBeNull();
    expect(onError).not.toHaveBeenCalled();

    // ...and it goes out for real once the connection returns.
    act(() => {
      emitNetInfo(true);
      wire.reopenAndDrain(queue);
    });
    expect(wire.pushed).toEqual(['what is a derivative']);
    expect(queue.size).toBe(0);
  });

  it('gates on transport state, not just NetInfo, when the server goes away', async () => {
    // The exact simulator scenario: backend stopped, device network fine.
    // NetInfo keeps reporting isConnected=true, so a NetInfo-only gate lets
    // the send through into a dead socket.
    const wire = createFakeWire();
    const queue = new OfflineQueue();
    const { result } = renderHook(
      () => useStreamingRN({ request: { ...request, offlineQueue: queue } }),
      { wrapper: wrapper(wire.atmosphere) },
    );

    await act(async () => { await Promise.resolve(); });
    expect(result.current.canSend).toBe(true);

    act(() => wire.dropConnection());

    expect(result.current.isConnected).toBe(true);        // radio is fine
    expect(result.current.connectionStatus.phase).toBe('closed');
    expect(result.current.canSend).toBe(false);           // stream is not

    let outcome: StreamingSendResult | undefined;
    act(() => { outcome = result.current.send('still there?'); });

    expect(wire.pushed).toHaveLength(0);
    expect(queue.size).toBe(1);
    expect(outcome?.outcome).toBe('queued');
  });

  it('renders the answer to a message that was queued while offline', async () => {
    // The drain path pushes bytes through the transport without going
    // through StreamingHandle.send(), so the handle's stale session id would
    // filter out every frame of the reply unless send() resets it on queue.
    const wire = createFakeWire();
    const queue = new OfflineQueue();
    const { result } = renderHook(
      () => useStreamingRN({ request: { ...request, offlineQueue: queue } }),
      { wrapper: wrapper(wire.atmosphere) },
    );

    await act(async () => { await Promise.resolve(); });

    // Turn one, online: establishes server session S1 on the handle.
    act(() => { result.current.send('first question'); });
    act(() => {
      wire.deliver({ event: 'text-delta', data: { text: 'answer one' }, sessionId: 'S1', seq: 1 });
      wire.deliver({ event: 'complete', data: {}, sessionId: 'S1', seq: 2 });
    });
    expect(result.current.fullText).toBe('answer one');

    // Turn two, offline: queued.
    act(() => emitNetInfo(false));
    act(() => { result.current.reset(); });
    act(() => { result.current.send('second question'); });
    expect(queue.size).toBe(1);

    // Back online — the server answers the drained prompt under a fresh
    // session id.
    act(() => {
      emitNetInfo(true);
      wire.reopenAndDrain(queue);
    });
    act(() => {
      wire.deliver({ event: 'text-delta', data: { text: 'answer two' }, sessionId: 'S2', seq: 1 });
      wire.deliver({ event: 'complete', data: {}, sessionId: 'S2', seq: 2 });
    });

    expect(result.current.fullText).toBe('answer two');
  });

  it('still reports sent, and reaches the wire, on the happy path', async () => {
    const wire = createFakeWire();
    const { result } = renderHook(
      () => useStreamingRN({ request }),
      { wrapper: wrapper(wire.atmosphere) },
    );

    await act(async () => { await Promise.resolve(); });

    let outcome: StreamingSendResult | undefined;
    act(() => { outcome = result.current.send('hello'); });

    expect(wire.pushed).toEqual(['hello']);
    expect(result.current.error).toBeNull();
    expect(result.current.isStreaming).toBe(true);
    expect(outcome?.outcome).toBe('sent');
  });
});
