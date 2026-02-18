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

import type { AtmosphereRequest, RoomMember, PresenceEvent } from '../../types';
import { AtmosphereRooms } from '../../room/rooms';
import { Atmosphere } from '../../core/atmosphere';
import type { Readable } from './atmosphere';

/**
 * State exposed by {@link createRoomStore}.
 */
export interface RoomStoreState<T> {
  joined: boolean;
  members: RoomMember[];
  messages: Array<{ data: T; member: RoomMember }>;
  error: Error | null;
}

/**
 * Creates a Svelte-compatible readable store for an Atmosphere room.
 *
 * ```svelte
 * <script>
 *   import { createRoomStore } from 'atmosphere.js/svelte';
 *
 *   const { store: lobby, broadcast } = createRoomStore(
 *     { url: '/atmosphere/room', transport: 'websocket' },
 *     'lobby',
 *     { id: 'user-1' },
 *   );
 *   // $lobby.members, $lobby.messages
 * </script>
 * ```
 */
export function createRoomStore<T = unknown>(
  request: AtmosphereRequest,
  roomName: string,
  member: RoomMember,
  instance?: Atmosphere,
) {
  const atmosphere = instance ?? new Atmosphere();
  const subscribers = new Set<(value: RoomStoreState<T>) => void>();

  let current: RoomStoreState<T> = {
    joined: false,
    members: [],
    messages: [],
    error: null,
  };

  let rooms: AtmosphereRooms | null = null;
  let connected = false;

  function notify() {
    for (const fn of subscribers) fn(current);
  }

  function update(partial: Partial<RoomStoreState<T>>) {
    current = { ...current, ...partial };
    notify();
  }

  async function connect() {
    if (connected) return;
    connected = true;
    rooms = new AtmosphereRooms(atmosphere, request);
    try {
      await rooms.join<T>(roomName, member, {
        joined: (_name, memberList) => {
          update({ joined: true, members: [...memberList] });
        },
        message: (data, sender) => {
          update({
            messages: [...current.messages, { data: data as T, member: sender }],
          });
        },
        join: (event: PresenceEvent) => {
          if (!current.members.some((m) => m.id === event.member.id)) {
            update({ members: [...current.members, event.member] });
          }
        },
        leave: (event: PresenceEvent) => {
          update({
            members: current.members.filter((m) => m.id !== event.member.id),
          });
        },
        error: (err) => update({ error: err }),
      });
    } catch (err) {
      update({
        error: err instanceof Error ? err : new Error(String(err)),
      });
    }
  }

  function disconnect() {
    connected = false;
    rooms?.leaveAll();
    rooms = null;
  }

  const store: Readable<RoomStoreState<T>> = {
    subscribe(run) {
      subscribers.add(run);
      if (subscribers.size === 1) connect();
      run(current);
      return () => {
        subscribers.delete(run);
        if (subscribers.size === 0) disconnect();
      };
    },
  };

  function broadcast(data: T) {
    rooms?.room(roomName)?.broadcast(data);
  }

  function sendTo(memberId: string, data: T) {
    rooms?.room(roomName)?.sendTo(memberId, data);
  }

  return { store, broadcast, sendTo };
}
