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

import { ref, type Ref } from 'vue';
import { MessageHistorySync } from '../../history/message-history-sync';
import type {
  HistoryStorage,
  HistorySyncMessage,
} from '../../history/message-history-sync';

export interface UseMessageHistoryOptions {
  storage?: HistoryStorage;
  storageKey?: string;
  instance?: MessageHistorySync;
}

export interface UseMessageHistoryResult {
  /** Underlying primitive (stable). */
  sync: MessageHistorySync;
  /** Reactive ref of the largest observed server id. */
  lastSeenId: Ref<number>;
  /** Feed an incoming message into the cursor. */
  observe: (message: HistorySyncMessage | null | undefined) => boolean;
  /** Reset the cursor to zero. */
  reset: () => void;
}

/**
 * Vue composable around {@link MessageHistorySync}. The {@code lastSeenId}
 * ref updates only when the cursor advances; consumers can read it
 * inside their reconnect handler to build a {@code sinceId} join frame.
 */
export function useMessageHistory(
  options: UseMessageHistoryOptions = {},
): UseMessageHistoryResult {
  const sync: MessageHistorySync = options.instance
    ?? new MessageHistorySync({
      storage: options.storage,
      storageKey: options.storageKey,
    });
  const lastSeenId = ref<number>(sync.lastSeenId);

  return {
    sync,
    lastSeenId,
    observe: (m) => {
      const advanced = sync.observe(m);
      if (advanced) lastSeenId.value = sync.lastSeenId;
      return advanced;
    },
    reset: () => {
      sync.reset();
      lastSeenId.value = 0;
    },
  };
}
