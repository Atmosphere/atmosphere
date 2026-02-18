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
import type { StreamingHandlers } from '../../src/streaming/types';
import type { SubscriptionHandlers, Subscription } from '../../src/types';

describe('parseStreamingMessage', () => {
  it('should parse a valid token message', () => {
    const raw = '{"type":"token","data":"Hello","sessionId":"abc-123","seq":1}';
    const msg = parseStreamingMessage(raw);
    expect(msg).toEqual({
      type: 'token',
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
    expect(parseStreamingMessage('{"type":"token","data":"x"}')).toBeNull();
  });

  it('should return null for unknown type', () => {
    expect(parseStreamingMessage('{"type":"unknown","sessionId":"a","seq":1}')).toBeNull();
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

  it('should dispatch token events', async () => {
    const onToken = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onToken },
    );

    capturedHandlers.message!({
      responseBody: '{"type":"token","data":"Hello","sessionId":"s1","seq":1}',
      status: 200,
      reasonPhrase: 'OK',
      messages: [],
      headers: {},
      state: 'messageReceived',
      transport: 'websocket',
      error: null,
    });

    expect(onToken).toHaveBeenCalledWith('Hello', 1);
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
    const onToken = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onToken },
    );

    const msg = '{"type":"token","data":"Hi","sessionId":"s1","seq":1}';
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

    expect(onToken).toHaveBeenCalledTimes(1);
  });

  it('should track sessionId from first message', async () => {
    const handle = await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      {},
    );

    expect(handle.sessionId).toBeNull();

    capturedHandlers.message!({
      responseBody: '{"type":"token","data":"x","sessionId":"sess-42","seq":1}',
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
    const onToken = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onToken },
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

    expect(onToken).not.toHaveBeenCalled();
  });

  it('should ignore non-streaming JSON messages', async () => {
    const onToken = vi.fn();
    await subscribeStreaming(
      mockAtmosphere,
      { url: '/ai/chat', transport: 'websocket' },
      { onToken },
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

    expect(onToken).not.toHaveBeenCalled();
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
