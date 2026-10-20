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
import { runIdStorageKey } from './run-id-store';

/**
 * The run id a client mints for its prompt (`AtmosphereRequest.clientRunId`).
 *
 * The server names a turn's run with an `X-Atmosphere-Run-Id` frame, and a
 * page reloaded before that frame reached it — or before the server even
 * received the prompt — had nothing to reattach by: the turn was lost to the
 * page while the server ran it. A client that mints the id itself keeps the
 * prompt and its id in the tab's `sessionStorage` before sending, sends the
 * prompt wrapped with the id, and reconnects with it: the server reattaches it
 * to the run, or, when it never received the prompt, says so
 * (`X-Atmosphere-Run-Unknown`) and the client sends the prompt again under the
 * same id. The server runs a prompt once per id.
 *
 * The wire envelope, a JSON object with exactly these two fields:
 * `{"clientRunId":"<uuid>","prompt":<the prompt as sent alone>}`.
 */

/** The envelope field naming the prompt's run id. */
export const CLIENT_RUN_ID_FIELD = 'clientRunId';

/** Metadata key of the server's frame saying it has no run by the id a reconnect carried. */
export const RUN_UNKNOWN_KEY = 'X-Atmosphere-Run-Unknown';

/** A prompt sent under a minted id that the server has not acknowledged yet. */
export interface PendingPrompt {
  runId: string;
  /** The prompt as the application pushed it: text, or an object sent as JSON. */
  payload: string | object;
}

/** A UUIDv4: `crypto.randomUUID` where there is one, else built from random bytes. */
export function mintClientRunId(): string {
  const c = (globalThis as { crypto?: Crypto }).crypto;
  if (c && typeof c.randomUUID === 'function') {
    return c.randomUUID();
  }
  const bytes = new Uint8Array(16);
  if (c && typeof c.getRandomValues === 'function') {
    c.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** The wire form of `payload` sent under `runId`. */
export function wrapPrompt(payload: string | object, runId: string): string {
  return JSON.stringify({ [CLIENT_RUN_ID_FIELD]: runId, prompt: payload });
}

const KEY_PREFIX = 'atmosphere.js:pending-prompt:';

/** Where `sessionStorage` is missing (React Native, Node), the pending prompt lives in memory. */
const memory = new Map<string, string>();

function storage(): Storage | null {
  try {
    return typeof sessionStorage !== 'undefined' && sessionStorage !== null ? sessionStorage : null;
  } catch {
    return null;
  }
}

function key(url: string): string {
  return KEY_PREFIX + runIdStorageKey(url).substring('atmosphere.js:run-id:'.length);
}

/** The prompt sent on `url`'s endpoint that the server has not acknowledged, or `null`. */
export function loadPendingPrompt(url: string): PendingPrompt | null {
  let raw: string | null | undefined;
  const store = storage();
  try {
    raw = store !== null ? store.getItem(key(url)) : memory.get(key(url));
  } catch (error) {
    logger.debug('Unable to read the pending prompt:', error);
    return null;
  }
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as PendingPrompt;
    return parsed && typeof parsed.runId === 'string' && parsed.payload !== undefined ? parsed : null;
  } catch {
    return null;
  }
}

/** Keep `prompt` as the one sent on `url`'s endpoint and not acknowledged yet. */
export function savePendingPrompt(url: string, prompt: PendingPrompt): void {
  const value = JSON.stringify(prompt);
  const store = storage();
  try {
    if (store !== null) store.setItem(key(url), value);
    else memory.set(key(url), value);
  } catch (error) {
    logger.debug('Unable to store the pending prompt:', error);
    memory.set(key(url), value);
  }
}

/** Forget the pending prompt of `url`'s endpoint. */
export function clearPendingPrompt(url: string): void {
  memory.delete(key(url));
  const store = storage();
  try {
    store?.removeItem(key(url));
  } catch (error) {
    logger.debug('Unable to remove the pending prompt:', error);
  }
}
