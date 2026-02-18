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

import { computed } from 'vue';
import type { AtmosphereRequest, RoomMember } from '../../types';
import { useRoom } from './useRoom';
import { Atmosphere } from '../../core/atmosphere';

/**
 * Vue composable for tracking who is online in an Atmosphere room.
 *
 * Thin convenience wrapper around {@link useRoom} exposing only
 * presence-related reactive state.
 *
 * ```vue
 * <script setup lang="ts">
 * import { usePresence } from 'atmosphere.js/vue';
 *
 * const { members, count, isOnline } = usePresence(
 *   { url: '/atmosphere/room', transport: 'websocket' },
 *   'lobby',
 *   { id: currentUser.id },
 * );
 * </script>
 * ```
 */
export function usePresence(
  request: AtmosphereRequest,
  roomName: string,
  member: RoomMember,
  instance?: Atmosphere,
) {
  const { joined, members } = useRoom(request, roomName, member, instance);
  const count = computed(() => members.value.length);
  const isOnline = (memberId: string) =>
    members.value.some((m) => m.id === memberId);

  return { joined, members, count, isOnline };
}
