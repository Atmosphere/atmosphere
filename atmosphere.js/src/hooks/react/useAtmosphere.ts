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
  ConnectionState,
  Subscription,
} from '../../types';
import { useAtmosphereContext } from './provider';
import { useAtmosphereCore } from '../shared/useAtmosphereCore';

/**
 * Options for {@link useAtmosphere}.
 */
export interface UseAtmosphereOptions {
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
 * request URL or transport changes.
 *
 * ```tsx
 * const { data, state, push, disconnect, suspend, resume } = useAtmosphere<ChatMessage>({
 *   request: { url: '/chat', transport: 'websocket' },
 * });
 * ```
 *
 * Requires an {@link AtmosphereProvider} ancestor.
 */
export function useAtmosphere<T = unknown>(
  options: UseAtmosphereOptions,
): UseAtmosphereResult<T> {
  const atmosphere = useAtmosphereContext();
  const { request, enabled = true } = options;

  const { subscription, state, data, error, push, disconnect, suspend, resume } =
    useAtmosphereCore<T>(atmosphere, request, undefined, enabled);

  return {
    subscription,
    state,
    data,
    error,
    push,
    disconnect,
    suspend,
    resume,
  };
}
