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
import { useRoom } from './useRoom';

/**
 * Options for {@link usePresence}.
 */
export interface UsePresenceOptions {
  /** Base request configuration (url, transport, etc.). */
  request: AtmosphereRequest;
  /** Room name to track presence for. */
  room: string;
  /** Local member identity. */
  member: RoomMember;
}

/**
 * Return type of {@link usePresence}.
 */
export interface UsePresenceResult {
  /** Whether we have joined the room. */
  joined: boolean;
  /** Current online members. */
  members: RoomMember[];
  /** Number of members currently online. */
  count: number;
  /** Check if a specific member is online. */
  isOnline: (memberId: string) => boolean;
}

/**
 * React hook for tracking who is online in an Atmosphere room.
 *
 * This is a thin convenience wrapper around {@link useRoom} that
 * exposes only presence-related state.
 *
 * ```tsx
 * const { members, count, isOnline } = usePresence({
 *   request: { url: '/atmosphere/room', transport: 'websocket' },
 *   room: 'lobby',
 *   member: { id: currentUser.id },
 * });
 * ```
 *
 * Requires an {@link AtmosphereProvider} ancestor.
 */
export function usePresence(options: UsePresenceOptions): UsePresenceResult {
  const { joined, members } = useRoom({
    request: options.request,
    room: options.room,
    member: options.member,
  });

  return {
    joined,
    members,
    count: members.length,
    isOnline: (memberId: string) => members.some((m) => m.id === memberId),
  };
}
