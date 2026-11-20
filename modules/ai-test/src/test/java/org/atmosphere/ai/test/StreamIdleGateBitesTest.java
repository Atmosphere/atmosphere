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
package org.atmosphere.ai.test;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stream idle meta-gate bites: a runtime that declares CANCELLATION with
 * neither a stalled-stream fixture nor a named covering test fails
 * {@code everyRuntimePinsTheStreamIdleBound}, and naming a test that does not
 * exist fails it too; each of the two documented ways out passes.
 */
class StreamIdleGateBitesTest {

    @Test
    void aCancellableRuntimeWithNoIdleCoverageFails() {
        var error = assertThrows(AssertionError.class,
                () -> new DeclaresCancellation().everyRuntimePinsTheStreamIdleBound());
        var message = String.valueOf(error.getMessage());
        assertTrue(message.contains("createStalledStreamFixture") && message.contains("streamIdleCoveredOutsideTck"),
                "the failure names both ways out: " + message);
    }

    @Test
    void aCoveringTestThatDoesNotExistFails() {
        assertThrows(AssertionError.class,
                () -> new NamesAMissingTest().everyRuntimePinsTheStreamIdleBound());
    }

    @Test
    void aNamedCoveringTestSatisfiesTheGate() {
        assertDoesNotThrow(() -> new NamesARealTest().everyRuntimePinsTheStreamIdleBound());
    }

    @Test
    void aStalledStreamFixtureSatisfiesTheGate() {
        assertDoesNotThrow(() -> new HasAFixture().everyRuntimePinsTheStreamIdleBound());
    }

    @Test
    void aRuntimeWithoutCancellationIsNotGated() {
        assertDoesNotThrow(() -> new NotCancellable().everyRuntimePinsTheStreamIdleBound());
    }

    // --- fixtures -------------------------------------------------------

    private static class DeclaresCancellation extends AbstractAgentRuntimeContractTest {
        Set<AiCapability> declared() {
            return Set.of(AiCapability.CANCELLATION);
        }

        @Override
        protected AgentRuntime createRuntime() {
            return new SilentRuntime(declared());
        }

        @Override
        protected AgentExecutionContext createTextContext() {
            return new AgentExecutionContext("hi", null, "m", null, "s", null, null,
                    List.of(), null, null, null, Map.of(), List.of(), null, null);
        }

        @Override
        protected AgentExecutionContext createToolCallContext() {
            return createTextContext();
        }

        @Override
        protected AgentExecutionContext createErrorContext() {
            return createTextContext();
        }

        @Override
        protected Set<AiCapability> expectedCapabilities() {
            return declared();
        }

        @Override
        protected Set<GenerationParamsSupport> expectedGenerationHonoring() {
            return Set.of();
        }
    }

    private static final class NamesAMissingTest extends DeclaresCancellation {
        @Override
        protected String streamIdleCoveredOutsideTck() {
            return "NoSuchIdleTimeoutTest";
        }
    }

    private static final class NamesARealTest extends DeclaresCancellation {
        @Override
        protected String streamIdleCoveredOutsideTck() {
            return "StreamIdleGateBitesTest";
        }
    }

    private static final class HasAFixture extends DeclaresCancellation {
        @Override
        protected StalledStreamFixture createStalledStreamFixture(StallingHttpServer server) {
            return StalledStreamFixture.of(createRuntime(), tools -> createTextContext())
                    .leftToProvider("a fixture of the gate's own test");
        }
    }

    private static final class NotCancellable extends DeclaresCancellation {
        @Override
        Set<AiCapability> declared() {
            return Set.of(AiCapability.TEXT_STREAMING);
        }
    }

    /** Declares what it is given; never dispatches. */
    private record SilentRuntime(Set<AiCapability> capabilities) implements AgentRuntime {
        @Override public String name() { return "stream-idle-gate-fixture"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return -1; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public void execute(AgentExecutionContext context, StreamingSession session) {
            session.complete();
        }
    }
}
