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
package org.atmosphere.agent.processor;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the Quarkus {@code @Agent} init crash: an {@code AgentRuntime}
 * whose {@code configure()} throws at registration time (e.g. the Quarkus
 * LangChain4j synthetic {@code ChatModel} bean pulls in
 * {@code TlsConfigurationRegistry}, which is not initialised during servlet
 * init) MUST NOT abort agent registration. Before the fix,
 * {@link AgentProcessor#resolveRuntime} called {@code configure()} unguarded,
 * so the exception propagated out of {@code handle()} and aborted the whole
 * Atmosphere annotation-scan phase — leaving the sample with zero registered
 * endpoints (console "Disconnected", {@code /atmosphere/ai-chat} → 404).
 *
 * <p>The fix mirrors the guard {@code AiEndpointProcessor#resolveRuntimeWithRouting}
 * already had, so {@code @Agent} and {@code @AiEndpoint} tolerate eager-configure
 * failure identically (Mode Parity). The runtime is re-resolved at request time
 * once the bean graph is fully wired.</p>
 */
public class AgentProcessorRuntimeConfigGuardTest {

    @Test
    public void throwingConfigureDoesNotAbortRegistration() {
        var processor = new AgentProcessor();
        var attempted = new AtomicBoolean(false);
        var throwing = new ThrowingConfigureRuntime(attempted);

        var resolved = assertDoesNotThrow(
                () -> processor.resolveRuntime(null, List.of(throwing)),
                "A backend whose configure() throws must not abort agent registration");

        assertTrue(attempted.get(),
                "configure() must have been attempted (and the exception swallowed)");
        assertSame(throwing, resolved,
                "resolveRuntime must still return the backend so the agent registers; "
                        + "the runtime is re-resolved at request time");
    }

    @Test
    public void throwingConfigureDoesNotMaskHealthyBackend() {
        var processor = new AgentProcessor();
        var firstAttempted = new AtomicBoolean(false);
        var secondAttempted = new AtomicBoolean(false);
        var throwing = new ThrowingConfigureRuntime(firstAttempted);
        var healthy = new HealthyRuntime(secondAttempted);

        // Both backends are configured in order; a throw on the first must not
        // stop the loop from configuring the second.
        assertDoesNotThrow(() -> processor.resolveRuntime(null, List.of(throwing, healthy)));

        assertTrue(firstAttempted.get(), "first backend configure() attempted");
        assertTrue(secondAttempted.get(),
                "a throw on an earlier backend must not skip configuring later backends");
    }

    /** Reproduces the Quarkus TLS-not-ready failure: configure() throws. */
    private static final class ThrowingConfigureRuntime implements AgentRuntime {
        private final AtomicBoolean configureAttempted;

        ThrowingConfigureRuntime(AtomicBoolean configureAttempted) {
            this.configureAttempted = configureAttempted;
        }

        @Override public String name() {
            return "throwing-configure-stub";
        }

        @Override public boolean isAvailable() {
            return true;
        }

        @Override public int priority() {
            return 0;
        }

        @Override public void configure(AiConfig.LlmSettings settings) {
            configureAttempted.set(true);
            throw new IllegalStateException(
                    "Synthetic bean instance for TlsConfigurationRegistry not initialized yet");
        }

        @Override public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING);
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            session.complete();
        }
    }

    /** A backend whose configure() succeeds, to prove the loop keeps going. */
    private static final class HealthyRuntime implements AgentRuntime {
        private final AtomicBoolean configureAttempted;

        HealthyRuntime(AtomicBoolean configureAttempted) {
            this.configureAttempted = configureAttempted;
        }

        @Override public String name() {
            return "healthy-stub";
        }

        @Override public boolean isAvailable() {
            return true;
        }

        @Override public int priority() {
            return 0;
        }

        @Override public void configure(AiConfig.LlmSettings settings) {
            configureAttempted.set(true);
        }

        @Override public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING);
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            session.complete();
        }
    }
}
