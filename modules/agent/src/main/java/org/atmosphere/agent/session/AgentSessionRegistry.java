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
package org.atmosphere.agent.session;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight registry tracking active agent sessions. Thread-safe,
 * designed to be accessed from both agent handlers and the admin control
 * plane.
 *
 * <p>Uses a global singleton pattern (like {@code AgentRuntimeResolver})
 * so it can be accessed from any module without explicit wiring.</p>
 *
 * @since 4.0
 */
public final class AgentSessionRegistry {

    private static final AgentSessionRegistry INSTANCE = new AgentSessionRegistry();

    private final ConcurrentHashMap<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    /**
     * Snapshot of an active agent session.
     *
     * @param sessionId     the resource UUID
     * @param agentName     the agent that owns this session
     * @param transport     the transport type (WEBSOCKET, LONG_POLLING, etc.)
     * @param startTime     when the session was established
     * @param lastActivity  when the last message was received
     * @param messageCount  number of messages received
     */
    public record SessionInfo(String sessionId, String agentName, String transport,
                              Instant startTime, Instant lastActivity,
                              int messageCount) {
    }

    private AgentSessionRegistry() {
    }

    /** Get the global registry instance. */
    public static AgentSessionRegistry instance() {
        return INSTANCE;
    }

    /**
     * Record a new session.
     */
    public void sessionStarted(String sessionId, String agentName, String transport) {
        var now = Instant.now();
        sessions.put(sessionId, new SessionInfo(
                sessionId, agentName, transport, now, now, 0));
    }

    /**
     * Record a message received on an existing session.
     */
    public void messageReceived(String sessionId) {
        sessions.computeIfPresent(sessionId, (id, info) ->
                new SessionInfo(id, info.agentName(), info.transport(),
                        info.startTime(), Instant.now(), info.messageCount() + 1));
    }

    /**
     * Remove a session when the connection is closed.
     */
    public void sessionEnded(String sessionId) {
        sessions.remove(sessionId);
    }

    /**
     * Get all active sessions for a specific agent.
     */
    public List<SessionInfo> sessionsForAgent(String agentName) {
        return sessions.values().stream()
                .filter(s -> s.agentName().equals(agentName))
                .toList();
    }

    /**
     * Get all active sessions across all agents.
     */
    public List<SessionInfo> allSessions() {
        return List.copyOf(sessions.values());
    }

    /**
     * Get the total number of active sessions.
     */
    public int totalSessionCount() {
        return sessions.size();
    }

    /**
     * Get the number of active sessions for a specific agent.
     */
    public int sessionCount(String agentName) {
        var count = new AtomicInteger();
        sessions.values().forEach(s -> {
            if (s.agentName().equals(agentName)) {
                count.incrementAndGet();
            }
        });
        return count.get();
    }
}
