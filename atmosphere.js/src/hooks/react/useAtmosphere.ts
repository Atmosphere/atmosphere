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

import { useMemo } from 'react';
import type {
  AtmosphereRequest,
  ConnectionState,
  Subscription,
} from '../../types';
import type { ConnectionStatusSnapshot } from '../../resilience';
import { useAtmosphereContext } from './provider';
import { useAtmosphereCore } from '../shared/useAtmosphereCore';
import type { CoreSubscriptionHandlers } from '../shared/useAtmosphereCore';

/**
 * Options for {@link useAtmosphere}.
 */
export interface UseAtmosphereOptions<T = unknown> extends CoreSubscriptionHandlers<T> {
  /** Atmosphere request configuration (url, transport, etc.). */
  request: AtmosphereRequest;
  /** If false, the connection is not opened automatically (default: true). */
  enabled?: boolean;
}

/**
 * Return type of {@link useAtmosphere}.
 */
export interface UseAtmosphereResult<T> {
  /** The active subscription, or null before connection. */
  subscription: Subscription | null;
  /** Current connection state. */
  state: ConnectionState;
  /** The last message received. */
  data: T | null;
  /** The last error, if any. */
  error: Error | null;
  /**
   * Reactive snapshot of the resilience state (phase + last event +
   * transport + attempt counter + viaFallback flag). Pass directly to
   * {@code <ConnectionStatusBadge status={connectionStatus} />}.
   */
  connectionStatus: ConnectionStatusSnapshot;
  /** Send a message on the subscription. */
  push: (message: string | object | ArrayBuffer) => void;
  /** Close the subscription. */
  disconnect: () => Promise<void>;
  /** Suspend the underlying transport (pause without closing). */
  suspend: () => void;
  /** Resume a previously suspended transport. */
  resume: () => Promise<void>;
}

/**
 * React hook that manages an Atmosphere subscription lifecycle.
 *
 * Connects on mount, disconnects on unmount, and re-connects when the
 * request URL or transport changes. Surfaces the full resilience event
 * stream via `connectionStatus` and optional callbacks
 * (`onReopen`, `onTransportFailure`, `onClientTimeout`,
 * `onFailureToReconnect`).
 *
 * ```tsx
 * const { data, connectionStatus, push } = useAtmosphere<ChatMessage>({
 *   request: { url: '/chat', transport: 'websocket', fallbackTransport: 'sse' },
 *   onTransportFailure: (reason) => console.warn('WS failed:', reason),
 * });
 *
 * return <ConnectionStatusBadge status={connectionStatus} />;
 * ```
 *
 * Requires an {@link AtmosphereProvider} ancestor.
 */
export function useAtmosphere<T = unknown>(
  options: UseAtmosphereOptions<T>,
): UseAtmosphereResult<T> {
  const atmosphere = useAtmosphereContext();
  const {
    request,
    enabled = true,
    onOpen,
    onMessage,
    onClose,
    onError,
    onReconnect,
    onReopen,
    onTransportFailure,
    onClientTimeout,
    onFailureToReconnect,
  } = options;

  // Pack the lifecycle callbacks into a single object the core hook can
  // read through a ref; rebuilding it on every render is fine because
  // the core hook stores the latest ref, not the value.
  const handlers = useMemo<CoreSubscriptionHandlers<T>>(
    () => ({
      onOpen,
      onMessage,
      onClose,
      onError,
      onReconnect,
      onReopen,
      onTransportFailure,
      onClientTimeout,
      onFailureToReconnect,
    }),
    [
      onOpen,
      onMessage,
      onClose,
      onError,
      onReconnect,
      onReopen,
      onTransportFailure,
      onClientTimeout,
      onFailureToReconnect,
    ],
  );

  const {
    subscription,
    state,
    data,
    error,
    connectionStatus,
    push,
    disconnect,
    suspend,
    resume,
  } = useAtmosphereCore<T>(atmosphere, request, handlers, enabled);

  return {
    subscription,
    state,
    data,
    error,
    connectionStatus,
    push,
    disconnect,
    suspend,
    resume,
  };
}
