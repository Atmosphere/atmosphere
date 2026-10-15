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
package org.atmosphere.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An environment's {@link AgentRuntimeRegistry} resolves what the static
 * {@link AgentRuntimeResolver} resolves for the same knobs — the same
 * runtimes, in the same order, the same selection failure — but with
 * instances of its own, attached to it.
 */
// Exercises the static AI facade (the AiConfig / AgentRuntimeResolver readers
// or an adapter's process-wide binder): deprecated for framework code, kept for
// code that runs with no application.
@SuppressWarnings("deprecation")
class AgentRuntimeRegistryResolutionTest {

    @AfterEach
    void tearDown() {
        System.clearProperty(AiConfig.RUNTIME_PROPERTY);
        AiConfig.resetHostConfig();
        AiConfig.resetForTesting();
        AgentRuntimeResolver.reset();
    }

    private static List<String> describe(List<AgentRuntime> runtimes) {
        return runtimes.stream().map(r -> r.name() + "=" + r.getClass().getName()).toList();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"built-in", " Demo ", "BUILT-IN"})
    void theRegistryResolvesWhatTheStaticResolverResolves(String runtime) {
        AiConfig.resetForTesting();
        var knobs = new HashMap<String, String>();
        if (runtime != null) {
            knobs.put(AiConfig.RUNTIME_PROPERTY, runtime);
        }
        var installed = AiConfig.installHostConfig(knobs::get);
        var expected = AgentRuntimeResolver.resolveAll();
        installed.close();
        AgentRuntimeResolver.reset();

        try (var environment = AiEnvironment.create(knobs::get)) {
            var resolved = environment.runtimes().resolveAll();
            assertEquals(describe(expected), describe(resolved), "knob " + runtime);
            for (int i = 0; i < resolved.size(); i++) {
                assertNotSame(expected.get(i), resolved.get(i),
                        "the environment's instance is its own: " + resolved.get(i).name());
            }
            for (var instance : resolved) {
                if (instance instanceof AbstractAgentRuntime<?> attached) {
                    assertSame(environment, attached.environment(),
                            instance.name() + " is attached to the environment that loaded it");
                }
            }
            assertSame(resolved, environment.runtimes().resolveAll(), "the resolution is cached");
        }
    }

    @Test
    void anUnknownRuntimeFailsTheSameWay() {
        var knobs = new HashMap<String, String>();
        knobs.put(AiConfig.RUNTIME_PROPERTY, "no-such-runtime");
        var installed = AiConfig.installHostConfig(knobs::get);
        var expected = assertThrows(AgentRuntimeSelectionException.class, AgentRuntimeResolver::resolveAll);
        installed.close();

        try (var environment = AiEnvironment.create(knobs::get)) {
            var failure = assertThrows(AgentRuntimeSelectionException.class,
                    () -> environment.runtimes().resolveAll());
            assertEquals(expected.getMessage(), failure.getMessage());
        }
    }

    @Test
    void anEnvironmentRuntimeKnobDoesNotReachAnotherEnvironment() {
        var builtIn = new HashMap<String, String>();
        builtIn.put(AiConfig.RUNTIME_PROPERTY, "built-in");
        var demo = new HashMap<String, String>();
        demo.put(AiConfig.RUNTIME_PROPERTY, "demo");
        try (var a = AiEnvironment.create(builtIn::get); var b = AiEnvironment.create(demo::get)) {
            assertEquals("built-in", a.runtimes().resolve().name());
            assertEquals("demo", b.runtimes().resolve().name());
            assertTrue(a.knob(AiConfig.RUNTIME_PROPERTY).orElseThrow().equals("built-in"));
        }
    }
}
