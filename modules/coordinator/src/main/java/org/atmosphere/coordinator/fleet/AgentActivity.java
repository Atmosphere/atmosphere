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
package org.atmosphere.coordinator.fleet;

import java.time.Duration;
import java.time.Instant;

/**
 * Agent activity state model for coordination observability. Captures what an
 * agent is doing right now, enabling real-time streaming of state transitions
 * to clients via {@link org.atmosphere.ai.AiEvent.AgentStep}.
 *
 * <p>Inspired by Google Scion's three-layer state model, but adapted for
 * Atmosphere's embedded library model. We omit Scion's "Phase" layer
 * (infrastructure lifecycle) since we are not a container orchestrator.</p>
 *
 * <p>Sealed to ensure exhaustive pattern matching in switch expressions.</p>
 */
public sealed interface AgentActivity {

    /** The agent name this activity applies to. */
    String agentName();

    /** Agent registered but not currently working. */
    record Idle(String agentName, Instant since) implements AgentActivity {}

    /** LLM processing — the agent is thinking about a response. */
    record Thinking(String agentName, String skill, Instant since) implements AgentActivity {}

    /** Running a tool or action within a skill. */
    record Executing(String agentName, String skill, String detail, Instant since) implements AgentActivity {}

    /** Agent needs human input to proceed. */
    record WaitingForInput(String agentName, String reason, Instant since) implements AgentActivity {}

    /**
     * Transient failure — the agent IS working, backing off between retry attempts.
     * Distinct from {@link CircuitOpen}: the system is still trying.
     */
    record Retrying(String agentName, String skill, int attempt, int maxAttempts,
                    Instant nextAttemptAt) implements AgentActivity {}

    /**
     * Policy decision — the circuit breaker is open, the agent is NOT being called.
     * Distinct from {@link Retrying}: the system has stopped calling this agent
     * until the cooldown expires.
     */
    record CircuitOpen(String agentName, String reason,
                       Instant cooldownUntil) implements AgentActivity {}

    /** Agent completed its task successfully. */
    record Completed(String agentName, String skill, Duration elapsed) implements AgentActivity {}

    /** Agent failed its task. */
    record Failed(String agentName, String skill, String error,
                  Duration elapsed) implements AgentActivity {}
}
