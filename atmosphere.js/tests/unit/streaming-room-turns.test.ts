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

import { describe, it, expect, vi } from 'vitest';
import { subscribeStreaming } from '../../src/streaming';
import type { Atmosphere } from '../../src/core/atmosphere';
import type { StreamingHandlers } from '../../src/streaming/types';
import type { SubscriptionHandlers } from '../../src/types';

/**
 * A member of an `@AiEndpoint(broadcastReply = true)` room receives every
 * classmate's turn, each under the session id of the room session that
 * streamed it, whether or not it ever sends a prompt itself. And a member that
 * reconnects in the middle of a turn may be written that turn's prompt again,
 * by the run's replay, under the run id the turn's `X-Atmosphere-Run-Id`
 * metadata frame named.
 */
describe('subscribeStreaming in a broadcastReply room', () => {
  let handlers: SubscriptionHandlers<string>;
  const frame = (body: Record<string, unknown>) => handlers.message!({
    responseBody: JSON.stringify(body),
    status: 200,
    reasonPhrase: 'OK',
    messages: [],
    headers: {},
    state: 'messageReceived',
    transport: 'websocket',
    error: null,
  });

  async function listen(h: StreamingHandlers) {
    const subscription = {
      id: 'sub-1',
      uuid: 'listener',
      state: 'connected' as const,
      push: vi.fn(),
      close: vi.fn().mockResolvedValue(undefined),
      suspend: vi.fn(),
      resume: vi.fn().mockResolvedValue(undefined),
      on: vi.fn(),
      off: vi.fn(),
    };
    const atmosphere = {
      subscribe: vi.fn().mockImplementation((_req, captured) => {
        handlers = captured;
        return Promise.resolve(subscription);
      }),
    } as unknown as Atmosphere;
    return subscribeStreaming(atmosphere, { url: '/atmosphere/classroom/math', transport: 'websocket' }, h);
  }

  /** One classmate's whole turn, as the room broadcasts it. */
  function turn(session: string, run: string, from: string, prompt: string, text: string) {
    frame({ type: 'metadata', key: 'X-Atmosphere-Run-Id', value: run, sessionId: session, seq: 1 });
    frame({ type: 'prompt', text: prompt, from, sessionId: session, seq: 2 });
    frame({ type: 'streaming-text', data: text, sessionId: session, seq: 3 });
    frame({ type: 'complete', sessionId: session, seq: 4 });
  }

  it('a member that never sends receives a second classmate\'s turn after the first', async () => {
    const events: string[] = [];
    await listen({
      onPrompt: (text, from) => events.push(`prompt:${from}:${text}`),
      onStreamingText: (text) => events.push(`text:${text}`),
      onComplete: () => events.push('complete'),
    });

    turn('room-session-1', 'run-1', 'alice', 'What is 2+2?', 'four');
    turn('room-session-2', 'run-2', 'bob', 'And 3+3?', 'six');

    expect(events).toEqual([
      'prompt:alice:What is 2+2?', 'text:four', 'complete',
      'prompt:bob:And 3+3?', 'text:six', 'complete',
    ]);
  });

  it('counts each received turn alone in its session stats', async () => {
    const totals: number[] = [];
    await listen({ onSessionComplete: (stats) => totals.push(stats.totalStreamingTexts) });

    turn('room-session-1', 'run-1', 'alice', 'q1', 'a');
    turn('room-session-2', 'run-2', 'bob', 'q2', 'b');

    expect(totals).toEqual([1, 1]);
  });

  it('does not take a frame of a turn it finished again as a new turn', async () => {
    const onStreamingText = vi.fn();
    const onComplete = vi.fn();
    await listen({ onStreamingText, onComplete });

    turn('room-session-1', 'run-1', 'alice', 'q1', 'a');
    // The same frames again, as a cache that kept them would hand them over.
    frame({ type: 'streaming-text', data: 'a', sessionId: 'room-session-1', seq: 3 });
    frame({ type: 'complete', sessionId: 'room-session-1', seq: 4 });

    expect(onStreamingText).toHaveBeenCalledTimes(1);
    expect(onComplete).toHaveBeenCalledTimes(1);
  });

  it('keeps following an unfinished turn while another one starts', async () => {
    const texts: string[] = [];
    await listen({ onStreamingText: (text) => texts.push(text) });

    frame({ type: 'streaming-text', data: 'mine', sessionId: 'room-session-1', seq: 1 });
    frame({ type: 'streaming-text', data: 'theirs', sessionId: 'room-session-2', seq: 1 });
    frame({ type: 'streaming-text', data: ' too', sessionId: 'room-session-1', seq: 2 });

    expect(texts).toEqual(['mine', ' too']);
  });

  it('reports the prompt of a turn once when the run replays it under the run id', async () => {
    const onPrompt = vi.fn();
    await listen({ onPrompt });

    frame({ type: 'metadata', key: 'X-Atmosphere-Run-Id', value: 'run-1', sessionId: 'room-session-1', seq: 1 });
    frame({ type: 'prompt', text: 'What is 2+2?', from: 'alice', sessionId: 'room-session-1', seq: 2 });
    frame({ type: 'streaming-text', data: 'fo', sessionId: 'room-session-1', seq: 3 });
    // Reconnected mid-answer: the run's replay starts again from its prompt.
    frame({ type: 'prompt', text: 'What is 2+2?', from: 'alice', sessionId: 'run-1', seq: 1 });

    expect(onPrompt).toHaveBeenCalledTimes(1);
  });

  it('reports a prompt frame written twice once', async () => {
    const onPrompt = vi.fn();
    await listen({ onPrompt });

    const echo = { type: 'prompt', text: 'hi', from: 'alice', sessionId: 'room-session-1', seq: 2 };
    frame(echo);
    frame(echo);

    expect(onPrompt).toHaveBeenCalledTimes(1);
  });

  it('ends the turn it follows with the terminal frame the run\'s replay writes under the run id', async () => {
    const events: string[] = [];
    await listen({
      onStreamingText: (text) => events.push(`text:${text}`),
      onComplete: () => events.push('complete'),
    });

    frame({ type: 'metadata', key: 'X-Atmosphere-Run-Id', value: 'run-1', sessionId: 'room-session-1', seq: 1 });
    frame({ type: 'streaming-text', data: 'fo', sessionId: 'room-session-1', seq: 3 });
    // The connection dropped before the room's complete; the replay ends the run.
    frame({ type: 'streaming-text', data: 'fo', sessionId: 'run-1', seq: 2 });
    frame({ type: 'complete', sessionId: 'run-1', seq: 3 });
    // The next classmate's turn is received.
    frame({ type: 'streaming-text', data: 'six', sessionId: 'room-session-2', seq: 3 });

    expect(events).toEqual(['text:fo', 'complete', 'text:six']);
  });
});
