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
 * Send Atmosphere's close message (a URL built by
 * `AtmosphereProtocol.buildCloseUrl`) as a body-less POST.
 *
 * With `beacon`, `navigator.sendBeacon` is tried first: it is the request a
 * page that is being hidden or unloaded can still send. When it is missing
 * (React Native, Node) or refuses the request (its queue is full), or without
 * `beacon`, `fetch` with `keepalive: true` sends it instead. Best effort: a
 * failure is logged at debug level and never thrown, so closing a
 * subscription cannot fail because the server could not be told.
 */
export function sendCloseMessage(
  url: string,
  options: { beacon: boolean; withCredentials?: boolean },
): void {
  try {
    if (options.beacon && typeof navigator !== 'undefined'
        && typeof navigator.sendBeacon === 'function'
        && navigator.sendBeacon(url)) {
      return;
    }
    if (typeof fetch !== 'function') {
      logger.debug('No fetch available; Atmosphere close message not sent');
      return;
    }
    fetch(url, {
      method: 'POST',
      keepalive: true,
      credentials: options.withCredentials ? 'include' : 'same-origin',
    }).catch((error) => {
      logger.debug('Atmosphere close message failed:', error);
    });
  } catch (error) {
    logger.debug('Atmosphere close message failed:', error);
  }
}
