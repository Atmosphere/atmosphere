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
import type { AtmosphereRequest } from '../../types';
import type { StreamingHandle, SessionStats, RoutingInfo, SendOptions } from '../../streaming/types';
import { subscribeStreaming } from '../../streaming';
import { useAtmosphereContext } from '../react/provider';
import { getRegisteredNetInfo } from '../../react-native/platform';
import { ConnectionStatus } from '../../resilience';
import type { ConnectionStatusSnapshot } from '../../resilience';

/**
 * Options for {@link useStreamingRN}.
 */
export interface UseStreamingRNOptions {
  request: AtmosphereRequest;
  enabled?: boolean;
  /** Called when the underlying transport opens (initial connect). */
  onOpen?: () => void;
  /** Called when the underlying transport closes. */
  onClose?: () => void;
  /** Called when a reconnection attempt begins. */
  onReconnect?: () => void;
  /** Called when the connection is re-established after a disconnect. */
  onReopen?: () => void;
  /** Called when the client-side heartbeat watchdog expires. */
  onClientTimeout?: () => void;
  /** Called when the primary transport fails and a fallback is attempted. */
  onTransportFailure?: (reason: string) => void;
  /** Called when reconnect attempts have been exhausted. */
  onFailureToReconnect?: () => void;
  /** Called on streaming error (governance denial, transport failure, etc.). */
  onError?: (error: string) => void;
}

/**
 * Return type of {@link useStreamingRN}.
 */
export interface UseStreamingRNResult {
  fullText: string;
  streamingTexts: string[];
  isStreaming: boolean;
  progress: string | null;
  metadata: Record<string, unknown>;
  stats: SessionStats | null;
  routing: RoutingInfo;
  error: string | null;
  isConnected: boolean;
  /**
   * Reactive snapshot of the resilience state (phase + last event +
   * transport + attempt counter + viaFallback flag). Drives the
   * {@code <ConnectionStatusBadgeRN />} component.
   */
  connectionStatus: ConnectionStatusSnapshot;
  send: (message: string | object, options?: SendOptions) => void;
  reset: () => void;
  close: () => void;
}

/**
 * React Native hook for AI/LLM streaming via Atmosphere.
 *
 * Wraps the core streaming logic with AppState and optional NetInfo
 * awareness:
 * - Pauses streaming when app moves to background
 * - Resumes streaming when app returns to foreground
 * - Suppresses sends when device is offline (if NetInfo installed)
 *
 * Exposes the full classic Atmosphere 3.x lifecycle surface
 * (`onOpen`/`onClose`/`onReconnect`/`onReopen`/`onClientTimeout`/
 * `onTransportFailure`/`onFailureToReconnect`) plus a reactive
 * `connectionStatus` snapshot that drives the RN Badge component.
 *
 * Requires an {@link AtmosphereProvider} ancestor.
 */
export function useStreamingRN(options: UseStreamingRNOptions): UseStreamingRNResult {
  const atmosphere = useAtmosphereContext();
  const {
    request, enabled = true,
    onOpen, onClose, onReconnect, onReopen,
    onClientTimeout, onTransportFailure, onFailureToReconnect, onError,
  } = options;

  const [streamingTexts, setStreamingTexts] = useState<string[]>([]);
  const [isStreaming, setIsStreaming] = useState(false);
  const [progress, setProgress] = useState<string | null>(null);
  const [metadata, setMetadata] = useState<Record<string, unknown>>({});
  const [stats, setStats] = useState<SessionStats | null>(null);
  const [routing, setRouting] = useState<RoutingInfo>({});
  const [error, setError] = useState<string | null>(null);
  const [isConnected, setIsConnected] = useState(true);

  // Connection-status tracking. The seed instance is replaced with the
  // streaming handle's own instance once subscribe() resolves.
  const statusInstanceRef = useRef<ConnectionStatus>(
    new ConnectionStatus({ initialTransport: request.transport }),
  );
  const [connectionStatus, setConnectionStatus] = useState<ConnectionStatusSnapshot>(
    statusInstanceRef.current.snapshot,
  );

  const handleRef = useRef<StreamingHandle | null>(null);
  const pausedInBackgroundRef = useRef(false);

  // Keep lifecycle callbacks in a ref so the subscribe effect doesn't re-run
  // when callers pass fresh closures every render.
  const lifecycleRef = useRef({
    onOpen, onClose, onReconnect, onReopen,
    onClientTimeout, onTransportFailure, onFailureToReconnect, onError,
  });
  lifecycleRef.current = {
    onOpen, onClose, onReconnect, onReopen,
    onClientTimeout, onTransportFailure, onFailureToReconnect, onError,
  };

  // --- Core streaming subscription ---
  useEffect(() => {
    if (!enabled) return;

    let cancelled = false;

    (async () => {
      try {
        const handle = await subscribeStreaming(atmosphere, request, {
          onOpen: () => {
            if (cancelled) return;
            lifecycleRef.current.onOpen?.();
          },
          onClose: () => {
            if (cancelled) return;
            lifecycleRef.current.onClose?.();
          },
          onReconnect: () => {
            if (cancelled) return;
            lifecycleRef.current.onReconnect?.();
          },
          onReopen: () => {
            if (cancelled) return;
            lifecycleRef.current.onReopen?.();
          },
          onClientTimeout: () => {
            if (cancelled) return;
            lifecycleRef.current.onClientTimeout?.();
          },
          onTransportFailure: (reason: string) => {
            if (cancelled) return;
            lifecycleRef.current.onTransportFailure?.(reason);
          },
          onFailureToReconnect: () => {
            if (cancelled) return;
            lifecycleRef.current.onFailureToReconnect?.();
          },
          onStreamingText: (text) => {
            if (cancelled) return;
            setIsStreaming(true);
            setStreamingTexts((prev) => [...prev, text]);
          },
          onProgress: (msg) => {
            if (!cancelled) setProgress(msg);
          },
          onComplete: () => {
            if (!cancelled) {
              setIsStreaming(false);
              setProgress(null);
            }
          },
          onError: (err) => {
            if (cancelled) return;
            setError(err);
            setIsStreaming(false);
            setProgress(null);
            lifecycleRef.current.onError?.(err);
          },
          onMetadata: (key, value) => {
            if (!cancelled) {
              setMetadata((prev) => ({ ...prev, [key]: value }));
              if (key.startsWith('routing.')) {
                const field = key.substring('routing.'.length);
                setRouting((prev) => ({ ...prev, [field]: value }));
              }
            }
          },
          onSessionComplete: (s, r) => {
            if (!cancelled) {
              setStats(s);
              setRouting(r);
            }
          },
        });

        if (!cancelled) {
          handleRef.current = handle;
          statusInstanceRef.current = handle.connectionStatus;
          setConnectionStatus(handle.connectionStatus.snapshot);
        } else {
          await handle.close();
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : String(err));
        }
      }
    })();

    return () => {
      cancelled = true;
      handleRef.current?.close();
      handleRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [atmosphere, request.url, request.transport, enabled]);

  // Mirror ConnectionStatus changes into React state.
  useEffect(() => {
    return statusInstanceRef.current.onChange(setConnectionStatus);
  }, [connectionStatus.phase === 'idle' ? 'idle' : 'live']);

  // --- AppState integration ---
  useEffect(() => {
    if (!AppState) return;

    const handleAppStateChange = (nextState: string) => {
      if (nextState === 'background' || nextState === 'inactive') {
        pausedInBackgroundRef.current = true;
      } else if (nextState === 'active' && pausedInBackgroundRef.current) {
        pausedInBackgroundRef.current = false;
        // Streaming resumes automatically via the underlying transport;
        // no explicit action needed here since the handle stays alive.
      }
    };

    const subscription = AppState.addEventListener('change', handleAppStateChange);
    return () => subscription.remove();
  }, []);

  // --- NetInfo integration (optional, requires setupReactNative({ netInfo })) ---
  useEffect(() => {
    const netInfo = getRegisteredNetInfo();
    if (!netInfo) return;

    const unsubscribe = netInfo.addEventListener((netState) => {
      setIsConnected(netState.isConnected ?? true);
    });

    return () => unsubscribe();
  }, []);

  const send = useCallback((message: string | object, sendOpts?: SendOptions) => {
    if (!isConnected) return; // Don't send when offline
    setIsStreaming(true);
    setError(null);
    handleRef.current?.send(message, sendOpts);
  }, [isConnected]);

  const reset = useCallback(() => {
    setStreamingTexts([]);
    setIsStreaming(false);
    setProgress(null);
    setMetadata({});
    setStats(null);
    setRouting({});
    setError(null);
  }, []);

  const close = useCallback(() => {
    handleRef.current?.close();
    setIsStreaming(false);
  }, []);

  const fullText = useMemo(() => streamingTexts.join(''), [streamingTexts]);

  return useMemo(
    () => ({
      fullText, streamingTexts, isStreaming, progress, metadata, stats, routing,
      error, isConnected, connectionStatus, send, reset, close,
    }),
    [fullText, streamingTexts, isStreaming, progress, metadata, stats, routing,
     error, isConnected, connectionStatus, send, reset, close],
  );
}
