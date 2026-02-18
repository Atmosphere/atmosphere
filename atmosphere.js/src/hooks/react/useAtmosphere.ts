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

import { useState, useEffect, useRef, useCallback } from 'react';
import type {
  AtmosphereRequest,
  ConnectionState,
  Subscription,
} from '../../types';
import { useAtmosphereContext } from './provider';

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
}

/**
 * React hook that manages an Atmosphere subscription lifecycle.
 *
 * Connects on mount, disconnects on unmount, and re-connects when the
 * request URL or transport changes.
 *
 * ```tsx
 * const { data, state, push } = useAtmosphere<ChatMessage>({
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

  const [state, setState] = useState<ConnectionState>('disconnected');
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const subRef = useRef<Subscription | null>(null);

  useEffect(() => {
    if (!enabled) return;

    let cancelled = false;

    (async () => {
      try {
        const sub = await atmosphere.subscribe<T>(request, {
          open: () => {
            if (!cancelled) setState('connected');
          },
          message: (response) => {
            if (!cancelled) {
              setState('connected');
              setData(response.responseBody);
            }
          },
          close: () => {
            if (!cancelled) setState('closed');
          },
          error: (err) => {
            if (!cancelled) {
              setState('error');
              setError(err);
            }
          },
          reconnect: () => {
            if (!cancelled) setState('reconnecting');
          },
        });
        if (!cancelled) {
          subRef.current = sub;
          setState(sub.state);
        } else {
          await sub.close();
        }
      } catch (err) {
        if (!cancelled) {
          setState('error');
          setError(err instanceof Error ? err : new Error(String(err)));
        }
      }
    })();

    return () => {
      cancelled = true;
      subRef.current?.close();
      subRef.current = null;
    };
    // Reconnect when URL or transport changes
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [atmosphere, request.url, request.transport, enabled]);

  const push = useCallback(
    (message: string | object | ArrayBuffer) => {
      subRef.current?.push(message);
    },
    [],
  );

  return {
    subscription: subRef.current,
    state,
    data,
    error,
    push,
  };
}
