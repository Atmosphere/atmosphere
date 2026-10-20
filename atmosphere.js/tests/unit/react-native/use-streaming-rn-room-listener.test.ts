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

import { describe, it, expect } from 'vitest';
import { createElement, type ReactNode } from 'react';
import { renderHook, act } from '@testing-library/react';
import { AtmosphereProvider, useStreamingRN } from '../../../src/hooks/react-native';
import type {
  AtmosphereRequest,
  AtmosphereResponse,
  Subscription,
  SubscriptionHandlers,
} from '../../../src/types';
import type { Atmosphere } from '../../../src/core/atmosphere';

/**
 * A student on the Expo classroom client who only listens: every classmate's
 * turn in the `@AiEndpoint(broadcastReply = true)` room streams into the hook,
 * not only the first one, although the hook never sends.
 */
function fakeRoom() {
  let handlers: SubscriptionHandlers<string> = {};
  const subscription: Subscription = {
    id: 'room-sub',
    uuid: 'listener',
    state: 'connected',
    push: () => {},
    close: async () => {},
    suspend: () => {},
    resume: async () => {},
    on: () => {},
    off: () => {},
  };
  const atmosphere = {
    subscribe: async (_request: AtmosphereRequest, h: SubscriptionHandlers<string>) => {
      handlers = h;
      handlers.open?.({ transport: 'websocket' } as AtmosphereResponse<string>);
      return subscription;
    },
  } as unknown as Atmosphere;
  const deliver = (frame: Record<string, unknown>) =>
    handlers.message?.({ responseBody: JSON.stringify(frame) } as AtmosphereResponse<string>);
  const turn = (session: string, from: string, prompt: string, words: string[]) => {
    deliver({ type: 'metadata', key: 'X-Atmosphere-Run-Id', value: `run-of-${session}`, sessionId: session, seq: 1 });
    deliver({ type: 'prompt', text: prompt, from, sessionId: session, seq: 2 });
    words.forEach((word, i) => deliver({ type: 'streaming-text', data: word, sessionId: session, seq: 3 + i }));
    deliver({ type: 'complete', sessionId: session, seq: 3 + words.length });
  };
  return { atmosphere, turn };
}

describe('useStreamingRN — a member of a broadcastReply room that never sends', () => {
  it('receives a second classmate\'s turn after the first', async () => {
    const room = fakeRoom();
    const prompts: string[] = [];
    const { result } = renderHook(
      () => useStreamingRN({
        request: { url: 'https://example.com/atmosphere/classroom/math', transport: 'websocket' },
        onPrompt: (text, from) => prompts.push(`${from}:${text}`),
      }),
      {
        wrapper: ({ children }: { children: ReactNode }) =>
          createElement(AtmosphereProvider, { instance: room.atmosphere }, children),
      },
    );
    await act(async () => { await Promise.resolve(); });

    act(() => room.turn('room-session-1', 'alice', 'What is 2+2?', ['Four', '.']));
    expect(result.current.fullText).toBe('Four.');
    expect(result.current.isStreaming).toBe(false);

    act(() => room.turn('room-session-2', 'bob', 'And 3+3?', [' Six', '.']));

    expect(prompts).toEqual(['alice:What is 2+2?', 'bob:And 3+3?']);
    expect(result.current.fullText).toBe('Four. Six.');
    expect(result.current.isStreaming).toBe(false);
  });
});
