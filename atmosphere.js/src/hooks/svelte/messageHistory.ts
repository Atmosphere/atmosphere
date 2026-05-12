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

import { MessageHistorySync } from '../../history/message-history-sync';
import type {
  HistoryStorage,
  HistorySyncMessage,
} from '../../history/message-history-sync';
import type { Readable } from './atmosphere';

export interface MessageHistoryStoreHandle {
  /** Underlying cursor primitive. */
  sync: MessageHistorySync;
  /** Readable store of {@code lastSeenId}. */
  store: Readable<number>;
  /** Feed an incoming message into the cursor. */
  observe: (message: HistorySyncMessage | null | undefined) => boolean;
  /** Reset the cursor to zero. */
  reset: () => void;
}

/**
 * Svelte-compatible readable store backed by {@link MessageHistorySync}.
 * Subscribers receive the current {@code lastSeenId} on subscribe and
 * again every time the cursor advances.
 */
export function createMessageHistoryStore(
  options: {
    storage?: HistoryStorage;
    storageKey?: string;
    instance?: MessageHistorySync;
  } = {},
): MessageHistoryStoreHandle {
  const sync: MessageHistorySync = options.instance
    ?? new MessageHistorySync({
      storage: options.storage,
      storageKey: options.storageKey,
    });

  const subscribers = new Set<(value: number) => void>();

  function notify() {
    const v = sync.lastSeenId;
    for (const fn of subscribers) fn(v);
  }

  const store: Readable<number> = {
    subscribe(run) {
      subscribers.add(run);
      run(sync.lastSeenId);
      return () => {
        subscribers.delete(run);
      };
    },
  };

  return {
    sync,
    store,
    observe: (m) => {
      const advanced = sync.observe(m);
      if (advanced) notify();
      return advanced;
    },
    reset: () => {
      sync.reset();
      notify();
    },
  };
}
