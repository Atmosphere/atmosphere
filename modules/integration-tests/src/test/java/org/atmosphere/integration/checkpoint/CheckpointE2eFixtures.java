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
package org.atmosphere.integration.checkpoint;

import org.atmosphere.coordinator.journal.CoordinationEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Shared fixtures used by the per-runtime checkpoint E2E tests. These tests
 * verify that the {@code CheckpointingCoordinationJournal} captures the
 * events emitted by an agent regardless of which AI runtime (Spring AI,
 * LangChain4j, Koog, Google ADK) is driving it.
 *
 * <p>The checkpoint SPI is deliberately runtime-neutral: it only consumes
 * the {@code CoordinationEvent} stream produced by the coordinator. These
 * helpers fabricate event streams in the shape each runtime typically
 * produces so the tests read as "runtime X emits these events, the bridge
 * captures them correctly" — no real LLM calls required.</p>
 */
final class CheckpointE2eFixtures {

    private CheckpointE2eFixtures() {}

    /** Helper to build an {@code AgentDispatched} event. */
    static CoordinationEvent.AgentDispatched dispatched(
            String coordinationId, String agent, String skill, Map<String, Object> args) {
        return new CoordinationEvent.AgentDispatched(
                coordinationId, agent, skill, args, Instant.now());
    }

    /** Helper to build an {@code AgentCompleted} event. */
    static CoordinationEvent.AgentCompleted completed(
            String coordinationId, String agent, String skill, String resultText, long millis) {
        return new CoordinationEvent.AgentCompleted(
                coordinationId, agent, skill, resultText,
                Duration.ofMillis(millis), Instant.now());
    }

    /** Helper to build an {@code AgentFailed} event. */
    static CoordinationEvent.AgentFailed failed(
            String coordinationId, String agent, String skill, String error, long millis) {
        return new CoordinationEvent.AgentFailed(
                coordinationId, agent, skill, error,
                Duration.ofMillis(millis), Instant.now());
    }

    /** Helper to build a {@code CoordinationStarted} event. */
    static CoordinationEvent.CoordinationStarted started(String coordinationId, String coordinator) {
        return new CoordinationEvent.CoordinationStarted(
                coordinationId, coordinator, Instant.now());
    }

    /** Helper to build a {@code CoordinationCompleted} event. */
    static CoordinationEvent.CoordinationCompleted allDone(
            String coordinationId, int callCount, long totalMillis) {
        return new CoordinationEvent.CoordinationCompleted(
                coordinationId, Duration.ofMillis(totalMillis), callCount, Instant.now());
    }
}
