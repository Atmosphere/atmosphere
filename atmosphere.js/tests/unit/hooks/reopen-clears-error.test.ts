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
import type { AtmosphereRequest, Subscription } from '../../../src/types';
import type { Atmosphere } from '../../../src/core/atmosphere';
import { AtmosphereProvider } from '../../../src/hooks/react/provider';
import { useStreaming } from '../../../src/hooks/react/useStreaming';
import { useStreamingRN } from '../../../src/hooks/react-native/useStreamingRN';
import { createStreamingStore } from '../../../src/hooks/svelte/streaming';

vi.mock('vue', async () => {
  const actual = await vi.importActual<typeof import('vue')>('vue');
  return { ...actual, onUnmounted: () => {} };
});
const { useStreaming: useVueStreaming } = await import('../../../src/hooks/vue/useStreaming');

/**
 * A dropped socket reports `WebSocket connection error` through the
 * transport's `error` handler. Once the transport reconnects, that error is
 * no longer true, yet both streaming hooks only cleared `error` in `send()`
 * and `reset()` — so the UI showed "Connected" next to a stale red
 * connection-error banner until the next prompt. The Vue and Svelte
 * streaming bindings carry the same contract (mode parity).
 */

function createMockAtmosphere() {
  let handlers: Record<string, (...args: unknown[]) => void> = {};
  const sub = {
    id: 'test-sub', state: 'connected', push: vi.fn(), close: vi.fn(async () => {}),
    suspend: vi.fn(), resume: vi.fn(async () => {}), on: vi.fn(), off: vi.fn(),
  } as unknown as Subscription;
  const atmosphere = {
    subscribe: vi.fn(async (_req: AtmosphereRequest, h: Record<string, unknown>) => {
      handlers = h as typeof handlers;
      return sub;
    }),
  } as unknown as Atmosphere;
  return { atmosphere, handlers: () => handlers };
}

const request: AtmosphereRequest = { url: 'http://localhost/ai', transport: 'websocket' };

function wrapper(atmosphere: Atmosphere) {
  return ({ children }: { children: ReactNode }) =>
    createElement(AtmosphereProvider, { instance: atmosphere }, children);
}

const hooks = [
  ['useStreaming', () => useStreaming({ request })],
  ['useStreamingRN', () => useStreamingRN({ request })],
] as const;

describe('streaming hooks clear the connection error once the transport is back', () => {
  let mock: ReturnType<typeof createMockAtmosphere>;

  beforeEach(() => {
    mock = createMockAtmosphere();
  });

  for (const [name, useHook] of hooks) {
    it(`${name}: an error followed by a reopen leaves error === null`, async () => {
      const { result } = renderHook(useHook, { wrapper: wrapper(mock.atmosphere) });
      await vi.waitFor(() => expect(mock.handlers().reopen).toBeDefined());
      act(() => mock.handlers().open?.());

      act(() => {
        mock.handlers().error?.(new Error('WebSocket connection error'));
        mock.handlers().close?.();
        mock.handlers().reconnect?.();
      });
      expect(result.current.error).toBe('WebSocket connection error');

      act(() => mock.handlers().reopen?.());
      expect(result.current.error).toBeNull();
    });

    it(`${name}: an error followed by a fresh open leaves error === null`, async () => {
      const { result } = renderHook(useHook, { wrapper: wrapper(mock.atmosphere) });
      await vi.waitFor(() => expect(mock.handlers().open).toBeDefined());

      act(() => mock.handlers().error?.(new Error('WebSocket connection error')));
      expect(result.current.error).toBe('WebSocket connection error');

      act(() => mock.handlers().open?.());
      expect(result.current.error).toBeNull();
    });
  }

  it('Vue useStreaming: an error followed by a reopen leaves error === null', async () => {
    const { error } = useVueStreaming(request, mock.atmosphere);
    await vi.waitFor(() => expect(mock.handlers().reopen).toBeDefined());
    mock.handlers().open?.();

    mock.handlers().error?.(new Error('WebSocket connection error'));
    mock.handlers().close?.();
    expect(error.value).toBe('WebSocket connection error');

    mock.handlers().reopen?.();
    expect(error.value).toBeNull();
  });

  it('Svelte streaming store: an error followed by a reopen leaves error === null', async () => {
    const { store } = createStreamingStore(request, mock.atmosphere);
    let snapshot: { error: string | null } = { error: null };
    const unsubscribe = store.subscribe((value) => { snapshot = value; });
    await vi.waitFor(() => expect(mock.handlers().reopen).toBeDefined());
    mock.handlers().open?.();

    mock.handlers().error?.(new Error('WebSocket connection error'));
    mock.handlers().close?.();
    expect(snapshot.error).toBe('WebSocket connection error');

    mock.handlers().reopen?.();
    expect(snapshot.error).toBeNull();
    unsubscribe();
  });
});
