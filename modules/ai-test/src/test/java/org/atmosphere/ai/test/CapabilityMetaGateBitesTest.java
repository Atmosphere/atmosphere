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

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demonstrates that the capability meta-gate actually bites.
 *
 * <p>{@code declaredCapabilitiesWithBehavioralHooksAreExercised} exists to stop a
 * runtime declaring a capability whose behavioural assertion then skips silently
 * because its hook returns {@code null} — Runtime Truth, Invariant #5. Every one
 * of the twelve runtime suites inherits it and passes, but a gate that only ever
 * passes proves nothing about what it would catch: its teeth were inferred from
 * reading its source, never demonstrated. This project has been burned by
 * exactly that (a coverage gate that walked its own artifact and self-satisfied
 * for months), so the gate gets a gate.</p>
 *
 * <p>Two synthetic subclasses, identical but for the hook: one declares VISION
 * and leaves {@code createImageContext()} at its {@code null} default, one
 * overrides it. The first must fail and name the hook; the second must pass.</p>
 */
class CapabilityMetaGateBitesTest {

    @Test
    void aDeclaredCapabilityWithNoBehaviouralHookFailsAndNamesTheHook() {
        var error = assertThrows(AssertionError.class,
                () -> new DeclaresVisionWithoutHook()
                        .declaredCapabilitiesWithBehavioralHooksAreExercised(),
                "declaring VISION while createImageContext() returns null must fail the TCK — "
                        + "otherwise the capability passes with zero behavioural checks");

        var message = String.valueOf(error.getMessage());
        assertTrue(message.contains("VISION"),
                "the failure must name the capability; got: " + message);
        assertTrue(message.contains("createImageContext()"),
                "the failure must name the hook to override, so the fix is obvious "
                        + "without reading the TCK source; got: " + message);
    }

    @Test
    void overridingTheHookSatisfiesTheGate() {
        assertDoesNotThrow(() -> new DeclaresVisionWithHook()
                        .declaredCapabilitiesWithBehavioralHooksAreExercised(),
                "a runtime that actually supplies the hook must pass — the gate must "
                        + "discriminate, not fail everything");
    }

    @Test
    void anEscapeHatchEntryAlsoSatisfiesTheGate() {
        assertDoesNotThrow(() -> new DeclaresVisionCoveredElsewhere()
                        .declaredCapabilitiesWithBehavioralHooksAreExercised(),
                "naming the test that proves the capability outside the TCK is the "
                        + "documented alternative to overriding the hook");
    }

    // --- fixtures -------------------------------------------------------

    /** Declares VISION and leaves every behavioural hook at its null default. */
    private static class DeclaresVisionWithoutHook extends AbstractAgentRuntimeContractTest {

        @Override
        protected AgentRuntime createRuntime() {
            return new SilentRuntime();
        }

        @Override
        protected AgentExecutionContext createTextContext() {
            return new AgentExecutionContext("hi", null, "m", null, "s", null, null,
                    java.util.List.of(), null, null, null, Map.of(), java.util.List.of(),
                    null, null);
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
            return Set.of(AiCapability.VISION);
        }

        @Override
        protected Set<GenerationParamsSupport> expectedGenerationHonoring() {
            return Set.of();
        }
    }

    /** Same, but supplies the VISION hook. */
    private static final class DeclaresVisionWithHook extends DeclaresVisionWithoutHook {
        @Override
        protected AgentExecutionContext createImageContext() {
            return createTextContext();
        }
    }

    /** Same, but points at a test elsewhere that proves VISION. */
    private static final class DeclaresVisionCoveredElsewhere extends DeclaresVisionWithoutHook {
        @Override
        protected Map<AiCapability, String> capabilitiesCoveredOutsideTck() {
            return Map.of(AiCapability.VISION,
                    "org.atmosphere.ai.test.CapabilityMetaGateBitesTest");
        }
    }

    /** Declares only what the fixtures need; never dispatches. */
    private static final class SilentRuntime implements AgentRuntime {
        @Override
        public String name() {
            return "meta-gate-fixture";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public int priority() {
            return -1;
        }

        @Override
        public void configure(AiConfig.LlmSettings settings) {
        }

        @Override
        public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.VISION);
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            session.complete();
        }
    }
}
