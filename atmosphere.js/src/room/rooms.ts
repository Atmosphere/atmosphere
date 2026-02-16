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

import type {
  AtmosphereRequest,
  Subscription,
  RoomMember,
  RoomMessage,
  RoomHandlers,
  RoomHandle,
  PresenceEvent,
} from '../types';
import { Atmosphere } from '../core/atmosphere';
import { logger } from '../utils/logger';

/**
 * Client-side room manager that provides a high-level API for
 * joining/leaving rooms with presence tracking.
 *
 * Works with the server-side `org.atmosphere.room.RoomManager` and
 * `RoomInterceptor`. Messages are JSON-encoded {@link RoomMessage}
 * envelopes that the server parses.
 *
 * ```typescript
 * const rooms = new AtmosphereRooms(atmosphere, {
 *   url: 'ws://localhost:8080/atmosphere/room',
 *   transport: 'websocket',
 * });
 *
 * const lobby = await rooms.join('lobby', { id: 'user-1' }, {
 *   message: (data, member) => console.log(`${member.id}: ${data}`),
 *   join: (event) => console.log(`${event.member.id} joined`),
 *   leave: (event) => console.log(`${event.member.id} left`),
 * });
 *
 * lobby.broadcast('Hello everyone!');
 * lobby.sendTo('user-2', 'Private message');
 * lobby.leave();
 * ```
 *
 * @since 5.0.0
 */
export class AtmosphereRooms {
  private atmosphere: Atmosphere;
  private baseRequest: AtmosphereRequest;
  private subscription: Subscription | null = null;
  private rooms = new Map<string, ManagedRoom>();

  constructor(atmosphere: Atmosphere, baseRequest: AtmosphereRequest) {
    this.atmosphere = atmosphere;
    this.baseRequest = baseRequest;
  }

  /**
   * Join a room with the given member identity.
   *
   * On first call, establishes the underlying transport connection.
   * Subsequent joins reuse the same connection.
   */
  async join<T = unknown>(
    roomName: string,
    member: RoomMember,
    handlers: RoomHandlers<T> = {},
  ): Promise<RoomHandle> {
    // Ensure connection exists
    if (!this.subscription) {
      await this.connect();
    }

    // Create managed room
    const room = new ManagedRoom(roomName, member, handlers as RoomHandlers, this.subscription!);
    this.rooms.set(roomName, room);

    // Send join command
    this.send({ type: 'join', room: roomName, member });

    logger.info(`Joined room '${roomName}' as ${member.id}`);
    return room;
  }

  /**
   * Leave a specific room.
   */
  leave(roomName: string): void {
    const room = this.rooms.get(roomName);
    if (room) {
      room.leave();
      this.rooms.delete(roomName);
    }
  }

  /**
   * Leave all rooms and close the connection.
   */
  async leaveAll(): Promise<void> {
    for (const room of this.rooms.values()) {
      room.leave();
    }
    this.rooms.clear();
    if (this.subscription) {
      await this.subscription.close();
      this.subscription = null;
    }
  }

  /**
   * Get a room handle by name.
   */
  room(name: string): RoomHandle | undefined {
    return this.rooms.get(name);
  }

  /**
   * Get all joined room names.
   */
  joinedRooms(): string[] {
    return Array.from(this.rooms.keys());
  }

  private async connect(): Promise<void> {
    this.subscription = await this.atmosphere.subscribe(this.baseRequest, {
      message: (response) => {
        this.handleMessage(response.responseBody as unknown as string);
      },
      error: (error) => {
        for (const room of this.rooms.values()) {
          room.handlers.error?.(error);
        }
      },
    });
  }

  private handleMessage(raw: string): void {
    let msg: RoomMessage;
    try {
      msg = typeof raw === 'string' ? JSON.parse(raw) : raw;
    } catch {
      logger.warn('Failed to parse room message:', raw);
      return;
    }

    const room = this.rooms.get(msg.room);
    if (!room) {
      logger.debug(`Message for unknown room '${msg.room}', ignoring`);
      return;
    }

    room.handleIncoming(msg);
  }

  private send(msg: RoomMessage): void {
    if (this.subscription) {
      this.subscription.push(JSON.stringify(msg));
    }
  }
}

/**
 * Internal managed room that tracks members and dispatches events.
 */
class ManagedRoom implements RoomHandle {
  readonly name: string;
  readonly handlers: RoomHandlers;
  private _members = new Map<string, RoomMember>();
  private localMember: RoomMember;
  private subscription: Subscription;
  private _left = false;

  constructor(
    name: string,
    localMember: RoomMember,
    handlers: RoomHandlers,
    subscription: Subscription,
  ) {
    this.name = name;
    this.localMember = localMember;
    this.handlers = handlers;
    this.subscription = subscription;

    // Add self to members
    this._members.set(localMember.id, localMember);
  }

  get members(): ReadonlyMap<string, RoomMember> {
    return this._members;
  }

  broadcast(data: unknown): void {
    if (this._left) throw new Error(`Already left room '${this.name}'`);
    const msg: RoomMessage = { type: 'broadcast', room: this.name, data };
    this.subscription.push(JSON.stringify(msg));
  }

  sendTo(memberId: string, data: unknown): void {
    if (this._left) throw new Error(`Already left room '${this.name}'`);
    const msg: RoomMessage = {
      type: 'direct',
      room: this.name,
      data,
      target: memberId,
    };
    this.subscription.push(JSON.stringify(msg));
  }

  leave(): void {
    if (this._left) return;
    this._left = true;
    const msg: RoomMessage = {
      type: 'leave',
      room: this.name,
      member: this.localMember,
    };
    this.subscription.push(JSON.stringify(msg));
    this._members.delete(this.localMember.id);
    logger.info(`Left room '${this.name}'`);
  }

  /**
   * Handle an incoming room protocol message.
   */
  handleIncoming(msg: RoomMessage): void {
    switch (msg.type) {
      case 'presence': {
        if (!msg.member) break;
        const event: PresenceEvent = {
          type: msg.data === 'leave' ? 'leave' : 'join',
          room: this.name,
          member: msg.member,
          timestamp: Date.now(),
        };
        if (event.type === 'join') {
          this._members.set(msg.member.id, msg.member);
          this.handlers.join?.(event);
        } else {
          this._members.delete(msg.member.id);
          this.handlers.leave?.(event);
        }
        break;
      }
      case 'broadcast':
      case 'direct': {
        const sender: RoomMember = msg.member ?? { id: 'unknown' };
        this.handlers.message?.(msg.data, sender);
        break;
      }
      case 'join': {
        // Server confirms join with member list
        if (Array.isArray(msg.data)) {
          for (const m of msg.data as RoomMember[]) {
            this._members.set(m.id, m);
          }
        }
        this.handlers.joined?.(this.name, Array.from(this._members.values()));
        break;
      }
      default:
        logger.debug(`Unknown room message type: ${msg.type}`);
    }
  }
}
