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

import type { AtmosphereRequest, RoomMember } from '../../types';
import { createRoomStore, type RoomStoreState } from './room';
import { Atmosphere } from '../../core/atmosphere';
import type { Readable } from './atmosphere';

/**
 * State exposed by {@link createPresenceStore}.
 */
export interface PresenceStoreState {
  joined: boolean;
  members: RoomMember[];
  count: number;
}

/**
 * Creates a Svelte-compatible readable store that tracks presence in a room.
 *
 * ```svelte
 * <script>
 *   import { createPresenceStore } from 'atmosphere.js/svelte';
 *
 *   const presence = createPresenceStore(
 *     { url: '/atmosphere/room', transport: 'websocket' },
 *     'lobby',
 *     { id: currentUser.id },
 *   );
 *   // $presence.members, $presence.count
 * </script>
 * ```
 */
export function createPresenceStore(
  request: AtmosphereRequest,
  roomName: string,
  member: RoomMember,
  instance?: Atmosphere,
) {
  const { store: roomStore } = createRoomStore(request, roomName, member, instance);
  const subscribers = new Set<(value: PresenceStoreState) => void>();

  let current: PresenceStoreState = { joined: false, members: [], count: 0 };
  let unsub: (() => void) | null = null;

  function notify() {
    for (const fn of subscribers) fn(current);
  }

  const store: Readable<PresenceStoreState> = {
    subscribe(run) {
      subscribers.add(run);
      if (subscribers.size === 1) {
        unsub = roomStore.subscribe((roomState: RoomStoreState<unknown>) => {
          current = {
            joined: roomState.joined,
            members: roomState.members,
            count: roomState.members.length,
          };
          notify();
        });
      }
      run(current);
      return () => {
        subscribers.delete(run);
        if (subscribers.size === 0 && unsub) {
          unsub();
          unsub = null;
        }
      };
    },
  };

  return store;
}
