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
import { parsePolicyDenial } from '../../src/streaming/policy-denial';
import { subscribeStreaming } from '../../src/streaming';
import { Atmosphere } from '../../src/core/atmosphere';
import type { SubscriptionHandlers, Subscription } from '../../src/types';

describe('parsePolicyDenial', () => {
  it('parses AiPipeline policy-deny prefix', () => {
    const d = parsePolicyDenial('Request denied by policy scope.support: off-topic prompt');
    expect(d).toEqual({
      kind: 'policy',
      policyName: 'scope.support',
      reason: 'off-topic prompt',
      raw: 'Request denied by policy scope.support: off-topic prompt',
    });
  });

  it('parses PolicyAdmissionGate deny prefix with quotes', () => {
    const d = parsePolicyDenial("Denied by policy 'scope.support': off-topic");
    expect(d?.kind).toBe('policy');
    expect(d?.policyName).toBe('scope.support');
    expect(d?.reason).toBe('off-topic');
  });

  it('parses PolicyAdmissionGate deny prefix without quotes', () => {
    const d = parsePolicyDenial('Denied by policy scope.support: off-topic');
    expect(d?.policyName).toBe('scope.support');
  });

  it('parses guardrail block prefix as a guardrail denial', () => {
    const d = parsePolicyDenial('Request blocked: PII detected (SSN)');
    expect(d).toEqual({
      kind: 'guardrail',
      reason: 'PII detected (SSN)',
      raw: 'Request blocked: PII detected (SSN)',
    });
  });

  it('parses policy eval failure as policy denial with failure prefix', () => {
    const d = parsePolicyDenial('Policy kill-switch evaluation failed: classpath problem');
    expect(d?.kind).toBe('policy');
    expect(d?.policyName).toBe('kill-switch');
    expect(d?.reason).toBe('evaluation failed: classpath problem');
  });

  it('returns null for transport errors', () => {
    expect(parsePolicyDenial('WebSocket closed')).toBeNull();
    expect(parsePolicyDenial('Connection refused')).toBeNull();
  });

  it('returns null for empty / missing input', () => {
    expect(parsePolicyDenial('')).toBeNull();
    expect(parsePolicyDenial(null)).toBeNull();
    expect(parsePolicyDenial(undefined)).toBeNull();
  });

  it('tolerates policy names with dots and dashes', () => {
    const d = parsePolicyDenial('Request denied by policy ms.custom-service-v2: forbidden');
    expect(d?.policyName).toBe('ms.custom-service-v2');
  });
});

describe('streaming dispatchError routing', () => {
  function buildMockAtmosphere() {
    const handlersHolder: { current: SubscriptionHandlers | undefined } = { current: undefined };
    const mock = {
      subscribe: vi.fn(async (_req: unknown, handlers: SubscriptionHandlers) => {
        handlersHolder.current = handlers;
        return {
          push: vi.fn(),
          close: vi.fn(async () => {}),
        } as unknown as Subscription;
      }),
    } as unknown as Atmosphere;
    return { mock, handlersHolder };
  }

  it('legacy error with governance prefix fires onPolicyDenied AND onError', async () => {
    const { mock, handlersHolder } = buildMockAtmosphere();
    const onError = vi.fn();
    const onPolicyDenied = vi.fn();

    await subscribeStreaming(
      mock,
      { url: '/ai/chat', transport: 'websocket' },
      { onError, onPolicyDenied },
    );

    handlersHolder.current!.message!({
      responseBody: '{"type":"error","data":"Request denied by policy scope.support: off-topic","sessionId":"s1","seq":1}',
      status: 200,
      reasonPhrase: 'OK',
      messages: [],
      headers: {},
      state: 'messageReceived',
      transport: 'websocket',
      error: null,
    });

    expect(onPolicyDenied).toHaveBeenCalledTimes(1);
    expect(onPolicyDenied).toHaveBeenCalledWith({
      kind: 'policy',
      policyName: 'scope.support',
      reason: 'off-topic',
      raw: 'Request denied by policy scope.support: off-topic',
    });
    expect(onError).toHaveBeenCalledWith('Request denied by policy scope.support: off-topic');
  });

  it('legacy error without governance prefix only fires onError', async () => {
    const { mock, handlersHolder } = buildMockAtmosphere();
    const onError = vi.fn();
    const onPolicyDenied = vi.fn();

    await subscribeStreaming(
      mock,
      { url: '/ai/chat', transport: 'websocket' },
      { onError, onPolicyDenied },
    );

    handlersHolder.current!.message!({
      responseBody: '{"type":"error","data":"WebSocket closed","sessionId":"s1","seq":1}',
      status: 200,
      reasonPhrase: 'OK',
      messages: [],
      headers: {},
      state: 'messageReceived',
      transport: 'websocket',
      error: null,
    });

    expect(onError).toHaveBeenCalledWith('WebSocket closed');
    expect(onPolicyDenied).not.toHaveBeenCalled();
  });

  it('AiEvent error with structured policy payload fires onPolicyDenied', async () => {
    const { mock, handlersHolder } = buildMockAtmosphere();
    const onPolicyDenied = vi.fn();
    const onError = vi.fn();

    await subscribeStreaming(
      mock,
      { url: '/ai/chat', transport: 'websocket' },
      { onError, onPolicyDenied },
    );

    handlersHolder.current!.message!({
      responseBody: JSON.stringify({
        event: 'error',
        data: {
          message: 'Request denied by policy kill-switch: incident-42',
          policyName: 'kill-switch',
          reason: 'incident-42',
          kind: 'policy',
        },
        sessionId: 's1',
        seq: 1,
      }),
      status: 200,
      reasonPhrase: 'OK',
      messages: [],
      headers: {},
      state: 'messageReceived',
      transport: 'websocket',
      error: null,
    });

    expect(onPolicyDenied).toHaveBeenCalledTimes(1);
    expect(onPolicyDenied.mock.calls[0][0].policyName).toBe('kill-switch');
    expect(onPolicyDenied.mock.calls[0][0].reason).toBe('incident-42');
  });

  it('guardrail block string fires onPolicyDenied with kind=guardrail', async () => {
    const { mock, handlersHolder } = buildMockAtmosphere();
    const onPolicyDenied = vi.fn();
    const onError = vi.fn();

    await subscribeStreaming(
      mock,
      { url: '/ai/chat', transport: 'websocket' },
      { onError, onPolicyDenied },
    );

    handlersHolder.current!.message!({
      responseBody: '{"type":"error","data":"Request blocked: PII detected","sessionId":"s1","seq":1}',
      status: 200,
      reasonPhrase: 'OK',
      messages: [],
      headers: {},
      state: 'messageReceived',
      transport: 'websocket',
      error: null,
    });

    expect(onPolicyDenied).toHaveBeenCalledWith(
      expect.objectContaining({ kind: 'guardrail', reason: 'PII detected' }),
    );
  });
});
