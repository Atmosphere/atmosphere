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

import { useCallback, useEffect, useRef, useState } from 'react';
import { OfflineQueue } from '../../queue/offline-queue';
import type {
  TrackedMessage,
  MessageState,
} from '../../types';

export interface UseOptimisticOptions<T> {
  /**
   * Optional cap on the number of in-flight optimistic messages tracked
   * before the oldest is evicted. Mirrors {@code OfflineQueue.maxSize}.
   */
  maxSize?: number;
  /**
   * If set, an optimistic send that has not been explicitly
   * {@link UseOptimisticResult#commit committed} or
   * {@link UseOptimisticResult#rollback rolled back} within this many
   * milliseconds is auto-confirmed. Useful for fire-and-forget streams
   * where the server does not echo acknowledgements back to the sender.
   */
  confirmAfterMs?: number;
  /**
   * Called after {@link UseOptimisticResult#rollback rollback} — typically
   * to surface the failure in the UI (toast, banner, etc.).
   */
  onRollback?: (message: TrackedMessage<T>, error: string) => void;
}

export interface UseOptimisticResult<T> {
  /**
   * Optimistically commit a message: appends it to {@link #messages} in
   * state {@code 'sent'} immediately and returns the tracked record so
   * the caller can pass the same id to {@link #commit} / {@link #rollback}
   * later.
   */
  send: (data: T) => TrackedMessage<T>;
  /** Mark a pending optimistic message as confirmed (server ACK received). */
  commit: (id: string) => void;
  /** Mark a pending optimistic message as failed and surface the reason. */
  rollback: (id: string, error: string) => void;
  /**
   * Live list of optimistic messages in arrival order. Each entry carries
   * a {@link MessageState}: {@code 'sent'} (in flight), {@code 'confirmed'}
   * (server-ack'd or auto-confirmed), {@code 'failed'} (rolled back).
   */
  messages: ReadonlyArray<TrackedMessage<T>>;
  /** Count of messages currently in {@code 'sent'} state. */
  inFlightCount: number;
  /** Forget every optimistic message regardless of state. */
  clear: () => void;
}

/**
 * Reactive optimistic-update hook. Renders a message in the UI before
 * the server confirms delivery, then flips state to confirmed/failed
 * once the round-trip resolves. Built on the {@link OfflineQueue}
 * tracking primitive so the same correlation id can drive both
 * optimistic UI and offline-queue ACK paths.
 *
 * ```tsx
 * const optimistic = useOptimistic<{ text: string }>({ confirmAfterMs: 5_000 });
 *
 * const onSendClick = (text: string) => {
 *   const handle = optimistic.send({ text });
 *   push(JSON.stringify({ ...handle.data, clientMessageId: handle.id }));
 * };
 *
 * const onServerEcho = (msg: { clientMessageId: string }) => {
 *   optimistic.commit(msg.clientMessageId);
 * };
 *
 * return optimistic.messages.map(m =>
 *   <ChatBubble
 *     key={m.id}
 *     message={m.data.text}
 *     sending={m.state === 'sent'}
 *     failed={m.state === 'failed'}
 *   />
 * );
 * ```
 *
 * @since 5.0.0
 */
export function useOptimistic<T>(
  options: UseOptimisticOptions<T> = {},
): UseOptimisticResult<T> {
  const { maxSize, confirmAfterMs, onRollback } = options;

  const queueRef = useRef<OfflineQueue<T> | null>(null);
  if (queueRef.current === null) {
    queueRef.current = new OfflineQueue<T>({ maxSize });
  }
  const queue = queueRef.current;

  // Mirror the queue's full pending + recently-resolved set as a reactive
  // array. Unlike `useOfflineQueue` which exposes the raw read-only views,
  // optimistic UI also wants to see confirmed/failed records (briefly) so
  // the bubble can fade or display a retry affordance.
  const [messages, setMessages] = useState<ReadonlyArray<TrackedMessage<T>>>([]);
  const timersRef = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());

  // Local store: id → mutable tracked record. The OfflineQueue primitive
  // drops entries from pendingAcks the moment they resolve, so we mirror
  // them here to keep the UI rendering "delivered" or "failed" bubbles
  // until the caller explicitly clears or evicts via maxSize.
  const recordsRef = useRef<Map<string, TrackedMessage<T>>>(new Map());

  const reconcile = useCallback(() => {
    setMessages(Array.from(recordsRef.current.values()));
  }, []);

  const send = useCallback((data: T): TrackedMessage<T> => {
    const tracked = queue.track(data);
    recordsRef.current.set(tracked.id, tracked);
    if (confirmAfterMs && confirmAfterMs > 0) {
      const timer = setTimeout(() => {
        const current = recordsRef.current.get(tracked.id);
        if (current && current.state === 'sent') {
          // Promote to confirmed in our mirror; the underlying queue
          // also gets the ack so any downstream consumer sees the
          // transition.
          queue.acknowledge(tracked.id);
          recordsRef.current.set(tracked.id, withState(current, 'confirmed'));
          reconcile();
        }
        timersRef.current.delete(tracked.id);
      }, confirmAfterMs);
      timersRef.current.set(tracked.id, timer);
    }
    reconcile();
    return tracked;
  }, [queue, confirmAfterMs, reconcile]);

  const commit = useCallback((id: string) => {
    const timer = timersRef.current.get(id);
    if (timer) {
      clearTimeout(timer);
      timersRef.current.delete(id);
    }
    // queue.acknowledge mutates the tracked record's state in place
    // (shared object reference). Always materialize a fresh record into
    // our mirror so React state-equality sees the transition and
    // {@code inFlightCount} updates in the next render.
    queue.acknowledge(id);
    const current = recordsRef.current.get(id);
    if (current) {
      recordsRef.current.set(id, withState(current, 'confirmed'));
      reconcile();
    }
  }, [queue, reconcile]);

  const rollback = useCallback((id: string, error: string) => {
    const timer = timersRef.current.get(id);
    if (timer) {
      clearTimeout(timer);
      timersRef.current.delete(id);
    }
    queue.fail(id, error);
    const current = recordsRef.current.get(id);
    if (current) {
      const failed = { ...current, state: 'failed' as MessageState, error };
      recordsRef.current.set(id, failed);
      reconcile();
      onRollback?.(failed, error);
    }
  }, [queue, reconcile, onRollback]);

  const clear = useCallback(() => {
    for (const timer of timersRef.current.values()) {
      clearTimeout(timer);
    }
    timersRef.current.clear();
    recordsRef.current.clear();
    queue.clear();
    reconcile();
  }, [queue, reconcile]);

  // Tear down lingering timers when the component unmounts so no stale
  // setTimeout fires against an unmounted setState.
  useEffect(() => {
    return () => {
      for (const timer of timersRef.current.values()) {
        clearTimeout(timer);
      }
      timersRef.current.clear();
    };
  }, []);

  const inFlightCount = messages.reduce(
    (n, m) => (m.state === 'sent' ? n + 1 : n),
    0,
  );

  return { send, commit, rollback, messages, inFlightCount, clear };
}

function withState<T>(
  m: TrackedMessage<T>,
  state: MessageState,
): TrackedMessage<T> {
  return { ...m, state };
}
