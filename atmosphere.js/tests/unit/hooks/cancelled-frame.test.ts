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
import { useChat } from '../../../src/hooks/react/useChat';
import { useStreamingRN } from '../../../src/hooks/react-native/useStreamingRN';
import { useChatRN } from '../../../src/hooks/react-native/useChatRN';
import { createStreamingStore } from '../../../src/hooks/svelte/streaming';

vi.mock('vue', async () => {
  const actual = await vi.importActual<typeof import('vue')>('vue');
  return { ...actual, onUnmounted: () => {} };
});
const { useStreaming: useVueStreaming } = await import('../../../src/hooks/vue/useStreaming');

/**
 * A run the server cuts short (the client that started it disconnected, an
 * operator cancelled it, the idle sweep reaped it) ends with one `cancelled`
 * frame. Every streaming binding must end such a run cleanly: streaming
 * stops, the reason is exposed, and it is not reported as an error — nor left
 * hanging in a "streaming" state waiting for a `complete` that never comes.
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

function frame(body: Record<string, unknown>) {
  return { responseBody: JSON.stringify({ sessionId: 's1', ...body }) };
}

const partial = frame({ type: 'streaming-text', data: 'partial ', seq: 1 });
const cancelled = frame({ type: 'cancelled', data: 'disconnect', seq: 2 });

const hooks = [
  ['useStreaming', () => useStreaming({ request })],
  ['useStreamingRN', () => useStreamingRN({ request })],
] as const;

describe('streaming bindings end a cancelled run cleanly', () => {
  let mock: ReturnType<typeof createMockAtmosphere>;

  beforeEach(() => {
    mock = createMockAtmosphere();
  });

  for (const [name, useHook] of hooks) {
    it(`${name}: the cancelled frame stops streaming, exposes the reason and is not an error`, async () => {
      const { result } = renderHook(useHook, { wrapper: wrapper(mock.atmosphere) });
      await vi.waitFor(() => expect(mock.handlers().message).toBeDefined());
      act(() => mock.handlers().open?.());

      act(() => mock.handlers().message?.(partial));
      expect(result.current.isStreaming).toBe(true);

      act(() => mock.handlers().message?.(cancelled));
      expect(result.current.isStreaming).toBe(false);
      expect(result.current.cancelled).toBe('disconnect');
      expect(result.current.error).toBeNull();
      expect(result.current.fullText).toBe('partial ');
      expect(result.current.stats?.status).toBe('cancelled');

      act(() => result.current.reset());
      expect(result.current.cancelled).toBeNull();
    });
  }

  for (const [name, useHook] of [
    ['useChat', () => useChat({ request })],
    ['useChatRN', () => useChatRN({ request })],
  ] as const) {
    it(`${name}: a cancelled answer ends as cancelled, not complete or stuck`, async () => {
      const { result } = renderHook(useHook, { wrapper: wrapper(mock.atmosphere) });
      await vi.waitFor(() => expect(mock.handlers().message).toBeDefined());
      act(() => mock.handlers().open?.());
      // useChatRN sends only once the stream is open.
      await vi.waitFor(() => expect((result.current as { canSend?: boolean }).canSend ?? true).toBe(true));

      act(() => {
        result.current.append('explain');
      });
      // A run cancelled before it streamed a word must not stay "submitted".
      act(() => mock.handlers().message?.(cancelled));

      const assistant = result.current.messages.find((m) => m.role === 'assistant');
      expect(assistant?.status).toBe('cancelled');
      expect(result.current.error).toBeNull();
    });
  }

  it('Vue useStreaming: the cancelled frame stops streaming and exposes the reason', async () => {
    const streaming = useVueStreaming(request, mock.atmosphere);
    await vi.waitFor(() => expect(mock.handlers().message).toBeDefined());

    mock.handlers().message?.(partial);
    expect(streaming.isStreaming.value).toBe(true);
    mock.handlers().message?.(cancelled);

    expect(streaming.isStreaming.value).toBe(false);
    expect(streaming.cancelled.value).toBe('disconnect');
    expect(streaming.error.value).toBeNull();
  });

  it('Svelte streaming store: the cancelled frame stops streaming and exposes the reason', async () => {
    const { store } = createStreamingStore(request, mock.atmosphere);
    let snapshot: { isStreaming: boolean; cancelled: string | null; error: string | null } =
      { isStreaming: false, cancelled: null, error: null };
    const unsubscribe = store.subscribe((s) => { snapshot = s; });
    await vi.waitFor(() => expect(mock.handlers().message).toBeDefined());

    mock.handlers().message?.(partial);
    expect(snapshot.isStreaming).toBe(true);
    mock.handlers().message?.(cancelled);

    expect(snapshot.isStreaming).toBe(false);
    expect(snapshot.cancelled).toBe('disconnect');
    expect(snapshot.error).toBeNull();
    unsubscribe();
  });
});
