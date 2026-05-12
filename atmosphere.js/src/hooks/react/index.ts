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

export { AtmosphereProvider, useAtmosphereContext } from './provider';
export { useAtmosphere } from './useAtmosphere';
export type { UseAtmosphereOptions, UseAtmosphereResult } from './useAtmosphere';
export { useAtmosphereCore } from '../shared/useAtmosphereCore';
export type { UseAtmosphereCoreResult, CoreSubscriptionHandlers } from '../shared/useAtmosphereCore';
export { useRoom } from './useRoom';
export { usePresence } from './usePresence';
export { useOfflineQueue } from './useOfflineQueue';
export type { UseOfflineQueueOptions, UseOfflineQueueResult } from './useOfflineQueue';
export { useMessageHistory } from './useMessageHistory';
export type { UseMessageHistoryOptions, UseMessageHistoryResult } from './useMessageHistory';
export { useOptimistic } from './useOptimistic';
export type { UseOptimisticOptions, UseOptimisticResult } from './useOptimistic';
export { useStreaming } from './useStreaming';
export type { AiEvent, StreamingConnectionState, UseStreamingOptions, UseStreamingResult } from './useStreaming';
export { useConnectionStatus } from './useConnectionStatus';
export type { UseConnectionStatusResult } from './useConnectionStatus';
export { useExternalConnectionStatus } from './useExternalConnectionStatus';
export type {
  UseExternalConnectionStatusOptions,
  UseExternalConnectionStatusResult,
} from './useExternalConnectionStatus';
export {
  ConnectionStatusBadge,
  DEFAULT_LABELS as CONNECTION_STATUS_LABELS,
  DEFAULT_COLORS as CONNECTION_STATUS_COLORS,
} from './ConnectionStatusBadge';
export type { ConnectionStatusBadgeProps } from './ConnectionStatusBadge';
// Re-export resilience types so consumers can `import type { ConnectionStatusSnapshot } from 'atmosphere.js/react'`.
export type {
  ConnectionEvent,
  ConnectionPhase,
  ConnectionStatusOptions,
  ConnectionStatusSnapshot,
  ConnectionTransportName,
} from '../../resilience';
