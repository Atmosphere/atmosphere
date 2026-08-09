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
import { subscribeStreaming, buildStreamingPayload } from '../../streaming';
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
 * What {@link useStreamingRN}'s `send` did with the message.
 *
 * - `sent` — handed to the open stream.
 * - `queued` — parked in `request.offlineQueue`; the transport drains it
 *   onto the wire when the connection reopens.
 * - `rejected` — not sent and not queued. The hook's `error` state is set
 *   and `onError` fired, so the message never disappears unannounced.
 */
export type StreamingSendOutcome = 'sent' | 'queued' | 'rejected';

/**
 * Outcome of a {@link useStreamingRN} `send` call.
 *
 * Every call returns one of these. There is no path on which a message is
 * dropped without either reaching the wire, entering the offline queue, or
 * raising an error.
 */
export interface StreamingSendResult {
  readonly outcome: StreamingSendOutcome;
  /** `queued` only — id of the {@code TrackedMessage} in the offline queue. */
  readonly messageId?: string;
  /** `queued` only — messages waiting in the queue after this enqueue. */
  readonly queueSize?: number;
  /** `rejected` only — why. Mirrored into the hook's `error` state. */
  readonly reason?: string;
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
  aiEvents: { event: string; data: Record<string, unknown> }[];
  error: string | null;
  /**
   * Device reachability as reported by NetInfo — and nothing else.
   *
   * It says the handset has a network, not that this stream can carry a
   * message: with the server down the radio is still up, so this stays
   * `true` while the socket is dead. Gate UI that means "your message can
   * go out now" on {@link #canSend}; use this one only to explain *why*
   * (airplane mode vs. server unreachable).
   *
   * Defaults to `true` when NetInfo was never registered via
   * {@code setupReactNative({ netInfo })} — an unmeasured device is
   * assumed reachable rather than assumed offline.
   */
  isConnected: boolean;
  /**
   * Whether a `send` right now would reach the wire: the hook is enabled,
   * the device is reachable, and the stream is open.
   *
   * This is the flag a Send button should consult. When it is `false`,
   * `send` queues (if `request.offlineQueue` is set) or rejects — it never
   * silently discards.
   */
  canSend: boolean;
  /**
   * Reactive snapshot of the resilience state (phase + last event +
   * transport + attempt counter + viaFallback flag). Drives the
   * {@code <ConnectionStatusBadgeRN />} component.
   */
  connectionStatus: ConnectionStatusSnapshot;
  /**
   * Send a prompt. Returns what happened to it — sent, queued, or
   * rejected — so the caller can render a pending bubble or an error
   * instead of guessing.
   */
  send: (message: string | object, options?: SendOptions) => StreamingSendResult;
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
 * - Diverts sends away from an unusable stream — into
 *   {@code request.offlineQueue} when one is configured, otherwise into
 *   the hook's `error` state. A send is never silently dropped.
 *
 * "Unusable" means either the device is offline (NetInfo) or the stream
 * is not open (resilience phase). Both are folded into {@link
 * UseStreamingRNResult#canSend}; {@link UseStreamingRNResult#isConnected}
 * keeps its narrower device-reachability meaning.
 *
 * To keep offline prompts instead of rejecting them, pass an
 * {@code OfflineQueue} on the request — the same primitive the web
 * Console uses, drained by the transport on reopen:
 *
 * ```tsx
 * const offline = useOfflineQueue<string>({ maxSize: 50 });
 * const { canSend, send } = useStreamingRN({
 *   request: { url, transport: 'websocket', offlineQueue: offline.queue },
 * });
 *
 * const result = send(prompt);   // 'sent' | 'queued' | 'rejected'
 * ```
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
  const [aiEvents, setAiEvents] = useState<{ event: string; data: Record<string, unknown> }[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [isConnected, setIsConnected] = useState(true);

  // Connection-status tracking. Seeded with an idle snapshot; the streaming
  // handle's own tracker takes over (and stays bound) once subscribe()
  // resolves — see the subscription effect below.
  const [connectionStatus, setConnectionStatus] = useState<ConnectionStatusSnapshot>(
    () => new ConnectionStatus({ initialTransport: request.transport }).snapshot,
  );

  const handleRef = useRef<StreamingHandle | null>(null);
  const pausedInBackgroundRef = useRef(false);

  // `send` must read the request that is current at call time — the offline
  // queue can be attached after mount — without re-creating the callback on
  // every parent render.
  const requestRef = useRef(request);
  requestRef.current = request;

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
    let unbindStatus: (() => void) | null = null;

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
          onAiEvent: (event, data) => {
            if (!cancelled) setAiEvents((prev) => [...prev, { event, data }]);
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
          // Bind to the handle's tracker here, in the same effect that owns
          // the handle, so the binding is unconditional and torn down with
          // it. Reading the snapshot and subscribing happen back-to-back
          // with no await between, so no transition can slip through.
          setConnectionStatus(handle.connectionStatus.snapshot);
          unbindStatus = handle.connectionStatus.onChange(setConnectionStatus);
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
      unbindStatus?.();
      unbindStatus = null;
      handleRef.current?.close();
      handleRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [atmosphere, request.url, request.transport, enabled]);

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

  // A send only reaches the wire when the device has a network *and* the
  // stream is open. NetInfo alone is not enough: with the server down the
  // radio stays up and `isConnected` stays true while the socket is dead.
  const canSend = enabled && isConnected && connectionStatus.phase === 'open';

  const send = useCallback((message: string | object, sendOpts?: SendOptions): StreamingSendResult => {
    const handle = handleRef.current;

    if (canSend && handle) {
      setIsStreaming(true);
      setError(null);
      handle.send(message, sendOpts);
      return { outcome: 'sent' };
    }

    // Not sendable. Park it if the caller gave us somewhere to park it —
    // the transport drains `request.offlineQueue` onto the wire when the
    // connection reopens (BaseTransport.drainOfflineQueue).
    const queue = requestRef.current.offlineQueue;
    if (queue) {
      const tracked = queue.enqueue(buildStreamingPayload(message, sendOpts));
      // The drain path pushes bytes straight through the transport, so it
      // never runs handle.send()'s session reset. Do it here or the drained
      // turn answers under a new server session id and every frame is
      // discarded by the stale-session guard.
      handle?.resetSession();
      // Deliberately not clearing `error`: whatever knocked the stream over
      // is still the reason this message is sitting in a queue.
      return { outcome: 'queued', messageId: tracked.id, queueSize: queue.size };
    }

    // Nowhere to park it. Surface it — the one thing we must not do is
    // drop it quietly.
    const reason = !isConnected
      ? 'Message not sent — device is offline.'
      : `Message not sent — connection is ${connectionStatus.phase}.`;
    setError(reason);
    setIsStreaming(false);
    lifecycleRef.current.onError?.(reason);
    return { outcome: 'rejected', reason };
  }, [canSend, isConnected, connectionStatus.phase]);

  const reset = useCallback(() => {
    setStreamingTexts([]);
    setIsStreaming(false);
    setProgress(null);
    setMetadata({});
    setStats(null);
    setRouting({});
    setAiEvents([]);
    setError(null);
  }, []);

  const close = useCallback(() => {
    handleRef.current?.close();
    setIsStreaming(false);
  }, []);

  const fullText = useMemo(() => streamingTexts.join(''), [streamingTexts]);

  return useMemo(
    () => ({
      fullText, streamingTexts, isStreaming, progress, metadata, stats, routing, aiEvents,
      error, isConnected, canSend, connectionStatus, send, reset, close,
    }),
    [fullText, streamingTexts, isStreaming, progress, metadata, stats, routing, aiEvents,
     error, isConnected, canSend, connectionStatus, send, reset, close],
  );
}
