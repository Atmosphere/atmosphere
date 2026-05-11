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
import type {
  AtmosphereRequest,
  ConnectionState,
  Subscription,
  SubscriptionHandlers,
} from '../../types';
import type { Atmosphere } from '../../core/atmosphere';
import { ConnectionStatus } from '../../resilience';
import type { ConnectionStatusSnapshot } from '../../resilience';

/**
 * Additional event handlers that callers can supply to
 * {@link useAtmosphereCore} without altering the subscription lifecycle.
 *
 * Covers the full classic Atmosphere 3.x lifecycle surface — the same
 * eight hooks tracked by {@link ConnectionStatus} — so consumers can
 * surface "reconnecting…", "fallback engaged", or "connection lost"
 * UI affordances without subscribing to the underlying transport
 * themselves.
 */
export interface CoreSubscriptionHandlers<T> {
  onOpen?: () => void;
  onMessage?: (data: T) => void;
  onClose?: () => void;
  onError?: (error: Error) => void;
  onReconnect?: () => void;
  /** Called when a connection is re-established after a disconnect (not on first open). */
  onReopen?: () => void;
  /** Called when the primary transport fails and a fallback is attempted. */
  onTransportFailure?: (reason: string) => void;
  /** Called when the client-side heartbeat watchdog expires. */
  onClientTimeout?: () => void;
  /** Called when reconnect attempts are exhausted. */
  onFailureToReconnect?: () => void;
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
  /**
   * Reactive snapshot of the resilience state (phase + last event +
   * transport + attempt counter). Updated on every lifecycle hook.
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
  /** Ref to the current subscription (for platform-specific effects). */
  subRef: React.RefObject<Subscription | null>;
}

/**
 * Shared React hook that manages an Atmosphere subscription lifecycle.
 *
 * This is the foundational hook used by both `useAtmosphere` (web) and
 * `useAtmosphereRN` (React Native). It handles:
 * - State management (`subscription`, `state`, `data`, `error`)
 * - The subscribe effect with all eight lifecycle handlers wired to a
 *   {@link ConnectionStatus} for unified resilience tracking
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

  // One ConnectionStatus per hook instance; its lifetime matches the hook.
  const statusRef = useRef<ConnectionStatus>(
    new ConnectionStatus({ initialTransport: request.transport }),
  );
  const [connectionStatus, setConnectionStatus] = useState<ConnectionStatusSnapshot>(
    statusRef.current.snapshot,
  );

  // Mirror status changes into React state so the component re-renders.
  useEffect(() => {
    return statusRef.current.onChange(setConnectionStatus);
  }, []);

  // Keep handlers in a ref so the effect doesn't re-run when handlers change
  const handlersRef = useRef(handlers);
  handlersRef.current = handlers;

  // Stable key for headers so the effect re-runs when headers change
  const headersKey = useMemo(
    () => (request.headers ? JSON.stringify(request.headers) : ''),
    [request.headers],
  );

  useEffect(() => {
    if (!enabled) return;

    let cancelled = false;

    (async () => {
      try {
        const baseHandlers: SubscriptionHandlers<T> = {
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
          reopen: () => {
            if (!cancelled) {
              setState('connected');
              handlersRef.current?.onReopen?.();
            }
          },
          transportFailure: (reason) => {
            if (!cancelled) {
              handlersRef.current?.onTransportFailure?.(reason);
            }
          },
          clientTimeout: () => {
            if (!cancelled) {
              handlersRef.current?.onClientTimeout?.();
            }
          },
          failureToReconnect: () => {
            if (!cancelled) {
              setState('error');
              handlersRef.current?.onFailureToReconnect?.();
            }
          },
        };

        // Wrap with ConnectionStatus so the resilience snapshot stays in sync.
        const subscriptionHandlers = statusRef.current.wrap(baseHandlers);

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
      // Reset status so a re-subscribe (e.g. on URL change) starts clean.
      statusRef.current.reset();
    };
    // Reconnect when URL, transport, auth, or headers change
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [atmosphere, request.url, request.transport, request.authToken, request.sessionToken, headersKey, enabled]);

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
    connectionStatus,
    push,
    disconnect,
    suspend,
    resume,
    subRef,
  };
}
