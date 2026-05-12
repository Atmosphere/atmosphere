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
import type { TrackedMessage, MessageState } from '../../types';

export interface UseOptimisticOptions<T> {
  maxSize?: number;
  confirmAfterMs?: number;
  onRollback?: (message: TrackedMessage<T>, error: string) => void;
}

export interface UseOptimisticResult<T> {
  send: (data: T) => TrackedMessage<T>;
  commit: (id: string) => void;
  rollback: (id: string, error: string) => void;
  messages: ComputedRef<ReadonlyArray<TrackedMessage<T>>>;
  inFlightCount: ComputedRef<number>;
  clear: () => void;
}

/**
 * Vue composable around {@link OfflineQueue.track}. Mirrors the React
 * version's surface but exposes computed refs for the message list and
 * the in-flight count.
 */
export function useOptimistic<T>(
  options: UseOptimisticOptions<T> = {},
): UseOptimisticResult<T> {
  const { maxSize, confirmAfterMs, onRollback } = options;
  const queue = new OfflineQueue<T>({ maxSize });

  const records: Ref<Map<string, TrackedMessage<T>>> = ref(new Map());
  const tick: Ref<number> = ref(0);
  const timers = new Map<string, ReturnType<typeof setTimeout>>();

  function bump() { tick.value++; }
  function set(id: string, m: TrackedMessage<T>) { records.value.set(id, m); bump(); }

  function send(data: T): TrackedMessage<T> {
    const tracked = queue.track(data);
    set(tracked.id, tracked);
    if (confirmAfterMs && confirmAfterMs > 0) {
      const timer = setTimeout(() => {
        const current = records.value.get(tracked.id);
        if (current && current.state === 'sent') {
          queue.acknowledge(tracked.id);
          set(tracked.id, withState(current, 'confirmed'));
        }
        timers.delete(tracked.id);
      }, confirmAfterMs);
      timers.set(tracked.id, timer);
    }
    return tracked;
  }

  function commit(id: string) {
    const timer = timers.get(id);
    if (timer) { clearTimeout(timer); timers.delete(id); }
    // OfflineQueue.acknowledge mutates the tracked record's state in
    // place (shared reference). Always materialize a fresh record so the
    // computed-from-tick view emits the transition.
    queue.acknowledge(id);
    const current = records.value.get(id);
    if (current) {
      set(id, withState(current, 'confirmed'));
    }
  }

  function rollback(id: string, error: string) {
    const timer = timers.get(id);
    if (timer) { clearTimeout(timer); timers.delete(id); }
    queue.fail(id, error);
    const current = records.value.get(id);
    if (current) {
      const failed = { ...current, state: 'failed' as MessageState, error };
      set(id, failed);
      onRollback?.(failed, error);
    }
  }

  function clear() {
    for (const t of timers.values()) clearTimeout(t);
    timers.clear();
    records.value.clear();
    queue.clear();
    bump();
  }

  onUnmounted(() => {
    for (const t of timers.values()) clearTimeout(t);
    timers.clear();
  });

  return {
    send,
    commit,
    rollback,
    messages: computed(() => { void tick.value; return Array.from(records.value.values()); }),
    inFlightCount: computed(() => {
      void tick.value;
      let n = 0;
      for (const m of records.value.values()) if (m.state === 'sent') n++;
      return n;
    }),
    clear,
  };
}

function withState<T>(m: TrackedMessage<T>, state: MessageState): TrackedMessage<T> {
  return { ...m, state };
}
