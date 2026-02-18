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

import type {
  AtmosphereRequest,
  ConnectionState,
  Subscription,
} from '../../types';
import { Atmosphere } from '../../core/atmosphere';

/** Minimal Svelte-compatible readable store contract. */
export interface Readable<T> {
  subscribe(run: (value: T) => void): () => void;
}

/**
 * State exposed by {@link createAtmosphereStore}.
 */
export interface AtmosphereStoreState<T> {
  state: ConnectionState;
  data: T | null;
  error: Error | null;
  subscription: Subscription | null;
}

/**
 * Creates a Svelte-compatible readable store backed by an Atmosphere subscription.
 *
 * The store connects immediately and disconnects when all subscribers unsubscribe.
 *
 * ```svelte
 * <script>
 *   import { createAtmosphereStore } from 'atmosphere.js/svelte';
 *
 *   const chat = createAtmosphereStore({ url: '/chat', transport: 'websocket' });
 *   // $chat.state, $chat.data, $chat.error
 * </script>
 * ```
 *
 * Returns an object with the store and a `push` function for sending messages.
 */
export function createAtmosphereStore<T = unknown>(
  request: AtmosphereRequest,
  instance?: Atmosphere,
) {
  const atmosphere = instance ?? new Atmosphere();
  const subscribers = new Set<(value: AtmosphereStoreState<T>) => void>();

  let current: AtmosphereStoreState<T> = {
    state: 'disconnected',
    data: null,
    error: null,
    subscription: null,
  };

  let sub: Subscription | null = null;
  let connected = false;

  function notify() {
    for (const fn of subscribers) fn(current);
  }

  function update(partial: Partial<AtmosphereStoreState<T>>) {
    current = { ...current, ...partial };
    notify();
  }

  async function connect() {
    if (connected) return;
    connected = true;
    try {
      sub = await atmosphere.subscribe<T>(request, {
        open: () => update({ state: 'connected' }),
        message: (response) =>
          update({ state: 'connected', data: response.responseBody }),
        close: () => update({ state: 'closed' }),
        error: (err) => update({ state: 'error', error: err }),
        reconnect: () => update({ state: 'reconnecting' }),
      });
      update({ subscription: sub, state: sub.state });
    } catch (err) {
      update({
        state: 'error',
        error: err instanceof Error ? err : new Error(String(err)),
      });
    }
  }

  function disconnect() {
    connected = false;
    sub?.close();
    sub = null;
  }

  const store: Readable<AtmosphereStoreState<T>> = {
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

  function push(message: string | object | ArrayBuffer) {
    sub?.push(message);
  }

  return { store, push };
}
