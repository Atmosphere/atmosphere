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
package org.atmosphere.ai.agentscope;

import io.agentscope.core.ReActAgent;
import org.atmosphere.ai.AgentExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Verifies the per-request AgentScope {@link ReActAgent} sidecar
 * ({@link AgentScopeAgent}). The runtime read path (
 * {@link AgentScopeAgentRuntime#doExecuteWithHandle}) is exercised end-to-end
 * by the runtime's own contract test; this test pins the helper-level
 * semantics: missing slot returns {@code null} (signals "use installed
 * default"), wrong slot type throws loudly so a misconfigured override
 * surfaces at the call site instead of silently dispatching against the
 * default agent, and {@code attach} replaces (the bridge is exclusive — one
 * request, one agent).
 */
class AgentScopeAgentBridgeTest {

    @Test
    void fromReturnsNullWhenNoSlot() {
        var ctx = baseContext(Map.of());
        assertNull(AgentScopeAgent.from(ctx),
                "missing slot must yield null so the runtime falls back to "
                        + "AgentScopeAgentRuntime.getNativeClient()");
    }

    @Test
    void fromReturnsNullWhenContextIsNull() {
        assertNull(AgentScopeAgent.from(null),
                "null context must yield null rather than NPE");
    }

    @Test
    void fromRejectsNonReActAgentSlot() {
        var ctx = baseContext(Map.of(AgentScopeAgent.METADATA_KEY, "not an agent"));
        var iae = assertThrows(IllegalArgumentException.class,
                () -> AgentScopeAgent.from(ctx),
                "a non-ReActAgent slot must fail loudly — silently dropping "
                        + "the override would mask the per-request agent never firing");
        assertTrue(iae.getMessage().contains(AgentScopeAgent.METADATA_KEY));
        assertTrue(iae.getMessage().contains(ReActAgent.class.getName()));
    }

    @Test
    void attachStoresAgentUnderCanonicalKey() {
        var agent = mock(ReActAgent.class);
        var ctx = AgentScopeAgent.attach(baseContext(Map.of()), agent);
        assertSame(agent, ctx.metadata().get(AgentScopeAgent.METADATA_KEY),
                "attach must store the agent under the canonical key the "
                        + "runtime reads from");
        assertSame(agent, AgentScopeAgent.from(ctx),
                "round-trip from(attach(ctx, a)) must return a");
    }

    @Test
    void attachReplacesPreviousAgent() {
        var first = mock(ReActAgent.class);
        var second = mock(ReActAgent.class);
        var withFirst = AgentScopeAgent.attach(baseContext(Map.of()), first);
        var withSecond = AgentScopeAgent.attach(withFirst, second);
        assertSame(second, AgentScopeAgent.from(withSecond),
                "the bridge is exclusive — a request dispatches against exactly "
                        + "one ReActAgent");
        assertNotSame(withFirst, withSecond);
    }

    @Test
    void attachRejectsNullArgs() {
        var ctx = baseContext(Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> AgentScopeAgent.attach(ctx, null),
                "null agent must fail loudly at attach time");
        assertThrows(IllegalArgumentException.class,
                () -> AgentScopeAgent.attach(null, mock(ReActAgent.class)),
                "null context must fail loudly at attach time");
    }

    @Test
    void attachPreservesOtherMetadataEntries() {
        var ctx = baseContext(Map.of("other.key", "preserved"));
        var withAgent = AgentScopeAgent.attach(ctx, mock(ReActAgent.class));
        assertEquals("preserved", withAgent.metadata().get("other.key"),
                "attach must not clobber unrelated metadata entries");
    }

    private static AgentExecutionContext baseContext(Map<String, Object> metadata) {
        return new AgentExecutionContext(
                "Hello", "You are helpful", "qwen-turbo",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), metadata,
                List.of(), null, null);
    }
}
