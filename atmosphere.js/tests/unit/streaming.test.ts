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
import { parseStreamingMessage } from '../../src/streaming/decoder';
import { subscribeStreaming } from '../../src/streaming';
import { Atmosphere } from '../../src/core/atmosphere';
import type { StreamingHandlers, SessionStats, RoutingInfo } from '../../src/streaming/types';
import type { SubscriptionHandlers, Subscription } from '../../src/types';

describe('parseStreamingMessage', () => {
  it('should parse a valid streaming-text message', () => {
    const raw = '{"type":"streaming-text","data":"Hello","sessionId":"abc-123","seq":1}';
    const msg = parseStreamingMessage(raw);
    expect(msg).toEqual({
      type: 'streaming-text',
      data: 'Hello',
      sessionId: 'abc-123',
      seq: 1,
    });
  });

  it('should parse a complete message without data', () => {
    const raw = '{"type":"complete","sessionId":"abc-123","seq":5}';
    const msg = parseStreamingMessage(raw);
    expect(msg).toEqual({
      type: 'complete',
      sessionId: 'abc-123',
      seq: 5,
    });
  });

  it('should parse a complete message with summary', () => {
    const raw = '{"type":"complete","data":"Full response","sessionId":"abc-123","seq":5}';
    const msg = parseStreamingMessage(raw);
    expect(msg?.data).toBe('Full response');
  });

  it('should parse a metadata message', () => {
    const raw = '{"type":"metadata","key":"model","value":"gpt-4","sessionId":"abc-123","seq":3}';
    const msg = parseStreamingMessage(raw);
    expect(msg).toEqual({
      type: 'metadata',
      key: 'model',
      value: 'gpt-4',
      sessionId: 'abc-123',
      seq: 3,
    });
  });

  it('should parse a progress message', () => {
    const raw = '{"type":"progress","data":"Thinking...","sessionId":"abc-123","seq":2}';
    const msg = parseStreamingMessage(raw);
    expect(msg?.type).toBe('progress');
    expect(msg?.data).toBe('Thinking...');
  });

  it('should parse a cancelled message', () => {
    const raw = '{"type":"cancelled","data":"disconnect","sessionId":"abc-123","seq":7}';
    const msg = parseStreamingMessage(raw);
    expect(msg?.type).toBe('cancelled');
    expect(msg?.data).toBe('disconnect');
  });

  it('should parse an error message', () => {
    const raw = '{"type":"error","data":"Connection failed","sessionId":"abc-123","seq":6}';
    const msg = parseStreamingMessage(raw);
    expect(msg?.type).toBe('error');
    expect(msg?.data).toBe('Connection failed');
  });

  it('should return null for non-JSON input', () => {
    expect(parseStreamingMessage('not json')).toBeNull();
    expect(parseStreamingMessage('')).toBeNull();
  });

  it('should return null for JSON without streaming type', () => {
    expect(parseStreamingMessage('{"text":"hello"}')).toBeNull();
  });

  it('should return null for JSON without sessionId', () => {
    expect(parseStreamingMessage('{"type":"streaming-text","data":"x"}')).toBeNull();
  });

  it('should return null for unknown type', () => {
    expect(parseStreamingMessage('{"type":"unknown","sessionId":"a","seq":1}')).toBeNull();
  });

  it('should parse a room prompt echo, its prompt in text', () => {
    const raw = '{"type":"prompt","text":"What is 2+2?","from":"member-a","sessionId":"abc-123","seq":1}';
    expect(parseStreamingMessage(raw)).toEqual({
      type: 'prompt',
      text: 'What is 2+2?',
      from: 'member-a',
      sessionId: 'abc-123',
      seq: 1,
    });
  });
});

describe('subscribeStreaming room prompts', () => {
  let capturedHandlers: SubscriptionHandlers<string>;
  const frame = (body: string) => capturedHandlers.message!({
    responseBody: body,
    status: 200,
    reasonPhrase: 'OK',
    messages: [],
    headers: {},
    state: 'messageReceived',
    transport: 'websocket',
    error: null,
  });

  async function subscribe(handlers: StreamingHandlers) {
    const subscription = {
      id: 'sub-1',
      uuid: 'me',
      state: 'connected' as const,
      push: vi.fn(),
      close: vi.fn().mockResolvedValue(undefined),
      suspend: vi.fn(),
      resume: vi.fn().mockResolvedValue(undefined),
      on: vi.fn(),
      off: vi.fn(),
    };
    const atmosphere = {
      subscribe: vi.fn().mockImplementation((_req, h) => {
        capturedHandlers = h;
        return Promise.resolve(subscription);
      }),
    } as unknown as Atmosphere;
    return subscribeStreaming(atmosphere, { url: '/ai/room', transport: 'websocket' }, handlers);
  }

  it("fires onPrompt with another member's prompt ahead of its reply", async () => {
    const calls: string[] = [];
    await subscribe({
      onPrompt: (text, from, own) => calls.push(`prompt:${text}:${from}:${own}`),
      onStreamingText: (text) => calls.push(`text:${text}`),
    });

    frame('{"type":"prompt","text":"What is 2+2?","from":"member-a","sessionId":"turn-1","seq":2}');
    frame('{"type":"streaming-text","data":"four","sessionId":"turn-1","seq":3}');

    expect(calls).toEqual(['prompt:What is 2+2?:member-a:false', 'text:four']);
  });

  it('reports a later turn\'s prompt without leaving the session it follows', async () => {
    const onPrompt = vi.fn();
    const onStreamingText = vi.fn();
    await subscribe({ onPrompt, onStreamingText });

    frame('{"type":"streaming-text","data":"mine","sessionId":"turn-1","seq":1}');
    frame('{"type":"prompt","text":"other?","from":"member-b","sessionId":"turn-2","seq":1}');
    frame('{"type":"streaming-text","data":" too","sessionId":"turn-1","seq":2}');

    expect(onPrompt).toHaveBeenCalledWith('other?', 'member-b', false);
    expect(onStreamingText).toHaveBeenLastCalledWith(' too', 2);
  });

  it('flags the echo of its own prompt', async () => {
    const onPrompt = vi.fn();
    const onStreamingText = vi.fn();
    await subscribe({ onPrompt, onStreamingText });

    // The room names this connection (tracking id "me") by its room member id.
    frame('{"type":"prompt","text":"hi","from":"08f7d7455edbd74a611646548809f1e8","sessionId":"turn-1","seq":2}');

    expect(onPrompt).toHaveBeenCalledWith('hi', '08f7d7455edbd74a611646548809f1e8', true);
    expect(onStreamingText).not.toHaveBeenCalled();
  });

  it('does not take a frame naming its raw tracking id for its own prompt', async () => {
    const onPrompt = vi.fn();
    await subscribe({ onPrompt });

    frame('{"type":"prompt","text":"hi","from":"me","sessionId":"turn-1","seq":2}');

    expect(onPrompt).toHaveBeenCalledWith('hi', 'me', false);
  });
});

describe('subscribeStreaming', () => {
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

  it('should dispatch streaming-text events', async () => {
    const onStreamingText = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onStreamingText },
    );

    capturedHandlers.message!({
      responseBody: '{"type":"streaming-text","data":"Hello","sessionId":"s1","seq":1}',
      status: 200,
      reasonPhrase: 'OK',
      messages: [],
      headers: {},
      state: 'messageReceived',
      transport: 'websocket',
      error: null,
    });

    expect(onStreamingText).toHaveBeenCalledWith('Hello', 1);
  });

  it('should dispatch progress events', async () => {
    const onProgress = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onProgress },
    );

    capturedHandlers.message!({
      responseBody: '{"type":"progress","data":"Thinking...","sessionId":"s1","seq":1}',
      status: 200,
      reasonPhrase: 'OK',
      messages: [],
      headers: {},
      state: 'messageReceived',
      transport: 'websocket',
      error: null,
    });

    expect(onProgress).toHaveBeenCalledWith('Thinking...', 1);
  });

  it('should dispatch complete events', async () => {
    const onComplete = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onComplete },
    );

    capturedHandlers.message!({
      responseBody: '{"type":"complete","data":"Done","sessionId":"s1","seq":3}',
      status: 200,
      reasonPhrase: 'OK',
      messages: [],
      headers: {},
      state: 'messageReceived',
      transport: 'websocket',
      error: null,
    });

    expect(onComplete).toHaveBeenCalledWith('Done');
  });

  it('should end a cancelled run through onCancelled, not onError or onComplete', async () => {
    const onCancelled = vi.fn();
    const onComplete = vi.fn();
    const onError = vi.fn();
    const onSessionComplete = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onCancelled, onComplete, onError, onSessionComplete },
    );

    capturedHandlers.message!({
      responseBody: '{"type":"cancelled","data":"operator","sessionId":"s1","seq":4}',
      status: 200,
      reasonPhrase: 'OK',
      messages: [],
      headers: {},
      state: 'messageReceived',
      transport: 'websocket',
      error: null,
    });

    expect(onCancelled).toHaveBeenCalledWith('operator');
    expect(onComplete).not.toHaveBeenCalled();
    expect(onError).not.toHaveBeenCalled();
    expect(onSessionComplete).toHaveBeenCalledTimes(1);
    expect(onSessionComplete.mock.calls[0][0].status).toBe('cancelled');
  });

  it('should end a cancelled run through onComplete when no onCancelled is registered', async () => {
    const onComplete = vi.fn();
    const onError = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onComplete, onError },
    );

    capturedHandlers.message!({
      responseBody: '{"type":"cancelled","data":"disconnect","sessionId":"s1","seq":4}',
      status: 200,
      reasonPhrase: 'OK',
      messages: [],
      headers: {},
      state: 'messageReceived',
      transport: 'websocket',
      error: null,
    });

    // Code written before the frame existed still stops streaming.
    expect(onComplete).toHaveBeenCalledTimes(1);
    expect(onComplete).toHaveBeenCalledWith();
    expect(onError).not.toHaveBeenCalled();
  });

  it('should dispatch error events', async () => {
    const onError = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onError },
    );

    capturedHandlers.message!({
      responseBody: '{"type":"error","data":"Oops","sessionId":"s1","seq":1}',
      status: 200,
      reasonPhrase: 'OK',
      messages: [],
      headers: {},
      state: 'messageReceived',
      transport: 'websocket',
      error: null,
    });

    expect(onError).toHaveBeenCalledWith('Oops');
  });

  it('should dispatch metadata events', async () => {
    const onMetadata = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onMetadata },
    );

    capturedHandlers.message!({
      responseBody: '{"type":"metadata","key":"model","value":"gpt-4","sessionId":"s1","seq":1}',
      status: 200,
      reasonPhrase: 'OK',
      messages: [],
      headers: {},
      state: 'messageReceived',
      transport: 'websocket',
      error: null,
    });

    expect(onMetadata).toHaveBeenCalledWith('model', 'gpt-4');
  });

  it('should deduplicate messages by sequence number', async () => {
    const onStreamingText = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onStreamingText },
    );

    const msg = '{"type":"streaming-text","data":"Hi","sessionId":"s1","seq":1}';
    const response = {
      responseBody: msg,
      status: 200,
      reasonPhrase: 'OK',
      messages: [],
      headers: {},
      state: 'messageReceived',
      transport: 'websocket' as const,
      error: null,
    };

    capturedHandlers.message!(response);
    capturedHandlers.message!(response); // duplicate

    expect(onStreamingText).toHaveBeenCalledTimes(1);
  });

  it('should track sessionId from first message', async () => {
    const handle = await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      {},
    );

    expect(handle.sessionId).toBeNull();

    capturedHandlers.message!({
      responseBody: '{"type":"streaming-text","data":"x","sessionId":"sess-42","seq":1}',
      status: 200,
      reasonPhrase: 'OK',
      messages: [],
      headers: {},
      state: 'messageReceived',
      transport: 'websocket',
      error: null,
    });

    expect(handle.sessionId).toBe('sess-42');
  });

  it('should forward send() to the underlying subscription', async () => {
    const handle = await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      {},
    );

    handle.send('Hello AI');
    expect(mockSubscription.push).toHaveBeenCalledWith('Hello AI');
  });

  it('should close the underlying subscription', async () => {
    const handle = await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      {},
    );

    await handle.close();
    expect(mockSubscription.close).toHaveBeenCalled();
  });

  it('should ignore non-string messages', async () => {
    const onStreamingText = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onStreamingText },
    );

    capturedHandlers.message!({
      responseBody: 42 as unknown as string,
      status: 200,
      reasonPhrase: 'OK',
      messages: [],
      headers: {},
      state: 'messageReceived',
      transport: 'websocket',
      error: null,
    });

    expect(onStreamingText).not.toHaveBeenCalled();
  });

  it('should ignore non-streaming JSON messages', async () => {
    const onStreamingText = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onStreamingText },
    );

    capturedHandlers.message!({
      responseBody: '{"text":"not a streaming message"}',
      status: 200,
      reasonPhrase: 'OK',
      messages: [],
      headers: {},
      state: 'messageReceived',
      transport: 'websocket',
      error: null,
    });

    expect(onStreamingText).not.toHaveBeenCalled();
  });

  it('should forward transport errors', async () => {
    const onError = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onError },
    );

    capturedHandlers.error!(new Error('WebSocket closed'));
    expect(onError).toHaveBeenCalledWith('WebSocket closed');
  });
});

describe('session stats and routing', () => {
  let mockAtmosphere: Atmosphere;
  let capturedHandlers: SubscriptionHandlers<string>;
  let mockSubscription: Subscription;

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

  it('should track streaming text count', async () => {
    const onSessionComplete = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onSessionComplete },
    );

    capturedHandlers.message!(makeResponse('{"type":"streaming-text","data":"a","sessionId":"s1","seq":1}'));
    capturedHandlers.message!(makeResponse('{"type":"streaming-text","data":"b","sessionId":"s1","seq":2}'));
    capturedHandlers.message!(makeResponse('{"type":"streaming-text","data":"c","sessionId":"s1","seq":3}'));
    capturedHandlers.message!(makeResponse('{"type":"complete","sessionId":"s1","seq":4}'));

    expect(onSessionComplete).toHaveBeenCalledTimes(1);
    const stats: SessionStats = onSessionComplete.mock.calls[0][0];
    expect(stats.totalStreamingTexts).toBe(3);
    expect(stats.status).toBe('complete');
  });

  it('should compute elapsedMs', async () => {
    const onSessionComplete = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onSessionComplete },
    );

    capturedHandlers.message!(makeResponse('{"type":"streaming-text","data":"a","sessionId":"s1","seq":1}'));
    capturedHandlers.message!(makeResponse('{"type":"complete","sessionId":"s1","seq":2}'));

    const stats: SessionStats = onSessionComplete.mock.calls[0][0];
    expect(stats.elapsedMs).toBeGreaterThanOrEqual(0);
  });

  it('should compute streaming texts per second', async () => {
    const onSessionComplete = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onSessionComplete },
    );

    capturedHandlers.message!(makeResponse('{"type":"streaming-text","data":"a","sessionId":"s1","seq":1}'));
    capturedHandlers.message!(makeResponse('{"type":"streaming-text","data":"b","sessionId":"s1","seq":2}'));
    capturedHandlers.message!(makeResponse('{"type":"complete","sessionId":"s1","seq":3}'));

    const stats: SessionStats = onSessionComplete.mock.calls[0][0];
    if (stats.elapsedMs > 0) {
      expect(stats.streamingTextsPerSecond).toBeCloseTo((stats.totalStreamingTexts / stats.elapsedMs) * 1000, 0);
    } else {
      expect(stats.streamingTextsPerSecond).toBe(0);
    }
  });

  it('should fire onSessionComplete on error with error status', async () => {
    const onSessionComplete = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onSessionComplete },
    );

    capturedHandlers.message!(makeResponse('{"type":"streaming-text","data":"a","sessionId":"s1","seq":1}'));
    capturedHandlers.message!(makeResponse('{"type":"error","data":"Oops","sessionId":"s1","seq":2}'));

    expect(onSessionComplete).toHaveBeenCalledTimes(1);
    const stats: SessionStats = onSessionComplete.mock.calls[0][0];
    expect(stats.status).toBe('error');
    expect(stats.totalStreamingTexts).toBe(1);
  });

  it('should extract routing.model from metadata', async () => {
    const onSessionComplete = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onSessionComplete },
    );

    capturedHandlers.message!(makeResponse('{"type":"metadata","key":"routing.model","value":"gpt-4o","sessionId":"s1","seq":1}'));
    capturedHandlers.message!(makeResponse('{"type":"complete","sessionId":"s1","seq":2}'));

    const routing: RoutingInfo = onSessionComplete.mock.calls[0][1];
    expect(routing.model).toBe('gpt-4o');
  });

  it('should extract routing.cost from metadata', async () => {
    const onSessionComplete = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onSessionComplete },
    );

    capturedHandlers.message!(makeResponse('{"type":"metadata","key":"routing.cost","value":0.03,"sessionId":"s1","seq":1}'));
    capturedHandlers.message!(makeResponse('{"type":"complete","sessionId":"s1","seq":2}'));

    const routing: RoutingInfo = onSessionComplete.mock.calls[0][1];
    expect(routing.cost).toBe(0.03);
  });

  it('should extract routing.latency from metadata', async () => {
    const onSessionComplete = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onSessionComplete },
    );

    capturedHandlers.message!(makeResponse('{"type":"metadata","key":"routing.latency","value":150,"sessionId":"s1","seq":1}'));
    capturedHandlers.message!(makeResponse('{"type":"complete","sessionId":"s1","seq":2}'));

    const routing: RoutingInfo = onSessionComplete.mock.calls[0][1];
    expect(routing.latency).toBe(150);
  });

  it('should still fire onMetadata for routing keys', async () => {
    const onMetadata = vi.fn();
    const onSessionComplete = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onMetadata, onSessionComplete },
    );

    capturedHandlers.message!(makeResponse('{"type":"metadata","key":"routing.model","value":"gpt-4o","sessionId":"s1","seq":1}'));
    capturedHandlers.message!(makeResponse('{"type":"complete","sessionId":"s1","seq":2}'));

    expect(onMetadata).toHaveBeenCalledWith('routing.model', 'gpt-4o');
    expect(onSessionComplete).toHaveBeenCalledTimes(1);
  });

  it('should reset stats between sends', async () => {
    const onSessionComplete = vi.fn();
    const handle = await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onSessionComplete },
    );

    // First session
    capturedHandlers.message!(makeResponse('{"type":"streaming-text","data":"a","sessionId":"s1","seq":1}'));
    capturedHandlers.message!(makeResponse('{"type":"streaming-text","data":"b","sessionId":"s1","seq":2}'));
    capturedHandlers.message!(makeResponse('{"type":"complete","sessionId":"s1","seq":3}'));

    expect(onSessionComplete).toHaveBeenCalledTimes(1);
    expect(onSessionComplete.mock.calls[0][0].totalStreamingTexts).toBe(2);

    // Send again (resets tracking)
    handle.send('next question');

    capturedHandlers.message!(makeResponse('{"type":"streaming-text","data":"c","sessionId":"s1","seq":4}'));
    capturedHandlers.message!(makeResponse('{"type":"complete","sessionId":"s1","seq":5}'));

    expect(onSessionComplete).toHaveBeenCalledTimes(2);
    expect(onSessionComplete.mock.calls[1][0].totalStreamingTexts).toBe(1);
  });

  it('should wrap message with hints when send options provided', async () => {
    const handle = await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      {},
    );

    handle.send('hello', { maxCost: 0.01 });

    expect(mockSubscription.push).toHaveBeenCalledWith({
      prompt: 'hello',
      hints: { maxCost: 0.01, maxLatencyMs: undefined },
    });
  });

  // -- Lifecycle hooks: pair with @AiEndpoint disconnect / streamCache /
  // -- heartbeat primitives so UI can render reconnect / lost-connection state.

  it('should dispatch onOpen when transport opens', async () => {
    const onOpen = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onOpen },
    );

    capturedHandlers.open!({
      responseBody: '',
      status: 200,
      reasonPhrase: 'OK',
      messages: [],
      headers: {},
      state: 'opening',
      transport: 'websocket',
      error: null,
    });

    expect(onOpen).toHaveBeenCalledTimes(1);
  });

  it('should dispatch onClose when transport closes', async () => {
    const onClose = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onClose },
    );

    capturedHandlers.close!({
      responseBody: '',
      status: 200,
      reasonPhrase: 'OK',
      messages: [],
      headers: {},
      state: 'closed',
      transport: 'websocket',
      error: null,
    });

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('should dispatch onReconnect on reconnection attempt', async () => {
    const onReconnect = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onReconnect },
    );

    capturedHandlers.reconnect!(
      { url: '/ai/chat', transport: 'websocket' },
      {
        responseBody: '',
        status: 200,
        reasonPhrase: 'OK',
        messages: [],
        headers: {},
        state: 'reconnect',
        transport: 'websocket',
        error: null,
      },
    );

    expect(onReconnect).toHaveBeenCalledTimes(1);
  });

  it('should dispatch onClientTimeout on heartbeat watchdog expiry', async () => {
    const onClientTimeout = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onClientTimeout },
    );

    capturedHandlers.clientTimeout!({ url: '/ai/chat', transport: 'websocket' });

    expect(onClientTimeout).toHaveBeenCalledTimes(1);
  });
});
