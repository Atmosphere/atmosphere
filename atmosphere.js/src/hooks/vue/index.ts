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

export { useAtmosphere } from './useAtmosphere';
export { useRoom } from './useRoom';
export { usePresence } from './usePresence';
export { useOfflineQueue } from './useOfflineQueue';
export type { UseOfflineQueueResult as UseOfflineQueueResultVue } from './useOfflineQueue';
export { useMessageHistory } from './useMessageHistory';
export type {
  UseMessageHistoryOptions as UseMessageHistoryOptionsVue,
  UseMessageHistoryResult as UseMessageHistoryResultVue,
} from './useMessageHistory';
export { useStreaming } from './useStreaming';
export { useConnectionStatus } from './useConnectionStatus';
export {
  ConnectionStatusBadge,
  DEFAULT_LABELS as CONNECTION_STATUS_LABELS,
  DEFAULT_COLORS as CONNECTION_STATUS_COLORS,
} from './ConnectionStatusBadge';
export type {
  ConnectionEvent,
  ConnectionPhase,
  ConnectionStatusOptions,
  ConnectionStatusSnapshot,
  ConnectionTransportName,
} from '../../resilience';
