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

import { ref, computed, onUnmounted, type ComputedRef, type Ref } from 'vue';
import { OfflineQueue } from '../../queue/offline-queue';
import type {
  OfflineQueueConfig,
  TrackedMessage,
} from '../../types';

/**
 * Vue composable result for the offline queue. The reactive properties
 * track every queue mutation (enqueue, drain, ACK, drop, fail).
 */
export interface UseOfflineQueueResult<T = string | object | ArrayBuffer> {
  /** The underlying queue instance (pass to {@code request.offlineQueue}). */
  queue: OfflineQueue<T>;
  /** Reactive snapshot of queued (pending) messages. */
  messages: ComputedRef<ReadonlyArray<TrackedMessage<T>>>;
  /** Reactive snapshot of in-flight messages awaiting ACK. */
  pending: ComputedRef<ReadonlyArray<TrackedMessage<T>>>;
  /** Reactive count of queued (pending) messages. */
  size: ComputedRef<number>;
  /** Reactive count of in-flight (awaiting ACK) messages. */
  pendingCount: ComputedRef<number>;
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
 * Vue composable that exposes the {@link OfflineQueue} primitive as
 * reactive refs.
 *
 * ```vue
 * <script setup lang="ts">
 * import { useOfflineQueue } from 'atmosphere.js/vue';
 *
 * const offline = useOfflineQueue<string>({ maxSize: 50 });
 *
 * // Pass offline.queue into the AtmosphereRequest so the transport
 * // can drain it on reconnect.
 * </script>
 * ```
 */
export function useOfflineQueue<T = string | object | ArrayBuffer>(
  options: OfflineQueueConfig & { instance?: OfflineQueue<T> } = {},
): UseOfflineQueueResult<T> {
  const { instance, maxSize, drainOnReconnect } = options;
  const queue: OfflineQueue<T> = instance
    ?? new OfflineQueue<T>({ maxSize, drainOnReconnect });

  // The reactive trigger: every queue event bumps a counter, and the
  // computed snapshots read the underlying queue's read-only views.
  const tick: Ref<number> = ref(0);
  const bump = () => { tick.value++; };

  queue.setHandlers({
    onEnqueue: bump,
    onDrain: bump,
    onAck: bump,
    onFailed: bump,
    onDrop: bump,
  });

  onUnmounted(() => {
    queue.setHandlers({});
  });

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const _trigger = (): number => tick.value;

  return {
    queue,
    messages: computed(() => { _trigger(); return queue.messages as ReadonlyArray<TrackedMessage<T>>; }),
    pending: computed(() => { _trigger(); return queue.pending as ReadonlyArray<TrackedMessage<T>>; }),
    size: computed(() => { _trigger(); return queue.size; }),
    pendingCount: computed(() => { _trigger(); return queue.pendingCount; }),
    enqueue: (data: T) => { const m = queue.enqueue(data); bump(); return m; },
    track: (data: T) => { const m = queue.track(data); bump(); return m; },
    acknowledge: (id: string) => queue.acknowledge(id),
    fail: (id: string, error: string) => queue.fail(id, error),
    clear: () => { queue.clear(); bump(); },
  };
}
