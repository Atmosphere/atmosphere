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
import type { StreamingHandle, SessionStats, RoutingInfo, SendOptions, CancelReason } from '../../streaming/types';
import { subscribeStreaming, buildStreamingPayload } from '../../streaming';
import { useAtmosphereContext } from '../react/provider';
import { getRegisteredNetInfo } from '../../react-native/platform';
import { ConnectionStatus } from '../../resilience';
import type { ConnectionStatusSnapshot } from '../../resilience';
import { logger } from '../../utils/logger';
import type { BackgroundBehavior } from './useAtmosphereRN';

/**
 * Options for {@link useStreamingRN}.
 */
export interface UseStreamingRNOptions {
  request: AtmosphereRequest;
  enabled?: boolean;
  /**
   * What the stream does while the app is not in the foreground
   * (AppState `background` or `inactive`):
   * - `keep-alive` (default) — nothing; the stream stays open and an
   *   answer that is streaming when the app is backgrounded keeps arriving.
   * - `suspend` — suspend the subscription and resume it on foreground.
   *   The socket stays open, but frames that arrive while suspended are
   *   dropped, so an in-flight answer comes back truncated: the hook ends
   *   that turn (`isStreaming` false, `progress` null) when it suspends.
   * - `disconnect` — close the stream and open a new one on foreground. An
   *   in-flight turn ends when the stream closes, and `connectionStatus`
   *   (so `canSend`) stays off `open` until the new stream is bound.
   *
   * The default differs from {@link useAtmosphereRN}'s `suspend` because a
   * suspended stream loses the frames of an in-flight answer.
   */
  backgroundBehavior?: BackgroundBehavior;
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
  /**
   * Called with a room member's prompt on an `@AiEndpoint(broadcastReply = true)`
   * room, ahead of its reply; `own` is `true` for this connection's own prompt.
   * See `StreamingHandlers.onPrompt`.
   */
  onPrompt?: (text: string, from: string, own: boolean) => void;
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
   * Why the server cut the last run short (its `cancelled` frame), or `null`.
   * A cancelled run is over, not failed: `error` stays `null`.
   */
  cancelled: CancelReason | null;
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
 * - Applies {@link UseStreamingRNOptions#backgroundBehavior} when the app
 *   leaves the foreground: keeps the stream open (default), suspends it
 *   until the app returns, or closes it and reconnects on return
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
    request, enabled = true, backgroundBehavior = 'keep-alive',
    onOpen, onClose, onReconnect, onReopen,
    onClientTimeout, onTransportFailure, onFailureToReconnect, onError, onPrompt,
  } = options;

  const [streamingTexts, setStreamingTexts] = useState<string[]>([]);
  const [isStreaming, setIsStreaming] = useState(false);
  const [progress, setProgress] = useState<string | null>(null);
  const [metadata, setMetadata] = useState<Record<string, unknown>>({});
  const [stats, setStats] = useState<SessionStats | null>(null);
  const [routing, setRouting] = useState<RoutingInfo>({});
  const [aiEvents, setAiEvents] = useState<{ event: string; data: Record<string, unknown> }[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [cancelledRun, setCancelledRun] = useState<CancelReason | null>(null);
  const [isConnected, setIsConnected] = useState(true);
  // True while `backgroundBehavior: 'disconnect'` holds the stream closed
  // because the app is in the background.
  const [closedInBackground, setClosedInBackground] = useState(false);
  const streamEnabled = enabled && !closedInBackground;

  // Connection-status tracking. Seeded with an idle snapshot; the streaming
  // handle's own tracker takes over (and stays bound) once subscribe()
  // resolves — see the subscription effect below.
  const [connectionStatus, setConnectionStatus] = useState<ConnectionStatusSnapshot>(
    () => new ConnectionStatus({ initialTransport: request.transport }).snapshot,
  );

  const handleRef = useRef<StreamingHandle | null>(null);
  const backgroundBehaviorRef = useRef(backgroundBehavior);
  backgroundBehaviorRef.current = backgroundBehavior;
  // The behavior applied when the app left the foreground, undone on return.
  const appliedInBackgroundRef = useRef<BackgroundBehavior | null>(null);

  // `send` must read the request that is current at call time — the offline
  // queue can be attached after mount — without re-creating the callback on
  // every parent render.
  const requestRef = useRef(request);
  requestRef.current = request;

  // Keep lifecycle callbacks in a ref so the subscribe effect doesn't re-run
  // when callers pass fresh closures every render.
  const lifecycleRef = useRef({
    onOpen, onClose, onReconnect, onReopen,
    onClientTimeout, onTransportFailure, onFailureToReconnect, onError, onPrompt,
  });
  lifecycleRef.current = {
    onOpen, onClose, onReconnect, onReopen,
    onClientTimeout, onTransportFailure, onFailureToReconnect, onError, onPrompt,
  };

  // --- Core streaming subscription ---
  useEffect(() => {
    if (!streamEnabled) return;

    let cancelled = false;
    let unbindStatus: (() => void) | null = null;

    (async () => {
      try {
        const handle = await subscribeStreaming(atmosphere, request, {
          onOpen: () => {
            if (cancelled) return;
            // An open transport retires any earlier connection error.
            setError(null);
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
            setError(null);
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
          onPrompt: (text, from, own) => {
            if (!cancelled) lifecycleRef.current.onPrompt?.(text, from, own);
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
          onCancelled: (reason) => {
            if (!cancelled) {
              setCancelledRun(reason);
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
      // The binding is gone, so the handle's own close event never reaches
      // `connectionStatus`: without this it stays `open` while no stream
      // exists, and `canSend` reads true through the whole reconnect.
      setConnectionStatus(new ConnectionStatus({ initialTransport: request.transport }).snapshot);
      // `cancelled` now drops the old handle's onComplete/onError, and the
      // next subscription starts a fresh session, so nothing else would ever
      // end a turn that was in flight on this one.
      setIsStreaming(false);
      setProgress(null);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [atmosphere, request.url, request.transport, streamEnabled]);

  // --- AppState integration ---
  useEffect(() => {
    if (!AppState) return;

    const handleAppStateChange = (nextState: string) => {
      if (nextState === 'background' || nextState === 'inactive') {
        if (appliedInBackgroundRef.current !== null) return;
        const behavior = backgroundBehaviorRef.current;
        if (behavior === 'suspend') {
          const handle = handleRef.current;
          if (!handle) return;
          handle.suspend();
          appliedInBackgroundRef.current = 'suspend';
          // Frames that arrive while suspended are dropped, the `complete`
          // frame among them, so an in-flight answer can never finish: end
          // the turn here rather than leave `isStreaming` stuck on.
          setIsStreaming(false);
          setProgress(null);
        } else if (behavior === 'disconnect') {
          // Unmounting the subscription effect closes the handle.
          setClosedInBackground(true);
          appliedInBackgroundRef.current = 'disconnect';
        }
      } else if (nextState === 'active') {
        const applied = appliedInBackgroundRef.current;
        appliedInBackgroundRef.current = null;
        if (applied === 'suspend') {
          handleRef.current?.resume().catch((err: unknown) => {
            logger.warn('Resuming the stream on foreground failed', err);
          });
        } else if (applied === 'disconnect') {
          setClosedInBackground(false);
        }
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
  const canSend = streamEnabled && isConnected && connectionStatus.phase === 'open';

  const send = useCallback((message: string | object, sendOpts?: SendOptions): StreamingSendResult => {
    const handle = handleRef.current;

    if (canSend && handle) {
      setIsStreaming(true);
      setError(null);
      setCancelledRun(null);
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
    setCancelledRun(null);
  }, []);

  const close = useCallback(() => {
    handleRef.current?.close();
    setIsStreaming(false);
  }, []);

  const fullText = useMemo(() => streamingTexts.join(''), [streamingTexts]);

  return useMemo(
    () => ({
      fullText, streamingTexts, isStreaming, progress, metadata, stats, routing, aiEvents,
      error, cancelled: cancelledRun, isConnected, canSend, connectionStatus, send, reset, close,
    }),
    [fullText, streamingTexts, isStreaming, progress, metadata, stats, routing, aiEvents,
     error, cancelledRun, isConnected, canSend, connectionStatus, send, reset, close],
  );
}
