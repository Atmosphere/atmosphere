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
import type { TrackedMessage, MessageState } from '../../types';
import type { Readable } from './atmosphere';

export interface OptimisticStoreState<T> {
  messages: ReadonlyArray<TrackedMessage<T>>;
  inFlightCount: number;
}

export interface OptimisticStoreHandle<T> {
  store: Readable<OptimisticStoreState<T>>;
  send: (data: T) => TrackedMessage<T>;
  commit: (id: string) => void;
  rollback: (id: string, error: string) => void;
  clear: () => void;
}

/**
 * Svelte-compatible readable store that mirrors the React/Vue
 * {@code useOptimistic} hook. Notifies subscribers on every
 * {@code send/commit/rollback/clear} call.
 */
export function createOptimisticStore<T>(
  options: {
    maxSize?: number;
    confirmAfterMs?: number;
    onRollback?: (message: TrackedMessage<T>, error: string) => void;
  } = {},
): OptimisticStoreHandle<T> {
  const { maxSize, confirmAfterMs, onRollback } = options;
  const queue = new OfflineQueue<T>({ maxSize });
  const records = new Map<string, TrackedMessage<T>>();
  const timers = new Map<string, ReturnType<typeof setTimeout>>();
  const subscribers = new Set<(value: OptimisticStoreState<T>) => void>();

  function snapshot(): OptimisticStoreState<T> {
    const messages = Array.from(records.values());
    return {
      messages,
      inFlightCount: messages.reduce((n, m) => (m.state === 'sent' ? n + 1 : n), 0),
    };
  }

  function notify() {
    const v = snapshot();
    for (const fn of subscribers) fn(v);
  }

  const store: Readable<OptimisticStoreState<T>> = {
    subscribe(run) {
      subscribers.add(run);
      run(snapshot());
      return () => { subscribers.delete(run); };
    },
  };

  function send(data: T): TrackedMessage<T> {
    const tracked = queue.track(data);
    records.set(tracked.id, tracked);
    notify();
    if (confirmAfterMs && confirmAfterMs > 0) {
      const t = setTimeout(() => {
        const current = records.get(tracked.id);
        if (current && current.state === 'sent') {
          queue.acknowledge(tracked.id);
          records.set(tracked.id, { ...current, state: 'confirmed' as MessageState });
          notify();
        }
        timers.delete(tracked.id);
      }, confirmAfterMs);
      timers.set(tracked.id, t);
    }
    return tracked;
  }

  function commit(id: string) {
    const t = timers.get(id);
    if (t) { clearTimeout(t); timers.delete(id); }
    // OfflineQueue.acknowledge mutates the tracked record in place; we
    // always materialize a fresh entry so subscribers see the transition.
    queue.acknowledge(id);
    const current = records.get(id);
    if (current) {
      records.set(id, { ...current, state: 'confirmed' as MessageState });
      notify();
    }
  }

  function rollback(id: string, error: string) {
    const t = timers.get(id);
    if (t) { clearTimeout(t); timers.delete(id); }
    queue.fail(id, error);
    const current = records.get(id);
    if (current) {
      const failed = { ...current, state: 'failed' as MessageState, error };
      records.set(id, failed);
      notify();
      onRollback?.(failed, error);
    }
  }

  function clear() {
    for (const t of timers.values()) clearTimeout(t);
    timers.clear();
    records.clear();
    queue.clear();
    notify();
  }

  return { store, send, commit, rollback, clear };
}
