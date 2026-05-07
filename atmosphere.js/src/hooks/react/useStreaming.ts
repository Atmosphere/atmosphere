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
import type { AtmosphereRequest } from '../../types';
import type { StreamingHandle, SessionStats, RoutingInfo, SendOptions } from '../../streaming/types';
import { subscribeStreaming } from '../../streaming';
import { useAtmosphereContext } from './provider';

/**
 * Options for {@link useStreaming}.
 */
export interface UseStreamingOptions {
  /** Atmosphere request configuration (url, transport, etc.). */
  request: AtmosphereRequest;
  /** If false, the connection is not opened automatically (default: true). */
  enabled?: boolean;
  /**
   * Called when the underlying transport opens (initial connect or reconnect
   * succeeds). Pairs with the server-side @AiEndpoint disconnect /
   * stream-resume work — UI clears any "reconnecting" indicator here.
   */
  onOpen?: () => void;
  /**
   * Called when the underlying transport closes. The server's
   * AiStreamingSession.cancelInflight fires this on the client when a
   * disconnect aborts the in-flight LLM call.
   */
  onClose?: () => void;
  /**
   * Called every time the client begins a reconnection attempt. With
   * @AiEndpoint(streamCache=UUIDBroadcasterCache.class), the server
   * replays cached frames on reconnect — keep the existing conversation
   * visible and surface a transient indicator.
   */
  onReconnect?: () => void;
  /**
   * Called when the client-side heartbeat watchdog expires before the
   * next server heartbeat lands. Pairs with @AiEndpoint(heartbeatSeconds=N).
   */
  onClientTimeout?: () => void;
}

/** Connection-state classification surfaced to consumers of {@link useStreaming}. */
export type StreamingConnectionState =
  | 'idle'
  | 'connecting'
  | 'connected'
  | 'reconnecting'
  | 'closed'
  | 'error';

/**
 * A structured AI event received from the server.
 */
export interface AiEvent {
  /** Event type (e.g., "tool-start", "tool-result", "agent-step", "entity-start"). */
  event: string;
  /** Structured event data. */
  data: Record<string, unknown>;
}

/**
 * Return type of {@link useStreaming}.
 */
export interface UseStreamingResult {
  /** All streaming text fragments received so far, concatenated. */
  fullText: string;
  /** Array of individual streaming text fragments in order. */
  streamingTexts: string[];
  /** Whether the stream is currently active (streaming texts arriving). */
  isStreaming: boolean;
  /** Last progress message from the server. */
  progress: string | null;
  /** Metadata received from the server (model name, streaming text counts, etc.). */
  metadata: Record<string, unknown>;
  /** Aggregated session statistics (available after session completes). */
  stats: SessionStats | null;
  /** Routing information extracted from server metadata. */
  routing: RoutingInfo;
  /** Structured AI events (tool calls, agent steps, entities, etc.). */
  aiEvents: AiEvent[];
  /** Error message, if any. */
  error: string | null;
  /** Send a prompt to the server to start streaming. */
  send: (message: string | object, options?: SendOptions) => void;
  /** Reset the accumulated state for a new conversation turn. */
  reset: () => void;
  /** Close the streaming connection. */
  close: () => void;
  /**
   * Current transport-layer connection state. Drives "Connecting…",
   * "Reconnecting…", "Connection lost" UI banners that pair with the
   * server-side @AiEndpoint disconnect / stream-resume primitives.
   */
  connectionState: StreamingConnectionState;
  /** True iff the transport is currently mid-reconnection. */
  isReconnecting: boolean;
}

/**
 * React hook for AI/LLM streaming via Atmosphere.
 *
 * Connects to an Atmosphere endpoint that uses the AI streaming wire protocol
 * ({@code atmosphere-ai} on the server side) and provides reactive state for
 * incremental streaming text rendering.
 *
 * ```tsx
 * const { fullText, isStreaming, send, progress } = useStreaming({
 *   request: { url: '/ai/chat', transport: 'websocket' },
 * });
 *
 * <button onClick={() => send('What is Atmosphere?')}>Ask</button>
 * <p>{fullText}</p>
 * {isStreaming && <span>{progress ?? 'Generating...'}</span>}
 * ```
 *
 * Requires an {@link AtmosphereProvider} ancestor.
 */
export function useStreaming(options: UseStreamingOptions): UseStreamingResult {
  const atmosphere = useAtmosphereContext();
  const { request, enabled = true, onOpen, onClose, onReconnect, onClientTimeout } = options;

  const [streamingTexts, setStreamingTexts] = useState<string[]>([]);
  const [isStreaming, setIsStreaming] = useState(false);
  const [progress, setProgress] = useState<string | null>(null);
  const [metadata, setMetadata] = useState<Record<string, unknown>>({});
  const [stats, setStats] = useState<SessionStats | null>(null);
  const [routing, setRouting] = useState<RoutingInfo>({});
  const [aiEvents, setAiEvents] = useState<AiEvent[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [connectionState, setConnectionState] = useState<StreamingConnectionState>('idle');

  const handleRef = useRef<StreamingHandle | null>(null);

  // Keep external lifecycle callbacks in a ref so the subscribe effect doesn't
  // re-run when callers pass fresh closures every render. Mirrors the existing
  // useAtmosphereCore pattern.
  const lifecycleRef = useRef({ onOpen, onClose, onReconnect, onClientTimeout });
  lifecycleRef.current = { onOpen, onClose, onReconnect, onClientTimeout };

  useEffect(() => {
    if (!enabled) return;

    let cancelled = false;
    setConnectionState('connecting');

    (async () => {
      try {
        const handle = await subscribeStreaming(atmosphere, request, {
          onOpen: () => {
            if (cancelled) return;
            setConnectionState('connected');
            lifecycleRef.current.onOpen?.();
          },
          onClose: () => {
            if (cancelled) return;
            setConnectionState('closed');
            lifecycleRef.current.onClose?.();
          },
          onReconnect: () => {
            if (cancelled) return;
            setConnectionState('reconnecting');
            lifecycleRef.current.onReconnect?.();
          },
          onClientTimeout: () => {
            if (cancelled) return;
            setConnectionState('reconnecting');
            lifecycleRef.current.onClientTimeout?.();
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
            if (!cancelled) {
              setError(err);
              setIsStreaming(false);
              setProgress(null);
              setConnectionState('error');
            }
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
            if (!cancelled) {
              setAiEvents((prev) => [...prev, { event, data }]);
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
  }, [atmosphere, request.url, request.transport, request.authToken, request.sessionToken, enabled]);

  const send = useCallback((message: string | object, options?: SendOptions) => {
    setIsStreaming(true);
    setError(null);
    handleRef.current?.send(message, options);
  }, []);

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
  const isReconnecting = connectionState === 'reconnecting';

  return useMemo(
    () => ({
      fullText, streamingTexts, isStreaming, progress, metadata, stats, routing,
      aiEvents, error, send, reset, close, connectionState, isReconnecting,
    }),
    [
      fullText, streamingTexts, isStreaming, progress, metadata, stats, routing,
      aiEvents, error, send, reset, close, connectionState, isReconnecting,
    ],
  );
}
