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

import { useEffect, useMemo, useState, useCallback, useRef } from 'react';
import { OfflineQueue } from '../../queue/offline-queue';
import type {
  OfflineQueueConfig,
  TrackedMessage,
} from '../../types';

/**
 * Options for {@link useOfflineQueue}.
 */
export interface UseOfflineQueueOptions extends OfflineQueueConfig {
  /**
   * Reuse an externally-created {@link OfflineQueue} instance instead of
   * letting the hook create one. Useful when the same queue must be shared
   * across components or driven by code outside the React tree.
   */
  instance?: OfflineQueue;
}

/**
 * Snapshot of the offline queue suitable for rendering. The arrays are
 * fresh references on every reactive update so React's referential-equality
 * checks fire.
 */
export interface UseOfflineQueueResult<T = string | object | ArrayBuffer> {
  /**
   * The underlying queue instance. Pass to {@code request.offlineQueue}
   * when calling {@link useAtmosphere} / {@link useStreaming} so the
   * transport can drain it on reconnect.
   */
  queue: OfflineQueue<T>;
  /** Add a message to the queue (transitions through pending → sent → confirmed). */
  enqueue: (data: T) => TrackedMessage<T>;
  /**
   * Track a directly-sent message (online path) for ACK correlation. Use
   * this when you want optimistic-UI semantics without queueing.
   */
  track: (data: T) => TrackedMessage<T>;
  /** Acknowledge a message by id (state → confirmed). */
  acknowledge: (messageId: string) => void;
  /** Mark a message as failed by id. */
  fail: (messageId: string, error: string) => void;
  /** Clear all queued and pending messages. */
  clear: () => void;
  /** Snapshot of messages waiting to be sent (state === 'pending'). */
  messages: ReadonlyArray<TrackedMessage<T>>;
  /** Snapshot of messages awaiting server ACK (state === 'sent'). */
  pending: ReadonlyArray<TrackedMessage<T>>;
  /** Count of queued (pending) messages. */
  size: number;
  /** Count of in-flight (sent, waiting on ACK) messages. */
  pendingCount: number;
}

/**
 * React hook for the client-side offline message queue.
 *
 * The hook owns an {@link OfflineQueue} (or reuses one passed via
 * {@code options.instance}) and re-renders the component on every queue
 * mutation — drain on reconnect, ACK, drop, failure. Pass the
 * {@code queue} field into the {@link AtmosphereRequest} so the active
 * transport drains it automatically on reconnect (handled by
 * {@code BaseTransport.drainOfflineQueue}).
 *
 * ```tsx
 * const offline = useOfflineQueue<ChatMessage>({ maxSize: 50 });
 * const { connectionStatus, push } = useAtmosphere<ChatMessage>({
 *   request: {
 *     url: '/atmosphere/chat',
 *     transport: 'websocket',
 *     offlineQueue: offline.queue,
 *   },
 * });
 *
 * const send = (msg: ChatMessage) => {
 *   if (connectionStatus.phase === 'open') {
 *     push(msg);
 *   } else {
 *     offline.enqueue(msg);
 *   }
 * };
 *
 * // Visualize queued-while-disconnected messages
 * return offline.messages.map(m => <PendingBubble key={m.id} msg={m} />);
 * ```
 *
 * Server ACK semantics: today the {@link OfflineQueue#acknowledge} call
 * must be driven from application code (e.g. when a broadcast echo for
 * the local sender's message arrives). A future {@code RoomProtocolCodec}
 * change will surface server-confirmed ids automatically — until then
 * the {@code 'confirmed'} state is opt-in.
 */
export function useOfflineQueue<T = string | object | ArrayBuffer>(
  options: UseOfflineQueueOptions = {},
): UseOfflineQueueResult<T> {
  const { instance, maxSize, drainOnReconnect } = options;

  // Create the queue once. Re-rendering must not rebuild it: the queue
  // is referenced by the transport via `request.offlineQueue` and
  // changing the reference between renders would orphan in-flight
  // tracked messages.
  const queueRef = useRef<OfflineQueue<T> | null>(null);
  if (queueRef.current === null) {
    queueRef.current = (instance as OfflineQueue<T> | undefined)
      ?? new OfflineQueue<T>({ maxSize, drainOnReconnect });
  }
  const queue = queueRef.current;

  // Tick state forces a re-render whenever the queue mutates. We snapshot
  // the read-only views inside useMemo against the tick so consumers
  // always see fresh array references.
  const [tick, setTick] = useState(0);
  const bumpTick = useCallback(() => setTick((n) => n + 1), []);

  useEffect(() => {
    queue.setHandlers({
      onDrain: bumpTick,
      onAck: bumpTick,
      onFailed: bumpTick,
      onDrop: bumpTick,
    });
    return () => {
      // Detach handlers when the component unmounts so the queue can
      // outlive this hook instance without holding stale callbacks.
      queue.setHandlers({});
    };
  }, [queue, bumpTick]);

  const enqueue = useCallback((data: T) => {
    const msg = queue.enqueue(data);
    bumpTick();
    return msg;
  }, [queue, bumpTick]);

  const track = useCallback((data: T) => {
    const msg = queue.track(data);
    bumpTick();
    return msg;
  }, [queue, bumpTick]);

  const acknowledge = useCallback((messageId: string) => {
    queue.acknowledge(messageId);
  }, [queue]);

  const fail = useCallback((messageId: string, error: string) => {
    queue.fail(messageId, error);
  }, [queue]);

  const clear = useCallback(() => {
    queue.clear();
    bumpTick();
  }, [queue, bumpTick]);

  // Re-snapshot on every tick so React's identity check fires.
  const snapshot = useMemo(() => ({
    messages: queue.messages as ReadonlyArray<TrackedMessage<T>>,
    pending: queue.pending as ReadonlyArray<TrackedMessage<T>>,
    size: queue.size,
    pendingCount: queue.pendingCount,
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }), [tick, queue]);

  return {
    queue,
    enqueue,
    track,
    acknowledge,
    fail,
    clear,
    ...snapshot,
  };
}
