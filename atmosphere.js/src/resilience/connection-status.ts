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
  AtmosphereResponse,
  SubscriptionHandlers,
} from '../types';
import type {
  ConnectionEvent,
  ConnectionPhase,
  ConnectionStatusOptions,
  ConnectionStatusSnapshot,
} from './types';

/**
 * Tracks the resilience state of an Atmosphere subscription by listening
 * to all eight lifecycle hooks (`open`, `reopen`, `reconnect`, `close`,
 * `error`, `transportFailure`, `clientTimeout`, `failureToReconnect`) and
 * collapsing them into a small phase machine plus a transient event
 * indicator.
 *
 * Typical usage with the vanilla client:
 *
 * ```ts
 * const status = new ConnectionStatus();
 * status.onChange((s) => renderBadge(s));
 * const sub = await atmosphere.subscribe(request, status.wrap({
 *   message: (m) => console.log(m),
 * }));
 * ```
 *
 * Framework adapters (React, Vue, Svelte, React Native) wrap this class
 * to expose a reactive snapshot.
 */
export class ConnectionStatus {
  private readonly now: () => number;
  private current: ConnectionStatusSnapshot;
  private readonly listeners = new Set<(snapshot: ConnectionStatusSnapshot) => void>();

  constructor(options: ConnectionStatusOptions = {}) {
    this.now = options.now ?? (() => Date.now());
    this.current = {
      phase: 'idle',
      lastEvent: null,
      transport: options.initialTransport ?? 'websocket',
      attempt: 0,
      lastError: null,
      viaFallback: false,
      since: this.now(),
    };
  }

  /** Current immutable snapshot. */
  get snapshot(): ConnectionStatusSnapshot {
    return this.current;
  }

  /**
   * Subscribe to snapshot changes. Returns an unsubscribe function.
   *
   * The listener fires synchronously on every phase or event change,
   * including transient events like `transportFailure` that do not
   * change the steady-state phase.
   */
  onChange(listener: (snapshot: ConnectionStatusSnapshot) => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  /**
   * Wrap a {@link SubscriptionHandlers} object so that this status tracks
   * every lifecycle event. The original handlers are preserved and called
   * after the status updates, so consumers can mix existing callbacks with
   * the resilience tracking without losing behavior.
   */
  wrap<T = unknown>(handlers: SubscriptionHandlers<T> = {}): SubscriptionHandlers<T> {
    return {
      ...handlers,
      open: (response) => {
        this.markOpen(response);
        handlers.open?.(response);
      },
      reopen: (response) => {
        // reopen fires when the connection is re-established after a
        // disconnect. Some transports emit ONLY reopen on reconnect (no
        // preceding open), so it must drive the phase transition itself
        // back to 'open'.
        this.update({
          phase: 'open',
          lastEvent: 'reopen',
          transport: response?.transport ?? this.current.transport,
          attempt: 0,
          lastError: null,
        });
        handlers.reopen?.(response);
      },
      reconnect: (request, response) => {
        this.markReconnect(request);
        handlers.reconnect?.(request, response);
      },
      close: (response) => {
        this.markEvent('close', { phase: 'closed' });
        handlers.close?.(response);
      },
      error: (error) => {
        this.markEvent('error', { lastError: error });
        handlers.error?.(error);
      },
      transportFailure: (reason, request) => {
        this.markTransportFailure(reason, request);
        handlers.transportFailure?.(reason, request);
      },
      clientTimeout: (request) => {
        this.markEvent('clientTimeout');
        handlers.clientTimeout?.(request);
      },
      failureToReconnect: (request, response) => {
        this.markEvent('failureToReconnect', { phase: 'lost' });
        handlers.failureToReconnect?.(request, response);
      },
      message: handlers.message,
      authTokenRefresh: handlers.authTokenRefresh,
      authExpired: handlers.authExpired,
    };
  }

  /**
   * Reset to the `idle` state. Useful when a consumer tears down a
   * subscription and intends to create a fresh one against the same
   * status instance.
   */
  reset(): void {
    this.update({
      phase: 'idle',
      lastEvent: null,
      attempt: 0,
      lastError: null,
      viaFallback: false,
    });
  }

  private markOpen(response: AtmosphereResponse<unknown> | undefined): void {
    const isReopen = this.current.phase === 'reconnecting';
    this.update({
      phase: 'open',
      lastEvent: isReopen ? 'reopen' : 'open',
      transport: response?.transport ?? this.current.transport,
      attempt: 0,
      lastError: null,
    });
  }

  private markReconnect(request: AtmosphereRequest | undefined): void {
    this.update({
      phase: 'reconnecting',
      lastEvent: 'reconnect',
      transport: request?.transport ?? this.current.transport,
      attempt: this.current.attempt + 1,
    });
  }

  private markTransportFailure(reason: string, request: AtmosphereRequest | undefined): void {
    this.update({
      lastEvent: 'transportFailure',
      transport: request?.fallbackTransport ?? request?.transport ?? this.current.transport,
      viaFallback: true,
      lastError: new Error(reason),
    });
  }

  private markEvent(
    event: ConnectionEvent,
    patch: Partial<Omit<ConnectionStatusSnapshot, 'lastEvent' | 'since'>> = {},
  ): void {
    this.update({ lastEvent: event, ...patch });
  }

  private update(
    patch: Partial<Omit<ConnectionStatusSnapshot, 'since'>> & { phase?: ConnectionPhase },
  ): void {
    const previousPhase = this.current.phase;
    const nextPhase = patch.phase ?? previousPhase;
    this.current = {
      ...this.current,
      ...patch,
      phase: nextPhase,
      since: nextPhase === previousPhase ? this.current.since : this.now(),
    };
    for (const listener of this.listeners) {
      listener(this.current);
    }
  }
}
