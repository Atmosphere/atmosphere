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

import { logger } from '../utils/logger';

/**
 * Configuration for {@link MessageHistorySync}.
 */
export interface MessageHistorySyncConfig {
  /**
   * Optional storage adapter. When supplied the {@code lastSeenId} is
   * persisted across page reloads (e.g. {@code localStorage}) so the
   * cursor survives a tab refresh. When omitted, the cursor lives only
   * in memory.
   */
  storage?: HistoryStorage;
  /**
   * Storage key for persistence. Defaults to {@code "atmosphere:lastSeenId"}.
   */
  storageKey?: string;
}

/**
 * Minimal storage interface — {@code localStorage}, {@code sessionStorage},
 * or an in-memory mock for tests. Async-friendly adapters can wrap an
 * {@code IDBStore} or {@code AsyncStorage} from React Native.
 */
export interface HistoryStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

/**
 * Optional payload shape that {@link MessageHistorySync} understands.
 * The server-side {@code RoomProtocolCodec.encodeMessage(room, id, ...)}
 * emits objects with an {@code id} field; the sync tracks the largest id
 * it has observed so a reconnect's {@code join} frame can carry
 * {@code sinceId = lastSeenId}.
 */
export interface HistorySyncMessage {
  /** Server-assigned monotonic id (omitted on legacy messages). */
  id?: number;
  type?: string;
  [key: string]: unknown;
}

/**
 * Client-side cursor tracker for the server's history-sync protocol.
 *
 * Atmosphere's room broadcasts may carry a server-assigned monotonic
 * {@code id} field. By feeding every received message through {@link #observe},
 * the sync remembers the largest id seen — which the client can then send
 * back as {@code sinceId} on the next {@code join} so the server replays
 * only the messages this client missed during the disconnect.
 *
 * ```ts
 * const sync = new MessageHistorySync();
 *
 * atmosphere.subscribe({
 *   onMessage: (raw) => {
 *     const parsed = JSON.parse(raw.responseBody) as HistorySyncMessage;
 *     sync.observe(parsed);
 *   }
 * });
 *
 * // On reconnect, build the join frame:
 * const join = { type: 'join', room: 'lobby', memberId: 'alice', sinceId: sync.lastSeenId };
 * ```
 *
 * Without history-sync the server would replay every cached message on
 * every reconnect, producing visible duplicates. With it, the on-reconnect
 * replay is "exactly what I missed."
 *
 * @since 5.0.0
 */
export class MessageHistorySync {
  private _lastSeenId: number = 0;
  private readonly storage?: HistoryStorage;
  private readonly storageKey: string;

  constructor(config: MessageHistorySyncConfig = {}) {
    this.storage = config.storage;
    this.storageKey = config.storageKey ?? 'atmosphere:lastSeenId';

    if (this.storage) {
      const raw = this.storage.getItem(this.storageKey);
      if (raw !== null) {
        const parsed = Number(raw);
        if (Number.isFinite(parsed) && parsed >= 0) {
          this._lastSeenId = parsed;
        }
      }
    }
  }

  /** Most recent server-assigned id observed via {@link #observe}. */
  get lastSeenId(): number {
    return this._lastSeenId;
  }

  /**
   * Feed a decoded message into the sync. Updates {@link #lastSeenId} only
   * when the incoming id is strictly greater (server is required to assign
   * monotonically) — out-of-order arrivals do not regress the cursor.
   *
   * @returns true when the cursor advanced, false when the message had no
   *          id or its id was older than what we've seen.
   */
  observe(message: HistorySyncMessage | null | undefined): boolean {
    if (!message || typeof message.id !== 'number' || !Number.isFinite(message.id)) {
      return false;
    }
    if (message.id <= this._lastSeenId) {
      return false;
    }
    this._lastSeenId = message.id;
    if (this.storage) {
      try {
        this.storage.setItem(this.storageKey, String(message.id));
      } catch (e) {
        // Storage may be unavailable (private-mode browsers, quota
        // exceeded). The in-memory cursor still works; surface the
        // failure at debug rather than swallowing it silently.
        logger.debug(`MessageHistorySync storage write failed: ${(e as Error).message}`);
      }
    }
    return true;
  }

  /**
   * Reset the cursor to zero. Use this after an explicit "clear history"
   * action — the next reconnect's join frame will request the server's
   * full buffer.
   */
  reset(): void {
    this._lastSeenId = 0;
    if (this.storage) {
      try {
        this.storage.removeItem(this.storageKey);
      } catch (e) {
        logger.debug(`MessageHistorySync storage clear failed: ${(e as Error).message}`);
      }
    }
  }
}
