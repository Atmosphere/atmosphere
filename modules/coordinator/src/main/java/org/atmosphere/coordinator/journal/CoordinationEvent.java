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

    record CoordinationStarted(
            String coordinationId,
            String coordinatorName,
            Instant timestamp
    ) implements CoordinationEvent {}

    record AgentDispatched(
            String coordinationId,
            String agentName,
            String skill,
            Map<String, String> args,
            Instant timestamp
    ) implements CoordinationEvent {
        public AgentDispatched {
            args = args != null ? Map.copyOf(args) : Map.of();
        }
    }

    record AgentCompleted(
            String coordinationId,
            String agentName,
            String skill,
            String resultText,
            Duration duration,
            Instant timestamp
    ) implements CoordinationEvent {}

    record AgentFailed(
            String coordinationId,
            String agentName,
            String skill,
            String error,
            Duration duration,
            Instant timestamp
    ) implements CoordinationEvent {}

    record AgentEvaluated(
            String coordinationId,
            String agentName,
            String evaluatorName,
            double score,
            boolean passed,
            Instant timestamp
    ) implements CoordinationEvent {}

    record CoordinationCompleted(
            String coordinationId,
            Duration totalDuration,
            int agentCallCount,
            Instant timestamp
    ) implements CoordinationEvent {}
}
