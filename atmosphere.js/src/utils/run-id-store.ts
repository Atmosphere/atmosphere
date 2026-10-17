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

import { logger } from './logger';

/**
 * Per-tab memory of the run in flight on an endpoint, so a page that is
 * reloaded mid-answer reattaches to it.
 *
 * The server surfaces the run id as an `X-Atmosphere-Run-Id` metadata frame
 * and, for a reattach grace after a connection is lost, keeps the run going
 * for a client that comes back with it. The transport holds the id in memory
 * (`AtmosphereProtocol.runId`), which a reload wipes; this store keeps a copy
 * in the tab's `sessionStorage`, which survives the reload and nothing else
 * (another tab, a closed tab). It is written when the id arrives, removed on
 * the run's terminal frame (`complete`, `error`, `cancelled`) and on a
 * deliberate `close()`, and read when a transport is created.
 *
 * Entries are keyed by the endpoint's origin and path: the query string is left
 * out, since it can carry an auth token and does not name another endpoint. An
 * id whose run the server no longer knows (the tab came back after the run left
 * the server's registry) is sent on each connect until the next run replaces it;
 * the server ignores a run id it does not know.
 *
 * `sessionStorage` can be missing (React Native, Node, a sandboxed frame) or
 * throw (storage disabled, quota): every access is best effort, and the client
 * then behaves as before, the run id living in memory only.
 */

const KEY_PREFIX = 'atmosphere.js:run-id:';

function storage(): Storage | null {
  try {
    return typeof sessionStorage !== 'undefined' && sessionStorage !== null ? sessionStorage : null;
  } catch {
    // Reading `sessionStorage` itself throws where storage is blocked.
    return null;
  }
}

/** The storage key of `url`'s endpoint: origin and path, no query or fragment. */
export function runIdStorageKey(url: string): string {
  let endpoint: string;
  try {
    const base = typeof location !== 'undefined' && location?.href ? location.href : undefined;
    const parsed = new URL(url, base);
    endpoint = parsed.origin + parsed.pathname;
  } catch {
    endpoint = url.split(/[?#]/, 1)[0];
  }
  return KEY_PREFIX + endpoint;
}

/** The run id stored for `url`'s endpoint in this tab, or `null`. */
export function loadRunId(url: string): string | null {
  const store = storage();
  if (store === null) return null;
  try {
    const value = store.getItem(runIdStorageKey(url));
    return value !== null && value.length > 0 ? value : null;
  } catch (error) {
    logger.debug('Unable to read the stored run id:', error);
    return null;
  }
}

/** Store `runId` as the run in flight on `url`'s endpoint in this tab. */
export function saveRunId(url: string, runId: string): void {
  const store = storage();
  if (store === null) return;
  try {
    store.setItem(runIdStorageKey(url), runId);
  } catch (error) {
    logger.debug('Unable to store the run id:', error);
  }
}

/** Forget the run stored for `url`'s endpoint in this tab. */
export function clearRunId(url: string): void {
  const store = storage();
  if (store === null) return;
  try {
    store.removeItem(runIdStorageKey(url));
  } catch (error) {
    logger.debug('Unable to remove the stored run id:', error);
  }
}
