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

import type { AtmosphereRequest, Subscription } from '../types';
import type { StreamingHandlers, StreamingHandle, StreamingMessage, SessionStats, RoutingInfo, SendOptions } from './types';
import { parseStreamingMessage } from './decoder';
import { Atmosphere } from '../core/atmosphere';

/**
 * Creates a streaming subscription to an Atmosphere endpoint that speaks the
 * AI streaming wire protocol.
 *
 * This is the framework-agnostic core. The React/Vue/Svelte hooks wrap this.
 *
 * ```ts
 * import { atmosphere } from 'atmosphere.js';
 * import { subscribeStreaming } from 'atmosphere.js/streaming';
 *
 * const handle = await subscribeStreaming(atmosphere, {
 *   url: '/ai/chat',
 *   transport: 'websocket',
 * }, {
 *   onToken: (token) => process.stdout.write(token),
 *   onComplete: () => console.log('\nDone!'),
 *   onError: (err) => console.error(err),
 * });
 *
 * handle.send('What is Atmosphere?');
 * ```
 */
export async function subscribeStreaming(
  atmosphere: Atmosphere,
  request: AtmosphereRequest,
  handlers: StreamingHandlers,
): Promise<StreamingHandle> {
  let sessionId: string | null = null;
  let lastSeq = -1;

  // Session stats tracking (reset between sends)
  let tokenCount = 0;
  let startTime: number | null = null;
  let routing: RoutingInfo = {};

  const sub: Subscription = await atmosphere.subscribe<string>(request, {
    message: (response) => {
      const raw = response.responseBody;
      if (typeof raw !== 'string') return;

      const msg = parseStreamingMessage(raw);
      if (!msg) return;

      // Track session ID from first message
      if (!sessionId) sessionId = msg.sessionId;

      // Dedup via sequence number
      if (msg.seq <= lastSeq) return;
      lastSeq = msg.seq;

      dispatch(msg, handlers, { tokenCount, startTime, routing }, (tc, st, rt) => {
        tokenCount = tc;
        startTime = st;
        routing = rt;
      });
    },
    error: (err) => {
      handlers.onError?.(err.message);
    },
  });

  return {
    get sessionId() {
      return sessionId;
    },
    send(message: string | object, options?: SendOptions) {
      // Reset tracking state for new session
      tokenCount = 0;
      startTime = null;
      routing = {};

      const payload = options && (options.maxCost !== undefined || options.maxLatencyMs !== undefined)
        ? { prompt: message, hints: { maxCost: options.maxCost, maxLatencyMs: options.maxLatencyMs } }
        : message;
      sub.push(payload);
    },
    async close() {
      await sub.close();
    },
  };
}

interface TrackingState {
  tokenCount: number;
  startTime: number | null;
  routing: RoutingInfo;
}

function extractRouting(key: string, value: unknown, routing: RoutingInfo): boolean {
  if (!key.startsWith('routing.')) return false;
  const field = key.substring('routing.'.length);
  switch (field) {
    case 'model':
      routing.model = value as string;
      return true;
    case 'cost':
      routing.cost = value as number;
      return true;
    case 'latency':
      routing.latency = value as number;
      return true;
    default:
      return false;
  }
}

function dispatch(
  msg: StreamingMessage,
  handlers: StreamingHandlers,
  state: TrackingState,
  updateState: (tokenCount: number, startTime: number | null, routing: RoutingInfo) => void,
): void {
  switch (msg.type) {
    case 'token':
      if (msg.data !== undefined) {
        handlers.onToken?.(msg.data, msg.seq);
        state.tokenCount++;
        if (!state.startTime) state.startTime = Date.now();
        updateState(state.tokenCount, state.startTime, state.routing);
      }
      break;
    case 'progress':
      if (msg.data !== undefined) handlers.onProgress?.(msg.data, msg.seq);
      break;
    case 'complete': {
      handlers.onComplete?.(msg.data);
      const elapsed = state.startTime ? Date.now() - state.startTime : 0;
      const stats: SessionStats = {
        totalTokens: state.tokenCount,
        elapsedMs: elapsed,
        status: 'complete',
        tokensPerSecond: elapsed > 0 ? (state.tokenCount / elapsed) * 1000 : 0,
      };
      handlers.onSessionComplete?.(stats, { ...state.routing });
      break;
    }
    case 'error': {
      handlers.onError?.(msg.data ?? 'Unknown error');
      const elapsed = state.startTime ? Date.now() - state.startTime : 0;
      const stats: SessionStats = {
        totalTokens: state.tokenCount,
        elapsedMs: elapsed,
        status: 'error',
        tokensPerSecond: elapsed > 0 ? (state.tokenCount / elapsed) * 1000 : 0,
      };
      handlers.onSessionComplete?.(stats, { ...state.routing });
      break;
    }
    case 'metadata':
      if (msg.key !== undefined) {
        extractRouting(msg.key, msg.value, state.routing);
        updateState(state.tokenCount, state.startTime, state.routing);
        handlers.onMetadata?.(msg.key, msg.value);
      }
      break;
  }
}
