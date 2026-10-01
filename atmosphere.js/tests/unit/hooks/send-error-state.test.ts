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
import { BaseTransport } from '../../../src/transports/base';
import { isSendError } from '../../../src/utils/send-error';
import { AtmosphereProvider } from '../../../src/hooks/react/provider';
import { useAtmosphere as useReactAtmosphere } from '../../../src/hooks/react/useAtmosphere';
import { useStreaming as useReactStreaming } from '../../../src/hooks/react/useStreaming';
import { createAtmosphereStore } from '../../../src/hooks/svelte/atmosphere';

vi.mock('vue', async () => {
  const actual = await vi.importActual<typeof import('vue')>('vue');
  return { ...actual, onUnmounted: () => {} };
});
const { useAtmosphere: useVueAtmosphere } = await import('../../../src/hooks/vue/useAtmosphere');
const { useStreaming: useVueStreaming } = await import('../../../src/hooks/vue/useStreaming');

/**
 * An HTTP transport reports a POST the server refused to the `error` handler
 * as an `AtmosphereSendError`: that message was not delivered, but the
 * connection is still up. The framework hooks used to treat every `error` as
 * a connection failure and flip their state to 'error' while the transport was
 * still connected.
 */

function sendError(): Error {
  const error = new Error('long-polling POST send failed with status 400');
  error.name = BaseTransport.SEND_ERROR_NAME;
  return error;
}

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

const request: AtmosphereRequest = { url: 'http://localhost/ai', transport: 'long-polling' };

function wrapper(atmosphere: Atmosphere) {
  return ({ children }: { children: ReactNode }) =>
    createElement(AtmosphereProvider, { instance: atmosphere }, children);
}

describe('framework hooks keep the connection state on an undelivered message', () => {
  let mock: ReturnType<typeof createMockAtmosphere>;

  beforeEach(() => {
    mock = createMockAtmosphere();
  });

  it('recognises the send error by name only', () => {
    expect(isSendError(sendError())).toBe(true);
    expect(isSendError(new Error('connection lost'))).toBe(false);
    expect(isSendError(null)).toBe(false);
  });

  it('React useAtmosphere records the error but stays connected', async () => {
    const onError = vi.fn();
    const { result } = renderHook(() => useReactAtmosphere({ request, onError }),
      { wrapper: wrapper(mock.atmosphere) });
    await vi.waitFor(() => expect(mock.handlers().error).toBeDefined());
    act(() => mock.handlers().open?.());
    expect(result.current.state).toBe('connected');

    act(() => mock.handlers().error?.(sendError()));
    expect(result.current.state).toBe('connected');
    expect(result.current.error?.name).toBe('AtmosphereSendError');
    expect(onError).toHaveBeenCalledOnce();

    act(() => mock.handlers().error?.(new Error('connection lost')));
    expect(result.current.state).toBe('error');
  });

  it('React useStreaming stops streaming but stays connected', async () => {
    const { result } = renderHook(() => useReactStreaming({ request }),
      { wrapper: wrapper(mock.atmosphere) });
    await vi.waitFor(() => expect(mock.handlers().error).toBeDefined());
    act(() => mock.handlers().open?.());
    await vi.waitFor(() => expect(result.current.connectionState).toBe('connected'));

    act(() => mock.handlers().error?.(sendError()));
    expect(result.current.connectionState).toBe('connected');
    expect(result.current.isStreaming).toBe(false);
    expect(result.current.error).toBe('long-polling POST send failed with status 400');

    act(() => mock.handlers().error?.(new Error('connection lost')));
    expect(result.current.connectionState).toBe('error');
  });

  it('Vue useAtmosphere records the error but stays connected', async () => {
    const { state, error } = useVueAtmosphere(request, mock.atmosphere);
    await vi.waitFor(() => expect(mock.handlers().error).toBeDefined());
    mock.handlers().open?.();

    mock.handlers().error?.(sendError());
    expect(state.value).toBe('connected');
    expect(error.value?.name).toBe('AtmosphereSendError');

    mock.handlers().error?.(new Error('connection lost'));
    expect(state.value).toBe('error');
  });

  it('Vue useStreaming stops streaming but stays connected', async () => {
    const { connectionState, isStreaming, error } = useVueStreaming(request, mock.atmosphere);
    await vi.waitFor(() => expect(mock.handlers().error).toBeDefined());
    mock.handlers().open?.();
    await vi.waitFor(() => expect(connectionState.value).toBe('connected'));

    mock.handlers().error?.(sendError());
    expect(connectionState.value).toBe('connected');
    expect(isStreaming.value).toBe(false);
    expect(error.value).toBe('long-polling POST send failed with status 400');

    mock.handlers().error?.(new Error('connection lost'));
    expect(connectionState.value).toBe('error');
  });

  it('Svelte atmosphere store records the error but stays connected', async () => {
    const { store } = createAtmosphereStore(request, mock.atmosphere);
    let snapshot: { state: string; error: Error | null } = { state: '', error: null };
    const unsubscribe = store.subscribe((value) => { snapshot = value; });
    await vi.waitFor(() => expect(mock.handlers().error).toBeDefined());
    mock.handlers().open?.();

    mock.handlers().error?.(sendError());
    expect(snapshot.state).toBe('connected');
    expect(snapshot.error?.name).toBe('AtmosphereSendError');

    mock.handlers().error?.(new Error('connection lost'));
    expect(snapshot.state).toBe('error');
    unsubscribe();
  });
});
