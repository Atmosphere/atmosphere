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

import type { TransportType } from '../types';

/**
 * Steady-state phase of an Atmosphere connection.
 *
 * The state machine is intentionally small so UIs can render a single
 * status indicator without inspecting the full lifecycle event stream:
 *
 *   idle ──subscribe()──▶ connecting ──open──▶ open ──close/reconnect──▶ reconnecting
 *                              │                                            │
 *                              └──fail (no fallback)──▶ lost ◀──exhausted──┘
 *                              │
 *                              └──close (clean)──▶ closed
 *
 * `closed` and `lost` are terminal until the next subscribe() call.
 */
export type ConnectionPhase =
  | 'idle'
  | 'connecting'
  | 'open'
  | 'reconnecting'
  | 'closed'
  | 'lost';

/**
 * Transient lifecycle event emitted alongside a phase change. UIs can
 * react to these for one-shot affordances (toasts, sound effects) that
 * the steady-state phase alone cannot express — for example, distinguishing
 * a first `open` from a `reopen` after a disconnect, or surfacing a
 * `transportFailure` that the steady-state phase folds into `connecting`.
 *
 * Maps 1:1 to the underlying {@link SubscriptionHandlers} callbacks.
 */
export type ConnectionEvent =
  | 'open'
  | 'reopen'
  | 'reconnect'
  | 'close'
  | 'error'
  | 'transportFailure'
  | 'clientTimeout'
  | 'failureToReconnect';

/**
 * Immutable snapshot of the connection's resilience state.
 */
export interface ConnectionStatusSnapshot {
  /** Steady-state phase. */
  readonly phase: ConnectionPhase;
  /** Last lifecycle event, or null if nothing has happened yet. */
  readonly lastEvent: ConnectionEvent | null;
  /** Currently-active transport. Updates after transport fallback. */
  readonly transport: TransportType;
  /** Reconnect attempt counter; resets to 0 on successful open. */
  readonly attempt: number;
  /** Most recent error, if any. */
  readonly lastError: Error | null;
  /**
   * Whether the current transport is a fallback (i.e. a transportFailure
   * event was observed since the last successful open). Useful for
   * showing a degraded-mode badge.
   */
  readonly viaFallback: boolean;
  /** Epoch ms when the current phase began. */
  readonly since: number;
}

/**
 * Options for {@link ConnectionStatus}.
 */
export interface ConnectionStatusOptions {
  /** Initial transport to record before the first event. Defaults to 'websocket'. */
  initialTransport?: TransportType;
  /**
   * Source of monotonic time for the `since` field, in ms. Defaults to
   * {@code Date.now}. Override for deterministic tests.
   */
  now?: () => number;
}
