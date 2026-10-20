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

/**
 * The client heartbeat each tracking id's protocol handshake announced.
 *
 * The server writes the handshake, which names the heartbeat interval, only to
 * a client that connects with tracking id 0. A connection that replaces a lost
 * one (a fallback transport, or a subscription rebuilt under the lost tracking
 * id) is never told the interval again, so it takes it from here. Internal to
 * the library; bounded, the oldest entry going first.
 */
export interface LearnedHeartbeat {
  interval: number;
  padding: string;
}

const MAX_ENTRIES = 32;
const learned = new Map<string, LearnedHeartbeat>();

/** Record what the handshake for `trackingId` announced. */
export function rememberHeartbeat(trackingId: string, heartbeat: LearnedHeartbeat): void {
  if (!trackingId || trackingId === '0') return;
  learned.delete(trackingId);
  learned.set(trackingId, heartbeat);
  if (learned.size > MAX_ENTRIES) {
    learned.delete(learned.keys().next().value as string);
  }
}

/** What the handshake for `trackingId` announced, if one was read. */
export function learnedHeartbeat(trackingId: string): LearnedHeartbeat | undefined {
  return learned.get(trackingId);
}
