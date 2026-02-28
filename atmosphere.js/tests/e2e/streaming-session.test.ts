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
import { subscribeStreaming } from '../../src/streaming';
import { Atmosphere } from '../../src/core/atmosphere';
import type { SessionStats, RoutingInfo } from '../../src/streaming/types';
import type { SubscriptionHandlers, Subscription } from '../../src/types';

/**
 * E2E-style integration tests that exercise the full streaming lifecycle:
 * connect → send → tokens → metadata → complete → stats → reset → send again.
 *
 * These differ from the unit tests in streaming.test.ts by simulating full
 * multi-turn conversations with interleaved metadata, routing info, and
 * verifying the complete integrated path through subscribeStreaming.
 */

function makeResponse(json: string) {
  return {
    responseBody: json,
    status: 200,
    reasonPhrase: 'OK',
    messages: [],
    headers: {},
    state: 'messageReceived' as const,
    transport: 'websocket' as const,
    error: null,
  };
}

function token(data: string, seq: number, sessionId = 's1') {
  return makeResponse(JSON.stringify({ type: 'token', data, sessionId, seq }));
}

function metadata(key: string, value: unknown, seq: number, sessionId = 's1') {
  return makeResponse(JSON.stringify({ type: 'metadata', key, value, sessionId, seq }));
}

function complete(seq: number, sessionId = 's1', data?: string) {
  return makeResponse(JSON.stringify({ type: 'complete', sessionId, seq, ...(data !== undefined && { data }) }));
}

function error(data: string, seq: number, sessionId = 's1') {
  return makeResponse(JSON.stringify({ type: 'error', data, sessionId, seq }));
}

describe('streaming session lifecycle', () => {
  let mockAtmosphere: Atmosphere;
  let capturedHandlers: SubscriptionHandlers<string>;
  let mockSubscription: Subscription;

  beforeEach(() => {
    mockSubscription = {
      id: 'sub-1',
      state: 'connected' as const,
      push: vi.fn(),
      close: vi.fn().mockResolvedValue(undefined),
      suspend: vi.fn(),
      resume: vi.fn().mockResolvedValue(undefined),
      on: vi.fn(),
      off: vi.fn(),
    };

    mockAtmosphere = {
      subscribe: vi.fn().mockImplementation((_req, handlers) => {
        capturedHandlers = handlers;
        return Promise.resolve(mockSubscription);
      }),
    } as unknown as Atmosphere;
  });

  it('should handle a full conversation turn with tokens, routing, and stats', async () => {
    const onToken = vi.fn();
    const onComplete = vi.fn();
    const onMetadata = vi.fn();
    const onSessionComplete = vi.fn();

    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onToken, onComplete, onMetadata, onSessionComplete },
    );

    // Simulate 5 tokens arriving
    capturedHandlers.message!(token('Hello', 1));
    capturedHandlers.message!(token(' ', 2));
    capturedHandlers.message!(token('world', 3));
    capturedHandlers.message!(token('!', 4));
    capturedHandlers.message!(token(' How', 5));

    // Routing metadata arrives mid-stream
    capturedHandlers.message!(metadata('routing.model', 'gpt-4o', 6));
    capturedHandlers.message!(metadata('routing.cost', 0.03, 7));

    // Stream completes
    capturedHandlers.message!(complete(8));

    expect(onToken).toHaveBeenCalledTimes(5);
    expect(onComplete).toHaveBeenCalledTimes(1);
    expect(onMetadata).toHaveBeenCalledTimes(2);
    expect(onSessionComplete).toHaveBeenCalledTimes(1);

    const stats: SessionStats = onSessionComplete.mock.calls[0][0];
    expect(stats.totalTokens).toBe(5);
    expect(stats.status).toBe('complete');

    const routing: RoutingInfo = onSessionComplete.mock.calls[0][1];
    expect(routing.model).toBe('gpt-4o');
    expect(routing.cost).toBe(0.03);
  });

  it('should reset stats between turns in a multi-turn conversation', async () => {
    const onSessionComplete = vi.fn();

    const handle = await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onSessionComplete },
    );

    // First turn: 3 tokens
    capturedHandlers.message!(token('aaa', 1));
    capturedHandlers.message!(token('bbb', 2));
    capturedHandlers.message!(token('ccc', 3));
    capturedHandlers.message!(metadata('routing.model', 'gpt-4o', 4));
    capturedHandlers.message!(complete(5));

    expect(onSessionComplete).toHaveBeenCalledTimes(1);
    const firstStats: SessionStats = onSessionComplete.mock.calls[0][0];
    expect(firstStats.totalTokens).toBe(3);
    expect(firstStats.status).toBe('complete');
    expect(onSessionComplete.mock.calls[0][1].model).toBe('gpt-4o');

    // Send second prompt (stats reset)
    handle.send('next question');

    // Second turn: 2 tokens, different routing
    capturedHandlers.message!(token('ddd', 6));
    capturedHandlers.message!(token('eee', 7));
    capturedHandlers.message!(metadata('routing.model', 'claude-3', 8));
    capturedHandlers.message!(complete(9));

    expect(onSessionComplete).toHaveBeenCalledTimes(2);
    const secondStats: SessionStats = onSessionComplete.mock.calls[1][0];
    expect(secondStats.totalTokens).toBe(2);
    expect(secondStats.status).toBe('complete');
    expect(onSessionComplete.mock.calls[1][1].model).toBe('claude-3');
  });

  it('should handle routing metadata arriving before tokens', async () => {
    const onToken = vi.fn();
    const onSessionComplete = vi.fn();

    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onToken, onSessionComplete },
    );

    // Routing arrives first
    capturedHandlers.message!(metadata('routing.model', 'gpt-4o-mini', 1));
    capturedHandlers.message!(metadata('routing.latency', 95, 2));

    // Then a single token
    capturedHandlers.message!(token('Hi', 3));

    // Complete
    capturedHandlers.message!(complete(4));

    expect(onToken).toHaveBeenCalledTimes(1);
    expect(onSessionComplete).toHaveBeenCalledTimes(1);

    const stats: SessionStats = onSessionComplete.mock.calls[0][0];
    expect(stats.totalTokens).toBe(1);

    const routing: RoutingInfo = onSessionComplete.mock.calls[0][1];
    expect(routing.model).toBe('gpt-4o-mini');
    expect(routing.latency).toBe(95);
  });

  it('should preserve stats and routing on mid-stream error', async () => {
    const onError = vi.fn();
    const onSessionComplete = vi.fn();

    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onError, onSessionComplete },
    );

    // 3 tokens arrive
    capturedHandlers.message!(token('One', 1));
    capturedHandlers.message!(token(' two', 2));
    capturedHandlers.message!(token(' three', 3));

    // Routing cost arrives
    capturedHandlers.message!(metadata('routing.cost', 0.02, 4));

    // Error mid-stream
    capturedHandlers.message!(error('Connection lost', 5));

    expect(onError).toHaveBeenCalledWith('Connection lost');
    expect(onSessionComplete).toHaveBeenCalledTimes(1);

    const stats: SessionStats = onSessionComplete.mock.calls[0][0];
    expect(stats.status).toBe('error');
    expect(stats.totalTokens).toBe(3);

    const routing: RoutingInfo = onSessionComplete.mock.calls[0][1];
    expect(routing.cost).toBe(0.02);
  });

  it('should wrap message with maxCost hint', async () => {
    const handle = await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      {},
    );

    handle.send('hello', { maxCost: 0.05 });

    expect(mockSubscription.push).toHaveBeenCalledWith({
      prompt: 'hello',
      hints: { maxCost: 0.05, maxLatencyMs: undefined },
    });
  });

  it('should wrap message with maxLatencyMs hint', async () => {
    const handle = await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      {},
    );

    handle.send('hello', { maxLatencyMs: 500 });

    expect(mockSubscription.push).toHaveBeenCalledWith({
      prompt: 'hello',
      hints: { maxCost: undefined, maxLatencyMs: 500 },
    });
  });

  it('should wrap message with both cost and latency hints', async () => {
    const handle = await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      {},
    );

    handle.send('hello', { maxCost: 0.01, maxLatencyMs: 200 });

    expect(mockSubscription.push).toHaveBeenCalledWith({
      prompt: 'hello',
      hints: { maxCost: 0.01, maxLatencyMs: 200 },
    });
  });

  it('should pass message unchanged when no send options provided', async () => {
    const handle = await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      {},
    );

    handle.send('hello');

    expect(mockSubscription.push).toHaveBeenCalledWith('hello');
  });
});

describe('metadata and routing coexistence', () => {
  let mockAtmosphere: Atmosphere;
  let capturedHandlers: SubscriptionHandlers<string>;
  let mockSubscription: Subscription;

  beforeEach(() => {
    mockSubscription = {
      id: 'sub-1',
      state: 'connected' as const,
      push: vi.fn(),
      close: vi.fn().mockResolvedValue(undefined),
      suspend: vi.fn(),
      resume: vi.fn().mockResolvedValue(undefined),
      on: vi.fn(),
      off: vi.fn(),
    };

    mockAtmosphere = {
      subscribe: vi.fn().mockImplementation((_req, handlers) => {
        capturedHandlers = handlers;
        return Promise.resolve(mockSubscription);
      }),
    } as unknown as Atmosphere;
  });

  it('should not treat non-routing metadata as routing info', async () => {
    const onMetadata = vi.fn();
    const onSessionComplete = vi.fn();

    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onMetadata, onSessionComplete },
    );

    // "model" without "routing." prefix — just regular metadata
    capturedHandlers.message!(metadata('model', 'gpt-4', 1));
    capturedHandlers.message!(complete(2));

    expect(onMetadata).toHaveBeenCalledWith('model', 'gpt-4');

    const routing: RoutingInfo = onSessionComplete.mock.calls[0][1];
    expect(routing.model).toBeUndefined();
    expect(routing.cost).toBeUndefined();
    expect(routing.latency).toBeUndefined();
  });

  it('should fire onMetadata and populate routing for routing-prefixed keys', async () => {
    const onMetadata = vi.fn();
    const onSessionComplete = vi.fn();

    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onMetadata, onSessionComplete },
    );

    capturedHandlers.message!(metadata('routing.model', 'gpt-4o', 1));
    capturedHandlers.message!(metadata('routing.cost', 0.05, 2));
    capturedHandlers.message!(metadata('routing.latency', 120, 3));
    capturedHandlers.message!(token('Hi', 4));
    capturedHandlers.message!(complete(5));

    // All three fired onMetadata
    expect(onMetadata).toHaveBeenCalledWith('routing.model', 'gpt-4o');
    expect(onMetadata).toHaveBeenCalledWith('routing.cost', 0.05);
    expect(onMetadata).toHaveBeenCalledWith('routing.latency', 120);

    // And all three populated routing in onSessionComplete
    const routing: RoutingInfo = onSessionComplete.mock.calls[0][1];
    expect(routing.model).toBe('gpt-4o');
    expect(routing.cost).toBe(0.05);
    expect(routing.latency).toBe(120);
  });
});
