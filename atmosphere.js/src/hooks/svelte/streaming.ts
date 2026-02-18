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

import type { AtmosphereRequest } from '../../types';
import type { StreamingHandle } from '../../streaming/types';
import { subscribeStreaming } from '../../streaming';
import { Atmosphere } from '../../core/atmosphere';
import type { Readable } from './atmosphere';

/**
 * State exposed by {@link createStreamingStore}.
 */
export interface StreamingStoreState {
  tokens: string[];
  fullText: string;
  isStreaming: boolean;
  progress: string | null;
  metadata: Record<string, unknown>;
  error: string | null;
}

/**
 * Creates a Svelte-compatible readable store for AI/LLM streaming.
 *
 * ```svelte
 * <script>
 *   import { createStreamingStore } from 'atmosphere.js/svelte';
 *
 *   const { store, send, reset } = createStreamingStore(
 *     { url: '/ai/chat', transport: 'websocket' },
 *   );
 *   // $store.fullText, $store.isStreaming
 * </script>
 *
 * <button on:click={() => send('What is Atmosphere?')}>Ask</button>
 * <p>{$store.fullText}</p>
 * ```
 */
export function createStreamingStore(
  request: AtmosphereRequest,
  instance?: Atmosphere,
) {
  const atmosphere = instance ?? new Atmosphere();
  const subscribers = new Set<(value: StreamingStoreState) => void>();

  let current: StreamingStoreState = {
    tokens: [],
    fullText: '',
    isStreaming: false,
    progress: null,
    metadata: {},
    error: null,
  };

  let handle: StreamingHandle | null = null;
  let connected = false;

  function notify() {
    for (const fn of subscribers) fn(current);
  }

  function update(partial: Partial<StreamingStoreState>) {
    current = { ...current, ...partial };
    if ('tokens' in partial) {
      current.fullText = current.tokens.join('');
    }
    notify();
  }

  async function connect() {
    if (connected) return;
    connected = true;
    try {
      handle = await subscribeStreaming(atmosphere, request, {
        onToken: (token) => {
          update({ tokens: [...current.tokens, token], isStreaming: true });
        },
        onProgress: (msg) => {
          update({ progress: msg });
        },
        onComplete: () => {
          update({ isStreaming: false });
        },
        onError: (err) => {
          update({ error: err, isStreaming: false });
        },
        onMetadata: (key, value) => {
          update({ metadata: { ...current.metadata, [key]: value } });
        },
      });
    } catch (err) {
      update({
        error: err instanceof Error ? err.message : String(err),
      });
    }
  }

  function disconnect() {
    connected = false;
    handle?.close();
    handle = null;
  }

  const store: Readable<StreamingStoreState> = {
    subscribe(run) {
      subscribers.add(run);
      if (subscribers.size === 1) connect();
      run(current);
      return () => {
        subscribers.delete(run);
        if (subscribers.size === 0) disconnect();
      };
    },
  };

  function send(message: string | object) {
    update({ isStreaming: true, error: null });
    handle?.send(message);
  }

  function reset() {
    update({
      tokens: [],
      fullText: '',
      isStreaming: false,
      progress: null,
      metadata: {},
      error: null,
    });
  }

  return { store, send, reset };
}
