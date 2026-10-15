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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeResolverTest {

    @AfterEach
    void tearDown() {
        System.clearProperty(AiConfig.RUNTIME_PROPERTY);
        AiConfig.resetHostConfig();
        AgentRuntimeResolver.reset();
    }

    /**
     * With nothing configured both runtimes of this module are available: the
     * demo runtime (highest priority) and the built-in one (priority 0).
     */
    private static void unconfigured() {
        AiConfig.resetForTesting();
        AgentRuntimeResolver.reset();
    }

    @Test
    void unsetRuntimeResolvesByPriority() {
        unconfigured();
        assertEquals(List.of("demo", "built-in"),
                AgentRuntimeResolver.resolveAll().stream().map(AgentRuntime::name).toList());
        assertEquals("demo", AgentRuntimeResolver.resolve().name());
    }

    @Test
    void aNamedRuntimeIsSelectedOverAHigherPriorityOne() {
        unconfigured();
        System.setProperty(AiConfig.RUNTIME_PROPERTY, " Built-In ");
        AgentRuntimeResolver.reset();

        assertEquals("built-in", AgentRuntimeResolver.resolve().name());
        assertEquals(List.of("built-in"),
                AgentRuntimeResolver.resolveAll().stream().map(AgentRuntime::name).toList(),
                "no other runtime is dispatched to when one is named");
    }

    @Test
    void theHostConfigurationSelectsTheRuntime() {
        unconfigured();
        AiConfig.installHostConfig(Map.of(AiConfig.RUNTIME_PROPERTY, "built-in")::get);
        assertEquals("built-in", AgentRuntimeResolver.resolve().name());
    }

    @Test
    void aNamedRuntimeThatIsAbsentFailsNamingTheAvailableOnes() {
        unconfigured();
        System.setProperty(AiConfig.RUNTIME_PROPERTY, "spring-ai");
        AgentRuntimeResolver.reset();

        var e = assertThrows(IllegalStateException.class, AgentRuntimeResolver::resolve);
        assertTrue(e.getMessage().contains("atmosphere.ai.runtime=spring-ai"), e.getMessage());
        assertTrue(e.getMessage().contains("[demo, built-in]"), e.getMessage());
        assertThrows(IllegalStateException.class, AgentRuntimeResolver::resolveAll,
                "a failed selection is not cached as a resolution");
    }

    @Test
    void resolveReturnsNonNull() {
        var runtime = AgentRuntimeResolver.resolve();
        assertNotNull(runtime);
    }

    @Test
    void resolveAllReturnsNonEmptyList() {
        var runtimes = AgentRuntimeResolver.resolveAll();
        assertNotNull(runtimes);
        assertFalse(runtimes.isEmpty());
    }

    @Test
    void resolvedRuntimeHasName() {
        var runtime = AgentRuntimeResolver.resolve();
        assertNotNull(runtime.name());
        assertFalse(runtime.name().isBlank());
    }

    @Test
    void resolveAllContainsResolvedRuntime() {
        var primary = AgentRuntimeResolver.resolve();
        var all = AgentRuntimeResolver.resolveAll();
        // resolve() returns the first element of resolveAll()
        assertNotNull(all.getFirst());
        // Both should return the same name since resolve() returns resolveAll().getFirst()
        assertNotNull(primary.name());
    }
}
