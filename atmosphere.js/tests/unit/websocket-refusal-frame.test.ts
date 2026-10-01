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
import type { AtmosphereRequest, AtmosphereResponse, Subscription } from '../../src/types';
import type { Atmosphere } from '../../src/core/atmosphere';
import { subscribeStreaming } from '../../src/streaming';
import { AtmosphereProvider } from '../../src/hooks/react/provider';
import { useStreaming as useReactStreaming } from '../../src/hooks/react/useStreaming';

vi.mock('vue', async () => {
  const actual = await vi.importActual<typeof import('vue')>('vue');
  return { ...actual, onUnmounted: () => {} };
});
const { useStreaming: useVueStreaming } = await import('../../src/hooks/vue/useStreaming');

/**
 * An Atmosphere AI endpoint answers a WebSocket prompt whose connection it no
 * longer holds with a terminal error frame over the socket
 * (AiEndpointHandler.webSocketRefusalFrame, pinned in
 * AiEndpointHandlerPromptRepollTest). subscribeStreaming reports only frames
 * that name a session, so the frame names one of its own: without it the
 * refusal was dropped and the hooks kept `isStreaming` true forever.
 */
const REFUSAL_MESSAGE = 'Prompt not delivered: this connection is no longer registered on the server;'
  + ' reconnect and send it again';
const REFUSAL_FRAME = `{"type":"error","data":"${REFUSAL_MESSAGE}","sessionId":"0b6c1f8e-refused","seq":1}`;

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
  return { atmosphere, sub, handlers: () => handlers };
}

function frame(body: string): AtmosphereResponse<string> {
  return { responseBody: body } as unknown as AtmosphereResponse<string>;
}

const request: AtmosphereRequest = { url: 'ws://localhost/ai', transport: 'websocket' };

describe('the WebSocket refusal frame ends the pending turn', () => {
  let mock: ReturnType<typeof createMockAtmosphere>;

  beforeEach(() => {
    mock = createMockAtmosphere();
  });

  it('subscribeStreaming reports it to onError', async () => {
    const onError = vi.fn();
    const onSessionComplete = vi.fn();
    const handle = await subscribeStreaming(mock.atmosphere, request, { onError, onSessionComplete });

    handle.send('hi');
    expect(mock.sub.push).toHaveBeenCalledWith('hi');
    mock.handlers().message?.(frame(REFUSAL_FRAME));

    expect(onError).toHaveBeenCalledWith(REFUSAL_MESSAGE);
    expect(onSessionComplete).toHaveBeenCalledWith(expect.objectContaining({ status: 'error' }), {});
  });

  it('subscribeStreaming drops the same frame without a session (why the frame names one)', async () => {
    const onError = vi.fn();
    const handle = await subscribeStreaming(mock.atmosphere, request, { onError });

    handle.send('hi');
    mock.handlers().message?.(frame(`{"type":"error","data":"${REFUSAL_MESSAGE}"}`));

    expect(onError).not.toHaveBeenCalled();
  });

  it('React useStreaming stops streaming and records the error', async () => {
    const wrapper = ({ children }: { children: ReactNode }) =>
      createElement(AtmosphereProvider, { instance: mock.atmosphere }, children);
    const { result } = renderHook(() => useReactStreaming({ request }), { wrapper });
    await vi.waitFor(() => expect(mock.handlers().message).toBeDefined());
    await vi.waitFor(() => expect(typeof result.current.send).toBe('function'));
    // The hook adopts the handle once subscribe resolves.
    await act(async () => { await Promise.resolve(); });

    act(() => result.current.send('hi'));
    expect(mock.sub.push).toHaveBeenCalledWith('hi');
    expect(result.current.isStreaming).toBe(true);

    act(() => mock.handlers().message?.(frame(REFUSAL_FRAME)));
    expect(result.current.isStreaming).toBe(false);
    expect(result.current.error).toBe(REFUSAL_MESSAGE);
  });

  it('Vue useStreaming stops streaming and records the error', async () => {
    const { send, isStreaming, error } = useVueStreaming(request, mock.atmosphere);
    await vi.waitFor(() => expect(mock.handlers().message).toBeDefined());
    await Promise.resolve();

    send('hi');
    expect(mock.sub.push).toHaveBeenCalledWith('hi');
    expect(isStreaming.value).toBe(true);

    mock.handlers().message?.(frame(REFUSAL_FRAME));
    expect(isStreaming.value).toBe(false);
    expect(error.value).toBe(REFUSAL_MESSAGE);
  });
});
