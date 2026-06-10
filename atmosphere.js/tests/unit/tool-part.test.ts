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
import { parseToolPart } from '../../src/streaming/tool-part';
import { subscribeStreaming } from '../../src/streaming';
import { Atmosphere } from '../../src/core/atmosphere';
import type { SubscriptionHandlers, Subscription } from '../../src/types';

describe('parseToolPart', () => {
  it('parses tool-start into a typed tool-call part', () => {
    expect(parseToolPart('tool-start', { toolName: 'search', arguments: { q: 'x' } })).toEqual({
      type: 'tool-call',
      toolName: 'search',
      arguments: { q: 'x' },
      state: 'started',
    });
  });

  it('parses tool-result and tool-error', () => {
    expect(parseToolPart('tool-result', { toolName: 'search', result: { hits: 3 } })).toEqual({
      type: 'tool-result',
      toolName: 'search',
      result: { hits: 3 },
      state: 'completed',
    });
    expect(parseToolPart('tool-error', { toolName: 'search', error: 'rate limited' })).toEqual({
      type: 'tool-error',
      toolName: 'search',
      error: 'rate limited',
      state: 'error',
    });
  });

  it('returns null for non-tool events and missing event', () => {
    expect(parseToolPart('agent-step', { stepName: 'plan' })).toBeNull();
    expect(parseToolPart('', {})).toBeNull();
    expect(parseToolPart(null, {})).toBeNull();
  });

  it('tolerates missing fields', () => {
    expect(parseToolPart('tool-start', {})).toEqual({
      type: 'tool-call',
      toolName: '',
      arguments: {},
      state: 'started',
    });
  });
});

describe('dispatch tool-part routing', () => {
  function buildMockAtmosphere() {
    const handlersHolder: { current: SubscriptionHandlers | undefined } = { current: undefined };
    const mock = {
      subscribe: vi.fn(async (_req: unknown, handlers: SubscriptionHandlers) => {
        handlersHolder.current = handlers;
        return { push: vi.fn(), close: vi.fn(async () => {}) } as unknown as Subscription;
      }),
    } as unknown as Atmosphere;
    return { mock, handlersHolder };
  }

  it('a tool-start AiEvent fires onToolPart AND onAiEvent (back-compat)', async () => {
    const { mock, handlersHolder } = buildMockAtmosphere();
    const onToolPart = vi.fn();
    const onAiEvent = vi.fn();

    await subscribeStreaming(
      mock,
      { url: '/ai/chat', transport: 'websocket' },
      { onToolPart, onAiEvent },
    );

    handlersHolder.current!.message!({
      responseBody:
        '{"event":"tool-start","data":{"toolName":"search","arguments":{"q":"x"}},"sessionId":"s1","seq":1}',
      status: 200,
      reasonPhrase: 'OK',
      messages: [],
      headers: {},
    } as never);

    expect(onToolPart).toHaveBeenCalledTimes(1);
    expect(onToolPart).toHaveBeenCalledWith({
      type: 'tool-call',
      toolName: 'search',
      arguments: { q: 'x' },
      state: 'started',
    });
    expect(onAiEvent).toHaveBeenCalledWith('tool-start', { toolName: 'search', arguments: { q: 'x' } });
  });
});
