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
import type { StreamingHandle } from '../../streaming/types';
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
}

/**
 * Return type of {@link useStreaming}.
 */
export interface UseStreamingResult {
  /** All tokens received so far, concatenated. */
  fullText: string;
  /** Array of individual tokens in order. */
  tokens: string[];
  /** Whether the stream is currently active (tokens arriving). */
  isStreaming: boolean;
  /** Last progress message from the server. */
  progress: string | null;
  /** Metadata received from the server (model name, token counts, etc.). */
  metadata: Record<string, unknown>;
  /** Error message, if any. */
  error: string | null;
  /** Send a prompt to the server to start streaming. */
  send: (message: string | object) => void;
  /** Reset the accumulated state for a new conversation turn. */
  reset: () => void;
  /** Close the streaming connection. */
  close: () => void;
}

/**
 * React hook for AI/LLM streaming via Atmosphere.
 *
 * Connects to an Atmosphere endpoint that uses the AI streaming wire protocol
 * ({@code atmosphere-ai} on the server side) and provides reactive state for
 * token-by-token rendering.
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
  const { request, enabled = true } = options;

  const [tokens, setTokens] = useState<string[]>([]);
  const [isStreaming, setIsStreaming] = useState(false);
  const [progress, setProgress] = useState<string | null>(null);
  const [metadata, setMetadata] = useState<Record<string, unknown>>({});
  const [error, setError] = useState<string | null>(null);

  const handleRef = useRef<StreamingHandle | null>(null);

  useEffect(() => {
    if (!enabled) return;

    let cancelled = false;

    (async () => {
      try {
        const handle = await subscribeStreaming(atmosphere, request, {
          onToken: (token) => {
            if (cancelled) return;
            setIsStreaming(true);
            setTokens((prev) => [...prev, token]);
          },
          onProgress: (msg) => {
            if (!cancelled) setProgress(msg);
          },
          onComplete: () => {
            if (!cancelled) setIsStreaming(false);
          },
          onError: (err) => {
            if (!cancelled) {
              setError(err);
              setIsStreaming(false);
            }
          },
          onMetadata: (key, value) => {
            if (!cancelled) setMetadata((prev) => ({ ...prev, [key]: value }));
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
  }, [atmosphere, request.url, request.transport, enabled]);

  const send = useCallback((message: string | object) => {
    setIsStreaming(true);
    setError(null);
    handleRef.current?.send(message);
  }, []);

  const reset = useCallback(() => {
    setTokens([]);
    setIsStreaming(false);
    setProgress(null);
    setMetadata({});
    setError(null);
  }, []);

  const close = useCallback(() => {
    handleRef.current?.close();
    setIsStreaming(false);
  }, []);

  const fullText = useMemo(() => tokens.join(''), [tokens]);

  return useMemo(
    () => ({ fullText, tokens, isStreaming, progress, metadata, error, send, reset, close }),
    [fullText, tokens, isStreaming, progress, metadata, error, send, reset, close],
  );
}
