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

import { useCallback, useMemo, useState } from 'react';
import type {
  ConnectionEvent,
  ConnectionPhase,
  ConnectionStatusSnapshot,
  ConnectionTransportName,
} from '../../resilience';

/**
 * Options for {@link useExternalConnectionStatus}.
 */
export interface UseExternalConnectionStatusOptions {
  /**
   * Transport name to embed in the snapshot — e.g. `'grpc'`, `'a2a'`,
   * `'ag-ui'`. Shows up in the Badge label as `Connected · <name>`.
   */
  transport: ConnectionTransportName;
  /** Initial phase before any event fires. Defaults to `'idle'`. */
  initialPhase?: ConnectionPhase;
}

/**
 * Return type of {@link useExternalConnectionStatus}.
 *
 * `status` is the reactive snapshot — pass directly to `<ConnectionStatusBadge>`.
 * The `mark*` functions are stateful transitions a non-atmosphere transport
 * client calls at its own lifecycle points (request sent, first byte, stream
 * complete, error).
 */
export interface UseExternalConnectionStatusResult {
  status: ConnectionStatusSnapshot;
  /** Mark the transition to `connecting` (request sent / stream opening). */
  markConnecting: () => void;
  /** Mark the transition to `open` (first byte / connection established). */
  markOpen: () => void;
  /** Mark the transition to `closed` (clean stream end). */
  markClosed: () => void;
  /** Mark the transition to `lost` with an optional error. */
  markLost: (error?: Error) => void;
  /** Reset to `idle`. */
  reset: () => void;
}

/**
 * React hook that lets consumers building on **non-atmosphere** transports
 * (gRPC / Connect, JSON-RPC + SSE, AG-UI, raw fetch, etc.) plug into the
 * same {@link ConnectionStatusBadge} the atmosphere.js hooks expose.
 *
 * Why this exists: `useAtmosphere` / `useStreaming` already produce a
 * `connectionStatus` snapshot internally because they own the subscription
 * lifecycle. When the consumer drives the protocol themselves, they need
 * to *build* the snapshot from their own state — and that's the boilerplate
 * this hook eliminates.
 *
 * ```tsx
 * import { useExternalConnectionStatus, ConnectionStatusBadge } from 'atmosphere.js/react';
 *
 * const { status, markConnecting, markOpen, markLost, markClosed } =
 *   useExternalConnectionStatus({ transport: 'grpc' });
 *
 * const subscribe = useCallback(async () => {
 *   markConnecting();
 *   try {
 *     const stream = client.subscribe(...);
 *     for await (const msg of stream) {
 *       if (firstByte) markOpen();
 *       // ...handle msg
 *     }
 *     markClosed();
 *   } catch (e) {
 *     markLost(e instanceof Error ? e : new Error(String(e)));
 *   }
 * }, []);
 *
 * return <ConnectionStatusBadge status={status} />;
 * ```
 *
 * `lastEvent` is derived from `phase` so the Badge's transient indicator
 * lights up correctly without consumers having to track events explicitly.
 */
export function useExternalConnectionStatus(
  options: UseExternalConnectionStatusOptions,
): UseExternalConnectionStatusResult {
  const initialPhase = options.initialPhase ?? 'idle';
  const [phase, setPhase] = useState<ConnectionPhase>(initialPhase);
  const [since, setSince] = useState<number>(() => Date.now());
  const [lastError, setLastError] = useState<Error | null>(null);

  const bump = useCallback((nextPhase: ConnectionPhase, error: Error | null = null) => {
    setPhase(nextPhase);
    setSince(Date.now());
    setLastError(error);
  }, []);

  const markConnecting = useCallback(() => bump('connecting'), [bump]);
  const markOpen = useCallback(() => bump('open'), [bump]);
  const markClosed = useCallback(() => bump('closed'), [bump]);
  const markLost = useCallback((error?: Error) => bump('lost', error ?? null), [bump]);
  const reset = useCallback(() => bump('idle'), [bump]);

  const transport = options.transport;

  const status: ConnectionStatusSnapshot = useMemo(() => {
    let lastEvent: ConnectionEvent | null;
    switch (phase) {
      case 'open': lastEvent = 'open'; break;
      case 'connecting': lastEvent = null; break;
      case 'reconnecting': lastEvent = 'reconnect'; break;
      case 'lost': lastEvent = 'failureToReconnect'; break;
      case 'closed': lastEvent = 'close'; break;
      case 'idle': default: lastEvent = null;
    }
    return {
      phase,
      lastEvent,
      transport,
      attempt: 0,
      lastError,
      viaFallback: false,
      since,
    };
  }, [phase, since, lastError, transport]);

  return useMemo(
    () => ({ status, markConnecting, markOpen, markClosed, markLost, reset }),
    [status, markConnecting, markOpen, markClosed, markLost, reset],
  );
}
