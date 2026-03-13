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
  TrackedMessage,
  MessageState,
  OfflineQueueConfig,
  MessageTrackingHandlers,
} from '../types';
import { logger } from '../utils/logger';

let idCounter = 0;

function generateId(): string {
  return `msg-${Date.now()}-${++idCounter}`;
}

/**
 * Client-side offline message queue with optimistic state tracking.
 *
 * When the connection is unavailable, messages are queued locally.
 * On reconnect, the queue is drained automatically. Each message
 * is tracked through states: pending → sent → confirmed | failed.
 *
 * ```typescript
 * const queue = new OfflineQueue({ maxSize: 50 });
 *
 * // When disconnected, queue instead of throwing
 * queue.enqueue('Hello');
 *
 * // On reconnect, drain to the send function
 * queue.drain((msg) => subscription.push(msg));
 * ```
 *
 * @since 5.0.0
 */
export class OfflineQueue<T = string | object | ArrayBuffer> {
  private queue: MutableTrackedMessage<T>[] = [];
  private readonly config: Required<OfflineQueueConfig>;
  private handlers: MessageTrackingHandlers<T> = {};
  private pendingAcks = new Map<string, MutableTrackedMessage<T>>();

  constructor(config: OfflineQueueConfig = {}) {
    this.config = {
      maxSize: config.maxSize ?? 100,
      drainOnReconnect: config.drainOnReconnect ?? true,
    };
  }

  /**
   * Set handlers for queue events.
   */
  setHandlers(handlers: MessageTrackingHandlers<T>): void {
    this.handlers = handlers;
  }

  /**
   * Add a message to the offline queue.
   * Returns the tracked message with a client-generated ID.
   */
  enqueue(data: T): TrackedMessage<T> {
    const msg: MutableTrackedMessage<T> = {
      id: generateId(),
      data,
      state: 'pending',
      createdAt: Date.now(),
    };

    if (this.queue.length >= this.config.maxSize) {
      const dropped = this.queue.shift()!;
      logger.warn(`Offline queue full (${this.config.maxSize}), dropping oldest message`);
      this.handlers.onDrop?.(dropped);
    }

    this.queue.push(msg);
    logger.debug(`Enqueued message ${msg.id} (queue size: ${this.queue.length})`);
    return msg;
  }

  /**
   * Drain the queue, sending each message via the provided send function.
   * Messages transition from 'pending' to 'sent'.
   */
  drain(sendFn: (data: T, messageId: string) => void): void {
    const pending = [...this.queue];
    this.queue = [];

    for (const msg of pending) {
      try {
        msg.state = 'sent';
        this.pendingAcks.set(msg.id, msg);
        sendFn(msg.data, msg.id);
        this.handlers.onDrain?.(msg);
        logger.debug(`Drained message ${msg.id}`);
      } catch (e) {
        msg.state = 'failed';
        msg.error = e instanceof Error ? e.message : String(e);
        this.handlers.onFailed?.(msg, msg.error);
        logger.warn(`Failed to drain message ${msg.id}: ${msg.error}`);
      }
    }
  }

  /**
   * Acknowledge a message by ID. Transitions from 'sent' to 'confirmed'.
   */
  acknowledge(messageId: string): void {
    const msg = this.pendingAcks.get(messageId);
    if (msg) {
      msg.state = 'confirmed';
      this.pendingAcks.delete(messageId);
      this.handlers.onAck?.(messageId);
      logger.debug(`Message ${messageId} confirmed`);
    }
  }

  /**
   * Mark a message as failed.
   */
  fail(messageId: string, error: string): void {
    const msg = this.pendingAcks.get(messageId);
    if (msg) {
      msg.state = 'failed';
      msg.error = error;
      this.pendingAcks.delete(messageId);
      this.handlers.onFailed?.(msg, error);
    }
  }

  /**
   * Track a directly-sent message (not from queue) for ACK tracking.
   * Used for optimistic updates when the connection is online.
   */
  track(data: T): TrackedMessage<T> {
    const msg: MutableTrackedMessage<T> = {
      id: generateId(),
      data,
      state: 'sent',
      createdAt: Date.now(),
    };
    this.pendingAcks.set(msg.id, msg);
    return msg;
  }

  /** Current queue size (messages waiting to be sent). */
  get size(): number {
    return this.queue.length;
  }

  /** Number of messages awaiting server acknowledgment. */
  get pendingCount(): number {
    return this.pendingAcks.size;
  }

  /** Whether auto-drain on reconnect is enabled. */
  get drainOnReconnect(): boolean {
    return this.config.drainOnReconnect;
  }

  /** Get all queued messages (read-only snapshot). */
  get messages(): ReadonlyArray<TrackedMessage<T>> {
    return [...this.queue];
  }

  /** Get all messages pending acknowledgment (read-only snapshot). */
  get pending(): ReadonlyArray<TrackedMessage<T>> {
    return [...this.pendingAcks.values()];
  }

  /** Clear all queued messages and pending acks. */
  clear(): void {
    this.queue = [];
    this.pendingAcks.clear();
  }
}

interface MutableTrackedMessage<T> {
  readonly id: string;
  readonly data: T;
  state: MessageState;
  readonly createdAt: number;
  error?: string;
}
