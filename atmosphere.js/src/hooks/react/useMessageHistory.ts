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

import { useCallback, useRef, useState } from 'react';
import { MessageHistorySync } from '../../history/message-history-sync';
import type {
  HistorySyncMessage,
  HistoryStorage,
} from '../../history/message-history-sync';

/**
 * Options for {@link useMessageHistory}.
 */
export interface UseMessageHistoryOptions {
  /** Persistent storage adapter (e.g. {@code localStorage}) — optional. */
  storage?: HistoryStorage;
  /** Storage key (defaults to {@code "atmosphere:lastSeenId"}). */
  storageKey?: string;
  /**
   * Reuse a {@link MessageHistorySync} instance instead of constructing
   * one inside the hook. Useful when the same cursor needs to be shared
   * across components or driven outside the React tree.
   */
  instance?: MessageHistorySync;
}

/**
 * Reactive view of the history-sync cursor.
 */
export interface UseMessageHistoryResult {
  /** Underlying primitive — pass to non-React code or other hooks. */
  sync: MessageHistorySync;
  /** Largest observed server-assigned id. Used as {@code sinceId} on reconnect. */
  lastSeenId: number;
  /** Feed an incoming decoded message into the cursor. */
  observe: (message: HistorySyncMessage | null | undefined) => boolean;
  /** Reset the cursor to zero (next reconnect re-replays everything). */
  reset: () => void;
}

/**
 * React hook around {@link MessageHistorySync}.
 *
 * Returns a stable {@code sync} reference plus a reactive {@code lastSeenId}
 * so consumers can rebuild the join frame on reconnect:
 *
 * ```tsx
 * const history = useMessageHistory({ storage: window.localStorage });
 *
 * useAtmosphere({
 *   request: { url: '/atmosphere/chat', transport: 'websocket' },
 *   onMessage: (raw) => {
 *     const parsed = JSON.parse(String(raw.responseBody));
 *     history.observe(parsed);
 *     // ... normal message handling ...
 *   },
 *   onReopen: () => {
 *     // The next join frame must carry sinceId so we don't get duplicates.
 *     subscription.push(JSON.stringify({
 *       type: 'join', room: 'lobby', memberId, sinceId: history.lastSeenId
 *     }));
 *   },
 * });
 * ```
 *
 * @since 5.0.0
 */
export function useMessageHistory(
  options: UseMessageHistoryOptions = {},
): UseMessageHistoryResult {
  const syncRef = useRef<MessageHistorySync | null>(null);
  if (syncRef.current === null) {
    syncRef.current = options.instance
      ?? new MessageHistorySync({
        storage: options.storage,
        storageKey: options.storageKey,
      });
  }
  const sync = syncRef.current;

  const [lastSeenId, setLastSeenId] = useState<number>(sync.lastSeenId);

  const observe = useCallback((message: HistorySyncMessage | null | undefined): boolean => {
    const advanced = sync.observe(message);
    if (advanced) {
      setLastSeenId(sync.lastSeenId);
    }
    return advanced;
  }, [sync]);

  const reset = useCallback(() => {
    sync.reset();
    setLastSeenId(0);
  }, [sync]);

  return { sync, lastSeenId, observe, reset };
}
