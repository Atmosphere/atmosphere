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

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { AtmosphereRooms } from '../../src/room/rooms';
import type {
  RoomMessage,
  RoomMember,
  PresenceEvent,
  Subscription,
  AtmosphereRequest,
} from '../../src/types';
import { Atmosphere } from '../../src/core/atmosphere';

// Mock subscription
function createMockSubscription(): Subscription & { pushed: string[] } {
  const pushed: string[] = [];
  return {
    id: 'test-sub',
    state: 'connected' as const,
    pushed,
    push: vi.fn((msg: string | object | ArrayBuffer) => {
      pushed.push(typeof msg === 'string' ? msg : JSON.stringify(msg));
    }),
    close: vi.fn(async () => {}),
    suspend: vi.fn(),
    resume: vi.fn(async () => {}),
    on: vi.fn(),
    off: vi.fn(),
  };
}

// Mock Atmosphere that captures the message handler
function createMockAtmosphere(): {
  atmosphere: Atmosphere;
  mockSub: ReturnType<typeof createMockSubscription>;
  triggerMessage: (body: string) => void;
} {
  const mockSub = createMockSubscription();
  let messageHandler: ((response: { responseBody: string }) => void) | null = null;

  const atmosphere = {
    subscribe: vi.fn(async (_req: AtmosphereRequest, handlers: Record<string, unknown>) => {
      messageHandler = handlers.message as typeof messageHandler;
      return mockSub;
    }),
  } as unknown as Atmosphere;

  return {
    atmosphere,
    mockSub,
    triggerMessage: (body: string) => {
      messageHandler?.({ responseBody: body });
    },
  };
}

describe('AtmosphereRooms', () => {
  let mock: ReturnType<typeof createMockAtmosphere>;
  let rooms: AtmosphereRooms;
  const baseRequest: AtmosphereRequest = {
    url: 'ws://localhost:8080/atmosphere/room',
    transport: 'websocket',
  };
  const member: RoomMember = { id: 'user-1', info: { name: 'Alice' } };

  beforeEach(() => {
    mock = createMockAtmosphere();
    rooms = new AtmosphereRooms(mock.atmosphere, baseRequest);
  });

  describe('join', () => {
    it('should establish connection on first join', async () => {
      await rooms.join('lobby', member);
      expect(mock.atmosphere.subscribe).toHaveBeenCalledOnce();
    });

    it('should reuse connection for subsequent joins', async () => {
      await rooms.join('lobby', member);
      await rooms.join('chat', member);
      expect(mock.atmosphere.subscribe).toHaveBeenCalledOnce();
    });

    it('should send join message', async () => {
      await rooms.join('lobby', member);
      const sent = JSON.parse(mock.mockSub.pushed[0]) as RoomMessage;
      expect(sent.type).toBe('join');
      expect(sent.room).toBe('lobby');
      expect(sent.member).toEqual(member);
    });

    it('should return a RoomHandle', async () => {
      const handle = await rooms.join('lobby', member);
      expect(handle.name).toBe('lobby');
      expect(handle.members.has('user-1')).toBe(true);
    });
  });

  describe('RoomHandle.broadcast', () => {
    it('should send broadcast message', async () => {
      const handle = await rooms.join('lobby', member);
      handle.broadcast('Hello!');
      const sent = JSON.parse(mock.mockSub.pushed[1]) as RoomMessage;
      expect(sent.type).toBe('broadcast');
      expect(sent.room).toBe('lobby');
      expect(sent.data).toBe('Hello!');
    });

    it('should throw if already left', async () => {
      const handle = await rooms.join('lobby', member);
      handle.leave();
      expect(() => handle.broadcast('fail')).toThrow("Already left room 'lobby'");
    });
  });

  describe('RoomHandle.sendTo', () => {
    it('should send direct message with target', async () => {
      const handle = await rooms.join('lobby', member);
      handle.sendTo('user-2', 'whisper');
      const sent = JSON.parse(mock.mockSub.pushed[1]) as RoomMessage;
      expect(sent.type).toBe('direct');
      expect(sent.target).toBe('user-2');
      expect(sent.data).toBe('whisper');
    });
  });

  describe('RoomHandle.leave', () => {
    it('should send leave message', async () => {
      const handle = await rooms.join('lobby', member);
      handle.leave();
      const sent = JSON.parse(mock.mockSub.pushed[1]) as RoomMessage;
      expect(sent.type).toBe('leave');
      expect(sent.room).toBe('lobby');
      expect(sent.member).toEqual(member);
    });

    it('should remove self from members', async () => {
      const handle = await rooms.join('lobby', member);
      expect(handle.members.size).toBe(1);
      handle.leave();
      expect(handle.members.size).toBe(0);
    });

    it('should be idempotent', async () => {
      const handle = await rooms.join('lobby', member);
      handle.leave();
      handle.leave(); // no-op
      // Only one leave message sent (index 1 after the join at index 0)
      const leaveMessages = mock.mockSub.pushed
        .map((s) => JSON.parse(s) as RoomMessage)
        .filter((m) => m.type === 'leave');
      expect(leaveMessages).toHaveLength(1);
    });
  });

  describe('presence events', () => {
    it('should fire join handler on presence join message', async () => {
      const joinEvents: PresenceEvent[] = [];
      await rooms.join('lobby', member, {
        join: (event) => joinEvents.push(event),
      });

      const presenceMsg: RoomMessage = {
        type: 'presence',
        room: 'lobby',
        data: 'join',
        member: { id: 'user-2', info: { name: 'Bob' } },
      };
      mock.triggerMessage(JSON.stringify(presenceMsg));

      expect(joinEvents).toHaveLength(1);
      expect(joinEvents[0].type).toBe('join');
      expect(joinEvents[0].member.id).toBe('user-2');
    });

    it('should fire leave handler on presence leave message', async () => {
      const leaveEvents: PresenceEvent[] = [];
      await rooms.join('lobby', member, {
        leave: (event) => leaveEvents.push(event),
      });

      // First add user-2 via join presence
      mock.triggerMessage(
        JSON.stringify({
          type: 'presence',
          room: 'lobby',
          data: 'join',
          member: { id: 'user-2' },
        } as RoomMessage),
      );

      // Then remove via leave presence
      mock.triggerMessage(
        JSON.stringify({
          type: 'presence',
          room: 'lobby',
          data: 'leave',
          member: { id: 'user-2' },
        } as RoomMessage),
      );

      expect(leaveEvents).toHaveLength(1);
      expect(leaveEvents[0].type).toBe('leave');
    });

    it('should update members map on presence events', async () => {
      const handle = await rooms.join('lobby', member);
      expect(handle.members.size).toBe(1);

      mock.triggerMessage(
        JSON.stringify({
          type: 'presence',
          room: 'lobby',
          data: 'join',
          member: { id: 'user-2' },
        } as RoomMessage),
      );

      expect(handle.members.size).toBe(2);
      expect(handle.members.has('user-2')).toBe(true);

      mock.triggerMessage(
        JSON.stringify({
          type: 'presence',
          room: 'lobby',
          data: 'leave',
          member: { id: 'user-2' },
        } as RoomMessage),
      );

      expect(handle.members.size).toBe(1);
    });
  });

  describe('message routing', () => {
    it('should route broadcast messages to the correct room', async () => {
      const messages: unknown[] = [];
      await rooms.join('lobby', member, {
        message: (data) => messages.push(data),
      });
      await rooms.join('chat', member);

      mock.triggerMessage(
        JSON.stringify({
          type: 'broadcast',
          room: 'lobby',
          data: 'hello lobby',
          member: { id: 'user-2' },
        } as RoomMessage),
      );

      expect(messages).toEqual(['hello lobby']);
    });

    it('should handle direct messages', async () => {
      const messages: unknown[] = [];
      await rooms.join('lobby', member, {
        message: (data) => messages.push(data),
      });

      mock.triggerMessage(
        JSON.stringify({
          type: 'direct',
          room: 'lobby',
          data: 'secret',
          member: { id: 'user-2' },
        } as RoomMessage),
      );

      expect(messages).toEqual(['secret']);
    });

    it('should fire joined handler with member list', async () => {
      let joinedRoom: string | null = null;
      let joinedMembers: RoomMember[] = [];
      await rooms.join('lobby', member, {
        joined: (room, members) => {
          joinedRoom = room;
          joinedMembers = members;
        },
      });

      mock.triggerMessage(
        JSON.stringify({
          type: 'join',
          room: 'lobby',
          data: [{ id: 'user-2' }, { id: 'user-3' }],
        } as RoomMessage),
      );

      expect(joinedRoom).toBe('lobby');
      expect(joinedMembers.length).toBe(3); // self + 2 others
    });

    it('should ignore messages for unknown rooms', async () => {
      await rooms.join('lobby', member);
      // Should not throw
      mock.triggerMessage(
        JSON.stringify({
          type: 'broadcast',
          room: 'unknown',
          data: 'ignored',
        } as RoomMessage),
      );
    });
  });

  describe('leaveAll', () => {
    it('should leave all rooms and close connection', async () => {
      await rooms.join('lobby', member);
      await rooms.join('chat', member);

      await rooms.leaveAll();

      expect(mock.mockSub.close).toHaveBeenCalled();
      expect(rooms.joinedRooms()).toEqual([]);
    });
  });

  describe('room lookup', () => {
    it('should return room handle by name', async () => {
      await rooms.join('lobby', member);
      const handle = rooms.room('lobby');
      expect(handle).toBeDefined();
      expect(handle!.name).toBe('lobby');
    });

    it('should return undefined for unknown room', () => {
      expect(rooms.room('nonexistent')).toBeUndefined();
    });

    it('should list joined rooms', async () => {
      await rooms.join('a', member);
      await rooms.join('b', member);
      expect(rooms.joinedRooms().sort()).toEqual(['a', 'b']);
    });
  });

  describe('error handling', () => {
    it('should propagate errors to all room handlers', async () => {
      const errors: Error[] = [];
      await rooms.join('lobby', member, {
        error: (err) => errors.push(err),
      });

      // Get error handler from subscribe call
      const subscribeCall = (mock.atmosphere.subscribe as ReturnType<typeof vi.fn>).mock
        .calls[0];
      const handlers = subscribeCall[1] as { error: (err: Error) => void };
      handlers.error(new Error('connection lost'));

      expect(errors).toHaveLength(1);
      expect(errors[0].message).toBe('connection lost');
    });

    it('should handle malformed messages gracefully', async () => {
      await rooms.join('lobby', member);
      // Should not throw
      mock.triggerMessage('not valid json {{{');
    });
  });
});
