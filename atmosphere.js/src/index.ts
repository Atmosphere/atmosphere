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

import { Atmosphere } from './core/atmosphere';

// Export main class
export { Atmosphere };

// Export types
export type * from './types';

// Export transports for advanced use
export { WebSocketTransport } from './transports/websocket';
export { SSETransport } from './transports/sse';
export { LongPollingTransport } from './transports/long-polling';
export { StreamingTransport } from './transports/streaming';
export { BaseTransport } from './transports/base';

// Export protocol utilities
export { AtmosphereProtocol } from './utils/protocol';

// Export room support
export { AtmosphereRooms } from './room/rooms';

// Export AI streaming support
export { subscribeStreaming } from './streaming';
export type { StreamingMessage, StreamingHandlers, StreamingHandle, SessionStats, RoutingInfo, SendOptions } from './streaming/types';

// Export logger for configuration
export { logger } from './utils/logger';

// Default singleton instance for convenience
export const atmosphere = new Atmosphere();

// Version
export { VERSION } from './version';
