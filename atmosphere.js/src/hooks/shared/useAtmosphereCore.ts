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
  SubscriptionHandlers,
} from '../../types';
import type { Atmosphere } from '../../core/atmosphere';

/**
 * Additional event handlers that callers can supply to
 * {@link useAtmosphereCore} without altering the subscription lifecycle.
 */
export interface CoreSubscriptionHandlers<T> {
  onOpen?: () => void;
  onMessage?: (data: T) => void;
  onClose?: () => void;
  onError?: (error: Error) => void;
  onReconnect?: () => void;
}

/**
 * Return type of {@link useAtmosphereCore}.
 *
 * Contains the full subscription lifecycle state plus manual control
 * methods (`disconnect`, `suspend`, `resume`) that let consumers manage
 * the connection without reaching through the raw {@link Subscription}.
 */
export interface UseAtmosphereCoreResult<T> {
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
  /** Ref to the current subscription (for platform-specific effects). */
  subRef: React.RefObject<Subscription | null>;
}

/**
 * Shared React hook that manages an Atmosphere subscription lifecycle.
 *
 * This is the foundational hook used by both `useAtmosphere` (web) and
 * `useAtmosphereRN` (React Native). It handles:
 * - State management (`subscription`, `state`, `data`, `error`)
 * - The subscribe effect with open/message/close/error/reconnect handlers
 * - Cleanup on unmount or dependency change
 * - Manual control callbacks (`push`, `disconnect`, `suspend`, `resume`)
 *
 * Platform-specific hooks layer additional behavior (e.g. AppState,
 * NetInfo) on top of this core.
 */
export function useAtmosphereCore<T = unknown>(
  atmosphere: Atmosphere,
  request: AtmosphereRequest,
  handlers?: CoreSubscriptionHandlers<T>,
  enabled: boolean = true,
): UseAtmosphereCoreResult<T> {
  const [state, setState] = useState<ConnectionState>('disconnected');
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const subRef = useRef<Subscription | null>(null);

  // Keep handlers in a ref so the effect doesn't re-run when handlers change
  const handlersRef = useRef(handlers);
  handlersRef.current = handlers;

  useEffect(() => {
    if (!enabled) return;

    let cancelled = false;

    (async () => {
      try {
        const subscriptionHandlers: SubscriptionHandlers<T> = {
          open: () => {
            if (!cancelled) {
              setState('connected');
              handlersRef.current?.onOpen?.();
            }
          },
          message: (response) => {
            if (!cancelled) {
              setState('connected');
              setData(response.responseBody);
              handlersRef.current?.onMessage?.(response.responseBody);
            }
          },
          close: () => {
            if (!cancelled) {
              setState('closed');
              handlersRef.current?.onClose?.();
            }
          },
          error: (err) => {
            if (!cancelled) {
              setState('error');
              setError(err);
              handlersRef.current?.onError?.(err);
            }
          },
          reconnect: () => {
            if (!cancelled) {
              setState('reconnecting');
              handlersRef.current?.onReconnect?.();
            }
          },
        };

        const sub = await atmosphere.subscribe<T>(request, subscriptionHandlers);
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

  const disconnect = useCallback(async () => {
    const sub = subRef.current;
    if (sub) {
      await sub.close();
      subRef.current = null;
    }
  }, []);

  const suspend = useCallback(() => {
    subRef.current?.suspend();
  }, []);

  const resume = useCallback(async () => {
    const sub = subRef.current;
    if (sub) {
      await sub.resume();
    }
  }, []);

  return {
    subscription: subRef.current,
    state,
    data,
    error,
    push,
    disconnect,
    suspend,
    resume,
    subRef,
  };
}
