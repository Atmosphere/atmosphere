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

import { OfflineQueue } from '../../queue/offline-queue';
import type {
  OfflineQueueConfig,
  TrackedMessage,
} from '../../types';
import type { Readable } from './atmosphere';

/**
 * State surfaced by the Svelte offline-queue store.
 */
export interface OfflineQueueStoreState<T = string | object | ArrayBuffer> {
  /** Messages queued offline, awaiting drain. */
  messages: ReadonlyArray<TrackedMessage<T>>;
  /** Messages sent but awaiting server ACK. */
  pending: ReadonlyArray<TrackedMessage<T>>;
  /** Count of queued (offline) messages. */
  size: number;
  /** Count of in-flight messages awaiting ACK. */
  pendingCount: number;
}

/**
 * Imperative handle returned alongside the readable store.
 */
export interface OfflineQueueStoreHandle<T = string | object | ArrayBuffer> {
  /** The underlying queue (pass to {@code request.offlineQueue}). */
  queue: OfflineQueue<T>;
  /** Reactive store of queue state. */
  store: Readable<OfflineQueueStoreState<T>>;
  /** Add a message to the queue. */
  enqueue: (data: T) => TrackedMessage<T>;
  /** Track a directly-sent message for ACK correlation. */
  track: (data: T) => TrackedMessage<T>;
  /** Acknowledge a message (sent → confirmed). */
  acknowledge: (messageId: string) => void;
  /** Mark a message as failed. */
  fail: (messageId: string, error: string) => void;
  /** Clear all queued and pending messages. */
  clear: () => void;
}

/**
 * Creates a Svelte-compatible readable store backed by an
 * {@link OfflineQueue}. The store notifies subscribers on every queue
 * mutation (enqueue, drain, ACK, drop, fail).
 *
 * ```svelte
 * <script lang="ts">
 *   import { createOfflineQueueStore } from 'atmosphere.js/svelte';
 *
 *   const offline = createOfflineQueueStore<string>({ maxSize: 50 });
 *   // Pass offline.queue into the AtmosphereRequest.
 *   // $offline.store.messages, $offline.store.size, ...
 * </script>
 * ```
 */
export function createOfflineQueueStore<T = string | object | ArrayBuffer>(
  options: OfflineQueueConfig & { instance?: OfflineQueue<T> } = {},
): OfflineQueueStoreHandle<T> {
  const { instance, maxSize, drainOnReconnect } = options;
  const queue: OfflineQueue<T> = instance
    ?? new OfflineQueue<T>({ maxSize, drainOnReconnect });

  const subscribers = new Set<(value: OfflineQueueStoreState<T>) => void>();

  function snapshot(): OfflineQueueStoreState<T> {
    return {
      messages: queue.messages as ReadonlyArray<TrackedMessage<T>>,
      pending: queue.pending as ReadonlyArray<TrackedMessage<T>>,
      size: queue.size,
      pendingCount: queue.pendingCount,
    };
  }

  function notify() {
    const value = snapshot();
    for (const fn of subscribers) fn(value);
  }

  queue.setHandlers({
    onEnqueue: notify,
    onDrain: notify,
    onAck: notify,
    onFailed: notify,
    onDrop: notify,
  });

  const store: Readable<OfflineQueueStoreState<T>> = {
    subscribe(run) {
      subscribers.add(run);
      run(snapshot());
      return () => {
        subscribers.delete(run);
      };
    },
  };

  return {
    queue,
    store,
    enqueue: (data: T) => { const m = queue.enqueue(data); notify(); return m; },
    track: (data: T) => { const m = queue.track(data); notify(); return m; },
    acknowledge: (id: string) => queue.acknowledge(id),
    fail: (id: string, error: string) => queue.fail(id, error),
    clear: () => { queue.clear(); notify(); },
  };
}
