/*
 * Copyright 2008-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.admin;

import java.time.Duration;
import java.time.Instant;

/**
 * Sealed interface for control plane events streamed to admin dashboard
 * clients via WebSocket. Each variant carries the minimal data needed for
 * real-time display.
 *
 * @since 4.0
 */
public sealed interface AdminEvent {

    /** When the event occurred. */
    Instant timestamp();

    record ResourceConnected(String uuid, String transport, String broadcaster,
                             Instant timestamp) implements AdminEvent {
    }

    record ResourceDisconnected(String uuid, String reason,
                                Instant timestamp) implements AdminEvent {
    }

    record BroadcasterCreated(String id, Instant timestamp) implements AdminEvent {
    }

    record BroadcasterDestroyed(String id, Instant timestamp) implements AdminEvent {
    }

    record MessageBroadcast(String broadcasterId, int resourceCount,
                            Instant timestamp) implements AdminEvent {
    }

    record AgentSessionStarted(String agentName, String sessionId,
                               Instant timestamp) implements AdminEvent {
    }

    record AgentSessionEnded(String agentName, String sessionId,
                             Duration duration, int messageCount,
                             Instant timestamp) implements AdminEvent {
    }

    record TaskStateChanged(String taskId, String oldState, String newState,
                            Instant timestamp) implements AdminEvent {
    }

    record AgentDispatched(String coordinationId, String agentName,
                           String skill, Instant timestamp) implements AdminEvent {
    }

    record AgentCompleted(String coordinationId, String agentName,
                          Duration duration, Instant timestamp) implements AdminEvent {
    }

    record ControlActionExecuted(String principal, String action,
                                 String target, boolean success,
                                 Instant timestamp) implements AdminEvent {
    }
}
