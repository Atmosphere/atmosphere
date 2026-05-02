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
package org.atmosphere.ai.adk;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.SequentialAgent;
import org.atmosphere.ai.AgentExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link AdkRootAgent} sidecar — read/attach round-trip,
 * type-mismatch behavior, and composition with the orchestration agent
 * builders ({@link SequentialAgent}). Mirrors the test shape of
 * {@code SpringAiAdvisorsBridgeTest} and
 * {@code LangChain4jAiServicesBridgeTest}.
 */
class AdkRootAgentBridgeTest {

    @Test
    void fromReturnsNullWhenNoSlotPresent() {
        assertNull(AdkRootAgent.from(baseContext(Map.of())),
                "default path: no metadata slot means no custom root");
    }

    @Test
    void fromReturnsNullForNullContext() {
        assertNull(AdkRootAgent.from(null));
    }

    @Test
    void attachThenReadRoundTripsBaseAgent() {
        var leaf = LlmAgent.builder()
                .name("leaf")
                .description("solo agent")
                .model("gemini-2.5-flash")
                .build();
        var ctx = baseContext(Map.of());
        var attached = AdkRootAgent.attach(ctx, leaf);

        var read = AdkRootAgent.from(attached);
        assertSame(leaf, read,
                "attached BaseAgent must round-trip via metadata slot");
        assertNull(AdkRootAgent.from(ctx),
                "original context must remain unmutated (immutable metadata)");
    }

    @Test
    void attachAcceptsSequentialOrchestrationRoot() {
        var planner = LlmAgent.builder()
                .name("planner").description("plan").model("gemini-2.5-flash").build();
        var coder = LlmAgent.builder()
                .name("coder").description("code").model("gemini-2.5-flash").build();
        var pipeline = SequentialAgent.builder()
                .name("code-pipeline")
                .description("plan then code")
                .subAgents(planner, coder)
                .build();

        var attached = AdkRootAgent.attach(baseContext(Map.of()), pipeline);

        var read = AdkRootAgent.from(attached);
        assertNotNull(read);
        assertEquals("code-pipeline", read.name());
        assertEquals(2, read.subAgents().size(),
                "SequentialAgent must preserve its sub-agent topology through the bridge");
    }

    @Test
    void attachReplacesPreviousRoot() {
        var first = LlmAgent.builder()
                .name("first").description("a").model("gemini-2.5-flash").build();
        var second = LlmAgent.builder()
                .name("second").description("b").model("gemini-2.5-flash").build();

        var afterFirst = AdkRootAgent.attach(baseContext(Map.of()), first);
        var afterSecond = AdkRootAgent.attach(afterFirst, second);

        assertSame(second, AdkRootAgent.from(afterSecond),
                "second attach must replace the first — root agent slot is exclusive");
    }

    @Test
    void attachRejectsNullContext() {
        var leaf = LlmAgent.builder()
                .name("x").description("x").model("gemini-2.5-flash").build();
        assertThrows(IllegalArgumentException.class,
                () -> AdkRootAgent.attach(null, leaf));
    }

    @Test
    void attachRejectsNullAgent() {
        assertThrows(IllegalArgumentException.class,
                () -> AdkRootAgent.attach(baseContext(Map.of()), (BaseAgent) null));
    }

    @Test
    void fromRejectsTypeMismatchInSlot() {
        var ctx = baseContext(Map.of(AdkRootAgent.METADATA_KEY, "not-a-base-agent"));

        var ex = assertThrows(IllegalArgumentException.class,
                () -> AdkRootAgent.from(ctx));
        assertTrue(ex.getMessage().contains(AdkRootAgent.METADATA_KEY),
                "diagnostic must name the slot key so misconfiguration is loud");
    }

    @Test
    void metadataKeyIsStable() {
        // Lock in the wire-format key so external callers and reflection-based
        // configuration cannot drift silently if the constant is renamed.
        assertEquals("adk.rootAgent", AdkRootAgent.METADATA_KEY);
    }

    private static AgentExecutionContext baseContext(Map<String, Object> metadata) {
        return new AgentExecutionContext(
                "Hello", "You are helpful", "gemini-2.5-flash",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), metadata,
                List.of(), null, null);
    }
}
