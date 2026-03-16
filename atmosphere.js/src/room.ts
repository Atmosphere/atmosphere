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
 * Subpath entry point for room support.
 *
 * ```ts
 * import { AtmosphereRooms } from 'atmosphere.js/room';
 * import type { RoomHandle, RoomHandlers, RoomMember } from 'atmosphere.js/room';
 * ```
 */
export { AtmosphereRooms } from './room/rooms';
export type {
  RoomMember,
  RoomMessage,
  RoomHandlers,
  RoomHandle,
  PresenceEvent,
  PresenceEventType,
  TypingEvent,
} from './types';
