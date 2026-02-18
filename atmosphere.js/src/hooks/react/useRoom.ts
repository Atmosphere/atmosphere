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

import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import type {
  AtmosphereRequest,
  RoomMember,
  RoomHandle,
  PresenceEvent,
} from '../../types';
import { AtmosphereRooms } from '../../room/rooms';
import { useAtmosphereContext } from './provider';

/**
 * Options for {@link useRoom}.
 */
export interface UseRoomOptions {
  /** Base request configuration (url, transport, etc.). */
  request: AtmosphereRequest;
  /** Room name to join. */
  room: string;
  /** Local member identity. */
  member: RoomMember;
}

/**
 * Return type of {@link useRoom}.
 */
export interface UseRoomResult<T> {
  /** Whether the room has been joined. */
  joined: boolean;
  /** Current room members. */
  members: RoomMember[];
  /** Messages received in the room (append-only during component lifetime). */
  messages: Array<{ data: T; member: RoomMember }>;
  /** Broadcast a message to all room members. */
  broadcast: (data: T) => void;
  /** Send a direct message to a specific member. */
  sendTo: (memberId: string, data: T) => void;
  /** The last error, if any. */
  error: Error | null;
}

/**
 * React hook that joins an Atmosphere room and tracks members and messages.
 *
 * ```tsx
 * const { members, messages, broadcast } = useRoom<ChatMessage>({
 *   request: { url: '/atmosphere/room', transport: 'websocket' },
 *   room: 'lobby',
 *   member: { id: 'user-1' },
 * });
 *
 * broadcast({ text: 'Hello!' });
 * ```
 *
 * Requires an {@link AtmosphereProvider} ancestor.
 */
export function useRoom<T = unknown>(
  options: UseRoomOptions,
): UseRoomResult<T> {
  const atmosphere = useAtmosphereContext();
  const { request, room, member } = options;

  const [joined, setJoined] = useState(false);
  const [members, setMembers] = useState<RoomMember[]>([]);
  const [messages, setMessages] = useState<Array<{ data: T; member: RoomMember }>>([]);
  const [error, setError] = useState<Error | null>(null);

  const roomsRef = useRef<AtmosphereRooms | null>(null);
  const handleRef = useRef<RoomHandle | null>(null);

  useEffect(() => {
    let cancelled = false;

    const rooms = new AtmosphereRooms(atmosphere, request);
    roomsRef.current = rooms;

    (async () => {
      try {
        const handle = await rooms.join<T>(room, member, {
          joined: (_name, memberList) => {
            if (cancelled) return;
            setJoined(true);
            setMembers([...memberList]);
          },
          message: (data, sender) => {
            if (cancelled) return;
            setMessages((prev) => [...prev, { data: data as T, member: sender }]);
          },
          join: (event: PresenceEvent) => {
            if (cancelled) return;
            setMembers((prev) => {
              if (prev.some((m) => m.id === event.member.id)) return prev;
              return [...prev, event.member];
            });
          },
          leave: (event: PresenceEvent) => {
            if (cancelled) return;
            setMembers((prev) => prev.filter((m) => m.id !== event.member.id));
          },
          error: (err) => {
            if (!cancelled) setError(err);
          },
        });

        if (!cancelled) {
          handleRef.current = handle;
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err : new Error(String(err)));
        }
      }
    })();

    return () => {
      cancelled = true;
      rooms.leaveAll();
      roomsRef.current = null;
      handleRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [atmosphere, request.url, request.transport, room, member.id]);

  const broadcast = useCallback(
    (data: T) => handleRef.current?.broadcast(data),
    [],
  );

  const sendTo = useCallback(
    (memberId: string, data: T) => handleRef.current?.sendTo(memberId, data),
    [],
  );

  return useMemo(
    () => ({ joined, members, messages, broadcast, sendTo, error }),
    [joined, members, messages, broadcast, sendTo, error],
  );
}
