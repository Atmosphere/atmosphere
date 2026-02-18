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
import type { StreamingHandlers, StreamingHandle, StreamingMessage } from './types';
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

      dispatch(msg, handlers);
    },
    error: (err) => {
      handlers.onError?.(err.message);
    },
  });

  return {
    get sessionId() {
      return sessionId;
    },
    send(message: string | object) {
      sub.push(message);
    },
    async close() {
      await sub.close();
    },
  };
}

function dispatch(msg: StreamingMessage, handlers: StreamingHandlers): void {
  switch (msg.type) {
    case 'token':
      if (msg.data !== undefined) handlers.onToken?.(msg.data, msg.seq);
      break;
    case 'progress':
      if (msg.data !== undefined) handlers.onProgress?.(msg.data, msg.seq);
      break;
    case 'complete':
      handlers.onComplete?.(msg.data);
      break;
    case 'error':
      handlers.onError?.(msg.data ?? 'Unknown error');
      break;
    case 'metadata':
      if (msg.key !== undefined) handlers.onMetadata?.(msg.key, msg.value);
      break;
  }
}
