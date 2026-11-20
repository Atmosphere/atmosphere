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
import { endpointKey } from './run-id-store';

/**
 * Per-tab memory of the durable session token the server assigned on an
 * endpoint, so a page reloaded after (or during) a server restart rejoins its
 * durable session — its rooms and broadcaster subscriptions — instead of
 * starting a new one.
 *
 * The transport holds the token in memory (`AtmosphereProtocol.sessionToken`)
 * and sends it on every reconnect; this store keeps a copy in the tab's
 * `sessionStorage`, like the run id (see `run-id-store`), keyed by the
 * endpoint's origin and path. It is written whenever the server assigns a
 * token (the `X-Atmosphere-Session-Token` response header, or the in-band
 * frame a WebSocket gets) and read when a transport is created. A token the
 * server no longer knows is answered with a new one, which replaces it.
 *
 * `sessionStorage` can be missing or throw (React Native, Node, a sandboxed
 * frame, storage blocked): the token then lives in memory only.
 */

const KEY_PREFIX = 'atmosphere.js:session-token:';

function storage(): Storage | null {
  try {
    return typeof sessionStorage !== 'undefined' && sessionStorage !== null ? sessionStorage : null;
  } catch {
    // Reading `sessionStorage` itself throws where storage is blocked.
    return null;
  }
}

/** The storage key of `url`'s session token. */
export function sessionTokenStorageKey(url: string): string {
  return KEY_PREFIX + endpointKey(url);
}

/** The session token stored for `url`'s endpoint, or `null`. */
export function loadSessionToken(url: string): string | null {
  const store = storage();
  if (store === null) return null;
  try {
    const value = store.getItem(sessionTokenStorageKey(url));
    return value !== null && value.length > 0 ? value : null;
  } catch (error) {
    logger.debug('Unable to read the stored session token:', error);
    return null;
  }
}

/** Store `token` as `url`'s endpoint session token. */
export function saveSessionToken(url: string, token: string): void {
  const store = storage();
  if (store === null) return;
  try {
    store.setItem(sessionTokenStorageKey(url), token);
  } catch (error) {
    logger.debug('Unable to store the session token:', error);
  }
}
