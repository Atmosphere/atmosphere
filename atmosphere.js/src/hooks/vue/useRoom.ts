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

import { ref, onUnmounted, type Ref } from 'vue';
import type {
  AtmosphereRequest,
  RoomMember,
  RoomHandle,
  PresenceEvent,
} from '../../types';
import { AtmosphereRooms } from '../../room/rooms';
import { Atmosphere } from '../../core/atmosphere';

/**
 * Vue composable that joins an Atmosphere room and tracks members and messages.
 *
 * ```vue
 * <script setup lang="ts">
 * import { useRoom } from 'atmosphere.js/vue';
 *
 * const { members, messages, broadcast } = useRoom<ChatMessage>(
 *   { url: '/atmosphere/room', transport: 'websocket' },
 *   'lobby',
 *   { id: 'user-1' },
 * );
 *
 * broadcast({ text: 'Hello!' });
 * </script>
 * ```
 */
export function useRoom<T = unknown>(
  request: AtmosphereRequest,
  roomName: string,
  member: RoomMember,
  instance?: Atmosphere,
) {
  const atmosphere = instance ?? new Atmosphere();
  const joined: Ref<boolean> = ref(false);
  const members: Ref<RoomMember[]> = ref([]);
  const messages: Ref<Array<{ data: T; member: RoomMember }>> = ref([]);
  const error: Ref<Error | null> = ref(null);

  let handle: RoomHandle | null = null;
  const rooms = new AtmosphereRooms(atmosphere, request);

  const connect = async () => {
    try {
      handle = await rooms.join<T>(roomName, member, {
        joined: (_name, memberList) => {
          joined.value = true;
          members.value = [...memberList];
        },
        message: (data, sender) => {
          messages.value = [...messages.value, { data: data as T, member: sender }];
        },
        join: (event: PresenceEvent) => {
          if (!members.value.some((m) => m.id === event.member.id)) {
            members.value = [...members.value, event.member];
          }
        },
        leave: (event: PresenceEvent) => {
          members.value = members.value.filter((m) => m.id !== event.member.id);
        },
        error: (err) => { error.value = err; },
      });
    } catch (err) {
      error.value = err instanceof Error ? err : new Error(String(err));
    }
  };

  const broadcast = (data: T) => handle?.broadcast(data);
  const sendTo = (memberId: string, data: T) => handle?.sendTo(memberId, data);

  connect();

  onUnmounted(() => {
    rooms.leaveAll();
  });

  return { joined, members, messages, broadcast, sendTo, error };
}
