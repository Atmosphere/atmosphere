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

import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the MCP.md wiring's guard and (the genuinely risky part) the
 * Ownership / terminal-path lifecycle: live MCP connections opened from MCP.md
 * are closed exactly once on framework shutdown, and one failing close does not
 * block siblings (Correctness Invariants #1 and #2).
 */
class WorkspaceMcpWiringTest {

    @Test
    void wireReturnsNullForEmptyOrNullRefs() {
        var registry = new DefaultToolRegistry();
        assertNull(WorkspaceMcpToolWiring.wire("agent", List.of(), registry));
        assertNull(WorkspaceMcpToolWiring.wire("agent", null, registry));
        assertTrue(registry.allTools().isEmpty(), "no refs → no tools registered");
    }

    @Test
    void closeClosesEachRegistryOnceAndIsIdempotent() {
        var processor = new AgentProcessor();
        var closes = new AtomicInteger();
        processor.trackMcpRegistryForTest(closes::incrementAndGet);

        processor.closeOwnedMcpRegistries();
        processor.closeOwnedMcpRegistries(); // second shutdown must be a no-op

        assertEquals(1, closes.get(), "MCP registry must close exactly once across shutdowns");
    }

    @Test
    void closeContinuesPastAFailingRegistry() {
        var processor = new AgentProcessor();
        var siblingClosed = new AtomicBoolean();
        processor.trackMcpRegistryForTest(() -> {
            throw new IllegalStateException("boom");
        });
        processor.trackMcpRegistryForTest(() -> siblingClosed.set(true));

        assertDoesNotThrow(processor::closeOwnedMcpRegistries);
        assertTrue(siblingClosed.get(), "a failing close must not block sibling registries (Inv #2)");
    }
}
