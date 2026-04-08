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
package org.atmosphere.coordinator.journal;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Sealed event hierarchy for coordination execution journaling. Each variant
 * captures a distinct lifecycle moment in an agent coordination.
 */
public sealed interface CoordinationEvent {

    String coordinationId();

    Instant timestamp();

    /** Returns a single-line human-readable log representation of this event. */
    String toLogLine();

    record CoordinationStarted(
            String coordinationId,
            String coordinatorName,
            Instant timestamp
    ) implements CoordinationEvent {
        @Override
        public String toLogLine() {
            return "START  " + coordinatorName;
        }
    }

    record AgentDispatched(
            String coordinationId,
            String agentName,
            String skill,
            Map<String, Object> args,
            Instant timestamp
    ) implements CoordinationEvent {
        public AgentDispatched {
            args = args != null ? Map.copyOf(args) : Map.of();
        }

        @Override
        public String toLogLine() {
            return "DISPATCH  " + agentName + " -> " + skill;
        }
    }

    record AgentCompleted(
            String coordinationId,
            String agentName,
            String skill,
            String resultText,
            Duration duration,
            Instant timestamp
    ) implements CoordinationEvent {
        @Override
        public String toLogLine() {
            return "DONE  " + agentName + " (" + duration.toMillis() + "ms)";
        }
    }

    record AgentFailed(
            String coordinationId,
            String agentName,
            String skill,
            String error,
            Duration duration,
            Instant timestamp
    ) implements CoordinationEvent {
        @Override
        public String toLogLine() {
            return "FAILED  " + agentName + ": " + error;
        }
    }

    record AgentEvaluated(
            String coordinationId,
            String agentName,
            String evaluatorName,
            double score,
            boolean passed,
            Instant timestamp
    ) implements CoordinationEvent {
        @Override
        public String toLogLine() {
            return "EVAL  " + agentName + " score=" + score;
        }
    }

    record AgentHandoff(
            String coordinationId,
            String fromAgent,
            String toAgent,
            String reason,
            Instant timestamp
    ) implements CoordinationEvent {
        @Override
        public String toLogLine() {
            return "HANDOFF  " + fromAgent + " -> " + toAgent
                    + (reason != null ? " (" + reason + ")" : "");
        }
    }

    record RouteEvaluated(
            String coordinationId,
            String inputAgent,
            int matchedRouteIndex,
            String selectedAgent,
            boolean matched,
            Instant timestamp
    ) implements CoordinationEvent {
        @Override
        public String toLogLine() {
            return matched
                    ? "ROUTE  " + inputAgent + " -> route[" + matchedRouteIndex + "] -> " + selectedAgent
                    : "ROUTE  " + inputAgent + " -> no match (fallback)";
        }
    }

    record CoordinationCompleted(
            String coordinationId,
            Duration totalDuration,
            int agentCallCount,
            Instant timestamp
    ) implements CoordinationEvent {
        @Override
        public String toLogLine() {
            return "COMPLETE  " + agentCallCount + " calls in " + totalDuration.toMillis() + "ms";
        }
    }

    /**
     * An agent's activity state changed (e.g., thinking, executing, retrying).
     * Provides observability into what agents are doing during a coordination.
     */
    record AgentActivityChanged(
            String coordinationId,
            String agentName,
            String activityType,
            String detail,
            Instant timestamp
    ) implements CoordinationEvent {
        @Override
        public String toLogLine() {
            return "ACTIVITY  " + agentName + " -> " + activityType
                    + (detail != null && !detail.isEmpty() ? " (" + detail + ")" : "");
        }
    }

    /**
     * An agent's circuit breaker state changed (e.g., CLOSED to OPEN).
     */
    record CircuitStateChanged(
            String coordinationId,
            String agentName,
            String fromState,
            String toState,
            Instant timestamp
    ) implements CoordinationEvent {
        @Override
        public String toLogLine() {
            return "CIRCUIT  " + agentName + " " + fromState + " -> " + toState;
        }
    }
}
