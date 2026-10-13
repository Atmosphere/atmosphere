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

import type { SubscriptionHandlers } from '../types';
import { logger } from '../utils/logger';

type HandlerName = keyof SubscriptionHandlers;

/** Every handler a transport may call. */
const HANDLER_NAMES: readonly HandlerName[] = [
  'message', 'open', 'close', 'error', 'reconnect', 'clientTimeout', 'transportFailure',
  'failureToReconnect', 'reopen', 'authTokenRefresh', 'authExpired',
];

/**
 * Holds the events a transport reports while `subscribe()` is still connecting
 * and delivers them, in order, once the caller holds the `Subscription`.
 *
 * Every transport can report `open` from inside `connect()` (a WebSocket or SSE
 * `onopen`, the first long-polling or streaming response), i.e. before
 * `subscribe()` has resolved, so an `open` handler could not use the
 * subscription it was about to receive. After {@link release}, the held events
 * are delivered by a task scheduled with `setTimeout(0)`, which runs after the
 * caller's `await subscribe()` continuation, or earlier by the first event the
 * transport reports after `subscribe()` resolved: network events are tasks, so
 * that one also runs after the continuation. From then on events are delivered
 * as they happen.
 *
 * The handlers are read from `target` when an event is delivered, so handlers
 * `subscribe()` installs after the transport was created receive it too.
 */
export class HandlerGate<T> {
  /** The handlers to give the transport. */
  readonly handlers: SubscriptionHandlers<T>;
  private held: Array<() => void> | null = [];
  private released = false;
  private discarded = false;
  private flushing = false;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private isClosed: () => boolean = () => false;

  /**
   * @param target the application's handlers
   * @param suppress decides, when the transport reports an event, that it is
   *        dropped (the fallback logic of `subscribe()` drops the primary
   *        transport's errors until it opened)
   */
  constructor(target: SubscriptionHandlers<T>, suppress: (name: HandlerName) => boolean = () => false) {
    const handlers: Record<string, (...args: unknown[]) => void> = {};
    for (const name of HANDLER_NAMES) {
      handlers[name] = (...args: unknown[]) => {
        if (this.discarded || suppress(name)) {
          return;
        }
        this.dispatch(() => {
          const handler = target[name] as ((...a: unknown[]) => void) | undefined;
          handler?.(...args);
        });
      };
    }
    this.handlers = handlers as SubscriptionHandlers<T>;
  }

  /**
   * `subscribe()` resolved: deliver the held events from a new task, or with
   * the next event, whichever comes first. Held events are dropped when
   * `isClosed()` is true by then: the application closed the subscription
   * before they could reach it.
   */
  release(isClosed: () => boolean): void {
    if (this.held === null || this.discarded) {
      return;
    }
    this.released = true;
    this.isClosed = isClosed;
    this.timer = setTimeout(() => {
      this.timer = null;
      this.flush();
    }, 0);
  }

  /**
   * Deliver the held events now and every later one as it happens. Used when
   * `subscribe()` rejects, so the `error` handler still runs before the
   * rejection is observed.
   */
  flush(): void {
    if (this.held === null || this.flushing || this.discarded) {
      return;
    }
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    if (this.released && this.isClosed()) {
      logger.debug(`Dropping ${this.held.length} event(s) of a subscription closed before they were delivered`);
      this.held = null;
      return;
    }
    this.flushing = true;
    const held = this.held;
    let failure: { error: unknown } | null = null;
    try {
      // A handler may cause new events: they join the queue, keeping the order.
      while (held.length > 0) {
        try {
          held.shift()!();
        } catch (error) {
          // Deliver the remaining events before reporting the first failure.
          failure ??= { error };
        }
      }
    } finally {
      this.held = null;
      this.flushing = false;
    }
    if (failure) {
      throw failure.error;
    }
  }

  /** The transport was abandoned (fallback): drop what it reported and will report. */
  discard(): void {
    this.discarded = true;
    this.held = null;
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }

  private dispatch(call: () => void): void {
    if (this.held === null) {
      call();
      return;
    }
    this.held.push(call);
    if (this.released) {
      this.flush();
    }
  }
}
