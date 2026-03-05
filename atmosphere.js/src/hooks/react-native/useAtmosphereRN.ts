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

import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { AppState } from 'react-native';
import type {
  AtmosphereRequest,
  ConnectionState,
  Subscription,
} from '../../types';
import { useAtmosphereContext } from '../react/provider';
import { getRegisteredNetInfo } from '../../react-native/platform';

/**
 * Background behavior when the React Native app is not in the foreground.
 * - `suspend` — pause the transport (default); resume on foreground
 * - `disconnect` — fully close the connection; reconnect on foreground
 * - `keep-alive` — do nothing; connection stays open
 */
export type BackgroundBehavior = 'suspend' | 'disconnect' | 'keep-alive';

/**
 * Options for {@link useAtmosphereRN}.
 */
export interface UseAtmosphereRNOptions {
  request: AtmosphereRequest;
  enabled?: boolean;
  backgroundBehavior?: BackgroundBehavior;
}

/**
 * Return type of {@link useAtmosphereRN}.
 */
export interface UseAtmosphereRNResult<T> {
  subscription: Subscription | null;
  state: ConnectionState;
  data: T | null;
  error: Error | null;
  push: (message: string | object | ArrayBuffer) => void;
  isConnected: boolean;
  isInternetReachable: boolean;
}


/**
 * React Native hook that manages an Atmosphere subscription with
 * AppState and optional NetInfo integration.
 *
 * - Auto-suspends/disconnects when the app moves to background
 * - Auto-resumes/reconnects when the app returns to foreground
 * - Suppresses reconnection when device is offline (if NetInfo is installed)
 * - Falls back gracefully when NetInfo is not available
 *
 * Requires an {@link AtmosphereProvider} ancestor.
 *
 * ```tsx
 * const { data, state, push, isConnected } = useAtmosphereRN<ChatMessage>({
 *   request: { url: 'https://example.com/chat', transport: 'websocket' },
 *   backgroundBehavior: 'suspend',
 * });
 * ```
 */
export function useAtmosphereRN<T = unknown>(
  options: UseAtmosphereRNOptions,
): UseAtmosphereRNResult<T> {
  const atmosphere = useAtmosphereContext();
  const { request, enabled = true, backgroundBehavior = 'suspend' } = options;

  const [state, setState] = useState<ConnectionState>('disconnected');
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [isConnected, setIsConnected] = useState(true);
  const [isInternetReachable, setIsInternetReachable] = useState(true);

  const subRef = useRef<Subscription | null>(null);
  const backgroundBehaviorRef = useRef(backgroundBehavior);
  backgroundBehaviorRef.current = backgroundBehavior;

  // Track whether we need to reconnect on foreground
  const wasBackgroundedRef = useRef(false);

  // --- Core subscription lifecycle ---
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [atmosphere, request.url, request.transport, enabled]);

  // --- AppState integration ---
  useEffect(() => {
    if (!AppState) return;

    const handleAppState = (nextState: string) => {
      const sub = subRef.current;
      if (!sub) return;

      if (nextState === 'background' || nextState === 'inactive') {
        const behavior = backgroundBehaviorRef.current;
        if (behavior === 'suspend') {
          sub.suspend();
          wasBackgroundedRef.current = true;
        } else if (behavior === 'disconnect') {
          sub.close();
          wasBackgroundedRef.current = true;
        }
        // 'keep-alive' does nothing
      } else if (nextState === 'active' && wasBackgroundedRef.current) {
        wasBackgroundedRef.current = false;
        sub.resume().catch(() => {
          // Resume failed — state will reflect via handlers
        });
      }
    };

    const subscription = AppState.addEventListener('change', handleAppState);
    return () => subscription.remove();
  }, []);

  // --- NetInfo integration (optional, requires setupReactNative({ netInfo })) ---
  useEffect(() => {
    const netInfo = getRegisteredNetInfo();
    if (!netInfo) return;

    const unsubscribe = netInfo.addEventListener((netState) => {
      const connected = netState.isConnected ?? true;
      const reachable = netState.isInternetReachable ?? true;

      setIsConnected(connected);
      setIsInternetReachable(reachable);

      const sub = subRef.current;
      if (!sub) return;

      if (!connected) {
        // Offline — suspend to avoid fruitless reconnects
        sub.suspend();
      } else if (sub.state === 'suspended') {
        // Back online — resume
        sub.resume().catch(() => {});
      }
    });

    return () => unsubscribe();
  }, []);

  const push = useCallback(
    (message: string | object | ArrayBuffer) => {
      subRef.current?.push(message);
    },
    [],
  );

  return useMemo(
    () => ({
      subscription: subRef.current,
      state,
      data,
      error,
      push,
      isConnected,
      isInternetReachable,
    }),
    [state, data, error, push, isConnected, isInternetReachable],
  );
}
